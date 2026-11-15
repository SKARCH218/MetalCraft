package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.platform.BlendFactor
import com.mojang.blaze3d.platform.BlendOp
import com.mojang.blaze3d.platform.CompareOp

/** Translations from Blaze3D enums to the numbers the native library expects (see `Formats.swift`). */
internal object MetalFormats {
    const val NO_FORMAT = -1

    /** Metal primitive type for topologies Metal cannot draw directly (triangle fans). */
    const val PRIMITIVE_TRIANGLE = 3
    const val PRIMITIVE_UNSUPPORTED_FAN = -1

    fun code(format: GpuFormat): Int = when (format) {
        GpuFormat.R8_UNORM -> 0
        GpuFormat.R8_SNORM -> 1
        GpuFormat.RG8_UNORM -> 2
        GpuFormat.RG8_SNORM -> 3
        GpuFormat.RGB8_UNORM -> 4
        GpuFormat.RGB8_SNORM -> 5
        GpuFormat.RGBA8_UNORM -> 6
        GpuFormat.RGBA8_SNORM -> 7
        GpuFormat.R16_UNORM -> 8
        GpuFormat.R16_SNORM -> 9
        GpuFormat.RG16_UNORM -> 10
        GpuFormat.RG16_SNORM -> 11
        GpuFormat.RGB16_UNORM -> 12
        GpuFormat.RGB16_SNORM -> 13
        GpuFormat.RGBA16_UNORM -> 14
        GpuFormat.RGBA16_SNORM -> 15
        GpuFormat.R8_UINT -> 16
        GpuFormat.R8_SINT -> 17
        GpuFormat.RG8_UINT -> 18
        GpuFormat.RG8_SINT -> 19
        GpuFormat.RGB8_UINT -> 20
        GpuFormat.RGB8_SINT -> 21
        GpuFormat.RGBA8_UINT -> 22
        GpuFormat.RGBA8_SINT -> 23
        GpuFormat.R16_UINT -> 24
        GpuFormat.R16_SINT -> 25
        GpuFormat.RG16_UINT -> 26
        GpuFormat.RG16_SINT -> 27
        GpuFormat.RGB16_UINT -> 28
        GpuFormat.RGB16_SINT -> 29
        GpuFormat.RGBA16_UINT -> 30
        GpuFormat.RGBA16_SINT -> 31
        GpuFormat.R32_UINT -> 32
        GpuFormat.R32_SINT -> 33
        GpuFormat.RG32_UINT -> 34
        GpuFormat.RG32_SINT -> 35
        GpuFormat.RGB32_UINT -> 36
        GpuFormat.RGB32_SINT -> 37
        GpuFormat.RGBA32_UINT -> 38
        GpuFormat.RGBA32_SINT -> 39
        GpuFormat.R16_FLOAT -> 40
        GpuFormat.RG16_FLOAT -> 41
        GpuFormat.RGB16_FLOAT -> 42
        GpuFormat.RGBA16_FLOAT -> 43
        GpuFormat.R32_FLOAT -> 44
        GpuFormat.RG32_FLOAT -> 45
        GpuFormat.RGB32_FLOAT -> 46
        GpuFormat.RGBA32_FLOAT -> 47
        GpuFormat.RGB10A2_UNORM -> 48
        GpuFormat.RGB10A2_UINT -> 49
        GpuFormat.RG11B10_FLOAT -> 50
        GpuFormat.D32_FLOAT -> 51
        GpuFormat.D32_FLOAT_S8_UINT -> 52
        GpuFormat.D24_UNORM_S8_UINT -> 53
        GpuFormat.D16_UNORM -> 54
        GpuFormat.S8_UINT -> 55
    }

    /** `MTLBlendFactor` raw value. */
    fun blendFactor(factor: BlendFactor): Int = when (factor) {
        BlendFactor.ZERO -> 0
        BlendFactor.ONE -> 1
        BlendFactor.SRC_COLOR -> 2
        BlendFactor.ONE_MINUS_SRC_COLOR -> 3
        BlendFactor.SRC_ALPHA -> 4
        BlendFactor.ONE_MINUS_SRC_ALPHA -> 5
        BlendFactor.DST_COLOR -> 6
        BlendFactor.ONE_MINUS_DST_COLOR -> 7
        BlendFactor.DST_ALPHA -> 8
        BlendFactor.ONE_MINUS_DST_ALPHA -> 9
        BlendFactor.SRC_ALPHA_SATURATE -> 10
        BlendFactor.CONSTANT_COLOR -> 11
        BlendFactor.ONE_MINUS_CONSTANT_COLOR -> 12
        BlendFactor.CONSTANT_ALPHA -> 13
        BlendFactor.ONE_MINUS_CONSTANT_ALPHA -> 14
    }

    /** `MTLBlendOperation` raw value. */
    fun blendOp(op: BlendOp): Int = when (op) {
        BlendOp.ADD -> 0
        BlendOp.SUBTRACT -> 1
        BlendOp.REVERSE_SUBTRACT -> 2
        BlendOp.MIN -> 3
        BlendOp.MAX -> 4
    }

    /** `MTLCompareFunction` raw value. */
    fun compare(op: CompareOp): Int = when (op) {
        CompareOp.NEVER_PASS -> 0
        CompareOp.LESS_THAN -> 1
        CompareOp.EQUAL -> 2
        CompareOp.LESS_THAN_OR_EQUAL -> 3
        CompareOp.GREATER_THAN -> 4
        CompareOp.NOT_EQUAL -> 5
        CompareOp.GREATER_THAN_OR_EQUAL -> 6
        CompareOp.ALWAYS_PASS -> 7
    }

    /** `MTLColorWriteMask` raw value. */
    fun writeMask(state: ColorTargetState): Int {
        var mask = 0
        if (state.writeRed()) mask = mask or 8
        if (state.writeGreen()) mask = mask or 4
        if (state.writeBlue()) mask = mask or 2
        if (state.writeAlpha()) mask = mask or 1
        return mask
    }

    /** `MTLPrimitiveType` raw value. Lines and quads reach the backend already expanded to triangles. */
    fun primitive(topology: PrimitiveTopology): Int = when (topology) {
        PrimitiveTopology.POINTS -> 0
        PrimitiveTopology.DEBUG_LINES -> 1
        PrimitiveTopology.DEBUG_LINE_STRIP -> 2
        PrimitiveTopology.LINES, PrimitiveTopology.TRIANGLES, PrimitiveTopology.QUADS -> PRIMITIVE_TRIANGLE
        PrimitiveTopology.TRIANGLE_STRIP -> 4
        PrimitiveTopology.TRIANGLE_FAN -> PRIMITIVE_UNSUPPORTED_FAN
    }
}
