package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.PolygonMode
import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.native.Mcn
import dev.skarch.metalcraft.native.NativeException
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * A game pipeline translated for Metal. The Metal pipeline state depends on the depth attachment it is used
 * with, so native variants are created on first use per depth format.
 */
internal class MetalPipeline(private val device: MetalDevice, val info: RenderPipeline, private val shaders: TranslatedShaders?) :
    CompiledRenderPipeline {
    // The vertex and fragment functions compiled by Metal, shared by all variants. 0 if that failed.
    private var shaderPair = 0L
    val entries: Array<BindingEntry> = shaders?.entries ?: emptyArray()
    val primitive: Int = MetalFormats.primitive(info.primitiveTopology)
    val cull: Int = if (info.isCull) 1 else 0
    val wireframe: Int = if (info.polygonMode == PolygonMode.WIREFRAME) 1 else 0
    val depthBias: Float
    val depthBiasSlope: Float

    // Index 0 is "no depth attachment", index n + 1 is depth format code n. 0 means not created yet.
    private val variants = LongArray(VARIANT_COUNT)
    private var broken = false

    init {
        val depth = info.depthStencilState
        // Like the Vulkan backend, the bias only applies when both parts are set.
        val biased = depth != null && depth.depthBiasConstant() != 0.0f && depth.depthBiasScaleFactor() != 0.0f
        depthBias = if (biased) depth!!.depthBiasConstant() else 0.0f
        depthBiasSlope = if (biased) depth!!.depthBiasScaleFactor() else 0.0f

        // Everything Metal could reject is tried now, so a bad shader fails the resource reload (where the
        // game can fall back) instead of a draw in the middle of a frame. Most passes have a 32-bit float
        // depth attachment, so that variant is built up front.
        if (shaders != null) {
            try {
                shaderPair = compileShaders(shaders)
                variants[MetalFormats.code(GpuFormat.D32_FLOAT) + 1] = create(MetalFormats.code(GpuFormat.D32_FLOAT))
            } catch (e: NativeException) {
                broken = true
                LOGGER.error("Couldn't compile pipeline {}: {}", info.location, e.message)
                LOGGER.debug("Vertex MSL:\n{}\nFragment MSL:\n{}", shaders.vertexMsl, shaders.fragmentMsl)
            }
        }
    }

    private fun compileShaders(shaders: TranslatedShaders): Long {
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(JAVA_LONG)
            Mcn.check(
                Mcn.shaderPairCreate.invokeExact(device.context, arena.allocateFrom(shaders.vertexMsl), arena.allocateFrom(shaders.fragmentMsl), out) as Int,
            )
            return out.get(JAVA_LONG, 0)
        }
    }

    override fun isValid(): Boolean = shaders != null && !broken

    /** Native pipeline for a render pass whose depth attachment has [depthFormat] (or [MetalFormats.NO_FORMAT]). */
    fun variant(depthFormat: Int): Long {
        val existing = variants[depthFormat + 1]
        if (existing != 0L) return existing
        val created = try {
            create(depthFormat)
        } catch (e: NativeException) {
            broken = true
            LOGGER.error("Couldn't compile pipeline {} for depth format {}: {}", info.location, depthFormat, e.message)
            throw IllegalStateException("Pipeline ${info.location} could not be compiled for Metal", e)
        }
        variants[depthFormat + 1] = created
        return created
    }

    fun destroy() {
        for (i in variants.indices) {
            if (variants[i] != 0L) {
                Mcn.release.invokeExact(variants[i]) as Int
                variants[i] = 0L
            }
        }
        if (shaderPair != 0L) {
            Mcn.release.invokeExact(shaderPair) as Int
            shaderPair = 0L
        }
    }

    private fun create(depthFormat: Int): Long {
        check(shaderPair != 0L) { "Pipeline ${info.location} has no valid shaders" }
        val layout = ArrayList<Int>(64)

        val targets = info.colorTargetStates
        layout.add(targets.size)
        for (target in targets) {
            val blend = target?.blendFunction()?.orElse(null)
            layout.add(if (target != null) MetalFormats.code(target.format()) else MetalFormats.NO_FORMAT)
            layout.add(if (target != null) MetalFormats.writeMask(target) else 0)
            layout.add(if (blend != null) 1 else 0)
            layout.add(if (blend != null) MetalFormats.blendOp(blend.color().op()) else 0)
            layout.add(if (blend != null) MetalFormats.blendOp(blend.alpha().op()) else 0)
            layout.add(if (blend != null) MetalFormats.blendFactor(blend.color().sourceFactor()) else 0)
            layout.add(if (blend != null) MetalFormats.blendFactor(blend.color().destFactor()) else 0)
            layout.add(if (blend != null) MetalFormats.blendFactor(blend.alpha().sourceFactor()) else 0)
            layout.add(if (blend != null) MetalFormats.blendFactor(blend.alpha().destFactor()) else 0)
        }

        val depth = info.depthStencilState
        layout.add(depthFormat)
        // A pipeline without depth state still has to match a pass that has a depth attachment: test nothing.
        layout.add(if (depth != null) MetalFormats.compare(depth.depthTest()) else COMPARE_ALWAYS)
        layout.add(if (depth != null && depth.writeDepth()) 1 else 0)

        val formats = info.vertexFormatBindings
        layout.add(formats.count { it != null })
        for ((slot, format) in formats.withIndex()) {
            if (format == null) continue
            layout.add(vertexBufferIndex(slot))
            layout.add(format.vertexSize)
            layout.add(if (format.stepRate > 0) 1 else 0)
            layout.add(format.stepRate)
        }
        layout.add(formats.sumOf { it?.elements?.size ?: 0 })
        var location = 0
        for ((slot, format) in formats.withIndex()) {
            if (format == null) continue
            for (element in format.elements) {
                layout.add(location++)
                layout.add(vertexBufferIndex(slot))
                layout.add(element.offset())
                layout.add(MetalFormats.code(element.format()))
            }
        }

        Arena.ofConfined().use { arena ->
            val layoutSegment = arena.allocate(JAVA_INT, layout.size.toLong())
            for (i in layout.indices) layoutSegment.setAtIndex(JAVA_INT, i.toLong(), layout[i])
            val out = arena.allocate(JAVA_LONG)
            Mcn.check(
                Mcn.pipelineCreate.invokeExact(
                    device.context, shaderPair, layoutSegment, layout.size, arena.allocateFrom(info.location.toString()), out,
                ) as Int,
            )
            return out.get(JAVA_LONG, 0)
        }
    }

    companion object {
        private const val VARIANT_COUNT = 57
        private const val COMPARE_ALWAYS = 7

        /**
         * Metal's vertex stage has one table for all buffers. Uniform blocks take the low indices (their
         * binding numbers), vertex buffers count down from the top so the two never meet.
         */
        fun vertexBufferIndex(slot: Int): Int = 30 - slot

    }
}
