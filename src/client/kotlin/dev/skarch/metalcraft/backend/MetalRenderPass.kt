package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassBackend
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTextureView
import dev.skarch.metalcraft.native.Mcn
import dev.skarch.metalcraft.pipeline.ShaderPipeline
import dev.skarch.metalcraft.upscale.Upscaler
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryUtil
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.IntBuffer
import java.util.function.Supplier

/**
 * The open render pass. There is one instance, reused for every pass, because only one pass can be open.
 *
 * Uniforms and textures are remembered by name and sent to Metal right before a draw, once the pipeline
 * that says where each name goes is known.
 */
internal class MetalRenderPass(private val device: MetalDevice, private val encoder: MetalCommandEncoder) : RenderPassBackend {
    private val context = device.context
    private val uniforms = HashMap<String, GpuBufferSlice>()
    private val textureViews = HashMap<String, MetalTextureView>()
    private val samplers = HashMap<String, MetalSampler>()
    private var pipeline: DrawProgram? = null
    private var extraTargets: IntArray? = null
    private var bindingsDirty = false
    private var debugGroups = 0

    // Set while the selected game pipeline has no counterpart in the active shader pack: its draws are dropped.
    private var skipDraws = false

    private var areaX = 0
    private var areaY = 0
    private var areaWidth = 0
    private var areaHeight = 0
    private var depthFormat = MetalFormats.NO_FORMAT

    // Set while the pass draws into the reduced-size world images instead of the game's main ones.
    private var redirect: Upscaler.Redirect? = null

    private var indexBuffer: MetalBuffer? = null
    private var wideIndices = 0

    private val texelBufferOut: MemorySegment = Arena.global().allocate(JAVA_LONG)
    private val uniformUploader = RenderPass.UniformUploader { name, buffer -> setUniform(name, buffer) }

    fun begin(x: Int, y: Int, width: Int, height: Int, depthFormat: Int, extraTargets: IntArray?, redirect: Upscaler.Redirect?) {
        this.extraTargets = extraTargets
        this.redirect = redirect
        areaX = x
        areaY = y
        areaWidth = width
        areaHeight = height
        this.depthFormat = depthFormat
        Mcn.passSetScissor.invokeExact(context, x, y, width, height) as Int
    }

    fun end() {
        uniforms.clear()
        textureViews.clear()
        samplers.clear()
        pipeline = null
        skipDraws = false
        indexBuffer = null
        bindingsDirty = false
        debugGroups = 0
    }

    override fun pushDebugGroup(label: Supplier<String>) {
        debugGroups++
    }

    override fun popDebugGroup() {
        check(debugGroups > 0) { "Can't pop more debug groups than was pushed!" }
        debugGroups--
    }

    override fun setPipeline(pipeline: RenderPipeline) {
        // An active shader pack replaces some of the game's pipelines with its own programs.
        // Pack programs for a pipeline are built the first time the game uses it, which can fail.
        val replacement = try {
            ShaderPipeline.substitute(pipeline, extraTargets != null)
        } catch (e: Exception) {
            ShaderPipeline.programFailed(e)
            null
        }
        skipDraws = replacement == null && extraTargets != null && ShaderPipeline.hidesGameDraws()
        if (skipDraws) return
        if (replacement != null) {
            try {
                setProgram(replacement)
                ShaderPipeline.bindFrameResources(this)
                return
            } catch (e: Exception) {
                // The game is in the middle of drawing the world: carry on with its own pipeline.
                ShaderPipeline.programFailed(e)
            }
        }
        setProgram(device.getOrCompilePipeline(pipeline))
    }

    /** Selects the shaders for the following draws. Shader pack passes call this directly. */
    fun setProgram(program: DrawProgram) {
        check(program.isUsable()) { "Pipeline is not valid (may contain invalid shaders?)" }
        this.pipeline = program
        bindingsDirty = true
        Mcn.passSetPipeline.invokeExact(
            context, program.variant(depthFormat, extraTargets), program.cull, program.wireframe, program.depthBias, program.depthBiasSlope,
        ) as Int
    }

    override fun bindTexture(name: String, textureView: GpuTextureView?, sampler: GpuSampler?) {
        if (textureView != null && sampler != null) {
            // While the world is drawn small, reading the game's main images means reading the small ones.
            val worldImages = Upscaler.redirect
            val view = when {
                worldImages == null -> textureView
                textureView.texture() === worldImages.mainColor -> worldImages.colorView
                textureView.texture() === worldImages.mainDepth -> worldImages.depthView
                else -> textureView
            }
            textureViews[name] = view as MetalTextureView
            samplers[name] = sampler as MetalSampler
            bindingsDirty = true
        } else if (textureView == null && sampler == null) {
            textureViews.remove(name)
            samplers.remove(name)
        } else {
            throw IllegalArgumentException("A texture needs a sampler and a sampler needs a texture")
        }
    }

    override fun setUniform(name: String, value: GpuBuffer) {
        uniforms[name] = value.slice()
        bindingsDirty = true
    }

    override fun setUniform(name: String, value: GpuBufferSlice) {
        uniforms[name] = value
        bindingsDirty = true
    }

    override fun enableScissor(x: Int, y: Int, width: Int, height: Int) {
        val scaled = redirect
        if (scaled == null) {
            Mcn.passSetScissor.invokeExact(context, x, y, width, height) as Int
            return
        }
        // The game gives the rectangle in pixels of its full-size image.
        val left = x * scaled.width / scaled.fullWidth
        val top = y * scaled.height / scaled.fullHeight
        val right = ((x + width).toLong() * scaled.width + scaled.fullWidth - 1) / scaled.fullWidth
        val bottom = ((y + height).toLong() * scaled.height + scaled.fullHeight - 1) / scaled.fullHeight
        Mcn.passSetScissor.invokeExact(context, left, top, (right - left).toInt(), (bottom - top).toInt()) as Int
    }

    override fun disableScissor() {
        Mcn.passSetScissor.invokeExact(context, areaX, areaY, areaWidth, areaHeight) as Int
    }

    override fun setVertexBuffer(slot: Int, vertexBuffer: GpuBufferSlice?) {
        val handle = if (vertexBuffer != null) (vertexBuffer.buffer() as MetalBuffer).handle else 0L
        Mcn.passSetVertexBuffer.invokeExact(context, MetalPipeline.vertexBufferIndex(slot), handle, vertexBuffer?.offset() ?: 0L) as Int
    }

    override fun setIndexBuffer(indexBuffer: GpuBuffer, indexType: IndexType) {
        this.indexBuffer = indexBuffer as MetalBuffer
        wideIndices = if (indexType == IndexType.INT) 1 else 0
    }

    override fun drawIndexed(indexCount: Int, instanceCount: Int, firstIndex: Int, vertexOffset: Int, firstInstance: Int) {
        if (skipDraws) return
        val pipeline = readyPipeline()
        val indices = checkNotNull(indexBuffer) { "No index buffer is set" }
        val indexSize = if (wideIndices != 0) 4L else 2L
        if (pipeline.primitive == MetalFormats.PRIMITIVE_UNSUPPORTED_FAN) {
            drawFan(indexCount, instanceCount, vertexOffset, firstInstance, indices.contents + firstIndex * indexSize)
            return
        }
        Mcn.passDrawIndexed.invokeExact(
            context, pipeline.primitive, indexCount, wideIndices, indices.handle, firstIndex * indexSize,
            instanceCount, vertexOffset, firstInstance,
        ) as Int
    }

    override fun draw(vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int) {
        if (skipDraws) return
        val pipeline = pipeline
        if (pipeline == null || !pipeline.isUsable()) return
        pushBindings(pipeline)
        if (pipeline.primitive == MetalFormats.PRIMITIVE_UNSUPPORTED_FAN) {
            drawFan(vertexCount, instanceCount, firstVertex, firstInstance, 0L)
            return
        }
        Mcn.passDraw.invokeExact(context, pipeline.primitive, firstVertex, vertexCount, instanceCount, firstInstance) as Int
    }

    /**
     * Metal has no triangle fans, so a fan is drawn as a triangle list with generated indices. [sourceIndices]
     * is the address of the fan's own indices, or 0 when its vertices are simply consecutive.
     */
    private fun drawFan(count: Int, instanceCount: Int, baseVertex: Int, firstInstance: Int, sourceIndices: Long) {
        if (count < 3) return
        val indexCount = (count - 2) * 3
        val view = encoder.transientMemory().allocateStaging(indexCount * 4L, 4L, GpuBuffer.USAGE_INDEX)
        val target = MemoryUtil.memAddress(view.data())
        val wide = wideIndices != 0
        fun corner(i: Int): Int = when {
            sourceIndices == 0L -> i
            wide -> MemoryUtil.memGetInt(sourceIndices + i * 4L)
            else -> MemoryUtil.memGetShort(sourceIndices + i * 2L).toInt() and 0xFFFF
        }
        val first = corner(0)
        for (triangle in 0 until count - 2) {
            val base = target + triangle * 12L
            MemoryUtil.memPutInt(base, first)
            MemoryUtil.memPutInt(base + 4, corner(triangle + 1))
            MemoryUtil.memPutInt(base + 8, corner(triangle + 2))
        }
        Mcn.passDrawIndexed.invokeExact(
            context, MetalFormats.PRIMITIVE_TRIANGLE, indexCount, 1, (view.slice().buffer() as MetalBuffer).handle, view.slice().offset(),
            instanceCount, baseVertex, firstInstance,
        ) as Int
    }

    override fun <T : Any> drawMultipleIndexed(
        draws: Collection<RenderPass.Draw<T>>, defaultIndexBuffer: GpuBuffer?, defaultIndexType: IndexType?,
        dynamicUniforms: Collection<String>, uniformArgument: T,
    ) {
        if (skipDraws) return
        for (draw in draws) {
            draw.uniformUploaderConsumer()?.accept(uniformArgument, uniformUploader)
            val indices = draw.indexBuffer() ?: checkNotNull(defaultIndexBuffer) { "Draw has no index buffer" }
            val type = draw.indexType() ?: checkNotNull(defaultIndexType) { "Draw has no index type" }
            setIndexBuffer(indices, type)
            setVertexBuffer(draw.slot(), draw.vertexBuffer().slice())
            drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0)
        }
    }

    // The device reports these features as missing, so the game does not call them.

    override fun multiDrawIndexed(drawParameters: IntBuffer, instanceCount: Int, firstInstance: Int, drawCount: Int) =
        throw UnsupportedOperationException("Metal backend does not support the multiDrawDirectInterleaved device feature")

    override fun multiDrawIndexed(firstIndexOffsets: PointerBuffer, indexCounts: IntBuffer, vertexOffsets: IntBuffer, drawCount: Int) =
        throw UnsupportedOperationException("Metal backend does not support the multiDrawDirectSeparate device feature")

    override fun drawIndexedIndirect(commands: GpuBufferSlice, drawCount: Int) =
        throw UnsupportedOperationException("Metal backend does not support the drawIndirect device feature")

    override fun multiDraw(drawParameters: IntBuffer, instanceCount: Int, firstInstance: Int, drawCount: Int) =
        throw UnsupportedOperationException("Metal backend does not support the multiDrawDirectInterleaved device feature")

    override fun multiDraw(firstVertices: IntBuffer, vertexCounts: IntBuffer, drawCount: Int) =
        throw UnsupportedOperationException("Metal backend does not support the multiDrawDirectSeparate device feature")

    override fun drawIndirect(commands: GpuBufferSlice, drawCount: Int) =
        throw UnsupportedOperationException("Metal backend does not support the drawIndirect device feature")

    override fun writeTimestamp(pool: GpuQueryPool, index: Int) {
        (pool as MetalQueryPool).write(index)
    }

    private fun readyPipeline(): DrawProgram {
        val pipeline = pipeline
        check(pipeline != null && pipeline.isUsable()) { "Pipeline is missing or not valid" }
        pushBindings(pipeline)
        return pipeline
    }

    private fun pushBindings(pipeline: DrawProgram) {
        if (!bindingsDirty) return
        val entries = pipeline.entries
        for (entry in entries) {
            val index = entry.index
            when (entry.kind) {
                BindingEntry.UNIFORM_BUFFER -> {
                    val slice = uniforms[entry.name] ?: throw IllegalStateException("Missing uniform ${entry.name} (should be UNIFORM_BUFFER)")
                    Mcn.passSetBuffer.invokeExact(context, index, (slice.buffer() as MetalBuffer).handle, slice.offset()) as Int
                }
                BindingEntry.TEXTURE -> {
                    val view = textureViews[entry.name] ?: throw IllegalStateException("Missing sampler ${entry.name}")
                    Mcn.passSetTexture.invokeExact(context, index, view.handle, samplers.getValue(entry.name).handle) as Int
                }
                else -> {
                    val slice = uniforms[entry.name] ?: throw IllegalStateException("Missing uniform ${entry.name} (should be TEXEL_BUFFER)")
                    val format = checkNotNull(entry.texelFormat) { "Texel buffer ${entry.name} has no format" }
                    Mcn.check(
                        Mcn.texelBufferCreate.invokeExact(
                            (slice.buffer() as MetalBuffer).handle, MetalFormats.code(format), slice.offset(), slice.length(),
                            format.blockSize(), texelBufferOut,
                        ) as Int,
                    )
                    val texture = texelBufferOut.get(JAVA_LONG, 0)
                    Mcn.passSetTexture.invokeExact(context, index, texture, 0L) as Int
                    encoder.releaseLater(texture)
                }
            }
        }
        bindingsDirty = false
    }
}
