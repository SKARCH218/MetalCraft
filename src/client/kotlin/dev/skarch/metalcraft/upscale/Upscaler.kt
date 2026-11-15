package dev.skarch.metalcraft.upscale

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import dev.skarch.metalcraft.MetalCraft
import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.backend.BindingEntry
import dev.skarch.metalcraft.backend.MetalDevice
import dev.skarch.metalcraft.backend.MetalFormats
import dev.skarch.metalcraft.backend.MetalRenderPass
import dev.skarch.metalcraft.backend.MetalSampler
import dev.skarch.metalcraft.backend.MetalTexture
import dev.skarch.metalcraft.backend.MetalTextureView
import dev.skarch.metalcraft.backend.PackProgram
import dev.skarch.metalcraft.config.MetalCraftConfig
import dev.skarch.metalcraft.native.Mcn
import dev.skarch.metalcraft.pipeline.ShaderPipeline
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.lwjgl.system.MemoryUtil
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.OptionalDouble

/**
 * MetalFX upscaling (CLAUDE.md section 3-6). The world is drawn into images smaller than the window, MetalFX
 * enlarges the result into the game's own image, and the game then draws the first-person hand and the GUI
 * at full resolution on top.
 *
 * While the world is being drawn, [redirect] tells the backend to send everything aimed at the game's main
 * colour and depth images to the small ones instead.
 *
 * Temporal mode shifts the projection by a fraction of a pixel every frame and hands MetalFX a motion image
 * computed from depth and the camera's movement. Things that move on their own (entities, later Create
 * contraptions) are treated as standing still, so they can leave a faint trail.
 */
object Upscaler {
    const val MODE_PROPERTY = "metalcraft.upscale"
    const val SCALE_PROPERTY = "metalcraft.renderScale"

    enum class Mode { OFF, SPATIAL, TEMPORAL }

    /** Where the world is drawn instead of the game's main images. */
    internal class Redirect(
        val mainColor: GpuTexture, val mainDepth: GpuTexture?,
        val colorTexture: MetalTexture, val colorView: MetalTextureView,
        val depthTexture: MetalTexture, val depthView: MetalTextureView,
        val width: Int, val height: Int, val fullWidth: Int, val fullHeight: Int,
    ) {
        val fullArea = RenderPass.RenderArea(0, 0, width, height)
    }

    /** The images the world is drawn into this frame: the small ones, or the game's own when not upscaling. */
    internal class WorldTarget {
        lateinit var colorTexture: MetalTexture
        lateinit var colorView: MetalTextureView
        lateinit var depthView: MetalTextureView
        var width = 0
        var height = 0
    }

    /** Non-null while the world is drawn at reduced resolution. Read by the backend for every pass. */
    @JvmField
    internal var redirect: Redirect? = null

    private val mode: Mode
    private val scalePercent: Int
    private var failed = false

    private var state: Targets? = null
    private val worldTarget = WorldTarget()
    private val handleOut: MemorySegment = Arena.global().allocate(JAVA_LONG)

    // Temporal state
    private var warnedTransparency = false
    private var depthSaved = false
    private var historyLevel: Any? = null
    private var historyPack: String? = null
    private var lastFrameNanos = 0L
    private var jitterIndex = 0
    private var jitterX = 0f
    private var jitterY = 0f
    private var historyValid = false
    private val projection = Matrix4f()
    private val viewProjection = Matrix4f()
    private val inverseViewProjection = Matrix4f()
    private val previousViewProjection = Matrix4f()
    private var previousX = 0.0
    private var previousY = 0.0
    private var previousZ = 0.0
    private val motionData: ByteBuffer = MemoryUtil.memAlloc(MOTION_UNIFORMS_SIZE)

    // Sign conventions between the game's images and MetalFX, adjustable for experiments.
    private val jitterSignX = java.lang.Float.parseFloat(System.getProperty("metalcraft.debug.jitterSignX", "1"))
    private val jitterSignY = java.lang.Float.parseFloat(System.getProperty("metalcraft.debug.jitterSignY", "1"))
    private val motionSign = java.lang.Float.parseFloat(System.getProperty("metalcraft.debug.motionSign", "1"))

    init {
        val requested = (System.getProperty(MODE_PROPERTY) ?: MetalCraftConfig.upscale).trim().lowercase()
        mode = when (requested) {
            "spatial" -> Mode.SPATIAL
            "temporal" -> Mode.TEMPORAL
            "off", "" -> Mode.OFF
            else -> {
                LOGGER.warn("Unknown upscale mode '{}' (expected off, spatial or temporal), using off", requested)
                Mode.OFF
            }
        }
        val percent = System.getProperty(SCALE_PROPERTY)?.toIntOrNull() ?: MetalCraftConfig.renderScale
        scalePercent = percent.coerceIn(50, 100)
    }

    /** The mode in use and the render scale in percent, for display. */
    fun describe(): String = if (isActive()) "$mode $scalePercent%" else "off"

    private fun isActive(): Boolean = !failed && mode != Mode.OFF && !(mode == Mode.SPATIAL && scalePercent == 100)

    internal fun worldTarget(minecraft: Minecraft): WorldTarget {
        val active = redirect
        val target = worldTarget
        if (active != null) {
            target.colorTexture = active.colorTexture
            target.colorView = active.colorView
            target.depthView = active.depthView
            target.width = active.width
            target.height = active.height
        } else {
            val main = minecraft.gameRenderer.mainRenderTarget()
            target.colorTexture = main.colorTexture as MetalTexture
            target.colorView = main.colorTextureView as MetalTextureView
            target.depthView = main.depthTextureView as MetalTextureView
            target.width = main.width
            target.height = main.height
        }
        return target
    }

    // ---- frame hooks, called from GameRendererMixin -------------------------------------------------------

    /**
     * Called when the game has built the projection for the world and before it draws anything with it.
     * In temporal mode [worldProjection] is shifted by this frame's sub-pixel jitter.
     */
    @JvmStatic
    fun beginWorld(worldProjection: Matrix4f) {
        redirect = null
        if (!isActive()) return
        val device = MetalDevice.current ?: return
        val minecraft = Minecraft.getInstance()
        val main = minecraft.gameRenderer.mainRenderTarget()
        val mainColor = main.colorTexture ?: return
        // "Improved transparency" draws translucent things into extra full-size images that take their depth
        // from the main one, which stays empty while the world goes to the small images.
        if (minecraft.options.improvedTransparency().get()) {
            if (!warnedTransparency) {
                warnedTransparency = true
                LOGGER.warn("MetalFX upscaling does not work with the Improved Transparency video setting yet. It stays off while that is on")
            }
            return
        }
        depthSaved = false
        try {
            val targets = targets(device, main.width, main.height)
            projection.set(worldProjection)
            if (mode == Mode.TEMPORAL) {
                jitterIndex = (jitterIndex + 1) % JITTER_SEQUENCE
                jitterX = halton(jitterIndex + 1, 2) - 0.5f
                jitterY = halton(jitterIndex + 1, 3) - 0.5f
                worldProjection.translateLocal(2f * jitterX / targets.width, 2f * jitterY / targets.height, 0f)
            }
            redirect = targets.redirect(mainColor, main.depthTexture, main.width, main.height)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Called after the world (and the shader pack's passes): enlarges the small image into the game's. */
    @JvmStatic
    fun endWorld() {
        val active = redirect ?: return
        redirect = null
        val device = MetalDevice.current ?: return
        val targets = state ?: return
        try {
            var reset = 0
            if (mode == Mode.TEMPORAL) {
                reset = if (writeMotion(device, targets)) 0 else 1
            }
            Mcn.check(
                Mcn.upscalerEncode.invokeExact(
                    device.context, targets.scaler, active.colorTexture.handle,
                    if (mode != Mode.TEMPORAL) 0L else if (depthSaved) targets.savedDepthTexture!!.handle else active.depthTexture.handle,
                    if (mode == Mode.TEMPORAL) targets.motionTexture!!.handle else 0L,
                    jitterX * jitterSignX, jitterY * jitterSignY, reset, (active.mainColor as MetalTexture).handle,
                ) as Int,
            )
        } catch (e: Exception) {
            fail(e)
        }
    }

    /**
     * The game is about to clear the world's depth (it does so before drawing "always on top" debug shapes).
     * Temporal upscaling still needs the depth of the scene, so a copy is kept.
     */
    internal fun worldDepthAboutToBeCleared(device: MetalDevice) {
        val active = redirect ?: return
        val targets = state ?: return
        val saved = targets.savedDepthTexture ?: return
        if (depthSaved) return
        device.encoder.copyTextureToTexture(active.depthTexture, saved, 0, 0, 0, 0, 0, active.width, active.height)
        depthSaved = true
    }

    internal fun deviceClosing() {
        redirect = null
        state?.destroy()
        state = null
    }

    private fun fail(error: Exception) {
        LOGGER.error("MetalFX upscaling failed and is switched off: {}", error.message)
        LOGGER.debug("Upscaling failure", error)
        failed = true
        redirect = null
        state?.destroy()
        state = null
    }

    // ---- temporal: motion ---------------------------------------------------------------------------------

    /**
     * Draws the motion image: for every pixel, how far (in pixels of the small image) the surface seen there
     * was from this spot one frame ago, assuming only the camera moved. Returns false when there is no usable
     * previous frame, in which case MetalFX has to start its history over.
     */
    private fun writeMotion(device: MetalDevice, targets: Targets): Boolean {
        val camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        // The un-jittered matrices: the jitter is told to MetalFX separately.
        projection.mul(camera.viewRotationMatrix, viewProjection)
        viewProjection.invert(inverseViewProjection)
        val position = camera.pos
        val deltaX = (position.x - previousX).toFloat()
        val deltaY = (position.y - previousY).toFloat()
        val deltaZ = (position.z - previousZ).toFloat()
        // The last frame is only usable if it showed the same world moments ago: not after a teleport, a change
        // of world or dimension, a different shader pack, or a pause in rendering.
        val minecraft = Minecraft.getInstance()
        val now = System.nanoTime()
        val pack = ShaderPipeline.selectedName()
        val continuous = historyValid && deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ < MAX_STEP * MAX_STEP &&
            historyLevel === minecraft.level && historyPack == pack && now - lastFrameNanos < MAX_FRAME_GAP_NANOS
        historyLevel = minecraft.level
        historyPack = pack
        lastFrameNanos = now
        if (!continuous) previousViewProjection.set(viewProjection)

        val data = motionData
        inverseViewProjection.get(0, data)
        previousViewProjection.get(64, data)
        data.putFloat(128, if (continuous) deltaX else 0f).putFloat(132, if (continuous) deltaY else 0f)
            .putFloat(136, if (continuous) deltaZ else 0f).putFloat(140, motionSign)
        data.putFloat(144, targets.width.toFloat()).putFloat(148, targets.height.toFloat()).putFloat(152, 0f).putFloat(156, 0f)
        data.position(0).limit(MOTION_UNIFORMS_SIZE)
        val uniforms = device.encoder.transientMemory().uploadGpu(data, 16L, GpuBuffer.USAGE_UNIFORM)

        val pass = device.encoder.createRenderPass(targets.motionPass!!) as MetalRenderPass
        try {
            pass.setProgram(targets.motionProgram!!)
            pass.setUniform(MOTION_UNIFORMS, uniforms)
            pass.bindTexture(DEPTH_INPUT, if (depthSaved) targets.savedDepthView!! else targets.depthView, targets.nearest)
            pass.draw(3, 1, 0, 0)
        } finally {
            device.encoder.submitRenderPass()
        }

        previousViewProjection.set(viewProjection)
        previousX = position.x
        previousY = position.y
        previousZ = position.z
        historyValid = true
        return continuous
    }

    private fun halton(index: Int, base: Int): Float {
        var result = 0f
        var fraction = 1f / base
        var i = index
        while (i > 0) {
            result += fraction * (i % base)
            i /= base
            fraction /= base
        }
        return result
    }

    // ---- images and the scaler for one window size --------------------------------------------------------

    private fun targets(device: MetalDevice, fullWidth: Int, fullHeight: Int): Targets {
        val current = state
        if (current != null && current.fullWidth == fullWidth && current.fullHeight == fullHeight) return current
        current?.destroy()
        state = null
        historyValid = false
        val fresh = Targets(device, fullWidth, fullHeight)
        state = fresh
        LOGGER.info("MetalFX {} upscaling: {}x{} -> {}x{}", mode.name.lowercase(), fresh.width, fresh.height, fullWidth, fullHeight)
        return fresh
    }

    private class Targets(private val device: MetalDevice, val fullWidth: Int, val fullHeight: Int) {
        val width = maxOf(1, Math.round(fullWidth * scalePercent / 100f))
        val height = maxOf(1, Math.round(fullHeight * scalePercent / 100f))
        val colorTexture: MetalTexture
        val colorView: MetalTextureView
        val depthTexture: MetalTexture
        val depthView: MetalTextureView
        var motionTexture: MetalTexture? = null
        var motionView: MetalTextureView? = null
        var motionProgram: PackProgram? = null
        var motionPass: RenderPassDescriptor? = null
        var savedDepthTexture: MetalTexture? = null
        var savedDepthView: MetalTextureView? = null
        val nearest: MetalSampler
        var scaler = 0L
        private var cached: Redirect? = null

        // Everything created so far, so a failure half way through can give it all back.
        private val owned = ArrayList<AutoCloseable>()

        private fun <T : AutoCloseable> own(created: T): T {
            owned.add(created)
            return created
        }

        init {
            val usage = GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST
            try {
                colorTexture = own(device.createTexture("MetalCraft world colour", usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1) as MetalTexture)
                colorView = own(device.createTextureView(colorTexture) as MetalTextureView)
                depthTexture = own(device.createTexture("MetalCraft world depth", usage, GpuFormat.D32_FLOAT, width, height, 1, 1) as MetalTexture)
                depthView = own(device.createTextureView(depthTexture) as MetalTextureView)
                nearest = own(
                    device.createSampler(
                        AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(0.0),
                    ) as MetalSampler,
                )
                if (mode == Mode.TEMPORAL) {
                    val motion = own(device.createTexture("MetalCraft motion", usage, GpuFormat.RG16_FLOAT, width, height, 1, 1) as MetalTexture)
                    motionTexture = motion
                    motionView = own(device.createTextureView(motion) as MetalTextureView)
                    motionPass = RenderPassDescriptor.create { "MetalCraft motion" }
                        .withColorAttachment(motionView!!)
                        .withRenderArea(RenderPass.RenderArea(0, 0, width, height))
                    val saved = own(device.createTexture("MetalCraft world depth (saved)", usage, GpuFormat.D32_FLOAT, width, height, 1, 1) as MetalTexture)
                    savedDepthTexture = saved
                    savedDepthView = own(device.createTextureView(saved) as MetalTextureView)
                    val header = source("pack_header.metal") + "\n"
                    val program = PackProgram(
                        device, "metalcraft/motion", header + source("pack_fullscreen.vsh.metal"), header + source("upscale_motion.fsh.metal"),
                        MOTION_ENTRIES, null, intArrayOf(MetalFormats.code(GpuFormat.RG16_FLOAT)), intArrayOf(15), false, -1, false, 0,
                    )
                    motionProgram = program
                    program.variant(MetalFormats.NO_FORMAT, null)
                }
                Mcn.check(
                    Mcn.upscalerCreate.invokeExact(
                        device.context, if (mode == Mode.TEMPORAL) 2 else 1, width, height, fullWidth, fullHeight, handleOut,
                    ) as Int,
                )
                scaler = handleOut.get(JAVA_LONG, 0)
            } catch (e: Exception) {
                destroy()
                throw e
            }
        }

        fun redirect(mainColor: GpuTexture, mainDepth: GpuTexture?, fullWidth: Int, fullHeight: Int): Redirect {
            val existing = cached
            if (existing != null && existing.mainColor === mainColor && existing.mainDepth === mainDepth) return existing
            return Redirect(mainColor, mainDepth, colorTexture, colorView, depthTexture, depthView, width, height, fullWidth, fullHeight)
                .also { cached = it }
        }

        fun destroy() {
            // Views before their textures.
            for (i in owned.indices.reversed()) owned[i].close()
            owned.clear()
            motionProgram?.destroy()
            motionProgram = null
            if (scaler != 0L) device.encoder.releaseLater(scaler)
            scaler = 0L
        }
    }

    private fun source(file: String): String {
        val container = FabricLoader.getInstance().getModContainer(MetalCraft.MOD_ID)
            .orElseThrow { IllegalStateException("Mod container '${MetalCraft.MOD_ID}' not found") }
        val path = container.findPath("assets/metalcraft/shaders/$file").orElseThrow { IllegalStateException("$file is missing from the mod") }
        return Files.readString(path)
    }

    private const val JITTER_SEQUENCE = 16
    private const val MAX_STEP = 16f
    private const val MAX_FRAME_GAP_NANOS = 250_000_000L
    private const val MOTION_UNIFORMS_SIZE = 160
    private const val MOTION_UNIFORMS = "MotionUniforms"
    private const val DEPTH_INPUT = "depth"
    private val MOTION_ENTRIES = arrayOf(
        BindingEntry(BindingEntry.UNIFORM_BUFFER, MOTION_UNIFORMS, null, 0),
        BindingEntry(BindingEntry.TEXTURE, DEPTH_INPUT, null, 0),
    )
}
