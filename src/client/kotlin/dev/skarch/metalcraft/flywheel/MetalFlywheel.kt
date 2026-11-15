package dev.skarch.metalcraft.flywheel

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.VertexFormat
import com.zurrtum.create.client.flywheel.api.backend.Engine
import com.zurrtum.create.client.flywheel.api.backend.RenderContext
import com.zurrtum.create.client.flywheel.api.instance.Instance
import com.zurrtum.create.client.flywheel.api.material.DepthTest
import com.zurrtum.create.client.flywheel.api.material.Material
import com.zurrtum.create.client.flywheel.api.material.Transparency
import com.zurrtum.create.client.flywheel.api.model.Mesh
import com.zurrtum.create.client.flywheel.api.vertex.MutableVertexList
import com.zurrtum.create.client.flywheel.backend.MaterialShaderIndices
import com.zurrtum.create.client.flywheel.backend.engine.AbstractInstancer
import com.zurrtum.create.client.flywheel.backend.engine.BaseInstancer
import com.zurrtum.create.client.flywheel.backend.engine.DrawManager
import com.zurrtum.create.client.flywheel.backend.engine.EngineImpl
import com.zurrtum.create.client.flywheel.backend.engine.InstancerKey
import com.zurrtum.create.client.flywheel.backend.engine.LightStorage
import com.zurrtum.create.client.flywheel.backend.engine.MaterialEncoder
import com.zurrtum.create.client.flywheel.backend.engine.MeshPool
import com.zurrtum.create.client.flywheel.backend.engine.embed.EmbeddedEnvironment
import com.zurrtum.create.client.flywheel.backend.engine.embed.Environment
import com.zurrtum.create.client.flywheel.backend.engine.embed.EnvironmentStorage
import com.zurrtum.create.client.flywheel.backend.engine.uniform.FogUniforms
import com.zurrtum.create.client.flywheel.backend.engine.uniform.FrameUniforms
import com.zurrtum.create.client.flywheel.backend.engine.uniform.LevelUniforms
import com.zurrtum.create.client.flywheel.backend.engine.uniform.OptionsUniforms
import com.zurrtum.create.client.flywheel.backend.engine.uniform.PlayerUniforms
import com.zurrtum.create.client.flywheel.backend.engine.uniform.UniformBuffer
import com.zurrtum.create.client.flywheel.backend.engine.uniform.Uniforms
import com.zurrtum.create.client.flywheel.lib.backend.SimpleBackend
import com.zurrtum.create.client.flywheel.lib.material.CutoutShaders
import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.backend.MetalDevice
import dev.skarch.metalcraft.backend.MetalFormats
import dev.skarch.metalcraft.backend.MetalPipeline
import dev.skarch.metalcraft.backend.MetalRenderPass
import dev.skarch.metalcraft.backend.PackProgram
import dev.skarch.metalcraft.pipeline.ConvertedPack
import dev.skarch.metalcraft.pipeline.ShaderPipeline
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.world.level.LevelAccessor
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.OptionalDouble

/**
 * Flywheel on Metal (CLAUDE.md section 3-7). Create draws its moving parts through Flywheel, whose own
 * backends use OpenGL directly. This registers a backend that keeps Flywheel's engine (instancers, light
 * storage, uniforms, shader pieces) and replaces what touches OpenGL: buffers, programs and draws go
 * through MetalCraft. Only referenced when Create is installed.
 */
object MetalFlywheel {
    fun register() {
        SimpleBackend.builder()
            .engineFactory { level -> MetalEngine(level) }
            // Above Flywheel's own backends, which cannot work without OpenGL anyway.
            .priority(2000)
            .supported { MetalDevice.current != null }
            .register(Identifier.fromNamespaceAndPath("metalcraft", "metal"))
        LOGGER.info("Flywheel backend for Metal registered")
    }
}

/** Flywheel's engine with the frame drawn through MetalCraft instead of inside an OpenGL state guard. */
private class MetalEngine(level: LevelAccessor) : EngineImpl(level, MetalDrawManager(), 256) {
    override fun render(context: RenderContext) {
        try {
            Uniforms.update(context)
            environmentStorage().flush()
            drawManager().render(lightStorage(), environmentStorage())
        } catch (e: Exception) {
            LOGGER.error("Flywheel on Metal failed, Create falls back to its plain renderers", e)
            drawManager().triggerFallback()
        }
    }

    // Block breaking overlays on instanced parts are not drawn yet.
    override fun renderCrumbling(context: RenderContext) {}
}

/** A mesh in MetalCraft buffers: Flywheel's vertex attributes as floats, 32-bit indices. */
private class FlwMesh(device: MetalDevice, mesh: Mesh) {
    val vertices: GpuBuffer
    val indices: GpuBuffer
    val indexCount = mesh.indexCount()

    init {
        val vertexCount = mesh.vertexCount()
        val vertexData = MemoryUtil.memCalloc(vertexCount * STRIDE)
        val indexData = MemoryUtil.memAlloc(indexCount * 4)
        try {
            mesh.write(Writer(MemoryUtil.memAddress(vertexData), vertexCount))
            mesh.indexSequence().fill(MemoryUtil.memAddress(indexData), indexCount)
            vertices = device.createBuffer({ "Flywheel mesh" }, GpuBuffer.USAGE_VERTEX, vertexData)
            indices = device.createBuffer({ "Flywheel mesh indices" }, GpuBuffer.USAGE_INDEX, indexData)
        } finally {
            MemoryUtil.memFree(vertexData)
            MemoryUtil.memFree(indexData)
        }
    }

    fun delete() {
        vertices.close()
        indices.close()
    }

    /** Receives a mesh's vertices from Flywheel and stores them in [FORMAT]. */
    private class Writer(private val base: Long, private val count: Int) : MutableVertexList {
        private fun at(index: Int, offset: Int) = base + index.toLong() * STRIDE + offset
        override fun vertexCount() = count
        override fun x(index: Int, x: Float) = MemoryUtil.memPutFloat(at(index, 0), x)
        override fun y(index: Int, y: Float) = MemoryUtil.memPutFloat(at(index, 4), y)
        override fun z(index: Int, z: Float) = MemoryUtil.memPutFloat(at(index, 8), z)
        override fun r(index: Int, r: Float) = MemoryUtil.memPutByte(at(index, 12), unorm(r))
        override fun g(index: Int, g: Float) = MemoryUtil.memPutByte(at(index, 13), unorm(g))
        override fun b(index: Int, b: Float) = MemoryUtil.memPutByte(at(index, 14), unorm(b))
        override fun a(index: Int, a: Float) = MemoryUtil.memPutByte(at(index, 15), unorm(a))
        override fun u(index: Int, u: Float) = MemoryUtil.memPutFloat(at(index, 16), u)
        override fun v(index: Int, v: Float) = MemoryUtil.memPutFloat(at(index, 20), v)
        override fun overlay(index: Int, overlay: Int) {
            MemoryUtil.memPutFloat(at(index, 24), (overlay and 0xFFFF).toShort().toFloat())
            MemoryUtil.memPutFloat(at(index, 28), (overlay ushr 16).toShort().toFloat())
        }
        override fun light(index: Int, light: Int) {
            MemoryUtil.memPutFloat(at(index, 32), (light and 0xFFFF).toFloat())
            MemoryUtil.memPutFloat(at(index, 36), (light ushr 16).toFloat())
        }
        override fun normalX(index: Int, normalX: Float) = MemoryUtil.memPutFloat(at(index, 40), normalX)
        override fun normalY(index: Int, normalY: Float) = MemoryUtil.memPutFloat(at(index, 44), normalY)
        override fun normalZ(index: Int, normalZ: Float) = MemoryUtil.memPutFloat(at(index, 48), normalZ)

        override fun x(index: Int) = MemoryUtil.memGetFloat(at(index, 0))
        override fun y(index: Int) = MemoryUtil.memGetFloat(at(index, 4))
        override fun z(index: Int) = MemoryUtil.memGetFloat(at(index, 8))
        override fun r(index: Int) = (MemoryUtil.memGetByte(at(index, 12)).toInt() and 0xFF) / 255f
        override fun g(index: Int) = (MemoryUtil.memGetByte(at(index, 13)).toInt() and 0xFF) / 255f
        override fun b(index: Int) = (MemoryUtil.memGetByte(at(index, 14)).toInt() and 0xFF) / 255f
        override fun a(index: Int) = (MemoryUtil.memGetByte(at(index, 15)).toInt() and 0xFF) / 255f
        override fun u(index: Int) = MemoryUtil.memGetFloat(at(index, 16))
        override fun v(index: Int) = MemoryUtil.memGetFloat(at(index, 20))
        override fun overlay(index: Int) =
            (MemoryUtil.memGetFloat(at(index, 24)).toInt() and 0xFFFF) or (MemoryUtil.memGetFloat(at(index, 28)).toInt() shl 16)
        override fun light(index: Int) =
            (MemoryUtil.memGetFloat(at(index, 32)).toInt() and 0xFFFF) or (MemoryUtil.memGetFloat(at(index, 36)).toInt() shl 16)
        override fun normalX(index: Int) = MemoryUtil.memGetFloat(at(index, 40))
        override fun normalY(index: Int) = MemoryUtil.memGetFloat(at(index, 44))
        override fun normalZ(index: Int) = MemoryUtil.memGetFloat(at(index, 48))

        private fun unorm(value: Float): Byte = Math.round(value.coerceIn(0f, 1f) * 255f).toByte()
    }

    companion object {
        const val STRIDE = 52
        val FORMAT: VertexFormat = VertexFormat.builder(0)
            .addAttribute("_flw_aPos", GpuFormat.RGB32_FLOAT)
            .addAttribute("_flw_aColor", GpuFormat.RGBA8_UNORM)
            .addAttribute("_flw_aTexCoord", GpuFormat.RG32_FLOAT)
            .addAttribute("_flw_aOverlay", GpuFormat.RG32_FLOAT)
            .addAttribute("_flw_aLight", GpuFormat.RG32_FLOAT)
            .addAttribute("_flw_aNormal", GpuFormat.RGB32_FLOAT)
            .build()
            .also { check(it.vertexSize == STRIDE) { "Flywheel mesh format is ${it.vertexSize} bytes" } }
    }
}

/** The instances of one model in one environment, with their data in a buffer the shader indexes. */
private class MetalInstancer<I : Instance>(key: InstancerKey<I>, recreate: Recreate<I>) : BaseInstancer<I>(key, recreate) {
    // One instance takes a whole number of 16-byte elements of the shader's array.
    private val stride = (type.layout().byteSize() + 15) / 16 * 16
    private val writer = type.writer()
    var buffer: GpuBuffer? = null
    var draws: List<FlwDraw> = emptyList()

    /** Writes the instances to the GPU if any of them changed. */
    fun update(device: MetalDevice) {
        if (changed.isEmpty && buffer != null) return
        val count = instances.size
        if (count == 0) return
        val bytes = stride.toLong() * count
        val data = MemoryUtil.memCalloc(bytes.toInt())
        try {
            var pointer = MemoryUtil.memAddress(data)
            for (i in 0 until count) {
                writer.write(pointer, instances[i])
                pointer += stride
            }
            val current = buffer
            if (current == null || current.size() < bytes) {
                current?.close()
                // Room to grow, so that adding parts one by one does not make a new buffer every time.
                val capacity = maxOf(bytes + stride * 16L, (bytes * 1.6).toLong())
                val grown = MemoryUtil.memCalloc(capacity.toInt())
                try {
                    MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(grown), bytes)
                    buffer = device.createBuffer({ "Flywheel instances" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, grown)
                } finally {
                    MemoryUtil.memFree(grown)
                }
            } else {
                device.encoder.writeToBuffer(current.slice(0, bytes), data)
            }
        } finally {
            MemoryUtil.memFree(data)
        }
        changed.clear()
    }

    // Same compaction as Flywheel's own instancers: deleted instances are punched out, the rest move up.
    override fun parallelUpdate() {
        if (deleted.isEmpty) return
        val oldSize = instances.size
        val removeCount = deleted.cardinality()
        if (oldSize == removeCount) {
            clear()
            return
        }
        val newSize = oldSize - removeCount
        var writePos = deleted.nextSetBit(0)
        if (writePos < newSize) changed.set(writePos, newSize)
        changed.clear(newSize, oldSize)
        var scanPos = writePos
        while (scanPos < oldSize && writePos < newSize) {
            scanPos = deleted.nextClearBit(scanPos)
            if (scanPos != writePos) {
                val handle = handles[scanPos]
                handles[writePos] = handle
                instances[writePos] = instances[scanPos]
                handle.index = writePos
            }
            scanPos++
            writePos++
        }
        deleted.clear()
        instances.subList(newSize, oldSize).clear()
        handles.subList(newSize, oldSize).clear()
    }

    override fun delete() {
        buffer?.close()
        buffer = null
    }
}

/** One mesh of an instancer's model with its material: one instanced draw call. */
private class FlwDraw(
    val instancer: MetalInstancer<*>, val mesh: FlwMesh, val material: Material, val environment: Environment, val bias: Int, val meshIndex: Int,
)

private class MetalDrawManager : DrawManager<MetalInstancer<*>>() {
    private val device = checkNotNull(MetalDevice.current) { "Metal is not active" }
    private val shaders = FlwShaders(Minecraft.getInstance().resourceManager)
    private val meshes = HashMap<Mesh, FlwMesh>()
    private val programs = HashMap<ProgramKey, PackProgram>()
    private val packPrograms = HashMap<ProgramKey, ConvertedPack.ExternalProgram>()
    private val draws = ArrayList<FlwDraw>()
    private var needSort = false

    private val drawData: ByteBuffer = MemoryUtil.memCalloc(FlwShaders.DRAW_BLOCK_SIZE)
    private val matrixData: Long = MemoryUtil.nmemCalloc(1, 112)
    private var lightLut: GpuBuffer? = null
    private var lightSections: GpuBuffer? = null

    private data class ProgramKey(val shader: FlwShaderKey, val state: Int, val packSlot: Int, val packFormats: List<Int>, val pack: Any? = null)

    override fun <I : Instance> create(key: InstancerKey<I>): MetalInstancer<*> = MetalInstancer(key, AbstractInstancer.Recreate(key, this))

    override fun <I : Instance> initialize(key: InstancerKey<I>, instancer: MetalInstancer<*>) {
        val added = ArrayList<FlwDraw>()
        for ((index, configured) in key.model().meshes().withIndex()) {
            val mesh = meshes.getOrPut(configured.mesh()) { FlwMesh(device, configured.mesh()) }
            added.add(FlwDraw(instancer, mesh, configured.material(), key.environment(), key.bias(), index))
        }
        instancer.draws = added
        draws.addAll(added)
        needSort = true
    }

    override fun render(lightStorage: LightStorage, environmentStorage: EnvironmentStorage) {
        super.render(lightStorage, environmentStorage)
        val emptied = instancers.values.removeIf { instancer ->
            if (instancer.instanceCount() == 0) {
                instancer.delete()
                draws.removeAll(instancer.draws.toSet())
                true
            } else {
                instancer.update(device)
                false
            }
        }
        if (needSort || emptied) {
            // Flywheel's order: by bias, then the mesh's place in its model.
            draws.sortWith(compareBy<FlwDraw> { it.bias }.thenBy { it.meshIndex })
            needSort = false
        }
        uploadLight(lightStorage)
        if (draws.isEmpty()) return

        val minecraft = Minecraft.getInstance()
        val target = minecraft.gameRenderer.mainRenderTarget()
        val descriptor = RenderPassDescriptor.create { "Flywheel" }
            .withColorAttachment(target.colorTextureView!!)
            .withDepthAttachment(target.depthTextureView!!, OptionalDouble.empty())
            .withRenderArea(RenderPass.RenderArea(0, 0, target.width, target.height))
        val pass = device.encoder.createRenderPass(descriptor) as MetalRenderPass
        try {
            val transient = device.encoder.transientMemory()
            val frame = upload(transient, FrameUniforms::class.java)
            val fog = upload(transient, FogUniforms::class.java)
            val options = upload(transient, OptionsUniforms::class.java)
            val player = upload(transient, PlayerUniforms::class.java)
            val level = upload(transient, LevelUniforms::class.java)
            val gbuffer = ShaderPipeline.gbuffer
            // With a converted shader pack the parts are drawn by the pack's own entity program.
            val pack = ShaderPipeline.activeConvertedPack()?.takeIf { it.flywheel != null }
            val samplers = RenderSystem.getSamplerCache()
            for (draw in draws) {
                val instances = draw.instancer.buffer ?: continue
                val material = draw.material
                val lit = if (pack != null) packProgram(draw, pack) else null
                pass.setProgram(lit?.program ?: program(draw, gbuffer))
                if (lit != null) pack!!.bindExternal(pass, lit, alphaTest(material))
                pass.setUniform("_FlwFrameUniforms", frame)
                pass.setUniform("_FlwFogUniforms", fog)
                pass.setUniform("_FlwOptionsUniforms", options)
                pass.setUniform("_FlwPlayerUniforms", player)
                pass.setUniform("_FlwLevelUniforms", level)
                pass.setUniform(FlwShaders.INSTANCE_BLOCK, instances)
                pass.setUniform(FlwShaders.LIGHT_LUT_BLOCK, lightLut!!)
                pass.setUniform(FlwShaders.LIGHT_SECTIONS_BLOCK, lightSections!!)
                pass.setUniform(FlwShaders.DRAW_BLOCK, writeDraw(transient, draw))
                val texture = minecraft.textureManager.getTexture(material.texture())
                val filter = if (material.blur()) FilterMode.LINEAR else FilterMode.NEAREST
                val own = texture.sampler
                pass.bindTexture(
                    if (lit != null) "Sampler0" else "flw_diffuseTex", texture.textureView,
                    samplers.getSampler(own.addressModeU, own.addressModeV, filter, filter, material.mipmap()),
                )
                pass.bindTexture("flw_overlayTex", minecraft.gameRenderer.overlayTexture().textureView, samplers.getClampToEdge(FilterMode.NEAREST))
                pass.bindTexture("flw_lightTex", minecraft.gameRenderer.lightmap(), samplers.getClampToEdge(FilterMode.LINEAR))
                pass.setVertexBuffer(0, draw.mesh.vertices.slice())
                pass.setIndexBuffer(draw.mesh.indices, IndexType.INT)
                pass.drawIndexed(draw.mesh.indexCount, draw.instancer.instanceCount(), 0, 0, 0)
            }
        } finally {
            device.encoder.submitRenderPass()
        }
    }

    /** The per-draw block: packed material, base instance and vertex, and the embedding's matrices. */
    private fun writeDraw(transient: com.mojang.blaze3d.systems.TransientMemory, draw: FlwDraw): GpuBufferSlice {
        val data = drawData
        val material = draw.material
        data.putInt(0, MaterialEncoder.packUberShader(material))
        data.putInt(4, MaterialEncoder.packProperties(material))
        data.putInt(8, 0)
        data.putInt(12, 0)
        val environment = draw.environment
        if (environment is EmbeddedEnvironment) {
            // Flywheel writes the composed pose (16 floats) and the padded normal matrix (12 floats).
            environment.flush(matrixData)
            MemoryUtil.memCopy(matrixData, MemoryUtil.memAddress(data) + 16, 112)
        }
        data.position(0).limit(FlwShaders.DRAW_BLOCK_SIZE)
        return transient.uploadGpu(data, 16L, GpuBuffer.USAGE_UNIFORM)
    }

    private fun upload(transient: com.mojang.blaze3d.systems.TransientMemory, holder: Class<*>): GpuBufferSlice {
        val buffer = UNIFORM_BUFFER.getOrPut(holder) { holder.getDeclaredField("BUFFER").apply { isAccessible = true } }.get(null) as UniformBuffer
        val size = (CLIENT_BUFFER.get(buffer) as com.zurrtum.create.client.flywheel.lib.memory.MemoryBlock).size().toInt()
        return transient.uploadGpu(MemoryUtil.memByteBuffer(buffer.ptr(), size), 16L, GpuBuffer.USAGE_UNIFORM)
    }

    /** Flywheel's light sections and the table that finds them, re-sent when they change. */
    private fun uploadLight(storage: LightStorage) {
        val changed = LIGHT_CHANGED.get(storage) as BitSet
        val sectionBytes = maxOf(storage.arena.capacity().toLong() * LightStorage.SECTION_SIZE_BYTES, 16L)
        val sections = lightSections
        if (sections == null || sections.size() < sectionBytes || !changed.isEmpty) {
            val data = MemoryUtil.memCalloc(sectionBytes.toInt())
            try {
                if (storage.arena.capacity() > 0) {
                    MemoryUtil.memCopy(storage.arena.indexToPointer(0), MemoryUtil.memAddress(data), storage.arena.capacity().toLong() * LightStorage.SECTION_SIZE_BYTES)
                }
                if (sections == null || sections.size() < sectionBytes) {
                    sections?.close()
                    lightSections = device.createBuffer({ "Flywheel light" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, data)
                } else {
                    device.encoder.writeToBuffer(sections.slice(0, sectionBytes), data)
                }
            } finally {
                MemoryUtil.memFree(data)
            }
            changed.clear()
        }
        if (lightLut == null || storage.checkNeedsLutRebuildAndClear()) {
            val lut = storage.createLut()
            val data = MemoryUtil.memCalloc(maxOf(lut.size * 4, 16))
            try {
                for (i in 0 until lut.size) data.putInt(i * 4, lut.getInt(i))
                lightLut?.close()
                lightLut = device.createBuffer({ "Flywheel light table" }, GpuBuffer.USAGE_UNIFORM, data)
            } finally {
                MemoryUtil.memFree(data)
            }
        }
    }

    private fun shaderKey(draw: FlwDraw): FlwShaderKey {
        val material = draw.material
        MaterialShaderIndices.fogSources().index(material.fog().source())
        MaterialShaderIndices.cutoutSources().index(material.cutout().source())
        return FlwShaderKey(
            draw.instancer.type, draw.environment.contextShader(), material.light().source(), material.shaders(),
            material.cutout() != CutoutShaders.OFF, MaterialShaderIndices.fogSources().all().size, MaterialShaderIndices.cutoutSources().all().size,
        )
    }

    private fun stateBits(material: Material): Int = (if (material.backfaceCulling()) 1 else 0) or (material.depthTest().ordinal shl 1) or
        (material.writeMask().ordinal shl 5) or (material.transparency().ordinal shl 8)

    /** The alpha below which the pack's program drops a pixel, from the material's cutout shader. */
    private fun alphaTest(material: Material): Float = when (material.cutout()) {
        CutoutShaders.OFF -> -1f
        CutoutShaders.HALF -> 0.5f
        CutoutShaders.ONE_TENTH -> 0.1f
        else -> 0.0001f
    }

    private fun packProgram(draw: FlwDraw, pack: ConvertedPack): ConvertedPack.ExternalProgram {
        val material = draw.material
        // Only the vertex side of Flywheel's shaders is used here: the pack's fragment stage does the rest.
        val full = shaderKey(draw)
        val shaderKey = FlwShaderKey(full.type, full.context, FLAT_LIGHT, full.shaders, false, 0, 0)
        val key = ProgramKey(shaderKey, stateBits(material), 0, emptyList(), pack)
        return packPrograms.getOrPut(key) {
            val template = pack.flywheel!!
            val translated = shaders.getPackLit(device, shaderKey, template)
            val depth = material.depthTest()
            pack.externalWorldProgram(
                "flywheel ${shaderKey.type.vertexShader().path}", translated.vertexMsl, translated.fragmentMsl, translated.entries, template.targets,
                FlwMesh.FORMAT, material.backfaceCulling(), if (depth == DepthTest.OFF) -1 else DEPTH_COMPARE.getValue(depth),
                material.writeMask().depth(), material.transparency() != Transparency.OPAQUE,
            )
        }
    }

    private fun program(draw: FlwDraw, gbuffer: ShaderPipeline.Gbuffer?): PackProgram {
        val material = draw.material
        val shaderKey = shaderKey(draw)
        // A transparency Metal has no blend state for yet is drawn as plain translucency.
        val transparency = material.transparency()
        val state = stateBits(material)
        val slot = gbuffer?.colorSlot ?: -1
        val key = ProgramKey(shaderKey, state, slot, gbuffer?.formats?.toList() ?: emptyList())
        return programs.getOrPut(key) {
            val translated = shaders.get(device, shaderKey)
            val mask = if (material.writeMask().color() != 0) WRITE_ALL else 0
            val formats: IntArray
            val masks: IntArray
            val fragment: String
            var blendMask = if (transparency == Transparency.OPAQUE) 0 else 1
            if (gbuffer == null) {
                formats = intArrayOf(MetalFormats.code(GpuFormat.RGBA8_UNORM))
                masks = intArrayOf(mask)
                fragment = translated.fragmentMsl
            } else {
                // With a shader pack the pass carries the pack's buffers; the colour goes to the pack's image.
                formats = IntArray(1 + gbuffer.formats.size) { if (it == 0) MetalFormats.code(GpuFormat.RGBA8_UNORM) else gbuffer.formats[it - 1] }
                val redirected = if (slot >= 0) MetalPipeline.redirectColor(translated.fragmentMsl, slot) else null
                val written = if (redirected != null) slot + 1 else 0
                masks = IntArray(formats.size) { if (it == written) mask else 0 }
                blendMask = blendMask shl written
                fragment = redirected ?: translated.fragmentMsl
            }
            val depth = material.depthTest()
            PackProgram(
                device, "flywheel/${shaderKey.type.vertexShader().path}", translated.vertexMsl, fragment, translated.entries, FlwMesh.FORMAT,
                formats, masks, blendMask, if (depth == DepthTest.OFF) -1 else DEPTH_COMPARE.getValue(depth), material.writeMask().depth(),
                if (material.backfaceCulling()) 1 else 0, BLEND[transparency],
            ).also { it.variant(MetalFormats.code(GpuFormat.D32_FLOAT), null) }
        }
    }

    override fun renderCrumbling(crumblingBlocks: List<Engine.CrumblingBlock>) {}

    override fun delete() {
        super.delete()
        for (mesh in meshes.values) mesh.delete()
        meshes.clear()
        for (program in programs.values) program.destroy()
        programs.clear()
        draws.clear()
        lightLut?.close()
        lightSections?.close()
        lightLut = null
        lightSections = null
        MemoryUtil.memFree(drawData)
        MemoryUtil.nmemFree(matrixData)
    }

    override fun triggerFallback() {
        Minecraft.getInstance().levelExtractor.allChanged()
    }

    // Flywheel's OpenGL mesh pool, which only its debug report asks for. There is none here.
    override fun meshPool(): MeshPool = throw UnsupportedOperationException("The Metal backend has no OpenGL mesh pool")

    private companion object {
        const val WRITE_ALL = 15

        // Stands in for the light shader in the key of pack-lit programs, where Flywheel's is not used.
        val FLAT_LIGHT: Identifier = Identifier.fromNamespaceAndPath("flywheel", "light/flat.glsl")

        // Flywheel's depth tests already follow the game's reversed depth.
        val DEPTH_COMPARE = mapOf(
            DepthTest.NEVER to 0, DepthTest.LESS to 1, DepthTest.EQUAL to 2, DepthTest.LEQUAL to 3,
            DepthTest.GREATER to 4, DepthTest.NOTEQUAL to 5, DepthTest.GEQUAL to 6, DepthTest.ALWAYS to 7,
        )

        // Metal blend operation and factor codes: colour op, alpha op, source and destination for colour, then for alpha.
        const val ZERO = 0
        const val ONE = 1
        const val SRC_COLOR = 2
        const val SRC_ALPHA = 4
        const val ONE_MINUS_SRC_ALPHA = 5
        const val DST_COLOR = 6
        val BLEND: Map<Transparency, IntArray> = mapOf(
            Transparency.ADDITIVE to intArrayOf(0, 0, ONE, ONE, ONE, ONE),
            Transparency.LIGHTNING to intArrayOf(0, 0, SRC_ALPHA, ONE, SRC_ALPHA, ONE),
            Transparency.GLINT to intArrayOf(0, 0, SRC_COLOR, ONE, ZERO, ONE),
            Transparency.CRUMBLING to intArrayOf(0, 0, DST_COLOR, SRC_COLOR, ONE, ZERO),
        )

        // Flywheel keeps these to itself; they are read once.
        val UNIFORM_BUFFER = HashMap<Class<*>, java.lang.reflect.Field>()
        val CLIENT_BUFFER: java.lang.reflect.Field = UniformBuffer::class.java.getDeclaredField("clientBuffer").apply { isAccessible = true }
        val LIGHT_CHANGED: java.lang.reflect.Field = LightStorage::class.java.getDeclaredField("changed").apply { isAccessible = true }
    }
}
