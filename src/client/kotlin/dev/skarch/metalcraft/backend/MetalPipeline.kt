package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.PolygonMode
import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.native.Mcn
import dev.skarch.metalcraft.pipeline.ShaderPipeline
import dev.skarch.metalcraft.native.NativeException
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * A game pipeline translated for Metal. The Metal pipeline state depends on the depth attachment it is used
 * with, so native variants are created on first use per depth format.
 */
internal class MetalPipeline(private val device: MetalDevice, val info: RenderPipeline, private val shaders: TranslatedShaders?) :
    CompiledRenderPipeline, DrawProgram {
    // The vertex and fragment functions compiled by Metal, shared by all variants. 0 if that failed.
    private var shaderPair = 0L
    override val entries: Array<BindingEntry> = shaders?.entries ?: emptyArray()
    override val primitive: Int = MetalFormats.primitive(info.primitiveTopology)
    override val cull: Int = if (info.isCull) 1 else 0
    override val wireframe: Int = if (info.polygonMode == PolygonMode.WIREFRAME) 1 else 0
    override val depthBias: Float
    override val depthBiasSlope: Float

    // Index 0 is "no depth attachment", index n + 1 is depth format code n. 0 means not created yet.
    private val variants = LongArray(VARIANT_COUNT)
    private var broken = false

    // The same variants for passes that carry a shader pack's extra colour targets. One layout at a time.
    private val extraVariants = LongArray(VARIANT_COUNT)
    private var extraLayout: IntArray? = null
    private var extraColorSlot = -1

    // The shaders again with the colour written to one of a shader pack's targets instead of the game's
    // image (see [variant]). 0 until needed; [redirectedSlot] is the extra target it was built for.
    private var redirectedPair = 0L
    private var redirectedSlot = -1

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
                variants[MetalFormats.code(GpuFormat.D32_FLOAT) + 1] = create(MetalFormats.code(GpuFormat.D32_FLOAT), null)
            } catch (e: NativeException) {
                broken = true
                LOGGER.error("Couldn't compile pipeline {}: {}", info.location, e.message)
                LOGGER.debug("Vertex MSL:\n{}\nFragment MSL:\n{}", shaders.vertexMsl, shaders.fragmentMsl)
            }
        }
    }

    private fun compileShaders(shaders: TranslatedShaders): Long = compile(shaders.vertexMsl, shaders.fragmentMsl)

    private fun compile(vertexMsl: String, fragmentMsl: String): Long {
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(JAVA_LONG)
            Mcn.check(Mcn.shaderPairCreate.invokeExact(device.context, arena.allocateFrom(vertexMsl), arena.allocateFrom(fragmentMsl), 0, out) as Int)
            return out.get(JAVA_LONG, 0)
        }
    }

    override fun isValid(): Boolean = shaders != null && !broken

    override fun isUsable(): Boolean = isValid

    /**
     * In a pass that carries a shader pack's extra targets, the game's own pipelines normally leave those
     * alone. A pack that keeps its picture in one of them (`Gbuffer.colorSlot`) would then never show what
     * the game draws without a pack program (block outlines, rain, name tags ...), so for such a pack the
     * pipeline's colour goes to that target instead, converted to the linear colours the pack works in.
     */
    override fun variant(depthFormat: Int, extraTargets: IntArray?): Long {
        val colorSlot = if (extraTargets == null) -1 else ShaderPipeline.gbuffer?.colorSlot ?: -1
        val cache = if (extraTargets == null) {
            variants
        } else {
            if (!extraTargets.contentEquals(extraLayout) || colorSlot != extraColorSlot) {
                releaseAll(extraVariants)
                extraLayout = extraTargets
                extraColorSlot = colorSlot
            }
            extraVariants
        }
        val existing = cache[depthFormat + 1]
        if (existing != 0L) return existing
        val created = try {
            create(depthFormat, extraTargets, colorSlot)
        } catch (e: NativeException) {
            broken = true
            LOGGER.error("Couldn't compile pipeline {} for depth format {}: {}", info.location, depthFormat, e.message)
            throw IllegalStateException("Pipeline ${info.location} could not be compiled for Metal", e)
        }
        cache[depthFormat + 1] = created
        return created
    }

    private fun releaseAll(handles: LongArray) {
        for (i in handles.indices) {
            if (handles[i] != 0L) {
                // The frame being recorded may still draw with it.
                device.encoder.releaseLater(handles[i])
                handles[i] = 0L
            }
        }
    }

    fun destroy() {
        releaseAll(variants)
        releaseAll(extraVariants)
        extraLayout = null
        if (redirectedPair != 0L) {
            device.encoder.releaseLater(redirectedPair)
            redirectedPair = 0L
        }
        if (shaderPair != 0L) {
            device.encoder.releaseLater(shaderPair)
            shaderPair = 0L
        }
    }

    /** The shader pair whose colour output is the pack target [slot], or 0 if the shaders cannot be redirected. */
    private fun redirectedShaders(slot: Int): Long {
        if (redirectedPair != 0L && redirectedSlot == slot) return redirectedPair
        val source = shaders ?: return 0L
        val fragment = redirectColor(source.fragmentMsl, slot) ?: return 0L
        if (redirectedPair != 0L) device.encoder.releaseLater(redirectedPair)
        redirectedPair = compile(source.vertexMsl, fragment)
        redirectedSlot = slot
        return redirectedPair
    }

    private fun create(depthFormat: Int, extraTargets: IntArray?, colorSlot: Int = -1): Long {
        check(shaderPair != 0L) { "Pipeline ${info.location} has no valid shaders" }
        val layout = ArrayList<Int>(64)

        val targets = info.colorTargetStates
        val only = targets.singleOrNull()
        val redirected = if (extraTargets != null && colorSlot in extraTargets.indices && only != null) redirectedShaders(colorSlot) else 0L
        layout.add(targets.size + (extraTargets?.size ?: 0))
        for (target in targets) {
            val blend = target?.blendFunction()?.orElse(null)
            layout.add(if (target != null) MetalFormats.code(target.format()) else MetalFormats.NO_FORMAT)
            if (redirected != 0L) {
                // The game's image is in the pass but this pipeline now writes the pack's instead.
                repeat(8) { layout.add(0) }
                continue
            }
            layout.add(if (target != null) MetalFormats.writeMask(target) else 0)
            layout.add(if (blend != null) 1 else 0)
            layout.add(if (blend != null) MetalFormats.blendOp(blend.color().op()) else 0)
            layout.add(if (blend != null) MetalFormats.blendOp(blend.alpha().op()) else 0)
            layout.add(if (blend != null) MetalFormats.blendFactor(blend.color().sourceFactor()) else 0)
            layout.add(if (blend != null) MetalFormats.blendFactor(blend.color().destFactor()) else 0)
            layout.add(if (blend != null) MetalFormats.blendFactor(blend.alpha().sourceFactor()) else 0)
            layout.add(if (blend != null) MetalFormats.blendFactor(blend.alpha().destFactor()) else 0)
        }

        // A shader pack's extra targets are present in the pass but this pipeline does not write to them.
        if (extraTargets != null) {
            for ((slot, format) in extraTargets.withIndex()) {
                layout.add(format)
                val blend = only?.blendFunction()?.orElse(null)
                if (redirected != 0L && slot == colorSlot && only != null) {
                    layout.add(MetalFormats.writeMask(only))
                    layout.add(if (blend != null) 1 else 0)
                    layout.add(if (blend != null) MetalFormats.blendOp(blend.color().op()) else 0)
                    layout.add(if (blend != null) MetalFormats.blendOp(blend.alpha().op()) else 0)
                    layout.add(if (blend != null) MetalFormats.blendFactor(blend.color().sourceFactor()) else 0)
                    layout.add(if (blend != null) MetalFormats.blendFactor(blend.color().destFactor()) else 0)
                    layout.add(if (blend != null) MetalFormats.blendFactor(blend.alpha().sourceFactor()) else 0)
                    layout.add(if (blend != null) MetalFormats.blendFactor(blend.alpha().destFactor()) else 0)
                } else {
                    repeat(8) { layout.add(0) }
                }
            }
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
                    device.context, if (redirected != 0L) redirected else shaderPair, layoutSegment, layout.size, arena.allocateFrom(info.location.toString()), out,
                ) as Int,
            )
            return out.get(JAVA_LONG, 0)
        }
    }

    companion object {
        /**
         * Fragment MSL (as SPIRV-Cross writes it) with its colour going to a shader pack's extra target
         * [slot] instead of the first attachment, or null if it has no such output. The colours of the
         * game's shaders are ready for the display; a pack's image holds linear light.
         */
        fun redirectColor(fragmentMsl: String, slot: Int): String? {
            val name = (COLOR_OUTPUT.find(fragmentMsl) ?: return null).groupValues[1]
            return fragmentMsl
                .replace("[[color(0)]]", "[[color(${slot + 1})]]")
                .replace("return out;", "out.$name.xyz = pow(max(out.$name.xyz, float3(0.0)), float3(2.2)); return out;")
        }

        private const val VARIANT_COUNT = 57
        private const val COMPARE_ALWAYS = 7

        /** The colour output SPIRV-Cross declares in a fragment shader's output struct. */
        private val COLOR_OUTPUT = Regex("""float4 (\w+) \[\[color\(0\)]]""")

        /**
         * Metal's vertex stage has one table for all buffers. Uniform blocks take the low indices (their
         * binding numbers), vertex buffers count down from the top so the two never meet.
         */
        fun vertexBufferIndex(slot: Int): Int = 30 - slot

    }
}
