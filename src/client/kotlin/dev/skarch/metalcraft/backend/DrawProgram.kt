package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.vertex.VertexFormat
import dev.skarch.metalcraft.native.Mcn
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/** What a render pass needs to know to draw with a set of shaders: a game pipeline or a shader pack program. */
internal interface DrawProgram {
    val entries: Array<BindingEntry>
    val primitive: Int
    val cull: Int
    val wireframe: Int
    val depthBias: Float
    val depthBiasSlope: Float

    fun isUsable(): Boolean

    /**
     * Native pipeline for a pass with the given depth attachment format ([MetalFormats.NO_FORMAT] for none).
     * [extraTargets] are the formats of colour attachments a shader pack added after the game's own; a game
     * pipeline leaves them untouched.
     */
    fun variant(depthFormat: Int, extraTargets: IntArray?): Long
}

/**
 * A program of a shader pack: Metal Shading Language written for MetalCraft, not translated from GLSL.
 * Its bindings are fixed by the pack conventions (see `pipeline/ShaderPipeline.kt`), given here as [entries].
 */
internal class PackProgram(
    private val device: MetalDevice,
    private val name: String,
    vertexMsl: String,
    fragmentMsl: String,
    override val entries: Array<BindingEntry>,
    private val vertexFormat: VertexFormat?,
    /** Format code of each colour output, in order. */
    private val colorFormats: IntArray,
    /** Metal colour write mask per output (15 = all, 0 = leave the target alone). */
    private val writeMasks: IntArray,
    /** Bit i set: output i is blended over what is there (straight alpha). */
    private val blendMask: Int,
    /** Metal compare function for the depth test, or -1 for a program that ignores depth. */
    private val depthCompare: Int,
    private val depthWrite: Boolean,
    override val cull: Int,
    /**
     * How blended outputs are combined: colour operation, alpha operation, then source and destination factors
     * for colour and for alpha (Metal codes). Null for straight alpha.
     */
    private val blendFunction: IntArray? = null,
    /** Lets Metal's compiler reorder floating point arithmetic: faster, results not bit-exact between programs. */
    fastMath: Boolean = false,
) : DrawProgram {
    override val primitive: Int = MetalFormats.PRIMITIVE_TRIANGLE
    override val wireframe: Int = 0
    override val depthBias: Float = 0f
    override val depthBiasSlope: Float = 0f

    private var shaderPair: Long
    private val variants = LongArray(VARIANT_COUNT)

    init {
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(JAVA_LONG)
            Mcn.check(Mcn.shaderPairCreate.invokeExact(device.context, arena.allocateFrom(vertexMsl), arena.allocateFrom(fragmentMsl), if (fastMath) 1 else 0, out) as Int)
            shaderPair = out.get(JAVA_LONG, 0)
        }
    }

    override fun isUsable(): Boolean = shaderPair != 0L

    override fun variant(depthFormat: Int, extraTargets: IntArray?): Long {
        val existing = variants[depthFormat + 1]
        if (existing != 0L) return existing
        val created = create(depthFormat)
        variants[depthFormat + 1] = created
        return created
    }

    fun destroy() {
        for (i in variants.indices) {
            // The frame being recorded may still draw with it.
            if (variants[i] != 0L) device.encoder.releaseLater(variants[i])
            variants[i] = 0L
        }
        if (shaderPair != 0L) device.encoder.releaseLater(shaderPair)
        shaderPair = 0L
    }

    private fun create(depthFormat: Int): Long {
        val layout = ArrayList<Int>(48)
        layout.add(colorFormats.size)
        for (i in colorFormats.indices) {
            val blend = blendMask and (1 shl i) != 0
            layout.add(colorFormats[i])
            layout.add(writeMasks[i])
            layout.add(if (blend) 1 else 0)
            val function = if (blend) blendFunction ?: STRAIGHT_ALPHA else NO_BLEND
            for (value in function) layout.add(value)
        }
        layout.add(depthFormat)
        layout.add(if (depthCompare >= 0) depthCompare else COMPARE_ALWAYS)
        layout.add(if (depthCompare >= 0 && depthWrite) 1 else 0)
        if (vertexFormat == null) {
            layout.add(0)
            layout.add(0)
        } else {
            layout.add(1)
            layout.add(MetalPipeline.vertexBufferIndex(0))
            layout.add(vertexFormat.vertexSize)
            layout.add(0)
            layout.add(0)
            layout.add(vertexFormat.elements.size)
            for ((location, element) in vertexFormat.elements.withIndex()) {
                layout.add(location)
                layout.add(MetalPipeline.vertexBufferIndex(0))
                layout.add(element.offset())
                layout.add(MetalFormats.code(element.format()))
            }
        }
        Arena.ofConfined().use { arena ->
            val segment = arena.allocate(JAVA_INT, layout.size.toLong())
            for (i in layout.indices) segment.setAtIndex(JAVA_INT, i.toLong(), layout[i])
            val out = arena.allocate(JAVA_LONG)
            Mcn.check(Mcn.pipelineCreate.invokeExact(device.context, shaderPair, segment, layout.size, arena.allocateFrom(name), out) as Int)
            return out.get(JAVA_LONG, 0)
        }
    }

    private companion object {
        const val VARIANT_COUNT = 57
        const val COMPARE_ALWAYS = 7
        const val BLEND_ADD = 0
        const val FACTOR_ONE = 1
        const val FACTOR_SRC_ALPHA = 4
        const val FACTOR_ONE_MINUS_SRC_ALPHA = 5
        val STRAIGHT_ALPHA = intArrayOf(BLEND_ADD, BLEND_ADD, FACTOR_SRC_ALPHA, FACTOR_ONE_MINUS_SRC_ALPHA, FACTOR_ONE, FACTOR_ONE_MINUS_SRC_ALPHA)
        val NO_BLEND = intArrayOf(BLEND_ADD, BLEND_ADD, 0, 0, 0, 0)
    }
}
