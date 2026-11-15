import Foundation
import Metal
import MetalFX

/// A MetalFX scaler for one pair of input and output sizes, plus the image it writes.
final class Upscaler {
    let spatial: MTLFXSpatialScaler?
    let temporal: MTLFXTemporalScaler?
    let output: MTLTexture

    init(spatial: MTLFXSpatialScaler?, temporal: MTLFXTemporalScaler?, output: MTLTexture) {
        self.spatial = spatial
        self.temporal = temporal
        self.output = output
    }
}

/// Creates a scaler from `inputWidth` x `inputHeight` to `outputWidth` x `outputHeight`.
/// `mode`: 1 spatial, 2 temporal. Colour is RGBA8, depth is 32-bit float, motion is RG16 float.
@_cdecl("mcn_upscaler_create")
public func mcn_upscaler_create(
    _ handle: Int, _ mode: Int32, _ inputWidth: Int32, _ inputHeight: Int32, _ outputWidth: Int32, _ outputHeight: Int32,
    _ outUpscaler: UnsafeMutablePointer<Int>?
) -> Int32 {
    guardedCall(MCN_ERR_METAL_UNAVAILABLE) {
        guard let outUpscaler else { throw SurfaceError("mcn_upscaler_create: output pointer is null") }
        let device = contextFromHandle(handle).device
        var spatial: MTLFXSpatialScaler?
        var temporal: MTLFXTemporalScaler?
        var usage: MTLTextureUsage
        if mode == 2 {
            guard MTLFXTemporalScalerDescriptor.supportsDevice(device) else { throw SurfaceError("MetalFX temporal upscaling is not available on this device") }
            let descriptor = MTLFXTemporalScalerDescriptor()
            descriptor.inputWidth = Int(inputWidth)
            descriptor.inputHeight = Int(inputHeight)
            descriptor.outputWidth = Int(outputWidth)
            descriptor.outputHeight = Int(outputHeight)
            descriptor.colorTextureFormat = .rgba8Unorm
            descriptor.depthTextureFormat = .depth32Float
            descriptor.motionTextureFormat = .rg16Float
            descriptor.outputTextureFormat = .rgba8Unorm
            guard let scaler = descriptor.makeTemporalScaler(device: device) else { throw SurfaceError("Could not create the MetalFX temporal scaler") }
            temporal = scaler
            usage = scaler.outputTextureUsage
        } else {
            guard MTLFXSpatialScalerDescriptor.supportsDevice(device) else { throw SurfaceError("MetalFX spatial upscaling is not available on this device") }
            let descriptor = MTLFXSpatialScalerDescriptor()
            descriptor.inputWidth = Int(inputWidth)
            descriptor.inputHeight = Int(inputHeight)
            descriptor.outputWidth = Int(outputWidth)
            descriptor.outputHeight = Int(outputHeight)
            descriptor.colorTextureFormat = .rgba8Unorm
            descriptor.outputTextureFormat = .rgba8Unorm
            // The game's image is display-encoded, not linear light.
            descriptor.colorProcessingMode = .perceptual
            guard let scaler = descriptor.makeSpatialScaler(device: device) else { throw SurfaceError("Could not create the MetalFX spatial scaler") }
            spatial = scaler
            usage = scaler.outputTextureUsage
        }
        let outputDescriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba8Unorm, width: Int(outputWidth), height: Int(outputHeight), mipmapped: false
        )
        outputDescriptor.storageMode = .private
        usage.insert(.shaderRead)
        outputDescriptor.usage = usage
        guard let output = device.makeTexture(descriptor: outputDescriptor) else { throw SurfaceError("Could not allocate the upscaled image") }
        output.label = "MetalCraft upscaled"
        outUpscaler.pointee = retainedHandle(Upscaler(spatial: spatial, temporal: temporal, output: output))
    }
}

/// Upscales `color` and copies the result into `target`, which has the scaler's output size.
/// The temporal scaler also needs `depth` (reversed, 1 = near), `motion` (texels, from this frame's position
/// to last frame's) and the jitter the frame was rendered with, in input pixels. `reset` drops its history.
@_cdecl("mcn_upscaler_encode")
public func mcn_upscaler_encode(
    _ handle: Int, _ upscaler: Int, _ color: Int, _ depth: Int, _ motion: Int,
    _ jitterX: Float, _ jitterY: Float, _ reset: Int32, _ target: Int
) -> Int32 {
    guardedCall(MCN_ERR_RENDER) {
        let ctx = contextFromHandle(handle)
        guard upscaler != 0, color != 0, target != 0 else { throw SurfaceError("mcn_upscaler_encode: a required handle is null") }
        guard ctx.render == nil else { throw SurfaceError("Upscaling inside a render pass") }
        ctx.endBlit()
        let scaler = Unmanaged<Upscaler>.fromOpaque(UnsafeRawPointer(bitPattern: upscaler)!).takeUnretainedValue()
        let commands = try ctx.commandBuffer()
        let colorTexture: MTLTexture = objectFromHandle(color)
        if let temporal = scaler.temporal {
            guard depth != 0, motion != 0 else { throw SurfaceError("Temporal upscaling needs depth and motion") }
            temporal.colorTexture = colorTexture
            temporal.depthTexture = objectFromHandle(depth)
            temporal.motionTexture = objectFromHandle(motion)
            temporal.outputTexture = scaler.output
            temporal.jitterOffsetX = jitterX
            temporal.jitterOffsetY = jitterY
            temporal.motionVectorScaleX = 1
            temporal.motionVectorScaleY = 1
            temporal.isDepthReversed = true
            temporal.reset = reset != 0
            temporal.encode(commandBuffer: commands)
        } else if let spatial = scaler.spatial {
            spatial.colorTexture = colorTexture
            spatial.outputTexture = scaler.output
            spatial.encode(commandBuffer: commands)
        }
        let destination: MTLTexture = objectFromHandle(target)
        try ctx.blitEncoder().copy(
            from: scaler.output, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0),
            sourceSize: MTLSize(width: scaler.output.width, height: scaler.output.height, depth: 1),
            to: destination, destinationSlice: 0, destinationLevel: 0, destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0)
        )
    }
}
