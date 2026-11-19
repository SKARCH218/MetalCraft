package dev.skarch.metalcraft.pipeline

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.config.MetalCraftConfig
import dev.skarch.metalcraft.backend.BindingEntry
import dev.skarch.metalcraft.backend.MetalDevice
import dev.skarch.metalcraft.backend.MetalFormats
import dev.skarch.metalcraft.backend.MetalRenderPass
import dev.skarch.metalcraft.backend.MetalSampler
import dev.skarch.metalcraft.backend.MetalTexture
import dev.skarch.metalcraft.backend.MetalTextureView
import dev.skarch.metalcraft.backend.PackProgram
import dev.skarch.metalcraft.upscale.Upscaler
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.material.FogType
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.IdentityHashMap
import java.util.OptionalDouble

/**
 * A shader pack that was converted from GLSL (`"format": 2`, see `ShaderPackConverter.kt` in `src/tools`),
 * loaded and ready to draw. Its programs keep the pack's OpenGL conventions, so this class provides what Iris
 * provides to such a pack: the colour buffers with their flipping, depth copies, the shadow map, the uniforms.
 *
 * A frame:
 *   shadow                      terrain from the light into shadowtex and shadowcolor
 *   sky                         the pack's sky program as a full-screen pass
 *   gbuffers (opaque)           the game draws the world; terrain, entities, particles and the sun and moon
 *                               use the pack's programs, which write the pack's buffers attached after the
 *                               game's image. Everything else is drawn by the game's own shader into the
 *                               pack's colour image (`MetalPipeline.variant`), unlit by the pack
 *   deferred                    full-screen passes, run right before the game draws translucent terrain
 *   gbuffers (translucent)      water and the like
 *   composite, final            full-screen passes; `final` writes the game's image
 *
 * Not covered yet: pack programs for weather, lines, moving blocks and the like (see above), the hand (the
 * game draws it afterwards with its own shader), translucent and entity shadows, per-entity uniforms, the
 * pack's options.
 */
internal class ConvertedPack(
    private val device: MetalDevice, val pack: ShaderPack, private val spec: ConvertedSpec,
    /** The program folder this was loaded for: `world0`, `world-1` (the Nether) or `world1` (the End). */
    val dimension: String,
) {
    private class Color(val spec: ConvertedSpec.Buffer) {
        var texture: MetalTexture? = null
        var view: MetalTextureView? = null
        var altTexture: MetalTexture? = null
        var altView: MetalTextureView? = null
        var needsAlt = false
        var mipmapped = false
    }

    private class Image(val texture: MetalTexture, val view: MetalTextureView, val repeat: Boolean, val linear: Boolean = true, val volume: Boolean = false)

    /** Where one texture of a program comes from. */
    /** [mip]: the program samples the smaller mip levels of this colour buffer. */
    private class Slot(val entryName: String, val source: Int, val index: Int, val compare: Boolean, val image: Image?, val mip: Boolean = false)

    private class Program(val spec: ConvertedSpec.Program, val program: PackProgram, val slots: Array<Slot>, val stage: String) {
        /** The pass name seen in GPU captures and in the per-pass timing report. */
        val label = java.util.function.Supplier { "pack " + spec.name }
    }

    /**
     * What Flywheel's backend (`flywheel/MetalFlywheel.kt`) needs to draw Create's parts lit by this pack:
     * the pack's entity program as GLSL. [vertex] lacks `mc_flywheel()`, which Flywheel's vertex code defines.
     */
    class FlywheelTemplate(val vertex: String, val fragment: String, val targets: IntArray)

    /** A world program built from shaders translated elsewhere, with where its textures come from. */
    class ExternalProgram internal constructor(val program: PackProgram, internal val slots: Array<Any>)

    val flywheel: FlywheelTemplate? = spec.flywheel?.let {
        FlywheelTemplate(pack.read(it.vertex), pack.read(it.fragment), spec.programs.getValue(it.program).targets)
    }

    /**
     * Builds a program that writes this pack's buffers from already translated shaders. [entries] are the
     * shaders' bindings with textures under their GLSL names; the block atlas name becomes `Sampler0`.
     */
    fun externalWorldProgram(
        label: String, vertexMsl: String, fragmentMsl: String, entries: Array<BindingEntry>, targets: IntArray, format: VertexFormat,
        cull: Boolean, depthCompare: Int, depthWrite: Boolean, blend: Boolean,
    ): ExternalProgram {
        val textures = entries.filter { it.kind == BindingEntry.TEXTURE }
        val description = ConvertedSpec.Program(
            label, "external", "", "",
            textures.map { ConvertedSpec.Texture(it.name, Regex("depth2d<float> ${it.name}\\b").containsMatchIn(vertexMsl + fragmentMsl), false) },
            targets, IntArray(0), -1f,
        )
        val slots = slots(description, "gbuffers")
        val bindings = Array(entries.size) { i ->
            val entry = entries[i]
            if (entry.kind == BindingEntry.TEXTURE) BindingEntry(BindingEntry.TEXTURE, slots[textures.indexOf(entry)].entryName, null, entry.index) else entry
        }
        val formats = attachmentFormats
        val masks = IntArray(formats.size) { if (attachments[it] in targets) WRITE_ALL else 0 }
        val blendMask = if (blend && 0 in targets) 1 shl attachments.indexOf(0) else 0
        val compiled = PackProgram(
            device, "${pack.id}/$label", vertexMsl, fragmentMsl, bindings, format, formats, masks, blendMask, depthCompare, depthWrite,
            if (cull) 1 else 0, null, fastMath = true,
        )
        @Suppress("UNCHECKED_CAST")
        return ExternalProgram(register(compiled, DEPTH_FORMAT), slots as Array<Any>)
    }

    /** Binds the pack's uniforms and textures for a draw with [external]. `Sampler0` is left to the caller. */
    fun bindExternal(pass: MetalRenderPass, external: ExternalProgram, alphaTest: Float) {
        pass.setUniform(PACK_UNIFORMS, frameSlice ?: return)
        pass.setUniform(DRAW_UNIFORMS, drawUniforms(alphaTest))
        for (slot in external.slots) bindSlot(pass, slot as Slot)
    }

    /** What replaces one game pipeline: a program and the per-draw uniforms that go with it. */
    private class Draw(val program: Program, val uniforms: GpuBufferSlice)

    private val colors = arrayOfNulls<Color>(16)
    private val images = HashMap<String, Image>()
    /** Colour buffers that world programs both read and write, see [slots]. */
    private val snapshots = it.unimi.dsi.fastutil.ints.IntArrayList()
    /** Colour buffers whose mip levels world programs sample; they are filled in before translucent things are drawn. */
    private val worldMips = it.unimi.dsi.fastutil.ints.IntArrayList()
    private val programs = ArrayList<PackProgram>()
    private val passes = ArrayList<Program>()
    private var terrain: Program? = null
    private var water: Program? = null
    private var shadow: Program? = null
    private var sky: Program? = null
    private val depthCopy: PackProgram
    private val debugView: PackProgram?
    private var debugStopped = false
    private var withCasters = false

    private val linear: MetalSampler
    private val nearest: MetalSampler
    private val linearRepeat: MetalSampler
    private val linearMip: MetalSampler
    private val nearestRepeat: MetalSampler
    private val shadowCompare: MetalSampler

    // The game binds the block atlas with a linear sampler and picks texels in its own terrain shader. A pack
    // samples the atlas directly and expects what OpenGL packs get: the nearest texel, blended between mip levels.
    private val atlas: MetalSampler

    private var depth0Texture: MetalTexture? = null
    private var depth0View: MetalTextureView? = null
    private var depth1Texture: MetalTexture? = null
    private var depth1View: MetalTextureView? = null
    // shadowtex1: what opaque terrain casts. shadowtex0: the same plus translucent terrain such as water.
    // A pack compares the two to find light that has passed through water or stained glass.
    private val itemIds = loadItemIds()
    private val shadowDepth: MetalTexture
    private val shadowDepthView: MetalTextureView
    private val shadowDepthAll: MetalTexture
    private val shadowDepthAllView: MetalTextureView
    private val shadowColors: Array<MetalTexture>
    private val shadowColorViews: Array<MetalTextureView>

    /** What entities cast into the shadow map. */
    private val casters = ShadowCasters(device)
    private val drawBuffers = ArrayList<GpuBuffer>()
    private val drawUniforms = HashMap<Float, GpuBufferSlice>()
    private val drawShadow: GpuBufferSlice

    // Game pipeline to what the pack draws instead; a null value means the pack has nothing for it.
    private val substitutes = IdentityHashMap<RenderPipeline, Draw?>()

    private var width = 0
    private var height = 0
    /**
     * The colour attachments of the world passes, as the buffers they hold. A pack converted with
     * `primaryTarget` has its colortex0 where the game image would be; otherwise the game image stays first
     * (-1 here) and every buffer the world programs write follows.
     */
    private val attachments: IntArray = if (spec.primaryTarget) {
        intArrayOf(spec.primaryBuffer) + spec.gbuffers.filter { it != spec.primaryBuffer }
    } else {
        intArrayOf(-1) + spec.gbuffers
    }
    private val attachmentFormats = IntArray(attachments.size) {
        if (attachments[it] < 0) MetalFormats.code(GpuFormat.RGBA8_UNORM) else MetalFormats.code(spec.buffers.getValue(attachments[it]).format)
    }
    private val gbufferFormats = attachmentFormats.copyOfRange(1, attachmentFormats.size)
    private val gbufferHandles = LongArray(attachments.size - 1)
    private var gbufferState: ShaderPipeline.Gbuffer? = null

    // Per-frame state.
    private var frameSlice: GpuBufferSlice? = null
    private var currentDraw: GpuBufferSlice? = null
    private var currentProgram: Program? = null
    private var deferredDone = false
    private var frameCounter = 0
    private var lastFrameNanos = 0L
    private var seconds = 0f
    private var hasPrevious = false
    private var reportedMissing = false

    private val data: ByteBuffer = MemoryUtil.memCalloc(spec.uniformBlockSize)
    private val offsets = Object2IntOpenHashMap<String>().apply { defaultReturnValue(-1) }
    private val types = HashMap<String, String>()
    private val written = HashSet<String>()
    private val customUniforms: CustomUniforms? = if (spec.customUniforms.isEmpty()) {
        null
    } else {
        val byName = spec.uniforms.associateBy { it.name }
        CustomUniforms(spec.customUniforms, { byName[it]?.offset ?: -1 }, { byName[it]?.type }, pack.id)
    }
    private var lastBiomeKey: Any? = null

    private val modelView = Matrix4f()
    private val projection = Matrix4f()
    private val inverse = Matrix4f()
    private val previousModelView = Matrix4f()
    private val previousProjection = Matrix4f()
    private val shadowModelView = Matrix4f()
    private val shadowProjection = Matrix4f()
    private val look = Vector3f()
    private var previousX = 0.0
    private var previousY = 0.0
    private var previousZ = 0.0
    private val lightProbe = BlockPos.MutableBlockPos()
    private val clearBlack = Vector4f(0f, 0f, 0f, 0f)
    private val clearWhite = Vector4f(1f, 1f, 1f, 1f)

    private val eyeInCave = Smoothed(6f, 12f)
    private val frameTimeSmooth = Smoothed(5f, 5f)
    private val eyeBrightnessSmooth = Smoothed(5f, 5f)
    private val eyeBlockSmooth = Smoothed(10f, 10f)
    private val eyeSkySmooth = Smoothed(10f, 10f)
    private val celestial = Matrix4f()
    private val eyeBrightnessSmooth2 = Smoothed(2f, 2f)
    private val rainFactor = Smoothed(3f, 3f)
    private val inDry = Smoothed(20f, 10f)
    private val inRainy = Smoothed(20f, 10f)
    private val inSnowy = Smoothed(20f, 10f)
    private val inBiome = Array(BIOME_UNIFORMS.size) { Smoothed(15f, 15f) }

    init {
        check(TerrainVertexFormat.enabled) { "Converted packs need the extended terrain vertex" }
        for (uniform in spec.uniforms) {
            offsets.put(uniform.name, uniform.offset)
            types[uniform.name] = uniform.type
        }
        linear = sampler(AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR)
        nearest = sampler(AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST)
        linearRepeat = sampler(AddressMode.REPEAT, FilterMode.LINEAR)
        linearMip = device.createSampler(
            AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty(),
        ) as MetalSampler
        nearestRepeat = sampler(AddressMode.REPEAT, FilterMode.NEAREST)
        shadowCompare = device.createShadowSampler()
        atlas = device.createSampler(
            AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty(),
        ) as MetalSampler
        val resolution = spec.shadowResolution
        shadowDepth = device.createTexture(
            "MetalCraft shadowtex", GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT, resolution, resolution, 1, 1,
        ) as MetalTexture
        shadowDepthView = device.createTextureView(shadowDepth) as MetalTextureView
        shadowDepthAll = device.createTexture(
            "MetalCraft shadowtex0", GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT, resolution, resolution, 1, 1,
        ) as MetalTexture
        shadowDepthAllView = device.createTextureView(shadowDepthAll) as MetalTextureView
        shadowColors = Array(2) { colorTexture("shadowcolor$it", GpuFormat.RGBA8_UNORM, resolution, resolution) }
        shadowColorViews = Array(2) { device.createTextureView(shadowColors[it]) as MetalTextureView }
        drawShadow = drawUniforms(0.1f)
        for (buffer in spec.buffers.values) colors[buffer.index] = Color(buffer)
        for (program in spec.programs.values) for (index in program.mipmaps) colors.getOrNull(index)?.mipmapped = true

        try {
            for ((name, image) in spec.images) {
                // Keyed by the buffer's current name, whichever the pack used.
                val buffer = name.substringAfterLast('.')
                val key = if (name.contains('.')) name.substringBeforeLast('.') + "." + (GAUX[buffer] ?: buffer) else name
                try {
                    images[key] = loadImage(image)
                } catch (e: java.io.IOException) {
                    LOGGER.warn("Shader pack '{}': image {} could not be read: {}", pack.id, image.file, e.message)
                }
            }
            // Packs that bring no noise image get random noise, as they would from an OpenGL shader loader.
            if ("noisetex" !in images) images["noisetex"] = defaultNoise()
            // The game has no normal or specular maps for its textures: flat, and nothing shiny.
            if ("normals" !in images) images["normals"] = flatImage("normals", 128, 128, 255, 255)
            if ("specular" !in images) images["specular"] = flatImage("specular", 0, 0, 0, 0)
            val header = ShaderPipeline.engineSource("pack_header.metal") + "\n"
            depthCopy = register(
                PackProgram(
                    device, "${pack.id}/depth copy", header + ShaderPipeline.engineSource("pack_fullscreen.vsh.metal"),
                    header + ShaderPipeline.engineSource("pack_depth_copy.fsh.metal"),
                    arrayOf(BindingEntry(BindingEntry.TEXTURE, GAME_DEPTH, null, 0)), null,
                    intArrayOf(MetalFormats.code(GpuFormat.R32_FLOAT)), intArrayOf(WRITE_ALL), 0, -1, false, 0,
                ),
                MetalFormats.NO_FORMAT,
            )
            debugView = if (DEBUG_VIEW == null) null else register(
                PackProgram(
                    device, "${pack.id}/debug view", header + ShaderPipeline.engineSource("pack_fullscreen.vsh.metal"),
                    header + ShaderPipeline.engineSource("pack_debug_view.fsh.metal"),
                    arrayOf(BindingEntry(BindingEntry.TEXTURE, "source", null, 0)), null,
                    intArrayOf(MetalFormats.code(GpuFormat.RGBA8_UNORM)), intArrayOf(WRITE_ALL), 0, -1, false, 0,
                ),
                MetalFormats.NO_FORMAT,
            )
            terrain = spec.programs["gbuffers_terrain"]?.let(::terrainProgram)
            water = spec.programs["gbuffers_water"]?.let(::terrainProgram)
            shadow = spec.programs["shadow"]?.let(::shadowProgram)
            sky = spec.sky?.let { passProgram(spec.programs.getValue(it)) }
            for (name in spec.passes) passes.add(passProgram(spec.programs.getValue(name)))
            for (pass in passes + listOfNotNull(sky)) {
                if (pass.spec.name == FINAL) continue
                for (target in pass.spec.targets) if (pass.slots.any { it.source == SOURCE_COLOR && it.index == target }) color(target).needsAlt = true
            }
        } catch (e: Exception) {
            destroy()
            throw e
        }
    }

    // ---- loading ----------------------------------------------------------------------------------------------

    private fun sampler(address: AddressMode, filter: FilterMode) =
        device.createSampler(address, address, filter, filter, 1, OptionalDouble.of(0.0)) as MetalSampler

    private fun colorTexture(label: String, format: GpuFormat, w: Int, h: Int, mipmapped: Boolean = false) = device.createTexture(
        label, GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST,
        format, w, h, 1, if (mipmapped) 32 - Integer.numberOfLeadingZeros(maxOf(w, h)) else 1,
    ) as MetalTexture

    private fun drawUniforms(alphaTest: Float): GpuBufferSlice = drawUniforms.getOrPut(alphaTest) {
        val bytes = MemoryUtil.memCalloc(16)
        try {
            bytes.putFloat(0, alphaTest)
            val buffer = device.createBuffer({ "MetalCraft pack draw uniforms" }, GpuBuffer.USAGE_UNIFORM, bytes)
            drawBuffers.add(buffer)
            buffer.slice()
        } finally {
            MemoryUtil.memFree(bytes)
        }
    }

    /** A one-pixel image of a single colour. */
    private fun flatImage(name: String, r: Int, g: Int, b: Int, alpha: Int): Image {
        val pixel = MemoryUtil.memAlloc(4)
        val texture = device.createTexture(name, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1) as MetalTexture
        try {
            pixel.put(0, r.toByte()).put(1, g.toByte()).put(2, b.toByte()).put(3, alpha.toByte())
            device.encoder.writeToTexture(texture, pixel, 0, 0, 0, 0, 1, 1)
            return Image(texture, device.createTextureView(texture) as MetalTextureView, true)
        } catch (e: Exception) {
            texture.close()
            throw e
        } finally {
            MemoryUtil.memFree(pixel)
        }
    }

    private fun defaultNoise(): Image {
        val size = NOISE_SIZE
        val pixels = MemoryUtil.memAlloc(size * size * 4)
        val texture = device.createTexture(
            "noisetex", GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, size, size, 1, 1,
        ) as MetalTexture
        try {
            // A fixed seed: the same pack looks the same on every start.
            val random = java.util.Random(NOISE_SEED)
            for (i in 0 until size * size * 4) pixels.put(i, random.nextInt(256).toByte())
            device.encoder.writeToTexture(texture, pixels, 0, 0, 0, 0, size, size)
            return Image(texture, device.createTextureView(texture) as MetalTextureView, true)
        } catch (e: Exception) {
            texture.close()
            throw e
        } finally {
            MemoryUtil.memFree(pixels)
        }
    }

    private fun loadImage(image: ConvertedSpec.Image): Image {
        if (image.format != null) return loadRawImage(image, image.format)
        val stream = if (image.file.contains(':')) {
            // A texture of the game or of a resource pack.
            Minecraft.getInstance().resourceManager.open(net.minecraft.resources.Identifier.parse(image.file))
        } else {
            Files.newInputStream(pack.path(image.file))
        }
        val pixels = stream.use { NativeImage.read(it) }
        try {
            val texture = device.createTexture(
                image.file, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, pixels.width, pixels.height, 1, 1,
            ) as MetalTexture
            try {
                device.encoder.writeToTexture(
                    texture, MemoryUtil.memByteBuffer(pixels.pointer, pixels.width * pixels.height * 4), 0, 0, 0, 0, pixels.width, pixels.height,
                )
                return Image(texture, device.createTextureView(texture) as MetalTextureView, image.repeat, image.linear)
            } catch (e: Exception) {
                texture.close()
                throw e
            }
        } finally {
            pixels.close()
        }
    }

    /** Raw texels as OpenGL would take them (`TEXTURE_2D` / `TEXTURE_3D`, an internal format, a pixel format and type). */
    private fun loadRawImage(image: ConvertedSpec.Image, formatName: String): Image {
        val format = ConvertedSpec.format(formatName)
        val components = when (image.pixel) { "RED", "RED_INTEGER" -> 1; "RG", "RG_INTEGER" -> 2; "RGB", "RGB_INTEGER" -> 3; "RGBA", "RGBA_INTEGER" -> 4
            else -> throw IllegalArgumentException("${image.file}: pixel format ${image.pixel} is not supported") }
        val size = when (image.pixelType) { "UNSIGNED_BYTE", "BYTE" -> 1; "HALF_FLOAT", "UNSIGNED_SHORT", "SHORT" -> 2; "FLOAT", "UNSIGNED_INT", "INT" -> 4
            else -> throw IllegalArgumentException("${image.file}: pixel type ${image.pixelType} is not supported") }
        val stored = when {
            format.name.startsWith("RGBA") || format.name.startsWith("RGB10") -> 4
            format.name.startsWith("RG") && !format.name.startsWith("RG11") -> 2
            format.name.startsWith("R") && !format.name.startsWith("RG") -> 1
            else -> throw IllegalArgumentException("${image.file}: format $formatName is not supported for images")
        }
        val texels = image.width * image.height * image.depth
        val bytes = Files.readAllBytes(pack.path(image.file))
        require(bytes.size >= texels * components * size) { "${image.file} has ${bytes.size} bytes, ${texels * components * size} expected" }
        require(components == stored || (components == 3 && stored == 4)) { "${image.file}: $components components do not fit $formatName" }
        val pixels = MemoryUtil.memAlloc(texels * stored * size)
        try {
            if (components == stored) {
                pixels.put(0, bytes, 0, texels * stored * size)
            } else {
                // Three components become four: Metal has no three-component formats. Alpha is one.
                val one = when (image.pixelType) { "HALF_FLOAT" -> byteArrayOf(0x00, 0x3C); "FLOAT" -> byteArrayOf(0, 0, 0x80.toByte(), 0x3F)
                    else -> ByteArray(size) { 0xFF.toByte() } }
                val step = 3 * size
                for (i in 0 until texels) {
                    pixels.put(i * 4 * size, bytes, i * step, step)
                    pixels.put(i * 4 * size + step, one, 0, size)
                }
            }
            val view = device.createImage(image.file, format, image.width, image.height, image.depth, image.volume, pixels)
            return Image(view.texture() as MetalTexture, view, image.repeat, image.linear, image.volume)
        } finally {
            MemoryUtil.memFree(pixels)
        }
    }

    /** Builds the Metal pipeline state right away, so that what Metal rejects fails the pack while it loads. */
    private fun register(created: PackProgram, depthFormat: Int): PackProgram {
        programs.add(created)
        created.variant(depthFormat, null)
        return created
    }

    private fun slots(program: ConvertedSpec.Program, stage: String): Array<Slot> = Array(program.textures.size) { i ->
        val texture = program.textures[i]
        val name = GAUX[texture.name] ?: texture.name
        // A pack can put an image file in place of a buffer for one stage (`texture.<stage>.<name>`).
        // The same buffer name can be a volume in one program and the plain buffer in the next.
        val image = (images["$stage.$name"] ?: images[texture.name])?.takeIf { it.volume == texture.volume }
        if (texture.volume && image == null) throw IllegalArgumentException("${program.name} reads the volume '${texture.name}', which the pack has no image for")
        when {
            image != null -> Slot(texture.name, SOURCE_IMAGE, 0, false, image)
            name in ATLAS_NAMES -> Slot("Sampler0", SOURCE_GAME, 0, false, null)
            // A world program cannot read an image it is drawing into. It reads a copy made once the opaque
            // world and the deferred passes are done, which is what translucent things want to see behind them.
            stage == "gbuffers" && name.startsWith("colortex") && name.removePrefix("colortex").toInt() in program.targets -> {
                val index = name.removePrefix("colortex").toInt()
                if (!snapshots.contains(index)) {
                    snapshots.add(index)
                    // World programs are built when first drawn; the second image is made with the next frame.
                    if (!color(index).needsAlt) {
                        color(index).needsAlt = true
                        width = 0
                    }
                }
                if (index in program.mipmaps && !worldMips.contains(index)) worldMips.add(index)
                Slot(texture.name, SOURCE_COLOR_SNAPSHOT, index, false, null, index in program.mipmaps)
            }
            name.startsWith("colortex") -> name.removePrefix("colortex").toInt().let { index ->
                if (stage == "gbuffers" && index in program.mipmaps && !worldMips.contains(index)) worldMips.add(index)
                Slot(texture.name, SOURCE_COLOR, color(index).spec.index, false, null, index in program.mipmaps)
            }
            name == "depthtex0" -> Slot(texture.name, SOURCE_DEPTH0, 0, false, null)
            name == "depthtex1" || name == "depthtex2" -> Slot(texture.name, SOURCE_DEPTH1, 0, false, null)
            name == "shadowtex0" || name == "shadowtex1" -> Slot(texture.name, SOURCE_SHADOW_DEPTH, name.last() - '0', texture.compare, null)
            name == "shadowcolor0" || name == "shadowcolor1" -> Slot(texture.name, SOURCE_SHADOW_COLOR, name.last() - '0', false, null)
            else -> throw IllegalArgumentException("${program.name} reads '${texture.name}', which the engine does not provide")
        }
    }

    private fun color(index: Int): Color = requireNotNull(colors.getOrNull(index)) { "colortex$index is not declared by the pack" }

    private fun entries(slots: Array<Slot>, vararg buffers: BindingEntry): Array<BindingEntry> =
        Array(buffers.size + slots.size) { i ->
            if (i < buffers.size) buffers[i] else BindingEntry(BindingEntry.TEXTURE, slots[i - buffers.size].entryName, null, i - buffers.size)
        }

    /** A program for world geometry: it writes the pack's buffers, attached after the game's own image. */
    private fun worldProgram(
        program: ConvertedSpec.Program, label: String, format: VertexFormat, geometryBlock: BindingEntry, withGlobals: Boolean,
        cull: Boolean, depthCompare: Int, depthWrite: Boolean, blendTargets: IntArray, blendFunction: IntArray?,
    ): Program {
        val slots = slots(program, "gbuffers")
        val formats = attachmentFormats
        val masks = IntArray(formats.size) { if (attachments[it] in program.targets) WRITE_ALL else 0 }
        var blend = 0
        var functions: Array<IntArray?>? = null
        // Without word from the pack, the buffers the caller names blend the way the game's pipeline does.
        if (!program.blendOff) for (target in blendTargets) if (target in program.targets) blend = blend or (1 shl attachments.indexOf(target))
        if (program.blendOff || program.blendFunctions.isNotEmpty()) {
            // The pack's settings: a function for one buffer or for all, or no factors for "does not blend".
            functions = arrayOfNulls(formats.size)
            for (i in formats.indices) {
                if (attachments[i] !in program.targets) continue
                val factors = program.blendFunctions[attachments[i]] ?: program.blendFunctions[-1] ?: continue
                if (factors.size < 4) {
                    blend = blend and (1 shl i).inv()
                } else {
                    blend = blend or (1 shl i)
                    functions[i] = intArrayOf(0, 0, factors[0], factors[1], factors[2], factors[3])
                }
            }
        }
        val buffers = if (withGlobals) {
            entries(slots, PACK_UNIFORMS_ENTRY, DRAW_UNIFORMS_ENTRY, geometryBlock, GLOBALS_ENTRY)
        } else {
            entries(slots, PACK_UNIFORMS_ENTRY, DRAW_UNIFORMS_ENTRY, geometryBlock)
        }
        val compiled = PackProgram(
            device, "${pack.id}/$label", shareSamplers(pack.read(program.vertex), program, slots), shareSamplers(pack.read(program.fragment), program, slots), buffers, format,
            formats, masks, blend, depthCompare, depthWrite, if (cull) 1 else 0, blendFunction, fastMath = true, blendFunctions = functions,
        )
        return Program(program, register(compiled, DEPTH_FORMAT), slots, "gbuffers")
    }

    private fun terrainProgram(program: ConvertedSpec.Program): Program = worldProgram(
        program, program.name, TerrainVertexFormat.FORMAT, CHUNK_SECTION_ENTRY, true, true, COMPARE_GREATER_EQUAL, true, program.blend, null,
    )

    /** What the pack draws in place of [pipeline], decided the first time the game uses it. */
    private fun createSubstitute(pipeline: RenderPipeline): Draw? {
        when {
            pipeline === RenderPipelines.SOLID_TERRAIN -> return terrain?.let { Draw(it, drawUniforms(-1f)) }
            pipeline === RenderPipelines.CUTOUT_TERRAIN -> return terrain?.let { Draw(it, drawUniforms(0.5f)) }
            pipeline === RenderPipelines.TRANSLUCENT_TERRAIN -> return water?.let { Draw(it, drawUniforms(it.spec.alphaTest)) }
        }
        val shader = pipeline.vertexShader.path
        val format = pipeline.getVertexFormatBinding(0) ?: return null
        val blend = pipeline.colorTargetState?.blendFunction()?.orElse(null)
        val name: String
        val kind: String
        var defaultAlpha = -1f
        when {
            pipeline === RenderPipelines.CELESTIAL -> {
                name = "gbuffers_skytextured"
                kind = "celestial"
            }
            (shader == "core/entity" || shader == "core/item") && format === DefaultVertexFormat.ENTITY -> {
                name = if (blend != null && "gbuffers_entities_translucent" in spec.programs) "gbuffers_entities_translucent" else "gbuffers_entities"
                kind = "entity"
            }
            shader == "core/particle" && format === DefaultVertexFormat.PARTICLE &&
                pipeline !== RenderPipelines.WEATHER_DEPTH_WRITE && pipeline !== RenderPipelines.WEATHER_NO_DEPTH_WRITE -> {
                name = "gbuffers_textured"
                kind = "particle"
                defaultAlpha = 0.1f
            }
            else -> return null
        }
        val program = spec.programs[name]?.takeIf { it.kind == kind } ?: return null
        val depth = pipeline.depthStencilState
        val function = blend?.let {
            intArrayOf(
                MetalFormats.blendOp(it.color().op()), MetalFormats.blendOp(it.alpha().op()),
                MetalFormats.blendFactor(it.color().sourceFactor()), MetalFormats.blendFactor(it.color().destFactor()),
                MetalFormats.blendFactor(it.alpha().sourceFactor()), MetalFormats.blendFactor(it.alpha().destFactor()),
            )
        }
        // A blended game pipeline blends into the pack's colour image; the pack can name further buffers.
        val blendTargets = if (blend == null) IntArray(0) else intArrayOf(0) + program.blend
        val built = worldProgram(
            program, "$name for ${pipeline.location.path}", format, DYNAMIC_TRANSFORMS_ENTRY, false, pipeline.isCull,
            if (depth != null) MetalFormats.compare(depth.depthTest()) else -1, depth != null && depth.writeDepth(), blendTargets, function,
        )
        val alpha = pipeline.shaderDefines.values()["ALPHA_CUTOUT"]?.toFloatOrNull() ?: defaultAlpha
        return Draw(built, drawUniforms(alpha))
    }

    private fun shadowProgram(program: ConvertedSpec.Program): Program {
        val slots = slots(program, "shadow")
        val formats = IntArray(program.targets.size) { MetalFormats.code(GpuFormat.RGBA8_UNORM) }
        val compiled = PackProgram(
            device, "${pack.id}/shadow", shareSamplers(pack.read(program.vertex), program, slots), shareSamplers(pack.read(program.fragment), program, slots),
            entries(slots, PACK_UNIFORMS_ENTRY, DRAW_UNIFORMS_ENTRY, CHUNK_SECTION_ENTRY, GLOBALS_ENTRY), TerrainVertexFormat.FORMAT,
            formats, IntArray(formats.size) { WRITE_ALL }, 0, COMPARE_LESS_EQUAL, true, 0, fastMath = true,
        )
        return Program(program, register(compiled, DEPTH_FORMAT), slots, "shadow")
    }

    private fun passProgram(program: ConvertedSpec.Program): Program {
        val stage = when {
            program.name.startsWith("prepare") -> "prepare"
            program.name.startsWith("deferred") -> "deferred"
            program.name.startsWith("gbuffers") -> "gbuffers"
            else -> "composite"
        }
        val slots = slots(program, stage)
        val formats = IntArray(program.targets.size) {
            MetalFormats.code(if (program.name == FINAL) GpuFormat.RGBA8_UNORM else color(program.targets[it]).spec.format)
        }
        // A full-screen pass blends only where the pack asks for it.
        var passBlend = 0
        val passFunctions = arrayOfNulls<IntArray>(formats.size)
        for (i in formats.indices) {
            val factors = program.blendFunctions[program.targets[i]] ?: program.blendFunctions[-1] ?: continue
            if (factors.size < 4) continue
            passBlend = passBlend or (1 shl i)
            passFunctions[i] = intArrayOf(0, 0, factors[0], factors[1], factors[2], factors[3])
        }
        val compiled = PackProgram(
            device, "${pack.id}/${program.name}", shareSamplers(pack.read(program.vertex), program, slots), shareSamplers(pack.read(program.fragment), program, slots),
            entries(slots, PACK_UNIFORMS_ENTRY), null,
            formats, IntArray(formats.size) { WRITE_ALL }, passBlend, -1, false, 0, fastMath = true, blendFunctions = passFunctions,
        )
        return Program(program, register(compiled, MetalFormats.NO_FORMAT), slots, stage)
    }

    // ---- frame ------------------------------------------------------------------------------------------------

    fun beginWorld(minecraft: Minecraft, camera: CameraRenderState, gameProjection: Matrix4f, bob: Matrix4f, target: Upscaler.WorldTarget) {
        resize(target.width, target.height)
        writeUniforms(minecraft, camera, gameProjection, bob)
        for (color in colors) {
            if (color != null && color.spec.clear) device.encoder.clearColorTexture(color.texture!!, clearBlack)
        }
        deferredDone = false
        debugStopped = false
        renderShadow(minecraft, camera)
        for (pass in passes) if (pass.stage == "prepare") runPass(pass, target)
        sky?.let { runPass(it, target) }
    }

    /** The pack's buffers the world programs write, for passes that draw into [mainColor]. */
    fun gbuffer(mainColor: GpuTexture): ShaderPipeline.Gbuffer {
        for (i in gbufferHandles.indices) gbufferHandles[i] = color(attachments[i + 1]).view!!.handle
        val primary = if (spec.primaryTarget) color(spec.primaryBuffer).view!!.handle else 0L
        val existing = gbufferState
        if (existing != null && existing.mainColor === mainColor) {
            existing.primary = primary
            return existing
        }
        val created = if (spec.primaryTarget) {
            ShaderPipeline.Gbuffer(mainColor, gbufferHandles, gbufferFormats, ShaderPipeline.Gbuffer.PRIMARY, attachmentFormats[0])
        } else {
            ShaderPipeline.Gbuffer(mainColor, gbufferHandles, gbufferFormats, spec.gbuffers.indexOf(0))
        }
        created.primary = primary
        gbufferState = created
        return created
    }

    /** The pack's program for a game pipeline, or null when the pack has none (the game's own shader then draws). */
    fun substitute(pipeline: RenderPipeline): PackProgram? {
        val draw = if (substitutes.containsKey(pipeline)) substitutes[pipeline] else createSubstitute(pipeline).also { substitutes[pipeline] = it }
        currentProgram = draw?.program
        currentDraw = draw?.uniforms
        return draw?.program?.program
    }

    /** Gives a game pass that just switched to one of the pack's programs what the game does not bind. */
    fun bindDraw(pass: MetalRenderPass) {
        val program = currentProgram ?: return
        pass.setUniform(PACK_UNIFORMS, frameSlice ?: return)
        pass.setUniform(DRAW_UNIFORMS, currentDraw ?: return)
        if (program.spec.kind == "terrain") pass.overrideSampler("Sampler0", atlas)
        bindTextures(pass, program)
    }

    /** Called before the game draws translucent terrain: the opaque world is complete. */
    fun beforeTranslucent(target: Upscaler.WorldTarget) {
        if (deferredDone) return
        deferredDone = true
        copyDepth(target.depthView, depth1View!!)
        copyDepth(target.depthView, depth0View!!)
        for (pass in passes) if (pass.stage == "deferred") runPass(pass, target)
        for (i in 0 until snapshots.size) {
            val color = color(snapshots.getInt(i))
            val from = color.texture!!
            val copy = color.altTexture ?: continue
            device.encoder.copyTextureToTexture(from, copy, 0, 0, 0, 0, 0, from.getWidth(0), from.getHeight(0))
        }
        for (i in 0 until worldMips.size) {
            val color = color(worldMips.getInt(i))
            // A buffer the translucent programs also write is read through its copy.
            val read = if (snapshots.contains(color.spec.index)) color.altTexture ?: color.texture else color.texture
            read?.let { device.encoder.generateMipmaps(it) }
        }
        // The deferred passes may have flipped buffers the translucent programs write to.
        gbuffer(target.colorTexture)
    }

    fun endWorld(target: Upscaler.WorldTarget) {
        beforeTranslucent(target)
        copyDepth(target.depthView, depth0View!!)
        for (pass in passes) if (pass.stage == "composite") runPass(pass, target)
        debugView?.let { showBuffer(it, target) }
        if (!reportedMissing) {
            reportedMissing = true
            val computed = customUniforms?.names ?: emptyList()
            val missing = spec.uniforms.map { it.name }.filter { it !in written && it !in computed }
            if (missing.isNotEmpty()) LOGGER.info("Shader pack '{}': uniforms the engine does not set yet (they are 0): {}", pack.id, missing)
        }
    }

    private fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) return
        releaseTargets()
        width = newWidth
        height = newHeight
        for (color in colors) {
            if (color == null) continue
            val w = if (color.spec.width > 0) color.spec.width else maxOf(1, (newWidth * color.spec.scale).toInt())
            val h = if (color.spec.height > 0) color.spec.height else maxOf(1, (newHeight * color.spec.scaleY).toInt())
            color.texture = colorTexture("colortex${color.spec.index}", color.spec.format, w, h, color.mipmapped)
            color.view = device.createTextureView(color.texture!!) as MetalTextureView
            // Buffers the pack does not clear keep their content between frames: start them from zero.
            device.encoder.clearColorTexture(color.texture!!, clearBlack)
            if (color.needsAlt) {
                color.altTexture = colorTexture("colortex${color.spec.index} (alt)", color.spec.format, w, h, color.mipmapped)
                color.altView = device.createTextureView(color.altTexture!!) as MetalTextureView
                device.encoder.clearColorTexture(color.altTexture!!, clearBlack)
            }
        }
        depth0Texture = colorTexture("depthtex0", GpuFormat.R32_FLOAT, newWidth, newHeight)
        depth0View = device.createTextureView(depth0Texture!!) as MetalTextureView
        depth1Texture = colorTexture("depthtex1", GpuFormat.R32_FLOAT, newWidth, newHeight)
        depth1View = device.createTextureView(depth1Texture!!) as MetalTextureView
        gbufferState = null
        hasPrevious = false
    }

    private fun renderShadow(minecraft: Minecraft, camera: CameraRenderState) {
        val program = shadow ?: return
        val position = camera.pos
        device.encoder.clearColorTexture(shadowColors[0], clearWhite)
        device.encoder.clearColorTexture(shadowColors[1], clearWhite)
        val sections = ShaderPipeline.shadowSections(minecraft, shadowModelView, spec.shadowDistance, position.x, position.y, position.z, true)
        withCasters = MetalCraftConfig.entityShadows
        if (withCasters) casters.update(minecraft, camera, spec.shadowDistance, shadowModelView)
        // Opaque terrain alone first. The second map starts as a copy of that depth and adds the translucent
        // layer on top, which leaves the colours of what is nearest to the light, translucent or not.
        drawShadow(program, sections, shadowDepthView, false)
        device.encoder.copyTextureToTexture(shadowDepth, shadowDepthAll, 0, 0, 0, 0, 0, spec.shadowResolution, spec.shadowResolution)
        drawShadow(program, sections, shadowDepthAllView, true)
    }

    private fun drawShadow(program: Program, sections: ChunkSectionsToRender, depth: MetalTextureView, translucentOnly: Boolean) {
        val descriptor = RenderPassDescriptor.create { "MetalCraft shadow" }
        for (i in program.spec.targets.indices) descriptor.withColorAttachment(shadowColorViews[program.spec.targets[i]])
        descriptor.withDepthAttachment(depth, if (translucentOnly) OptionalDouble.empty() else OptionalDouble.of(1.0))
            .withRenderArea(RenderPass.RenderArea(0, 0, spec.shadowResolution, spec.shadowResolution))
        val pass = device.encoder.createRenderPass(descriptor) as MetalRenderPass
        try {
            pass.setProgram(program.program)
            pass.setUniform(PACK_UNIFORMS, frameSlice!!)
            pass.setUniform(DRAW_UNIFORMS, drawShadow)
            RenderSystem.getGlobalSettingsUniform()?.let { pass.setUniform("Globals", it) }
            pass.bindTexture("Sampler0", sections.textureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
            bindTextures(pass, program)
            ShaderPipeline.drawShadowTerrain(pass, sections, translucentOnly)
            if (withCasters && !translucentOnly) casters.draw(pass)
        } finally {
            device.encoder.submitRenderPass()
        }
    }

    /** Debugging aid: the buffer named by METALCRAFT_DEBUG_VIEW in place of the pack's final image. */
    private fun showBuffer(program: PackProgram, target: Upscaler.WorldTarget) {
        val name = DEBUG_VIEW ?: return
        val view = when {
            name == "depthtex0" -> depth0View
            name == "depthtex1" -> depth1View
            name.startsWith("shadowcolor") -> shadowColorViews[name.last() - '0']
            else -> colors.getOrNull(name.removePrefix("colortex").toIntOrNull() ?: -1)?.view
        } ?: return
        val descriptor = RenderPassDescriptor.create { "MetalCraft debug view" }
            .withColorAttachment(target.colorView)
            .withRenderArea(RenderPass.RenderArea(0, 0, target.width, target.height))
        val pass = device.encoder.createRenderPass(descriptor) as MetalRenderPass
        try {
            pass.setProgram(program)
            pass.bindTexture("source", view, nearest)
            pass.draw(3, 1, 0, 0)
        } finally {
            device.encoder.submitRenderPass()
        }
    }

    private fun copyDepth(gameDepth: MetalTextureView, into: MetalTextureView) {
        val descriptor = RenderPassDescriptor.create { "MetalCraft depth copy" }
            .withColorAttachment(into)
            .withRenderArea(RenderPass.RenderArea(0, 0, into.getWidth(0), into.getHeight(0)))
        val pass = device.encoder.createRenderPass(descriptor) as MetalRenderPass
        try {
            pass.setProgram(depthCopy)
            pass.bindTexture(GAME_DEPTH, gameDepth, nearest)
            pass.draw(3, 1, 0, 0)
        } finally {
            device.encoder.submitRenderPass()
        }
    }

    private fun runPass(pass: Program, target: Upscaler.WorldTarget) {
        val isFinal = pass.spec.name == FINAL
        val targets = pass.spec.targets
        // A pass that writes nothing has nothing to draw into.
        if (targets.isEmpty() && !isFinal) return
        if (debugStopped || (isFinal && debugView != null)) return
        for (index in pass.spec.mipmaps) colors.getOrNull(index)?.texture?.let { device.encoder.generateMipmaps(it) }
        if (pass.spec.name == DEBUG_STOP) debugStopped = true
        val descriptor = RenderPassDescriptor.create(pass.label)
        var outWidth = target.width
        var outHeight = target.height
        if (isFinal) {
            descriptor.withColorAttachment(target.colorView)
        } else {
            for (index in targets) {
                val color = color(index)
                // A buffer the pass also reads is written to its second image, which then becomes the buffer.
                val view = if (reads(pass, index)) color.altView!! else color.view!!
                descriptor.withColorAttachment(view)
                outWidth = view.getWidth(0)
                outHeight = view.getHeight(0)
            }
        }
        descriptor.withRenderArea(RenderPass.RenderArea(0, 0, outWidth, outHeight))
        val renderPass = device.encoder.createRenderPass(descriptor) as MetalRenderPass
        try {
            renderPass.setProgram(pass.program)
            renderPass.setUniform(PACK_UNIFORMS, frameSlice!!)
            bindTextures(renderPass, pass)
            renderPass.draw(6, 1, 0, 0)
        } finally {
            device.encoder.submitRenderPass()
        }
        if (isFinal) return
        for (index in targets) {
            if (!reads(pass, index)) continue
            val color = color(index)
            val texture = color.texture
            val view = color.view
            color.texture = color.altTexture
            color.view = color.altView
            color.altTexture = texture
            color.altView = view
        }
    }

    private fun reads(pass: Program, colorIndex: Int): Boolean {
        for (slot in pass.slots) if (slot.source == SOURCE_COLOR && slot.index == colorIndex) return true
        return false
    }

    private fun bindTextures(pass: MetalRenderPass, program: Program) {
        for (slot in program.slots) bindSlot(pass, slot)
    }

    private fun imageSampler(image: Image): MetalSampler =
        if (image.linear) { if (image.repeat) linearRepeat else linear } else { if (image.repeat) nearestRepeat else nearest }

    /** The sampler [slot] is bound with, or null for the block atlas (the game binds that). */
    private fun samplerOf(slot: Slot): MetalSampler? = when (slot.source) {
        SOURCE_GAME -> null
        SOURCE_IMAGE -> imageSampler(slot.image!!)
        SOURCE_DEPTH0, SOURCE_DEPTH1 -> nearest
        SOURCE_SHADOW_DEPTH -> if (slot.compare) shadowCompare else nearest
        else -> if (slot.mip) linearMip else linear
    }

    /**
     * Metal has 31 texture slots but 16 for samplers. In a program with more textures, those past the 16th
     * are sampled with the sampler of an earlier texture that is bound with the same one.
     */
    private fun shareSamplers(msl: String, program: ConvertedSpec.Program, slots: Array<Slot>): String {
        if (slots.size <= MAX_SAMPLERS) return msl
        var text = msl
        val aliases = StringBuilder()
        for (i in MAX_SAMPLERS until slots.size) {
            val sampler = samplerOf(slots[i])
            val other = (0 until MAX_SAMPLERS).firstOrNull { sampler != null && samplerOf(slots[it]) === sampler }
                ?: throw IllegalArgumentException("${program.name} uses ${slots.size} textures and '${program.textures[i].name}' has no sampler to share")
            val name = program.textures[i].name
            val declaration = Regex(""",\s*sampler ${name}Smplr \[\[sampler\(\d+\)]]""")
            if (!declaration.containsMatchIn(text)) continue
            // The entry point loses the parameter and gets a local of the same name instead.
            text = text.replace(declaration, "")
            aliases.append("    sampler ${name}Smplr = ${program.textures[other].name}Smplr;\n")
        }
        if (aliases.isNotEmpty()) {
            val entry = text.indexOf(" main0(")
            val body = text.indexOf('{', entry)
            if (entry >= 0 && body >= 0) text = text.substring(0, body + 1) + "\n" + aliases + text.substring(body + 1)
        }
        return text
    }

    private fun bindSlot(pass: MetalRenderPass, slot: Slot) {
        run {
            when (slot.source) {
                SOURCE_GAME -> {}
                SOURCE_IMAGE -> pass.bindTexture(slot.entryName, slot.image!!.view, imageSampler(slot.image))
                SOURCE_COLOR -> pass.bindTexture(slot.entryName, color(slot.index).view!!, if (slot.mip) linearMip else linear)
                SOURCE_COLOR_SNAPSHOT -> pass.bindTexture(slot.entryName, color(slot.index).altView ?: color(slot.index).view!!, if (slot.mip) linearMip else linear)
                SOURCE_DEPTH0 -> pass.bindTexture(slot.entryName, depth0View!!, nearest)
                SOURCE_DEPTH1 -> pass.bindTexture(slot.entryName, depth1View!!, nearest)
                SOURCE_SHADOW_DEPTH -> pass.bindTexture(
                    slot.entryName, if (slot.index == 0) shadowDepthAllView else shadowDepthView, if (slot.compare) shadowCompare else nearest,
                )
                else -> pass.bindTexture(slot.entryName, shadowColorViews[slot.index], linear)
            }
        }
    }

    // ---- uniforms ---------------------------------------------------------------------------------------------

    private fun writeUniforms(minecraft: Minecraft, camera: CameraRenderState, gameProjection: Matrix4f, bob: Matrix4f) {
        val now = System.nanoTime()
        val held = ShaderPipeline.holdTime
        val frameTime = if (held || lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9).toFloat().coerceIn(0.0001f, 1f)
        lastFrameNanos = now
        if (held) {
            seconds = HELD_SECONDS
            frameCounter = 0
        } else {
            seconds += frameTime
        }
        // Iris wraps the counter every hour so that it keeps its precision as a float.
        if (seconds >= 3600f) seconds -= 3600f
        val level = minecraft.level
        val levelState = minecraft.gameRenderer.gameRenderState().levelRenderState
        val sky = levelState.skyRenderState
        val position = camera.pos

        // The camera. The pack gets the projection OpenGL would use for the same view: the game's has depth reversed.
        // The game puts the sway of walking and of being hurt into its projection. A pack works back from
        // pixels to positions with the projection alone, so the sway has to be part of the view instead.
        modelView.set(bob).mul(camera.viewRotationMatrix)
        matrix("mc_bob", bob)
        projection.set(gameProjection).mul(bob.invert(inverse))
        // The game's matrix has m22 = near / (far - near) and m32 = near * far / (far - near).
        val near = projection.m32() / (1f + projection.m22())
        val far = projection.m32() / projection.m22()
        projection.m22(-(far + near) / (far - near)).m32(-2f * far * near / (far - near))
        if (!hasPrevious) {
            previousModelView.set(modelView)
            previousProjection.set(projection)
            previousX = position.x
            previousY = position.y
            previousZ = position.z
            hasPrevious = true
        }
        matrix("gbufferModelView", modelView)
        matrix("gbufferModelViewInverse", modelView.invert(inverse))
        matrix("gbufferProjection", projection)
        matrix("gbufferProjectionInverse", projection.invert(inverse))
        matrix("gbufferPreviousModelView", previousModelView)
        matrix("gbufferPreviousProjection", previousProjection)
        vector("cameraPosition", position.x.toFloat(), position.y.toFloat(), position.z.toFloat())
        vector("previousCameraPosition", previousX.toFloat(), previousY.toFloat(), previousZ.toFloat())
        integers("cameraPositionInt", Math.floor(position.x).toInt(), Math.floor(position.y).toInt(), Math.floor(position.z).toInt())
        integers("previousCameraPositionInt", Math.floor(previousX).toInt(), Math.floor(previousY).toInt(), Math.floor(previousZ).toInt())
        vector("cameraPositionFract", fraction(position.x), fraction(position.y), fraction(position.z))
        vector("previousCameraPositionFract", fraction(previousX), fraction(previousY), fraction(previousZ))
        vector("eyePosition", position.x.toFloat(), position.y.toFloat(), position.z.toFloat())
        vector("relativeEyePosition", 0f, 0f, 0f)
        modelView.invert(inverse).transformDirection(look.set(0f, 0f, -1f))
        vector("playerLookVector", look.x, look.y, look.z)
        previousModelView.set(modelView)
        previousProjection.set(projection)
        previousX = position.x
        previousY = position.y
        previousZ = position.z

        // Time of day as the pack counts it: 0 at sunrise, 0.25 at noon, 0.5 at sunset.
        val turn = (sky.sunAngle / (2f * Math.PI.toFloat())).let { it - Math.floor(it.toDouble()).toFloat() }
        val sunAngle = if (turn < 0.75f) turn + 0.25f else turn - 0.75f
        float("sunAngle", sunAngle)
        // Where the sun, the moon and "up" are, in view space and 100 units away, as packs expect them.
        celestial.identity()
            .rotateY(Math.toRadians(-90.0).toFloat())
            .rotateZ(Math.toRadians(spec.sunPathRotation.toDouble()).toFloat())
            .rotateX(Math.toRadians(turn * 360.0).toFloat())
        celestial.transformDirection(look.set(0f, 100f, 0f))
        modelView.transformDirection(look)
        vector("sunPosition", look.x, look.y, look.z)
        if (sunAngle <= 0.5f) vector("shadowLightPosition", look.x, look.y, look.z)
        look.normalize()
        vector("sunVec", look.x, look.y, look.z)
        celestial.transformDirection(look.set(0f, -100f, 0f))
        modelView.transformDirection(look)
        vector("moonPosition", look.x, look.y, look.z)
        if (sunAngle > 0.5f) vector("shadowLightPosition", look.x, look.y, look.z)
        modelView.transformDirection(look.set(0f, 100f, 0f))
        vector("upPosition", look.x, look.y, look.z)
        look.normalize()
        vector("upVec", look.x, look.y, look.z)
        // The light that casts shadows is the sun by day and the moon by night. The matrices follow the
        // conventions packs are written against: the light looks down its own z axis from 100 blocks away,
        // and the map moves in whole steps so that its texels do not crawl as the camera moves.
        val shadowAngle = if (sunAngle <= 0.5f) sunAngle else sunAngle - 0.5f
        val skyAngle = if (shadowAngle < 0.25f) shadowAngle + 0.75f else shadowAngle - 0.25f
        shadowModelView.identity().translate(0f, 0f, -100f)
            .rotateX(Math.toRadians(90.0).toFloat())
            .rotateZ(Math.toRadians(skyAngle * -360.0).toFloat())
            .rotateX(Math.toRadians(spec.sunPathRotation.toDouble()).toFloat())
            .translate(snap(position.x), snap(position.y), snap(position.z))
        shadowProjection.setOrtho(-spec.shadowDistance, spec.shadowDistance, -spec.shadowDistance, spec.shadowDistance, SHADOW_NEAR, SHADOW_FAR)
        matrix("shadowModelView", shadowModelView)
        matrix("shadowModelViewInverse", shadowModelView.invert(inverse))
        matrix("shadowProjection", shadowProjection)
        matrix("shadowProjectionInverse", shadowProjection.invert(inverse))

        integer("frameCounter", frameCounter)
        float("frameTime", frameTime)
        float("frameTimeCounter", seconds)
        float("framemod2", (frameCounter % 2).toFloat())
        float("framemod4", (frameCounter % 4).toFloat())
        number("framemod8", frameCounter % 8)
        float("framemod600", (frameCounter % 600).toFloat())
        if (!held) frameCounter = (frameCounter + 1) % 720720
        float("frameTimeSmooth", frameTimeSmooth.update(frameTime, frameTime))
        val clock = level?.overworldClockTime ?: 0L
        integer("worldTime", (clock % 24000L).toInt())
        integer("worldDay", (clock / 24000L).toInt())
        integer("moonPhase", sky.moonPhase.ordinal)
        val rain = level?.getRainLevel(1f) ?: 0f
        float("rainStrength", rain)
        float("wetness", rain)
        float("rainFactor", rainFactor.update(rain, frameTime))

        val renderDistance = minecraft.options.effectiveRenderDistance * 16f
        float("near", NEAR)
        float("far", renderDistance)
        float("viewWidth", width.toFloat())
        float("viewHeight", height.toFloat())
        float("aspectRatio", width.toFloat() / height)
        float("screenBrightness", minecraft.options.gamma().get().toFloat())
        float("eyeAltitude", position.y.toFloat())
        float("cloudHeight", levelState.cloudHeight)
        val atlas = minecraft.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).texture
        integers("atlasSize", atlas.getWidth(0), atlas.getHeight(0))
        val fog = camera.fogData
        vector("fogColor", fog.color.x, fog.color.y, fog.color.z)
        vector("mc_fogColor", fog.color.x, fog.color.y, fog.color.z, 1f)
        // Iris sets this per draw; one value for all is right for cut-out textures and harmless for the rest.
        float("alphaTestRef", 0.1f)
        vector("skyColor", (sky.skyColor shr 16 and 0xFF) / 255f, (sky.skyColor shr 8 and 0xFF) / 255f, (sky.skyColor and 0xFF) / 255f)
        float("fogStart", fog.renderDistanceStart)
        float("fogEnd", fog.renderDistanceEnd)
        val fogRange = maxOf(fog.renderDistanceEnd - fog.renderDistanceStart, 0.001f)
        vector("mc_fog", fog.renderDistanceStart, fog.renderDistanceEnd, 1f / fogRange, 0f)
        val inWater = when (camera.fogType) {
            FogType.WATER -> 1
            FogType.LAVA -> 2
            FogType.POWDER_SNOW -> 3
            else -> 0
        }
        integer("isEyeInWater", inWater)

        // What the player holds: the light it gives off and the pack's own ID for it.
        val player = minecraft.player
        val mainHand = player?.mainHandItem
        val offHand = player?.offhandItem
        // The player's state as Iris reports it: fractions of the maximum, -1 where the game mode has none.
        val survival = minecraft.gameMode?.playerMode?.isSurvival == true
        if (player != null && survival) {
            float("currentPlayerHealth", player.health / player.maxHealth)
            float("currentPlayerHunger", player.foodData.foodLevel / 20f)
            float("currentPlayerAir", player.airSupply.toFloat() / player.maxAirSupply)
            float("currentPlayerArmor", player.armorValue / 50f)
        } else {
            float("currentPlayerHealth", -1f)
            float("currentPlayerHunger", -1f)
            float("currentPlayerAir", -1f)
            float("currentPlayerArmor", -1f)
        }
        float("maxPlayerHealth", player?.maxHealth ?: -1f)
        float("maxPlayerHunger", 20f)
        float("maxPlayerAir", player?.maxAirSupply?.toFloat() ?: -1f)
        float("maxPlayerArmor", 50f)
        float("nightVision", if (player?.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION) == true) 1f else 0f)
        float("blindness", if (player?.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) == true) 1f else 0f)
        float("darknessFactor", if (player?.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS) == true) 1f else 0f)
        integer("heldBlockLightValue", heldLight(mainHand))
        integer("heldBlockLightValue2", heldLight(offHand))
        integer("heldItemId", heldId(mainHand))
        integer("heldItemId2", heldId(offHand))

        var blockLight = 0
        var skyLight = 15
        if (level != null) {
            lightProbe.set(Math.floor(position.x).toInt(), Math.floor(position.y).toInt(), Math.floor(position.z).toInt())
            blockLight = level.getBrightness(LightLayer.BLOCK, lightProbe)
            skyLight = level.getBrightness(LightLayer.SKY, lightProbe)
        }
        integers("eyeBrightness", blockLight * 16, skyLight * 16)
        integers(
            "eyeBrightnessSmooth",
            Math.round(eyeBlockSmooth.update(blockLight * 16f, frameTime)), Math.round(eyeSkySmooth.update(skyLight * 16f, frameTime)),
        )
        val skyBrightness = skyLight * 16 / 240f
        float("eyeBrightnessM", eyeBrightnessSmooth.update(skyBrightness, frameTime))
        float("eyeBrightnessM2", eyeBrightnessSmooth2.update(if (skyLight >= 15) 1f else 0f, frameTime))
        val caveTarget = if (position.y < 5.0) skyBrightness else 1f
        float("isEyeInCave", if (inWater == 0) 1f - eyeInCave.update(caveTarget, frameTime) else 0f)
        float("starter", 1f)
        writeBiomeUniforms(level, frameTime)
        writeCustomUniforms(minecraft, level, frameTime)

        data.position(0).limit(spec.uniformBlockSize)
        frameSlice = device.encoder.transientMemory().uploadGpu(data, 16L, GpuBuffer.USAGE_UNIFORM)
    }

    /** The pack's own expressions, after everything they may refer to has been written. */
    private fun writeCustomUniforms(minecraft: Minecraft, level: Level?, frameTime: Float) {
        val custom = customUniforms ?: return
        val inputs = custom.inputs
        val player = minecraft.player
        if (player != null) {
            inputs.inWater = player.isInWater
            inputs.inLava = player.isInLava
            inputs.sneaking = player.isCrouching
            inputs.sprinting = player.isSprinting
            inputs.onGround = player.onGround()
            inputs.alive = player.isAlive
            inputs.burning = player.isOnFire
            inputs.hurt = player.hurtTime > 0
            inputs.invisible = player.isInvisible
            inputs.glowing = player.isCurrentlyGlowing
            inputs.riding = player.isPassenger
            inputs.wet = player.isInWaterOrRain
        }
        if (level != null) {
            val biome = level.getBiome(lightProbe)
            val climate = biome.value()
            inputs.temperature = climate.baseTemperature
            inputs.rainfall = if (climate.hasPrecipitation()) 1f else 0f
            inputs.precipitation = when (climate.getPrecipitationAt(lightProbe, level.seaLevel)) {
                Biome.Precipitation.RAIN -> 1f
                Biome.Precipitation.SNOW -> 2f
                else -> 0f
            }
            val key = biome.unwrapKey().orElse(null)
            if (key !== lastBiomeKey) {
                lastBiomeKey = key
                inputs.biomeName = key?.identifier()?.path ?: ""
            }
        }
        custom.evaluate(data, frameTime, written)
    }

    /** How much the camera is "in" each kind of biome, fading over a few seconds as the pack's own rules say. */
    private fun writeBiomeUniforms(level: Level?, frameTime: Float) {
        var precipitation = Biome.Precipitation.RAIN
        var biome: Holder<Biome>? = null
        if (level != null) {
            biome = level.getBiome(lightProbe)
            precipitation = biome.value().getPrecipitationAt(lightProbe, level.seaLevel)
        }
        float("inDry", inDry.update(if (precipitation == Biome.Precipitation.NONE) 1f else 0f, frameTime))
        float("inRainy", inRainy.update(if (precipitation == Biome.Precipitation.RAIN) 1f else 0f, frameTime))
        float("inSnowy", inSnowy.update(if (precipitation == Biome.Precipitation.SNOW) 1f else 0f, frameTime))
        for (i in BIOME_UNIFORMS.indices) {
            float(BIOME_UNIFORMS[i], inBiome[i].update(if (biome != null && biome.`is`(BIOME_KEYS[i])) 1f else 0f, frameTime))
        }
    }

    private fun fraction(value: Double): Float = (value - Math.floor(value)).toFloat()

    /** Offset that keeps the shadow map on a grid of [SHADOW_INTERVAL] blocks. */
    private fun snap(coordinate: Double): Float = (coordinate % SHADOW_INTERVAL).toFloat() - SHADOW_INTERVAL / 2f

    /**
     * Where [name] goes in the block, or -1 if the pack does not have it. During the first frame it also
     * checks that the pack declares it with the type the engine writes: anything else would overwrite its
     * neighbours, so such a uniform is left alone.
     */
    private fun offset(name: String, type: String, otherType: String? = null): Int {
        if (!reportedMissing) {
            written.add(name)
            val declared = types[name]
            if (declared != null && declared != type && declared != otherType) {
                LOGGER.warn("Shader pack '{}' declares uniform {} as {}, the engine provides {}. It stays 0", pack.id, name, declared, type)
                offsets.removeInt(name)
            }
        }
        return offsets.getInt(name)
    }

    /** A whole number for a uniform that packs declare as int or as float. */
    private fun number(name: String, value: Int) {
        val at = offset(name, "int", "float")
        if (at >= 0) {
            if (types[name] == "float") data.putFloat(at, value.toFloat()) else data.putInt(at, value)
        }
    }

    private fun float(name: String, value: Float) {
        val at = offset(name, "float")
        if (at >= 0) data.putFloat(at, value)
    }

    private fun heldLight(stack: net.minecraft.world.item.ItemStack?): Int {
        val item = stack?.item
        return if (item is net.minecraft.world.item.BlockItem) item.block.defaultBlockState().lightEmission else 0
    }

    private fun heldId(stack: net.minecraft.world.item.ItemStack?): Int = if (stack == null) 0 else itemIds.getInt(stack.item)

    /** The pack's `item.<id>=<names>` table as a lookup by item. Items it does not name are 0. */
    private fun loadItemIds(): it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<net.minecraft.world.item.Item> {
        val table = it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<net.minecraft.world.item.Item>()
        val text = pack.itemIdsFile?.let(pack::read) ?: return table
        for (line in text.lineSequence()) {
            val separator = line.indexOf('=')
            if (!line.startsWith("item.") || separator < 0) continue
            val id = line.substring(5, separator).trim().toIntOrNull() ?: continue
            for (name in line.substring(separator + 1).trim().split(Regex("\\s+"))) {
                val key = net.minecraft.resources.Identifier.tryParse(name) ?: continue
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(key).ifPresent { table.put(it, id) }
            }
        }
        return table
    }

    private fun integer(name: String, value: Int) {
        val at = offset(name, "int", "bool")
        if (at >= 0) data.putInt(at, value)
    }

    // A vector of two or three components is shorter than 16 bytes and the next uniform may follow it directly.

    private fun integers(name: String, x: Int, y: Int) {
        val at = offset(name, "ivec2")
        if (at >= 0) data.putInt(at, x).putInt(at + 4, y)
    }

    private fun integers(name: String, x: Int, y: Int, z: Int) {
        val at = offset(name, "ivec3")
        if (at >= 0) data.putInt(at, x).putInt(at + 4, y).putInt(at + 8, z)
    }

    private fun vector(name: String, x: Float, y: Float, z: Float) {
        val at = offset(name, "vec3")
        if (at >= 0) data.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z)
    }

    private fun vector(name: String, x: Float, y: Float, z: Float, w: Float) {
        val at = offset(name, "vec4")
        if (at >= 0) data.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, w)
    }

    private fun matrix(name: String, value: Matrix4f) {
        val at = offset(name, "mat4")
        if (at >= 0) value.get(at, data)
    }

    /** A value that follows its target with a half-life, as the pack's `smooth()` custom uniforms do. */
    private class Smoothed(private val up: Float, private val down: Float) {
        private var value = 0f
        private var started = false

        fun update(target: Float, frameTime: Float): Float {
            if (!started) {
                started = true
                value = target
            } else {
                val halfLife = (if (target > value) up else down) * 0.1f
                value += (target - value) * (1f - Math.exp((-frameTime * LN2 / halfLife).toDouble()).toFloat())
            }
            return value
        }
    }

    // ---- cleanup ----------------------------------------------------------------------------------------------

    private fun releaseTargets() {
        for (color in colors) {
            if (color == null) continue
            color.view?.close()
            color.texture?.close()
            color.altView?.close()
            color.altTexture?.close()
            color.view = null
            color.texture = null
            color.altView = null
            color.altTexture = null
        }
        depth0View?.close()
        depth0Texture?.close()
        depth1View?.close()
        depth1Texture?.close()
        depth0View = null
        depth0Texture = null
        depth1View = null
        depth1Texture = null
        gbufferState = null
        width = 0
        height = 0
    }

    fun destroy() {
        releaseTargets()
        for (image in images.values) {
            image.view.close()
            image.texture.close()
        }
        images.clear()
        for (view in shadowColorViews) view.close()
        for (texture in shadowColors) texture.close()
        shadowDepthView.close()
        shadowDepth.close()
        shadowDepthAllView.close()
        shadowDepthAll.close()
        linear.close()
        linearMip.close()
        nearestRepeat.close()
        nearest.close()
        linearRepeat.close()
        shadowCompare.close()
        atlas.close()
        casters.close()
        for (buffer in drawBuffers) buffer.close()
        drawBuffers.clear()
        // Programs may be bound in the frame being recorded; Metal keeps what a command buffer uses alive.
        for (program in programs) program.destroy()
        programs.clear()
        MemoryUtil.memFree(data)
    }

    private companion object {
        const val PACK_UNIFORMS = "PackUniforms"
        const val DRAW_UNIFORMS = "DrawUniforms"
        const val GAME_DEPTH = "gameDepth"
        const val FINAL = "final"
        const val WRITE_ALL = 15
        const val COMPARE_LESS_EQUAL = 3
        const val COMPARE_GREATER_EQUAL = 6
        const val NEAR = 0.05f
        const val SHADOW_NEAR = 0.05f
        const val SHADOW_FAR = 256f
        const val SHADOW_INTERVAL = 2f
        const val LN2 = 0.6931472f

        /** What the pack's clock reads while `ShaderPipeline.holdTime` is set. */
        const val HELD_SECONDS = 120f

        const val SOURCE_GAME = 0
        const val SOURCE_IMAGE = 1

        /** METALCRAFT_DEBUG_VIEW=colortex3 shows that buffer instead of the final image; METALCRAFT_DEBUG_STOP=deferred2 runs no full-screen pass after that one. */
        val DEBUG_VIEW: String? = System.getenv("METALCRAFT_DEBUG_VIEW")
        val DEBUG_STOP: String? = System.getenv("METALCRAFT_DEBUG_STOP")
        const val MAX_SAMPLERS = 16
        const val SOURCE_COLOR = 2
        const val SOURCE_DEPTH0 = 3
        const val SOURCE_DEPTH1 = 4
        const val SOURCE_SHADOW_DEPTH = 5
        const val SOURCE_SHADOW_COLOR = 6
        const val SOURCE_COLOR_SNAPSHOT = 7

        // Custom uniforms of the Complementary family of packs: 1 while the camera is in the biome.
        val BIOME_UNIFORMS = arrayOf(
            "inNetherWastes", "inCrimsonForest", "inWarpedForest", "inBasaltDeltas", "inSoulValley", "inPaleGarden", "inSulfurCaves",
        )
        val BIOME_KEYS = arrayOf(
            Biomes.NETHER_WASTES, Biomes.CRIMSON_FOREST, Biomes.WARPED_FOREST, Biomes.BASALT_DELTAS, Biomes.SOUL_SAND_VALLEY,
            Biomes.PALE_GARDEN, Biomes.SULFUR_CAVES,
        )

        val DEPTH_FORMAT = MetalFormats.code(GpuFormat.D32_FLOAT)
        // The names buffers had in older packs.
        val GAUX = mapOf(
            "gcolor" to "colortex0", "gdepth" to "colortex1", "gnormal" to "colortex2", "composite" to "colortex3",
            "gaux1" to "colortex4", "gaux2" to "colortex5", "gaux3" to "colortex6", "gaux4" to "colortex7",
            "gdepthtex" to "depthtex0", "shadow" to "shadowtex0", "watershadow" to "shadowtex0",
        )

        /** The block atlas, which the game binds itself as Sampler0. */
        val ATLAS_NAMES = setOf("tex", "gtexture", "texture")
        const val NOISE_SIZE = 256
        const val NOISE_SEED = 2626L

        // Buffer indices as the converter assigns them.
        val PACK_UNIFORMS_ENTRY = BindingEntry(BindingEntry.UNIFORM_BUFFER, PACK_UNIFORMS, null, 0)
        val DRAW_UNIFORMS_ENTRY = BindingEntry(BindingEntry.UNIFORM_BUFFER, DRAW_UNIFORMS, null, 1)
        val CHUNK_SECTION_ENTRY = BindingEntry(BindingEntry.UNIFORM_BUFFER, "ChunkSection", null, 2)
        val GLOBALS_ENTRY = BindingEntry(BindingEntry.UNIFORM_BUFFER, "Globals", null, 3)
        val DYNAMIC_TRANSFORMS_ENTRY = BindingEntry(BindingEntry.UNIFORM_BUFFER, "DynamicTransforms", null, 2)
    }
}
