package dev.skarch.metalcraft.pipeline

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import dev.skarch.metalcraft.MetalCraft
import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.backend.BindingEntry
import dev.skarch.metalcraft.backend.DrawProgram
import dev.skarch.metalcraft.backend.MetalDevice
import dev.skarch.metalcraft.backend.MetalFormats
import dev.skarch.metalcraft.backend.MetalRenderPass
import dev.skarch.metalcraft.backend.MetalSampler
import dev.skarch.metalcraft.backend.MetalTexture
import dev.skarch.metalcraft.backend.MetalTextureView
import dev.skarch.metalcraft.backend.PackProgram
import dev.skarch.metalcraft.config.MetalCraftConfig
import dev.skarch.metalcraft.mixin.LevelRendererAccessor
import dev.skarch.metalcraft.upscale.Upscaler
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.client.renderer.state.level.CameraRenderState
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.OptionalDouble

/**
 * The shader pack engine (CLAUDE.md section 3-5). With a pack selected, a frame of the world runs as
 *
 *   shadow      the terrain drawn from the light into the shadow map
 *   gbuffers    the game draws the world as usual; terrain uses the pack's program and also fills the
 *               pack's extra colour buffers
 *   deferred, composite, final   the pack's full-screen passes, the last of which writes the game image
 *
 * and the game then draws the first-person hand and the GUI on top. Everything runs on the render thread.
 *
 * Not covered yet: entities and other non-terrain geometry in the shadow map and with pack programs, shadows
 * from terrain the game has not built yet (it only builds what the camera has seen), and the "improved
 * transparency" setting (the pack is skipped while it is on).
 */
object ShaderPipeline {
    const val PROPERTY = "metalcraft.shaderpack"
    private const val OFF = "off"
    private const val FRAME_UNIFORMS = "FrameUniforms"
    private const val FRAME_UNIFORMS_SIZE = 512

    /** The pack's extra colour targets, attached to passes that draw into the game image. */
    internal class Gbuffer(val mainColor: GpuTexture, val handles: LongArray, val formats: IntArray)

    /** Non-null while the game draws the world with a pack active. Read by the backend for every pass. */
    @JvmField
    internal var gbuffer: Gbuffer? = null

    /**
     * While set, the clock and frame counter that converted packs animate with stand still, so that a frame
     * can be reproduced exactly. The automated test harness sets it around screenshots.
     */
    @JvmField
    @Volatile
    var holdTime = false

    private var packs: List<ShaderPack> = emptyList()
    private var selected: String = OFF
    private var loaded: LoadedPack? = null
    private var converted: ConvertedPack? = null
    private var failedPack: String? = null
    private var frameSlice: GpuBufferSlice? = null
    private var worldActive = false
    private var frameNumber = 0
    private var loadedAtNanos = 0L
    private var warnedTransparency = false

    private val frameData: ByteBuffer = MemoryUtil.memAlloc(FRAME_UNIFORMS_SIZE)
    private val modelViewInverse = Matrix4f()
    private val projectionInverse = Matrix4f()
    private val shadowModelView = Matrix4f()
    private val shadowProjection = Matrix4f()
    private val sunDirection = Vector3f()
    private val lightDirection = Vector3f()
    private val clearValue = Vector4f(0f, 0f, 0f, 0f)
    private val chunkSectionUniform = listOf("ChunkSection")
    private val savedVisibleSections = ObjectArrayList<SectionRenderDispatcher.RenderSection>(10000)
    private val sectionLookup = BlockPos.MutableBlockPos()
    private val sectionCentre = Vector3f()
    private var cameraX = 0.0
    private var cameraY = 0.0
    private var cameraZ = 0.0

    init {
        packs = ShaderPack.discover()
        selected = (System.getProperty(PROPERTY) ?: MetalCraftConfig.shaderPack).trim().ifEmpty { OFF }
        if (selected != OFF && packs.none { it.id == selected }) {
            LOGGER.warn("Shader pack '{}' was not found. Available: {}", selected, packs.map { it.id })
            selected = OFF
        }
    }

    /** Names of the packs that can be selected, in menu order. */
    fun availablePacks(): List<String> = packs.map { it.id }

    /** Folder name of the selected pack, or "off". */
    fun selectedId(): String = selected

    /** Display name of the selected pack, or "Off". */
    fun selectedName(): String = packs.firstOrNull { it.id == selected }?.displayName ?: "Off"

    /** Switches to the next pack (Off comes after the last one), remembers it in the config and returns its name. */
    fun cyclePack(): String {
        packs = ShaderPack.discover()
        val ids = listOf(OFF) + packs.map { it.id }
        selected = ids[(ids.indexOf(selected).coerceAtLeast(0) + 1) % ids.size]
        failedPack = null
        MetalCraftConfig.shaderPack = selected
        MetalCraftConfig.save()
        return selectedName()
    }

    // ---- frame hooks, called from GameRendererMixin -------------------------------------------------------

    /**
     * Called right before the game renders the world. [projection] is the matrix the world is drawn with;
     * the game has multiplied the walking and hurt sway of the view, [bob], into it.
     */
    @JvmStatic
    fun beginWorld(camera: CameraRenderState, projection: Matrix4f, bob: Matrix4f) {
        // Also clears what an interrupted previous frame may have left behind.
        gbuffer = null
        worldActive = false
        val device = MetalDevice.current ?: return
        val minecraft = Minecraft.getInstance()
        val pack = activePack(device, minecraft)
        val convertedPack = converted
        if (convertedPack != null) {
            try {
                val target = Upscaler.worldTarget(minecraft)
                convertedPack.beginWorld(minecraft, camera, projection, bob, target)
                gbuffer = convertedPack.gbuffer(target.colorTexture)
                worldActive = true
            } catch (e: Exception) {
                fail(convertedPack.pack.id, e)
            }
            return
        }
        if (pack == null) return
        try {
            // With upscaling on, the world (and so the pack) works on the smaller images.
            val target = Upscaler.worldTarget(minecraft)
            pack.resize(device, target.width, target.height)
            writeFrameUniforms(device, minecraft, pack, camera, projection, target.width, target.height)
            for (texture in pack.gbufferTextures) device.encoder.clearColorTexture(texture, clearValue)
            renderShadow(device, minecraft, pack)
            gbuffer = pack.gbuffer(target.colorTexture)
            worldActive = true
        } catch (e: Exception) {
            fail(pack.pack.id, e)
        }
    }

    /** Called when the game has finished the world, before the first-person hand and the GUI. */
    @JvmStatic
    fun endWorld() {
        gbuffer = null
        if (!worldActive) return
        worldActive = false
        val device = MetalDevice.current ?: return
        val convertedPack = converted
        if (convertedPack != null) {
            try {
                convertedPack.endWorld(Upscaler.worldTarget(Minecraft.getInstance()))
            } catch (e: Exception) {
                fail(convertedPack.pack.id, e)
            }
            return
        }
        val pack = loaded ?: return
        try {
            val target = Upscaler.worldTarget(Minecraft.getInstance())
            pack.runPasses(device, target.colorTexture, target.colorView, target.depthView)
        } catch (e: Exception) {
            fail(pack.pack.id, e)
        }
    }

    /** Called before the game draws a group of terrain layers (from `ChunkSectionsToRenderMixin`). */
    @JvmStatic
    fun beforeChunkGroup(group: ChunkSectionLayerGroup) {
        val pack = converted ?: return
        if (!worldActive || group != ChunkSectionLayerGroup.TRANSLUCENT) return
        try {
            val target = Upscaler.worldTarget(Minecraft.getInstance())
            pack.beforeTranslucent(target)
        } catch (e: Exception) {
            fail(pack.pack.id, e)
        }
    }

    // ---- backend hooks ------------------------------------------------------------------------------------

    /** The pack program that replaces a game pipeline for the current draw, or null to keep the game's. */
    internal fun substitute(pipeline: RenderPipeline, passHasExtraTargets: Boolean): DrawProgram? {
        // Pack programs write all G-buffer targets, so they only fit passes that carry them.
        if (gbuffer == null || !passHasExtraTargets) return null
        converted?.let { return it.substitute(pipeline) }
        val pack = loaded ?: return null
        return when {
            pipeline === RenderPipelines.SOLID_TERRAIN -> pack.terrainSolid
            pipeline === RenderPipelines.CUTOUT_TERRAIN -> pack.terrainCutout
            pipeline === RenderPipelines.TRANSLUCENT_TERRAIN -> pack.terrainTranslucent
            else -> null
        }
    }

    /** Gives a pass that just switched to a pack program what the game does not bind itself. */
    internal fun bindFrameResources(pass: MetalRenderPass) {
        converted?.let { return it.bindDraw(pass) }
        pass.setUniform(FRAME_UNIFORMS, frameSlice ?: return)
    }

    /**
     * Whether game pipelines without a pack program are left out of the world. A converted pack draws the
     * world into its own buffers, where the game's shaders have nothing to add.
     */
    internal fun hidesGameDraws(): Boolean = converted != null && gbuffer != null

    /** A pack program could not be used for a draw: the pack goes off and the game's own pipeline draws instead. */
    internal fun programFailed(error: Exception) {
        fail(loaded?.pack?.id ?: converted?.pack?.id ?: selected, error)
    }

    internal fun deviceClosing() {
        gbuffer = null
        worldActive = false
        loaded?.destroy()
        loaded = null
        converted?.destroy()
        converted = null
    }

    // ---- pack state ---------------------------------------------------------------------------------------

    private fun activePack(device: MetalDevice, minecraft: Minecraft): LoadedPack? {
        if (selected == OFF || selected == failedPack) {
            unload()
            return null
        }
        if (minecraft.options.improvedTransparency().get()) {
            if (!warnedTransparency) {
                warnedTransparency = true
                LOGGER.warn("Shader packs do not work with the Improved Transparency video setting yet. The pack stays off while it is on")
            }
            unload()
            return null
        }
        val current = loaded
        if (current != null && current.pack.id == selected) return current
        // A converted pack has its own programs for the Nether and for the End.
        val dimension = when (minecraft.level?.dimension()) {
            Level.NETHER -> "world-1"
            Level.END -> "world1"
            else -> "world0"
        }
        val currentConverted = converted
        if (currentConverted != null && currentConverted.pack.id == selected && currentConverted.dimension == dimension) return null
        // Another dimension of the same pack has the same block IDs: the terrain need not be rebuilt for it.
        unload(resetBlockIds = currentConverted == null || currentConverted.pack.id != selected)
        val pack = packs.firstOrNull { it.id == selected } ?: return null
        return try {
            if (pack.isConverted) {
                converted = ConvertedPack(device, pack, pack.converted(dimension), dimension)
                applyBlockIds(pack.blockIdsFile?.let(pack::read))
                LOGGER.info("Shader pack loaded: {}", pack.displayName)
                return null
            }
            val fresh = LoadedPack(device, pack)
            loaded = fresh
            applyBlockIds(pack.blockIdsFile?.let(pack::read))
            loadedAtNanos = System.nanoTime()
            LOGGER.info("Shader pack loaded: {}", pack.displayName)
            fresh
        } catch (e: Exception) {
            fail(pack.id, e)
            null
        }
    }

    private fun unload(resetBlockIds: Boolean = true) {
        gbuffer = null
        val convertedPack = converted
        if (convertedPack != null) {
            converted = null
            if (resetBlockIds) applyBlockIds(null)
            convertedPack.destroy()
        }
        val current = loaded ?: return
        loaded = null
        applyBlockIds(null)
        // Its targets may still be in use by the frame being recorded; the backend defers the release.
        current.destroy()
    }

    /** Block IDs live in the terrain vertices: when the table changes, every section is built again. */
    private fun applyBlockIds(table: String?) {
        if (BlockIds.load(table)) Minecraft.getInstance().levelExtractor.allChanged()
    }

    /** A pack that cannot be compiled or drawn is switched off until another one is selected. */
    private fun fail(packId: String, error: Exception) {
        LOGGER.error("Shader pack '{}' failed and is switched off: {}", packId, error.message)
        LOGGER.debug("Shader pack failure", error)
        failedPack = packId
        gbuffer = null
        worldActive = false
        unload()
    }

    // ---- per-frame work -----------------------------------------------------------------------------------

    private fun writeFrameUniforms(
        device: MetalDevice, minecraft: Minecraft, pack: LoadedPack, camera: CameraRenderState, projection: Matrix4f, width: Int, height: Int,
    ) {
        val levelState = minecraft.gameRenderer.gameRenderState().levelRenderState
        val sunAngle = levelState.skyRenderState.sunAngle
        // The same rotations the sky renderer applies to the sun, which it draws straight up before rotating.
        sunDirection.set(0f, 1f, 0f).rotateX(sunAngle).rotateY(-Math.PI.toFloat() / 2f).normalize()
        // Whatever is above the horizon casts the shadows; near the horizon they fade out.
        if (sunDirection.y >= 0f) lightDirection.set(sunDirection) else lightDirection.set(sunDirection).negate()
        // Dimensions without a sky (the Nether, the End) have no sun or moon to cast shadows.
        val hasSky = minecraft.level?.dimensionType()?.hasSkyLight() ?: false
        val lightStrength = if (hasSky) ((lightDirection.y - 0.05f) / 0.15f).coerceIn(0f, 1f) else 0f

        val distance = pack.pack.shadowDistance
        val up = if (lightDirection.y > 0.99f) UP_Z else UP_Y
        shadowModelView.setLookAt(
            lightDirection.x * SHADOW_EYE_DISTANCE, lightDirection.y * SHADOW_EYE_DISTANCE, lightDirection.z * SHADOW_EYE_DISTANCE,
            0f, 0f, 0f, up.x, up.y, up.z,
        )
        shadowProjection.setOrtho(-distance, distance, -distance, distance, 0.05f, SHADOW_EYE_DISTANCE * 2f, true)
        camera.viewRotationMatrix.invert(modelViewInverse)
        projection.invert(projectionInverse)

        val data = frameData
        camera.viewRotationMatrix.get(0, data)
        projection.get(64, data)
        modelViewInverse.get(128, data)
        projectionInverse.get(192, data)
        shadowModelView.get(256, data)
        shadowProjection.get(320, data)
        val position = camera.pos
        cameraX = position.x
        cameraY = position.y
        cameraZ = position.z
        putVector(384, position.x.toFloat(), position.y.toFloat(), position.z.toFloat(), 0f)
        putVector(400, sunDirection.x, sunDirection.y, sunDirection.z, sunAngle)
        putVector(416, lightDirection.x, lightDirection.y, lightDirection.z, lightStrength)
        putVector(432, width.toFloat(), height.toFloat(), 1f / width, 1f / height)
        val seconds = ((System.nanoTime() - loadedAtNanos) / 1e9).toFloat()
        val level = minecraft.level
        val dayTime = if (level != null) (level.overworldClockTime % 24000L).toFloat() else 0f
        val rain = level?.getRainLevel(1f) ?: 0f
        putVector(448, seconds, dayTime, rain, (frameNumber++).toFloat())
        putVector(464, distance, pack.pack.shadowResolution.toFloat(), 0f, 0f)
        val blockX = Math.floor(position.x).toInt()
        val blockY = Math.floor(position.y).toInt()
        val blockZ = Math.floor(position.z).toInt()
        data.putInt(480, blockX).putInt(484, blockY).putInt(488, blockZ).putInt(492, 0)
        putVector(496, (blockX - position.x).toFloat(), (blockY - position.y).toFloat(), (blockZ - position.z).toFloat(), 0f)
        data.position(0).limit(FRAME_UNIFORMS_SIZE)
        frameSlice = device.encoder.transientMemory().uploadGpu(data, 16L, GpuBuffer.USAGE_UNIFORM)
    }

    private fun putVector(offset: Int, x: Float, y: Float, z: Float, w: Float) {
        frameData.putFloat(offset, x).putFloat(offset + 4, y).putFloat(offset + 8, z).putFloat(offset + 12, w)
    }

    /**
     * The terrain to draw into the shadow map: every built section the shadow map covers, whether the camera
     * looks at it or not. The game only builds its draw lists from its list of visible sections, so that list
     * is swapped for the shadow casters while the lists are built and put back afterwards.
     * Sections the game has never built (never seen since they loaded) cast no shadow.
     */
    internal fun shadowSections(
        minecraft: Minecraft, shadowModelView: Matrix4f, distance: Float, cameraX: Double, cameraY: Double, cameraZ: Double,
    ): ChunkSectionsToRender {
        val renderer = minecraft.levelRenderer
        val viewArea = (renderer as LevelRendererAccessor).`metalcraft$viewArea`()
            ?: return renderer.prepareChunkRenders(shadowModelView)
        val reach = (distance / 16f).toInt() + 2
        val limit = distance + SECTION_RADIUS
        val centreX = Math.floor(cameraX).toInt() shr 4
        val centreY = Math.floor(cameraY).toInt() shr 4
        val centreZ = Math.floor(cameraZ).toInt() shr 4
        val minY = maxOf(centreY - reach, viewArea.minSectionY())
        val maxY = minOf(centreY + reach, viewArea.maxSectionY())
        val visible = renderer.visibleSections()
        savedVisibleSections.clear()
        savedVisibleSections.addAll(visible)
        visible.clear()
        try {
            for (x in centreX - reach..centreX + reach) {
                for (z in centreZ - reach..centreZ + reach) {
                    for (y in minY..maxY) {
                        val section = viewArea.getRenderSectionAt(sectionLookup.set(x shl 4, y shl 4, z shl 4)) ?: continue
                        val mesh = section.getSectionMesh()
                        if (mesh.isEmpty(ChunkSectionLayer.SOLID) && mesh.isEmpty(ChunkSectionLayer.CUTOUT)) continue
                        // Keep what falls inside the shadow map's square, seen from the light.
                        sectionCentre.set(
                            ((x shl 4) + 8 - cameraX).toFloat(), ((y shl 4) + 8 - cameraY).toFloat(), ((z shl 4) + 8 - cameraZ).toFloat(),
                        )
                        shadowModelView.transformPosition(sectionCentre)
                        if (Math.abs(sectionCentre.x) > limit || Math.abs(sectionCentre.y) > limit) continue
                        visible.add(section)
                    }
                }
            }
            return renderer.prepareChunkRenders(shadowModelView)
        } finally {
            visible.clear()
            visible.addAll(savedVisibleSections)
            savedVisibleSections.clear()
        }
    }

    /** Draws the opaque terrain around the camera from the light's point of view into the shadow map. */
    private fun renderShadow(device: MetalDevice, minecraft: Minecraft, pack: LoadedPack) {
        val program = pack.shadow ?: return
        val shadowView = pack.shadowView ?: return
        val sections = shadowSections(minecraft, shadowModelView, pack.pack.shadowDistance, cameraX, cameraY, cameraZ)
        val resolution = pack.pack.shadowResolution
        val descriptor = RenderPassDescriptor.create { "MetalCraft shadow" }
            .withDepthAttachment(shadowView, OptionalDouble.of(1.0))
            .withRenderArea(RenderPass.RenderArea(0, 0, resolution, resolution))
        val pass = device.encoder.createRenderPass(descriptor) as MetalRenderPass
        try {
            pass.setProgram(program)
            pass.setUniform(FRAME_UNIFORMS, frameSlice!!)
            pass.bindTexture("Sampler0", sections.textureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
            drawShadowTerrain(pass, sections)
        } finally {
            device.encoder.submitRenderPass()
        }
    }

    /** Draws the opaque layers of [sections] with the program and bindings already set on [pass]. */
    internal fun drawShadowTerrain(pass: MetalRenderPass, sections: ChunkSectionsToRender) {
        val sharedIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
        val defaultIndexBuffer = if (sections.maxIndicesRequired() == 0) null else sharedIndices.getBuffer(sections.maxIndicesRequired())
        for (layer in SHADOW_LAYERS) {
            val groups = sections.drawGroupsPerLayer()[layer] ?: continue
            for (draws in groups.values) {
                pass.drawMultipleIndexed(draws, defaultIndexBuffer, sharedIndices.type(), chunkSectionUniform, sections.chunkSectionInfos())
            }
        }
    }

    // ---- a pack compiled and ready to draw ----------------------------------------------------------------

    private class Target(val spec: BufferSpec) {
        var texture: MetalTexture? = null
        var view: MetalTextureView? = null
        var altTexture: MetalTexture? = null
        var altView: MetalTextureView? = null
    }

    private class LoadedPass(val spec: PassSpec, val program: PackProgram)

    private class LoadedPack(device: MetalDevice, val pack: ShaderPack) {
        val terrainSolid: PackProgram?
        val terrainCutout: PackProgram?
        val terrainTranslucent: PackProgram?
        val shadow: PackProgram?
        var shadowView: MetalTextureView? = null
        val gbufferTextures = ArrayList<MetalTexture>()

        private val programs = ArrayList<PackProgram>()
        private val passes = ArrayList<LoadedPass>()
        private val targets = LinkedHashMap<String, Target>()
        private var shadowTexture: MetalTexture? = null
        private var mainAltTexture: MetalTexture? = null
        private var mainAltView: MetalTextureView? = null
        private val linear: MetalSampler
        private val nearest: MetalSampler
        private var width = 0
        private var height = 0
        private var gbufferState: Gbuffer? = null

        // Formats of the pack's extra G-buffer targets. One array for the pack's lifetime: the backend keys
        // its pipeline variants on it.
        private val extraFormats = IntArray(pack.gbufferTargets.size) { MetalFormats.code(pack.buffers.getValue(pack.gbufferTargets[it]).format) }

        init {
            val header = engineSource("pack_header.metal") + "\n" + pack.includes.joinToString("\n") { pack.read(it) } + "\n"
            val fullscreenVertex = header + engineSource("pack_fullscreen.vsh.metal")
            for (spec in pack.buffers.values) targets[spec.name] = Target(spec)
            linear = sampler(device, FilterMode.LINEAR)
            nearest = sampler(device, FilterMode.NEAREST)

            try {
                val gbufferFormats = IntArray(1 + pack.gbufferTargets.size)
                gbufferFormats[0] = MetalFormats.code(GpuFormat.RGBA8_UNORM)
                for ((i, name) in pack.gbufferTargets.withIndex()) gbufferFormats[i + 1] = MetalFormats.code(pack.buffers.getValue(name).format)
                val writeAll = IntArray(gbufferFormats.size) { WRITE_ALL }
                // Translucent terrain is blended into the image and leaves the other buffers to what is behind it.
                val writeColorOnly = IntArray(gbufferFormats.size) { if (it == 0) WRITE_ALL else 0 }

                val terrain = pack.terrain
                if (terrain != null) {
                    val vertex = header + pack.read(terrain.vertex)
                    val fragment = pack.read(terrain.fragment)
                    // Defines go first so the pack's include files see them too.
                    fun build(name: String, defines: String, masks: IntArray, blend: Boolean) = program(
                        PackProgram(
                            device, "${pack.id}/$name", vertex, defines + header + fragment, TERRAIN_ENTRIES, TerrainVertexFormat.current(),
                            gbufferFormats, masks, if (blend) 1 else 0, COMPARE_GREATER_EQUAL, true, 1,
                        ),
                        DEPTH_FORMAT,
                    )
                    terrainSolid = build("gbuffers_terrain", "", writeAll, false)
                    terrainCutout = build("gbuffers_terrain (cutout)", "#define ALPHA_CUTOUT 0.5\n", writeAll, false)
                    terrainTranslucent = build("gbuffers_terrain (translucent)", "#define ALPHA_CUTOUT 0.1\n", writeColorOnly, true)
                } else {
                    terrainSolid = null
                    terrainCutout = null
                    terrainTranslucent = null
                }

                val shadowFiles = pack.shadow
                shadow = if (shadowFiles != null) {
                    program(
                        PackProgram(
                            device, "${pack.id}/shadow", header + pack.read(shadowFiles.vertex), header + pack.read(shadowFiles.fragment),
                            SHADOW_ENTRIES, TerrainVertexFormat.current(), IntArray(0), IntArray(0), 0, COMPARE_LESS_EQUAL, true, 0,
                        ),
                        DEPTH_FORMAT,
                    )
                } else {
                    null
                }
                if (shadow != null) {
                    val resolution = pack.shadowResolution
                    val texture = device.createTexture(
                        "MetalCraft shadow map", GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.D32_FLOAT, resolution, resolution, 1, 1,
                    ) as MetalTexture
                    shadowTexture = texture
                    shadowView = device.createTextureView(texture) as MetalTextureView
                }

                for (spec in pack.passes) {
                    val formats = IntArray(spec.outputs.size) { MetalFormats.code(formatOf(spec.outputs[it])) }
                    val entries = ArrayList<BindingEntry>()
                    entries.add(BindingEntry(BindingEntry.UNIFORM_BUFFER, FRAME_UNIFORMS, null, 0))
                    for ((i, input) in spec.inputs.withIndex()) entries.add(BindingEntry(BindingEntry.TEXTURE, input, null, i))
                    val compiled = PackProgram(
                        device, "${pack.id}/${spec.fragment}", fullscreenVertex, header + pack.read(spec.fragment), entries.toTypedArray(), null,
                        formats, IntArray(formats.size) { WRITE_ALL }, 0, -1, false, 0,
                    )
                    passes.add(LoadedPass(spec, program(compiled, MetalFormats.NO_FORMAT)))
                }
            } catch (e: Exception) {
                destroy()
                throw e
            }
        }

        /**
         * Registers a program and builds its Metal pipeline state for the depth format it will be used with,
         * so that anything Metal rejects fails the pack while it loads, not in the middle of a frame.
         */
        private fun program(created: PackProgram, depthFormat: Int): PackProgram {
            programs.add(created)
            created.variant(depthFormat, null)
            return created
        }

        private fun formatOf(buffer: String): GpuFormat =
            if (buffer == ShaderPack.MAIN_COLOR) GpuFormat.RGBA8_UNORM else pack.buffers.getValue(buffer).format

        private fun sampler(device: MetalDevice, filter: FilterMode) =
            device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, filter, filter, 1, OptionalDouble.of(0.0)) as MetalSampler

        /** (Re)creates the colour buffers for a game image of this size. */
        fun resize(device: MetalDevice, newWidth: Int, newHeight: Int) {
            if (newWidth == width && newHeight == height) return
            releaseTargets()
            width = newWidth
            height = newHeight
            for (target in targets.values) {
                val w = maxOf(1, (newWidth * target.spec.scale).toInt())
                val h = maxOf(1, (newHeight * target.spec.scale).toInt())
                target.texture = colorTexture(device, target.spec.name, target.spec.format, w, h)
                target.view = device.createTextureView(target.texture!!) as MetalTextureView
                // A second image is only needed by buffers some pass both reads and writes.
                if (passes.any { target.spec.name in it.spec.inputs && target.spec.name in it.spec.outputs }) {
                    target.altTexture = colorTexture(device, target.spec.name + " (alt)", target.spec.format, w, h)
                    target.altView = device.createTextureView(target.altTexture!!) as MetalTextureView
                }
            }
            if (passes.any { ShaderPack.MAIN_COLOR in it.spec.inputs && ShaderPack.MAIN_COLOR in it.spec.outputs }) {
                mainAltTexture = colorTexture(device, "colortex0 (alt)", GpuFormat.RGBA8_UNORM, newWidth, newHeight)
                mainAltView = device.createTextureView(mainAltTexture!!) as MetalTextureView
            }
            gbufferTextures.clear()
            for (name in pack.gbufferTargets) gbufferTextures.add(targets.getValue(name).texture!!)
            gbufferState = null
        }

        private fun colorTexture(device: MetalDevice, label: String, format: GpuFormat, w: Int, h: Int) = device.createTexture(
            label,
            GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST,
            format, w, h, 1, 1,
        ) as MetalTexture

        fun gbuffer(mainColor: GpuTexture): Gbuffer {
            val existing = gbufferState
            if (existing != null && existing.mainColor === mainColor) return existing
            val handles = LongArray(pack.gbufferTargets.size) { targets.getValue(pack.gbufferTargets[it]).view!!.handle }
            return Gbuffer(mainColor, handles, extraFormats).also { gbufferState = it }
        }

        fun runPasses(device: MetalDevice, mainTexture: MetalTexture, mainView: MetalTextureView, mainDepth: MetalTextureView) {
            val encoder = device.encoder
            val frame = frameSlice ?: return
            for (pass in passes) {
                val spec = pass.spec
                val descriptor = RenderPassDescriptor.create { "MetalCraft pass" }
                var outWidth = 0
                var outHeight = 0
                for (output in spec.outputs) {
                    val flips = output in spec.inputs
                    val view = when {
                        output == ShaderPack.MAIN_COLOR -> if (flips) mainAltView!! else mainView
                        flips -> targets.getValue(output).altView!!
                        else -> targets.getValue(output).view!!
                    }
                    descriptor.withColorAttachment(view)
                    outWidth = view.getWidth(0)
                    outHeight = view.getHeight(0)
                }
                descriptor.withRenderArea(RenderPass.RenderArea(0, 0, outWidth, outHeight))
                val renderPass = encoder.createRenderPass(descriptor) as MetalRenderPass
                try {
                    renderPass.setProgram(pass.program)
                    renderPass.setUniform(FRAME_UNIFORMS, frame)
                    for (input in spec.inputs) {
                        when (input) {
                            ShaderPack.MAIN_COLOR -> renderPass.bindTexture(input, mainView, linear)
                            ShaderPack.MAIN_DEPTH -> renderPass.bindTexture(input, mainDepth, nearest)
                            ShaderPack.SHADOW_DEPTH -> renderPass.bindTexture(input, checkNotNull(shadowView) { "The pack reads shadowtex0 but has no shadow program" }, nearest)
                            else -> renderPass.bindTexture(input, targets.getValue(input).view!!, linear)
                        }
                    }
                    renderPass.draw(3, 1, 0, 0)
                } finally {
                    encoder.submitRenderPass()
                }
                // A buffer that was read and written went to its second image: make that the current one.
                for (output in spec.outputs) {
                    if (output !in spec.inputs) continue
                    if (output == ShaderPack.MAIN_COLOR) {
                        // The game owns colortex0, so the result is copied back instead of swapping images.
                        encoder.copyTextureToTexture(mainAltTexture!!, mainTexture, 0, 0, 0, 0, 0, mainTexture.getWidth(0), mainTexture.getHeight(0))
                    } else {
                        val target = targets.getValue(output)
                        val texture = target.texture
                        val view = target.view
                        target.texture = target.altTexture
                        target.view = target.altView
                        target.altTexture = texture
                        target.altView = view
                        if (output in pack.gbufferTargets) gbufferState = null
                    }
                }
            }
            // Buffers may have swapped images; the gbuffer list has to follow.
            gbufferTextures.clear()
            for (name in pack.gbufferTargets) gbufferTextures.add(targets.getValue(name).texture!!)
        }

        private fun releaseTargets() {
            for (target in targets.values) {
                target.view?.close()
                target.texture?.close()
                target.altView?.close()
                target.altTexture?.close()
                target.view = null
                target.texture = null
                target.altView = null
                target.altTexture = null
            }
            mainAltView?.close()
            mainAltTexture?.close()
            mainAltView = null
            mainAltTexture = null
            gbufferTextures.clear()
            gbufferState = null
            width = 0
            height = 0
        }

        fun destroy() {
            releaseTargets()
            shadowView?.close()
            shadowTexture?.close()
            shadowView = null
            shadowTexture = null
            linear.close()
            nearest.close()
            // Programs may be bound in the frame being recorded; Metal keeps what a command buffer uses alive.
            for (program in programs) program.destroy()
            programs.clear()
        }
    }

    internal fun engineSource(file: String): String {
        val container = FabricLoader.getInstance().getModContainer(MetalCraft.MOD_ID)
            .orElseThrow { IllegalStateException("Mod container '${MetalCraft.MOD_ID}' not found") }
        val path = container.findPath("assets/metalcraft/shaders/$file").orElseThrow { IllegalStateException("$file is missing from the mod") }
        return Files.readString(path)
    }

    private const val SHADOW_EYE_DISTANCE = 256f

    /** Half the diagonal of a 16-block terrain section. */
    private const val SECTION_RADIUS = 13.9f
    private const val WRITE_ALL = 15
    private const val COMPARE_LESS_EQUAL = 3
    private const val COMPARE_GREATER_EQUAL = 6

    /** The game's depth buffers are 32-bit float. */
    private val DEPTH_FORMAT = MetalFormats.code(GpuFormat.D32_FLOAT)
    private val UP_Y = Vector3f(0f, 1f, 0f)
    private val UP_Z = Vector3f(0f, 0f, 1f)
    private val SHADOW_LAYERS = arrayOf(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT)

    // Bindings of the geometry programs, as documented in pack_header.metal.
    private val TERRAIN_ENTRIES = arrayOf(
        BindingEntry(BindingEntry.UNIFORM_BUFFER, FRAME_UNIFORMS, null, 0),
        BindingEntry(BindingEntry.UNIFORM_BUFFER, "Projection", null, 1),
        BindingEntry(BindingEntry.UNIFORM_BUFFER, "ChunkSection", null, 2),
        BindingEntry(BindingEntry.UNIFORM_BUFFER, "Globals", null, 3),
        BindingEntry(BindingEntry.UNIFORM_BUFFER, "Fog", null, 4),
        BindingEntry(BindingEntry.TEXTURE, "Sampler0", null, 0),
        BindingEntry(BindingEntry.TEXTURE, "Sampler2", null, 2),
    )
    private val SHADOW_ENTRIES = arrayOf(
        BindingEntry(BindingEntry.UNIFORM_BUFFER, FRAME_UNIFORMS, null, 0),
        BindingEntry(BindingEntry.UNIFORM_BUFFER, "ChunkSection", null, 2),
        BindingEntry(BindingEntry.TEXTURE, "Sampler0", null, 0),
    )
}
