import Metal

// Format codes shared with the Kotlin side (`MetalFormats.kt`). They follow Blaze3D's `GpuFormat` names.
// -1 means "no format".

func pixelFormat(code: Int32) -> MTLPixelFormat? {
    switch code {
    case 0: return .r8Unorm
    case 1: return .r8Snorm
    case 2: return .rg8Unorm
    case 3: return .rg8Snorm
    case 6: return .rgba8Unorm
    case 7: return .rgba8Snorm
    case 8: return .r16Unorm
    case 9: return .r16Snorm
    case 10: return .rg16Unorm
    case 11: return .rg16Snorm
    case 14: return .rgba16Unorm
    case 15: return .rgba16Snorm
    case 16: return .r8Uint
    case 17: return .r8Sint
    case 18: return .rg8Uint
    case 19: return .rg8Sint
    case 22: return .rgba8Uint
    case 23: return .rgba8Sint
    case 24: return .r16Uint
    case 25: return .r16Sint
    case 26: return .rg16Uint
    case 27: return .rg16Sint
    case 30: return .rgba16Uint
    case 31: return .rgba16Sint
    case 32: return .r32Uint
    case 33: return .r32Sint
    case 34: return .rg32Uint
    case 35: return .rg32Sint
    case 38: return .rgba32Uint
    case 39: return .rgba32Sint
    case 40: return .r16Float
    case 41: return .rg16Float
    case 43: return .rgba16Float
    case 44: return .r32Float
    case 45: return .rg32Float
    case 47: return .rgba32Float
    case 48: return .rgb10a2Unorm
    case 49: return .rgb10a2Uint
    case 50: return .rg11b10Float
    case 51: return .depth32Float
    // Apple GPUs have no 24-bit depth format; the 32-bit float one is a superset.
    case 52, 53: return .depth32Float_stencil8
    case 54: return .depth16Unorm
    case 55: return .stencil8
    // Three-component texture formats (4, 5, 12, 13, 20, 21, 28, 29, 36, 37, 42, 46) do not exist in Metal.
    default: return nil
    }
}

func vertexFormat(code: Int32) -> MTLVertexFormat? {
    switch code {
    case 0: return .ucharNormalized
    case 1: return .charNormalized
    case 2: return .uchar2Normalized
    case 3: return .char2Normalized
    case 4: return .uchar3Normalized
    case 5: return .char3Normalized
    case 6: return .uchar4Normalized
    case 7: return .char4Normalized
    case 8: return .ushortNormalized
    case 9: return .shortNormalized
    case 10: return .ushort2Normalized
    case 11: return .short2Normalized
    case 12: return .ushort3Normalized
    case 13: return .short3Normalized
    case 14: return .ushort4Normalized
    case 15: return .short4Normalized
    case 16: return .uchar
    case 17: return .char
    case 18: return .uchar2
    case 19: return .char2
    case 20: return .uchar3
    case 21: return .char3
    case 22: return .uchar4
    case 23: return .char4
    case 24: return .ushort
    case 25: return .short
    case 26: return .ushort2
    case 27: return .short2
    case 28: return .ushort3
    case 29: return .short3
    case 30: return .ushort4
    case 31: return .short4
    case 32: return .uint
    case 33: return .int
    case 34: return .uint2
    case 35: return .int2
    case 36: return .uint3
    case 37: return .int3
    case 38: return .uint4
    case 39: return .int4
    case 40: return .half
    case 41: return .half2
    case 42: return .half3
    case 43: return .half4
    case 44: return .float
    case 45: return .float2
    case 46: return .float3
    case 47: return .float4
    case 48: return .uint1010102Normalized
    case 50: return .floatRG11B10
    default: return nil
    }
}
