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
 *   gbuffers (opaque)           the game draws the world; terrain uses the pack's programs, which write the
 *                               pack's buffers attached after the game's image. Game pipelines the pack has
 *                               no program for yet are not drawn at all
 *   deferred                    full-screen passes, run right before the game draws translucent terrain
 *   gbuffers (translucent)      water and the like
 *   composite, final            full-screen passes; `final` writes the game's image
 *
 * Entities, particles and the sun and moon use the pack's programs too (`createSubstitute`). Not covered yet:
 * weather, lines, blocks moved by pistons and falling blocks (the game's `core/block` pipelines, which are
 * dropped like every pipeline without a pack program), the hand (the game draws it afterwards with its own
 * shader), translucent and entity
 * shadows, per-entity uniforms, the pack's options, the Nether and the End.
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
    }

    private class Image(val texture: MetalTexture, val view: MetalTextureView, val repeat: Boolean)

    /** Where one texture of a program comes from. */
    private class Slot(val entryName: String, val source: Int, val index: Int, val compare: Boolean, val image: Image?)

    private class Program(val spec: ConvertedSpec.Program, val program: PackProgram, val slots: Array<Slot>, val stage: String)

    /** What replaces one game pipeline: a program and the per-draw uniforms that go with it. */
    private class Draw(val program: Program, val uniforms: GpuBufferSlice)

    private val colors = arrayOfNulls<Color>(16)
    private val images = HashMap<String, Image>()
    private val programs = ArrayList<PackProgram>()
    private val passes = ArrayList<Program>()
    private var terrain: Program? = null
    private var water: Program? = null
    private var shadow: Program? = null
    private var sky: Program? = null
    private val depthCopy: PackProgram

    private val linear: MetalSampler
    private val nearest: MetalSampler
    private val linearRepeat: MetalSampler
    private val shadowCompare: MetalSampler

    private var depth0Texture: MetalTexture? = null
    private var depth0View: MetalTextureView? = null
    private var depth1Texture: MetalTexture? = null
    private var depth1View: MetalTextureView? = null
    private val shadowDepth: MetalTexture
    private val shadowDepthView: MetalTextureView
    private val shadowColors: Array<MetalTexture>
    private val shadowColorViews: Array<MetalTextureView>

    private val drawBuffers = ArrayList<GpuBuffer>()
    private val drawUniforms = HashMap<Float, GpuBufferSlice>()
    private val drawShadow: GpuBufferSlice

    // Game pipeline to what the pack draws instead; a null value means the pack has nothing for it.
    private val substitutes = IdentityHashMap<RenderPipeline, Draw?>()

    private var width = 0
    private var height = 0
    private val gbufferFormats = IntArray(spec.gbuffers.size) { MetalFormats.code(spec.buffers.getValue(spec.gbuffers[it]).format) }
    private val gbufferHandles = LongArray(spec.gbuffers.size)
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
        shadowCompare = device.createShadowSampler()
        val resolution = spec.shadowResolution
        shadowDepth = device.createTexture(
            "MetalCraft shadowtex", GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT, resolution, resolution, 1, 1,
        ) as MetalTexture
        shadowDepthView = device.createTextureView(shadowDepth) as MetalTextureView
        shadowColors = Array(2) { colorTexture("shadowcolor$it", GpuFormat.RGBA8_UNORM, resolution, resolution) }
        shadowColorViews = Array(2) { device.createTextureView(shadowColors[it]) as MetalTextureView }
        drawShadow = drawUniforms(0.1f)
        for (buffer in spec.buffers.values) colors[buffer.index] = Color(buffer)

        try {
            for ((name, image) in spec.images) images[name] = loadImage(image)
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

    private fun colorTexture(label: String, format: GpuFormat, w: Int, h: Int) = device.createTexture(
        label, GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST,
        format, w, h, 1, 1,
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

    private fun loadImage(image: ConvertedSpec.Image): Image {
        val pixels = Files.newInputStream(pack.path(image.file)).use { NativeImage.read(it) }
        try {
            val texture = device.createTexture(
                image.file, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, pixels.width, pixels.height, 1, 1,
            ) as MetalTexture
            try {
                device.encoder.writeToTexture(
                    texture, MemoryUtil.memByteBuffer(pixels.pointer, pixels.width * pixels.height * 4), 0, 0, 0, 0, pixels.width, pixels.height,
                )
                return Image(texture, device.createTextureView(texture) as MetalTextureView, image.repeat)
            } catch (e: Exception) {
                texture.close()
                throw e
            }
        } finally {
            pixels.close()
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
        val image = images["$stage.${texture.name}"] ?: images[texture.name]
        when {
            image != null -> Slot(texture.name, SOURCE_IMAGE, 0, false, image)
            name in ATLAS_NAMES -> Slot("Sampler0", SOURCE_GAME, 0, false, null)
            name.startsWith("colortex") -> Slot(texture.name, SOURCE_COLOR, color(name.removePrefix("colortex").toInt()).spec.index, false, null)
            name == "depthtex0" -> Slot(texture.name, SOURCE_DEPTH0, 0, false, null)
            name == "depthtex1" || name == "depthtex2" -> Slot(texture.name, SOURCE_DEPTH1, 0, false, null)
            name == "shadowtex0" || name == "shadowtex1" -> Slot(texture.name, SOURCE_SHADOW_DEPTH, 0, texture.compare, null)
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
        val formats = IntArray(1 + spec.gbuffers.size) { if (it == 0) MetalFormats.code(GpuFormat.RGBA8_UNORM) else gbufferFormats[it - 1] }
        val masks = IntArray(formats.size) { if (it > 0 && spec.gbuffers[it - 1] in program.targets) WRITE_ALL else 0 }
        var blend = 0
        for (target in blendTargets) if (target in program.targets) blend = blend or (1 shl (1 + spec.gbuffers.indexOf(target)))
        val buffers = if (withGlobals) {
            entries(slots, PACK_UNIFORMS_ENTRY, DRAW_UNIFORMS_ENTRY, geometryBlock, GLOBALS_ENTRY)
        } else {
            entries(slots, PACK_UNIFORMS_ENTRY, DRAW_UNIFORMS_ENTRY, geometryBlock)
        }
        val compiled = PackProgram(
            device, "${pack.id}/$label", pack.read(program.vertex), pack.read(program.fragment), buffers, format,
            formats, masks, blend, depthCompare, depthWrite, if (cull) 1 else 0, blendFunction, fastMath = true,
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
            device, "${pack.id}/shadow", pack.read(program.vertex), pack.read(program.fragment),
            entries(slots, PACK_UNIFORMS_ENTRY, DRAW_UNIFORMS_ENTRY, CHUNK_SECTION_ENTRY, GLOBALS_ENTRY), TerrainVertexFormat.FORMAT,
            formats, IntArray(formats.size) { WRITE_ALL }, 0, COMPARE_LESS_EQUAL, true, 0, fastMath = true,
        )
        return Program(program, register(compiled, DEPTH_FORMAT), slots, "shadow")
    }

    private fun passProgram(program: ConvertedSpec.Program): Program {
        val stage = if (program.name.startsWith("deferred")) "deferred" else if (program.name.startsWith("gbuffers")) "gbuffers" else "composite"
        val slots = slots(program, stage)
        val formats = IntArray(program.targets.size) {
            MetalFormats.code(if (program.name == FINAL) GpuFormat.RGBA8_UNORM else color(program.targets[it]).spec.format)
        }
        val compiled = PackProgram(
            device, "${pack.id}/${program.name}", pack.read(program.vertex), pack.read(program.fragment), entries(slots, PACK_UNIFORMS_ENTRY), null,
            formats, IntArray(formats.size) { WRITE_ALL }, 0, -1, false, 0, fastMath = true,
        )
        return Program(program, register(compiled, MetalFormats.NO_FORMAT), slots, stage)
    }

    // ---- frame ------------------------------------------------------------------------------------------------

    fun beginWorld(minecraft: Minecraft, camera: CameraRenderState, gameProjection: Matrix4f, target: Upscaler.WorldTarget) {
        resize(target.width, target.height)
        writeUniforms(minecraft, camera, gameProjection)
        for (color in colors) {
            if (color != null && color.spec.clear) device.encoder.clearColorTexture(color.texture!!, clearBlack)
        }
        deferredDone = false
        renderShadow(minecraft, camera)
        sky?.let { runPass(it, target) }
    }

    /** The pack's buffers the world programs write, for passes that draw into [mainColor]. */
    fun gbuffer(mainColor: GpuTexture): ShaderPipeline.Gbuffer {
        for (i in gbufferHandles.indices) gbufferHandles[i] = color(spec.gbuffers[i]).view!!.handle
        val existing = gbufferState
        if (existing != null && existing.mainColor === mainColor) return existing
        return ShaderPipeline.Gbuffer(mainColor, gbufferHandles, gbufferFormats).also { gbufferState = it }
    }

    /** The pack's program for a game pipeline, or null when the pack has none (the draw is then dropped). */
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
        bindTextures(pass, program)
    }

    /** Called before the game draws translucent terrain: the opaque world is complete. */
    fun beforeTranslucent(target: Upscaler.WorldTarget) {
        if (deferredDone) return
        deferredDone = true
        copyDepth(target.depthView, depth1View!!)
        copyDepth(target.depthView, depth0View!!)
        for (pass in passes) if (pass.stage == "deferred") runPass(pass, target)
        // The deferred passes may have flipped buffers the translucent programs write to.
        gbuffer(target.colorTexture)
    }

    fun endWorld(target: Upscaler.WorldTarget) {
        beforeTranslucent(target)
        copyDepth(target.depthView, depth0View!!)
        for (pass in passes) if (pass.stage != "deferred") runPass(pass, target)
        if (!reportedMissing) {
            reportedMissing = true
            val missing = spec.uniforms.map { it.name }.filter { it !in written }
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
            val w = maxOf(1, (newWidth * color.spec.scale).toInt())
            val h = maxOf(1, (newHeight * color.spec.scale).toInt())
            color.texture = colorTexture("colortex${color.spec.index}", color.spec.format, w, h)
            color.view = device.createTextureView(color.texture!!) as MetalTextureView
            // Buffers the pack does not clear keep their content between frames: start them from zero.
            device.encoder.clearColorTexture(color.texture!!, clearBlack)
            if (color.needsAlt) {
                color.altTexture = colorTexture("colortex${color.spec.index} (alt)", color.spec.format, w, h)
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
        val sections = ShaderPipeline.shadowSections(minecraft, shadowModelView, spec.shadowDistance, position.x, position.y, position.z)
        val descriptor = RenderPassDescriptor.create { "MetalCraft shadow" }
        for (i in program.spec.targets.indices) descriptor.withColorAttachment(shadowColorViews[program.spec.targets[i]])
        descriptor.withDepthAttachment(shadowDepthView, OptionalDouble.of(1.0))
            .withRenderArea(RenderPass.RenderArea(0, 0, spec.shadowResolution, spec.shadowResolution))
        val pass = device.encoder.createRenderPass(descriptor) as MetalRenderPass
        try {
            pass.setProgram(program.program)
            pass.setUniform(PACK_UNIFORMS, frameSlice!!)
            pass.setUniform(DRAW_UNIFORMS, drawShadow)
            RenderSystem.getGlobalSettingsUniform()?.let { pass.setUniform("Globals", it) }
            pass.bindTexture("Sampler0", sections.textureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
            bindTextures(pass, program)
            ShaderPipeline.drawShadowTerrain(pass, sections)
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
        val descriptor = RenderPassDescriptor.create { "MetalCraft pack pass" }
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
            renderPass.draw(3, 1, 0, 0)
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
        for (slot in program.slots) {
            when (slot.source) {
                SOURCE_GAME -> {}
                SOURCE_IMAGE -> pass.bindTexture(slot.entryName, slot.image!!.view, if (slot.image.repeat) linearRepeat else linear)
                SOURCE_COLOR -> pass.bindTexture(slot.entryName, color(slot.index).view!!, linear)
                SOURCE_DEPTH0 -> pass.bindTexture(slot.entryName, depth0View!!, nearest)
                SOURCE_DEPTH1 -> pass.bindTexture(slot.entryName, depth1View!!, nearest)
                SOURCE_SHADOW_DEPTH -> pass.bindTexture(slot.entryName, shadowDepthView, if (slot.compare) shadowCompare else nearest)
                else -> pass.bindTexture(slot.entryName, shadowColorViews[slot.index], linear)
            }
        }
    }

    // ---- uniforms ---------------------------------------------------------------------------------------------

    private fun writeUniforms(minecraft: Minecraft, camera: CameraRenderState, gameProjection: Matrix4f) {
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
        modelView.set(camera.viewRotationMatrix)
        // The game's matrix has m22 = near / (far - near) and m32 = near * far / (far - near).
        val near = gameProjection.m32() / (1f + gameProjection.m22())
        val far = gameProjection.m32() / gameProjection.m22()
        projection.set(gameProjection).m22(-(far + near) / (far - near)).m32(-2f * far * near / (far - near))
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
        float("framemod8", (frameCounter % 8).toFloat())
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
        vector("skyColor", (sky.skyColor shr 16 and 0xFF) / 255f, (sky.skyColor shr 8 and 0xFF) / 255f, (sky.skyColor and 0xFF) / 255f)
        val fogRange = maxOf(fog.renderDistanceEnd - fog.renderDistanceStart, 0.001f)
        vector("mc_fog", fog.renderDistanceStart, fog.renderDistanceEnd, 1f / fogRange, 0f)
        val inWater = when (camera.fogType) {
            FogType.WATER -> 1
            FogType.LAVA -> 2
            FogType.POWDER_SNOW -> 3
            else -> 0
        }
        integer("isEyeInWater", inWater)

        var blockLight = 0
        var skyLight = 15
        if (level != null) {
            lightProbe.set(Math.floor(position.x).toInt(), Math.floor(position.y).toInt(), Math.floor(position.z).toInt())
            blockLight = level.getBrightness(LightLayer.BLOCK, lightProbe)
            skyLight = level.getBrightness(LightLayer.SKY, lightProbe)
        }
        integers("eyeBrightness", blockLight * 16, skyLight * 16)
        val skyBrightness = skyLight * 16 / 240f
        float("eyeBrightnessM", eyeBrightnessSmooth.update(skyBrightness, frameTime))
        float("eyeBrightnessM2", eyeBrightnessSmooth2.update(if (skyLight >= 15) 1f else 0f, frameTime))
        val caveTarget = if (position.y < 5.0) skyBrightness else 1f
        float("isEyeInCave", if (inWater == 0) 1f - eyeInCave.update(caveTarget, frameTime) else 0f)
        float("starter", 1f)
        writeBiomeUniforms(level, frameTime)

        data.position(0).limit(spec.uniformBlockSize)
        frameSlice = device.encoder.transientMemory().uploadGpu(data, 16L, GpuBuffer.USAGE_UNIFORM)
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

    private fun float(name: String, value: Float) {
        val at = offset(name, "float")
        if (at >= 0) data.putFloat(at, value)
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
        linear.close()
        nearest.close()
        linearRepeat.close()
        shadowCompare.close()
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
        const val SOURCE_COLOR = 2
        const val SOURCE_DEPTH0 = 3
        const val SOURCE_DEPTH1 = 4
        const val SOURCE_SHADOW_DEPTH = 5
        const val SOURCE_SHADOW_COLOR = 6

        // Custom uniforms of the Complementary family of packs: 1 while the camera is in the biome.
        val BIOME_UNIFORMS = arrayOf(
            "inNetherWastes", "inCrimsonForest", "inWarpedForest", "inBasaltDeltas", "inSoulValley", "inPaleGarden", "inSulfurCaves",
        )
        val BIOME_KEYS = arrayOf(
            Biomes.NETHER_WASTES, Biomes.CRIMSON_FOREST, Biomes.WARPED_FOREST, Biomes.BASALT_DELTAS, Biomes.SOUL_SAND_VALLEY,
            Biomes.PALE_GARDEN, Biomes.SULFUR_CAVES,
        )

        val DEPTH_FORMAT = MetalFormats.code(GpuFormat.D32_FLOAT)
        val GAUX = mapOf("gaux1" to "colortex4", "gaux2" to "colortex5", "gaux3" to "colortex6", "gaux4" to "colortex7")

        /** The block atlas, which the game binds itself as Sampler0. */
        val ATLAS_NAMES = setOf("tex", "gtexture", "texture")

        // Buffer indices as the converter assigns them.
        val PACK_UNIFORMS_ENTRY = BindingEntry(BindingEntry.UNIFORM_BUFFER, PACK_UNIFORMS, null, 0)
        val DRAW_UNIFORMS_ENTRY = BindingEntry(BindingEntry.UNIFORM_BUFFER, DRAW_UNIFORMS, null, 1)
        val CHUNK_SECTION_ENTRY = BindingEntry(BindingEntry.UNIFORM_BUFFER, "ChunkSection", null, 2)
        val GLOBALS_ENTRY = BindingEntry(BindingEntry.UNIFORM_BUFFER, "Globals", null, 3)
        val DYNAMIC_TRANSFORMS_ENTRY = BindingEntry(BindingEntry.UNIFORM_BUFFER, "DynamicTransforms", null, 2)
    }
}
