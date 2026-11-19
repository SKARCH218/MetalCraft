import AppKit
import Foundation
import Metal
import QuartzCore

// The Metal half of the Blaze3D backend. The Kotlin side (`dev.skarch.metalcraft.backend`) decides what to
// draw; this file owns the Metal objects and encodes the commands.
//
// Handles are raw object pointers. Buffers, textures and samplers are passed as the retained Metal object
// itself and released with `mcn_release`. Everything runs on the render thread.

/// The compiled vertex and fragment functions of one game pipeline, shared by its variants.
final class ShaderPair {
    let vertex: MTLFunction
    let fragment: MTLFunction

    init(vertex: MTLFunction, fragment: MTLFunction) {
        self.vertex = vertex
        self.fragment = fragment
    }
}

/// One compiled pipeline variant: the render state plus the depth state it is used with.
final class RenderPipeline {
    let state: MTLRenderPipelineState
    let depthState: MTLDepthStencilState?

    init(state: MTLRenderPipelineState, depthState: MTLDepthStencilState?) {
        self.state = state
        self.depthState = depthState
    }
}

final class MetalContext {
    let device: MTLDevice
    let queue: MTLCommandQueue

    private(set) var commands: MTLCommandBuffer?
    var render: MTLRenderCommandEncoder?
    private var blit: MTLBlitCommandEncoder?
    var passWidth = 0
    var passHeight = 0
    /// Set while the scissor rectangle is empty: Metal cannot express that, so draws are dropped instead.
    var skipDraws = false

    private let completion = NSCondition()
    private var completedSubmit: UInt64 = 0
    private var nextSubmit: UInt64 = 1

    private var clearPipelines: [UInt64: RenderPipeline] = [:]
    private var presentPipeline: MTLRenderPipelineState?
    private var utilityLibrary: MTLLibrary?
    private(set) lazy var nearestSampler: MTLSamplerState? = {
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = .nearest
        descriptor.magFilter = .nearest
        return device.makeSamplerState(descriptor: descriptor)
    }()

    init(device: MTLDevice, queue: MTLCommandQueue) {
        self.device = device
        self.queue = queue
    }

    /// Per-pass GPU times, measured only when METALCRAFT_GPU_TIMING=1 is in the environment.
    private(set) lazy var timing: GpuTiming? = GpuTiming(device: device)
    private var timingBatch: GpuTiming.Batch?

    /// Makes `descriptor`'s pass report when its vertex and fragment stages start and end.
    func time(_ descriptor: MTLRenderPassDescriptor, name: String?) {
        guard let timing else { return }
        if timingBatch == nil { timingBatch = timing.batch() }
        timingBatch?.attach(descriptor, name: name ?? "unnamed")
    }

    func countTimedFrame() {
        timing?.frameShown()
    }

    func commandBuffer() throws -> MTLCommandBuffer {
        if let commands { return commands }
        guard let created = queue.makeCommandBuffer() else { throw SurfaceError("Could not create a command buffer") }
        commands = created
        return created
    }

    func blitEncoder() throws -> MTLBlitCommandEncoder {
        if let blit { return blit }
        guard render == nil else { throw SurfaceError("A copy was requested inside a render pass") }
        guard let created = try commandBuffer().makeBlitCommandEncoder() else {
            throw SurfaceError("Could not create a blit encoder")
        }
        blit = created
        return created
    }

    func endBlit() {
        blit?.endEncoding()
        blit = nil
    }

    /// Commits everything encoded so far and returns the index of this submit.
    func submit() -> UInt64 {
        endBlit()
        let index = nextSubmit
        nextSubmit += 1
        if let commands {
            commands.addCompletedHandler { [self] _ in markCompleted(index) }
            if let batch = timingBatch, let timing {
                commands.addCompletedHandler { _ in timing.finish(batch) }
                timingBatch = nil
            }
            commands.commit()
            self.commands = nil
        } else {
            markCompleted(index)
        }
        return index
    }

    private func markCompleted(_ index: UInt64) {
        completion.lock()
        if index > completedSubmit { completedSubmit = index }
        completion.broadcast()
        completion.unlock()
    }

    func waitForSubmit(_ index: UInt64, timeoutNanos: Int64) -> Bool {
        completion.lock()
        defer { completion.unlock() }
        if completedSubmit >= index { return true }
        if timeoutNanos <= 0 { return false }
        let deadline = Date(timeIntervalSinceNow: Double(timeoutNanos) / 1e9)
        while completedSubmit < index {
            if !completion.wait(until: deadline) { return completedSubmit >= index }
        }
        return true
    }

    // MARK: Built-in shaders (region clear, present)

    private static let utilitySource = """
    #include <metal_stdlib>
    using namespace metal;

    struct ClearParams { float4 color; float depth; };
    struct ClearVertex { float4 position [[position]]; };

    vertex ClearVertex clear_vertex(uint id [[vertex_id]], constant ClearParams& params [[buffer(0)]]) {
        float2 corner = float2((id << 1) & 2, id & 2);
        ClearVertex out;
        out.position = float4(corner * 2.0 - 1.0, params.depth, 1.0);
        return out;
    }

    fragment float4 clear_fragment(constant ClearParams& params [[buffer(0)]]) {
        return params.color;
    }

    struct PresentVertex { float4 position [[position]]; float2 uv; };

    vertex PresentVertex present_vertex(uint id [[vertex_id]]) {
        float2 corner = float2((id << 1) & 2, id & 2);
        PresentVertex out;
        out.position = float4(corner * 2.0 - 1.0, 0.0, 1.0);
        // The game keeps row 0 at the bottom of the image, like OpenGL, so the top of the window shows the last row.
        out.uv = corner;
        return out;
    }

    fragment float4 present_fragment(PresentVertex in [[stage_in]], texture2d<float> source [[texture(0)]],
                                     sampler nearest [[sampler(0)]]) {
        return float4(source.sample(nearest, in.uv).rgb, 1.0);
    }
    """

    private func utility() throws -> MTLLibrary {
        if let utilityLibrary { return utilityLibrary }
        let library = try device.makeLibrary(source: MetalContext.utilitySource, options: nil)
        utilityLibrary = library
        return library
    }

    func clearPipeline(color: MTLPixelFormat, depth: MTLPixelFormat) throws -> RenderPipeline {
        let key = UInt64(color.rawValue) << 32 | UInt64(depth.rawValue)
        if let cached = clearPipelines[key] { return cached }
        let library = try utility()
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.label = "MetalCraft region clear"
        descriptor.vertexFunction = library.makeFunction(name: "clear_vertex")
        descriptor.fragmentFunction = library.makeFunction(name: "clear_fragment")
        descriptor.colorAttachments[0].pixelFormat = color
        descriptor.depthAttachmentPixelFormat = depth
        if depth == .depth32Float_stencil8 { descriptor.stencilAttachmentPixelFormat = depth }
        let state = try device.makeRenderPipelineState(descriptor: descriptor)
        var depthState: MTLDepthStencilState?
        if depth != .invalid {
            let depthDescriptor = MTLDepthStencilDescriptor()
            depthDescriptor.depthCompareFunction = .always
            depthDescriptor.isDepthWriteEnabled = true
            depthState = device.makeDepthStencilState(descriptor: depthDescriptor)
        }
        let pipeline = RenderPipeline(state: state, depthState: depthState)
        clearPipelines[key] = pipeline
        return pipeline
    }

    func presentPipeline(target: MTLPixelFormat) throws -> MTLRenderPipelineState {
        if let presentPipeline { return presentPipeline }
        let library = try utility()
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.label = "MetalCraft present"
        descriptor.vertexFunction = library.makeFunction(name: "present_vertex")
        descriptor.fragmentFunction = library.makeFunction(name: "present_fragment")
        descriptor.colorAttachments[0].pixelFormat = target
        let state = try device.makeRenderPipelineState(descriptor: descriptor)
        presentPipeline = state
        return state
    }
}

// MARK: - Helpers

@inline(__always)
private func context(_ handle: Int) -> MetalContext {
    Unmanaged<MetalContext>.fromOpaque(UnsafeRawPointer(bitPattern: handle)!).takeUnretainedValue()
}

/// Reinterprets a handle as the Objective-C object it points to. Metal's protocols are single-pointer
/// existentials, so this is a plain cast without a dynamic type check.
@inline(__always)
private func object<T>(_ handle: Int, as type: T.Type = T.self) -> T {
    unsafeBitCast(UnsafeRawPointer(bitPattern: handle)!, to: T.self)
}

private func retained(_ value: AnyObject) -> Int {
    Int(bitPattern: Unmanaged.passRetained(value).toOpaque())
}

/// Runs `body` inside an autorelease pool and turns thrown errors into an error code plus message.
private func guarded(_ code: Int32 = MCN_ERR_RENDER, _ body: () throws -> Void) -> Int32 {
    do {
        try autoreleasepool { try body() }
        return MCN_OK
    } catch let error as SurfaceError {
        recordError(error.message)
    } catch {
        recordError(error.localizedDescription)
    }
    return code
}

// The same helpers for the other files of the library.
func contextFromHandle(_ handle: Int) -> MetalContext { context(handle) }
func objectFromHandle<T>(_ handle: Int) -> T { object(handle) }
func retainedHandle(_ value: AnyObject) -> Int { retained(value) }
func guardedCall(_ code: Int32, _ body: () throws -> Void) -> Int32 { guarded(code, body) }

private func label(_ text: UnsafePointer<CChar>?) -> String? {
    text.map { String(cString: $0) }
}

// MARK: - Device

@_cdecl("mcn_device_create")
public func mcn_device_create(_ outHandle: UnsafeMutablePointer<Int>?) -> Int32 {
    guard let outHandle else {
        recordError("mcn_device_create: output pointer is null")
        return MCN_ERR_INVALID_ARGUMENT
    }
    guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
        recordError("No Metal device available")
        return MCN_ERR_METAL_UNAVAILABLE
    }
    queue.label = "MetalCraft"
    outHandle.pointee = retained(MetalContext(device: device, queue: queue))
    return MCN_OK
}

@_cdecl("mcn_device_destroy")
public func mcn_device_destroy(_ handle: Int) -> Int32 {
    guard handle != 0 else { return MCN_OK }
    autoreleasepool {
        let owned = Unmanaged<MetalContext>.fromOpaque(UnsafeRawPointer(bitPattern: handle)!)
        let ctx = owned.takeUnretainedValue()
        let last = ctx.submit()
        _ = ctx.waitForSubmit(last, timeoutNanos: 5_000_000_000)
        owned.release()
    }
    return MCN_OK
}

/// Writes the device name into `name` and its limits into `limits`: [0] max buffer length, [1] max 2D
/// texture size, [2] recommended working set size.
@_cdecl("mcn_device_info")
public func mcn_device_info(
    _ handle: Int, _ name: UnsafeMutablePointer<CChar>?, _ capacity: Int32, _ limits: UnsafeMutablePointer<Int64>?
) -> Int32 {
    let ctx = context(handle)
    if let name, capacity > 0 { copyTruncated(Array(ctx.device.name.utf8), to: name, capacity: Int(capacity)) }
    if let limits {
        limits[0] = Int64(ctx.device.maxBufferLength)
        limits[1] = 16384
        limits[2] = Int64(clamping: ctx.device.recommendedMaxWorkingSetSize)
    }
    return MCN_OK
}

/// Releases a buffer, texture, sampler or pipeline handle.
@_cdecl("mcn_release")
public func mcn_release(_ handle: Int) -> Int32 {
    guard handle != 0 else { return MCN_OK }
    autoreleasepool { Unmanaged<AnyObject>.fromOpaque(UnsafeRawPointer(bitPattern: handle)!).release() }
    return MCN_OK
}

// MARK: - Resources

/// Creates a CPU-visible buffer. `outContents` receives the address of its memory, valid until release.
@_cdecl("mcn_buffer_create")
public func mcn_buffer_create(
    _ handle: Int, _ length: Int64, _ name: UnsafePointer<CChar>?,
    _ outBuffer: UnsafeMutablePointer<Int>?, _ outContents: UnsafeMutablePointer<Int>?
) -> Int32 {
    guarded(MCN_ERR_OUT_OF_MEMORY) {
        guard let outBuffer, let outContents, length > 0 else { throw SurfaceError("mcn_buffer_create: bad arguments") }
        guard let buffer = context(handle).device.makeBuffer(length: Int(length), options: .storageModeShared) else {
            throw SurfaceError("Could not allocate a \(length) byte buffer")
        }
        buffer.label = label(name)
        outContents.pointee = Int(bitPattern: buffer.contents())
        outBuffer.pointee = retained(buffer)
    }
}

/// `usage` bit 0: sampled, bit 1: render target.
@_cdecl("mcn_texture_create")
public func mcn_texture_create(
    _ handle: Int, _ format: Int32, _ width: Int32, _ height: Int32, _ layers: Int32, _ mipLevels: Int32,
    _ usage: Int32, _ cube: Int32, _ name: UnsafePointer<CChar>?, _ outTexture: UnsafeMutablePointer<Int>?
) -> Int32 {
    guarded(MCN_ERR_OUT_OF_MEMORY) {
        guard let outTexture else { throw SurfaceError("mcn_texture_create: output pointer is null") }
        guard let pixelFormat = pixelFormat(code: format) else { throw SurfaceError("Texture format \(format) is not available in Metal") }
        guard width > 0, height > 0, layers > 0, mipLevels > 0 else { throw SurfaceError("Texture size \(width)x\(height) is not valid") }
        let descriptor = MTLTextureDescriptor()
        descriptor.pixelFormat = pixelFormat
        descriptor.width = Int(width)
        descriptor.height = Int(height)
        descriptor.mipmapLevelCount = Int(mipLevels)
        if cube != 0 {
            descriptor.textureType = .typeCube
        } else if layers > 1 {
            descriptor.textureType = .type2DArray
            descriptor.arrayLength = Int(layers)
        } else {
            descriptor.textureType = .type2D
        }
        descriptor.storageMode = .private
        var metalUsage: MTLTextureUsage = []
        if usage & 1 != 0 { metalUsage.insert(.shaderRead) }
        if usage & 2 != 0 { metalUsage.insert(.renderTarget) }
        descriptor.usage = metalUsage
        guard let texture = context(handle).device.makeTexture(descriptor: descriptor) else {
            throw SurfaceError("Could not allocate a \(width)x\(height) texture")
        }
        texture.label = label(name)
        outTexture.pointee = retained(texture)
    }
}

/// A read-only image filled from memory: two-dimensional, or a volume (`volume` != 0) of `depth` slices.
/// `bytes` holds `depth` slices of `height` rows of `bytesPerRow` bytes. Returns the texture twice, each
/// handle with a reference of its own (one for the caller's texture object, one for its view).
@_cdecl("mcn_image_create")
public func mcn_image_create(
    _ handle: Int, _ format: Int32, _ width: Int32, _ height: Int32, _ depth: Int32, _ volume: Int32,
    _ bytes: UnsafeRawPointer?, _ bytesPerRow: Int32, _ name: UnsafePointer<CChar>?,
    _ outTexture: UnsafeMutablePointer<Int>?, _ outView: UnsafeMutablePointer<Int>?
) -> Int32 {
    guarded(MCN_ERR_OUT_OF_MEMORY) {
        guard let outTexture, let outView, let bytes else { throw SurfaceError("mcn_image_create: a pointer is null") }
        guard let pixelFormat = pixelFormat(code: format) else { throw SurfaceError("Image format \(format) is not available in Metal") }
        guard width > 0, height > 0, depth > 0, bytesPerRow > 0 else { throw SurfaceError("Image size \(width)x\(height)x\(depth) is not valid") }
        let descriptor = MTLTextureDescriptor()
        descriptor.pixelFormat = pixelFormat
        descriptor.width = Int(width)
        descriptor.height = Int(height)
        descriptor.textureType = volume != 0 ? .type3D : .type2D
        descriptor.depth = volume != 0 ? Int(depth) : 1
        descriptor.mipmapLevelCount = 1
        descriptor.storageMode = .shared
        descriptor.usage = .shaderRead
        guard let texture = context(handle).device.makeTexture(descriptor: descriptor) else {
            throw SurfaceError("Could not allocate a \(width)x\(height)x\(depth) image")
        }
        texture.replace(
            region: MTLRegionMake3D(0, 0, 0, Int(width), Int(height), volume != 0 ? Int(depth) : 1), mipmapLevel: 0, slice: 0,
            withBytes: bytes, bytesPerRow: Int(bytesPerRow), bytesPerImage: Int(bytesPerRow) * Int(height)
        )
        texture.label = label(name)
        outTexture.pointee = retained(texture)
        outView.pointee = retained(texture)
    }
}

/// A view of some mip levels: all six faces for a cube map, otherwise the first layer.
@_cdecl("mcn_texture_view_create")
public func mcn_texture_view_create(
    _ texture: Int, _ cube: Int32, _ baseMip: Int32, _ mipCount: Int32, _ outView: UnsafeMutablePointer<Int>?
) -> Int32 {
    guarded {
        guard let outView else { throw SurfaceError("mcn_texture_view_create: output pointer is null") }
        let source: MTLTexture = object(texture)
        let levels = Int(baseMip)..<Int(baseMip + mipCount)
        guard let view = source.makeTextureView(
            pixelFormat: source.pixelFormat, textureType: cube != 0 ? .typeCube : .type2D,
            levels: levels, slices: 0..<(cube != 0 ? 6 : 1)
        ) else { throw SurfaceError("Could not create a texture view") }
        view.label = source.label
        outView.pointee = retained(view)
    }
}

/// Exposes part of a buffer as a one-dimensional texture for GLSL `samplerBuffer` uniforms.
@_cdecl("mcn_texel_buffer_create")
public func mcn_texel_buffer_create(
    _ buffer: Int, _ format: Int32, _ offset: Int64, _ length: Int64, _ texelSize: Int32,
    _ outTexture: UnsafeMutablePointer<Int>?
) -> Int32 {
    guarded {
        guard let outTexture else { throw SurfaceError("mcn_texel_buffer_create: output pointer is null") }
        guard let pixelFormat = pixelFormat(code: format) else { throw SurfaceError("Texel buffer format \(format) is not available in Metal") }
        guard texelSize > 0, length >= Int64(texelSize) else { throw SurfaceError("Texel buffer view of \(length) bytes is empty") }
        let source: MTLBuffer = object(buffer)
        let descriptor = MTLTextureDescriptor.textureBufferDescriptor(
            with: pixelFormat, width: Int(length) / Int(texelSize), resourceOptions: source.resourceOptions, usage: .shaderRead
        )
        guard let texture = source.makeTexture(descriptor: descriptor, offset: Int(offset), bytesPerRow: Int(length)) else {
            throw SurfaceError("Could not create a texel buffer view (offset \(offset), length \(length))")
        }
        outTexture.pointee = retained(texture)
    }
}

/// Address modes: 0 repeat, 1 clamp to edge. Filters: 0 nearest, 1 linear. `mipFilter`: 0 nearest, 1 linear.
@_cdecl("mcn_sampler_create")
public func mcn_sampler_create(
    _ handle: Int, _ addressU: Int32, _ addressV: Int32, _ minFilter: Int32, _ magFilter: Int32, _ mipFilter: Int32,
    _ maxAnisotropy: Int32, _ maxLod: Float, _ outSampler: UnsafeMutablePointer<Int>?
) -> Int32 {
    guarded {
        guard let outSampler else { throw SurfaceError("mcn_sampler_create: output pointer is null") }
        let descriptor = MTLSamplerDescriptor()
        descriptor.sAddressMode = addressU == 0 ? .repeat : .clampToEdge
        descriptor.tAddressMode = addressV == 0 ? .repeat : .clampToEdge
        descriptor.minFilter = minFilter == 0 ? .nearest : .linear
        descriptor.magFilter = magFilter == 0 ? .nearest : .linear
        descriptor.mipFilter = mipFilter == 0 ? .nearest : .linear
        descriptor.maxAnisotropy = max(1, min(16, Int(maxAnisotropy)))
        descriptor.lodMaxClamp = maxLod
        guard let sampler = context(handle).device.makeSamplerState(descriptor: descriptor) else {
            throw SurfaceError("Could not create a sampler")
        }
        outSampler.pointee = retained(sampler)
    }
}

/// A sampler for shadow maps read with `sample_compare`: linear filtering of "is this depth <= the stored one".
@_cdecl("mcn_shadow_sampler_create")
public func mcn_shadow_sampler_create(_ handle: Int, _ outSampler: UnsafeMutablePointer<Int>?) -> Int32 {
    guarded {
        guard let outSampler else { throw SurfaceError("mcn_shadow_sampler_create: output pointer is null") }
        let descriptor = MTLSamplerDescriptor()
        descriptor.sAddressMode = .clampToEdge
        descriptor.tAddressMode = .clampToEdge
        descriptor.minFilter = .linear
        descriptor.magFilter = .linear
        descriptor.compareFunction = .lessEqual
        guard let sampler = context(handle).device.makeSamplerState(descriptor: descriptor) else {
            throw SurfaceError("Could not create a shadow sampler")
        }
        outSampler.pointee = retained(sampler)
    }
}

// MARK: - Pipelines

/// Compiles MSL sources whose entry points are both called `main0`. Fails if Metal rejects either one.
@_cdecl("mcn_shader_pair_create")
public func mcn_shader_pair_create(
    _ handle: Int, _ vertexSource: UnsafePointer<CChar>?, _ fragmentSource: UnsafePointer<CChar>?, _ fastMath: Int32,
    _ outPair: UnsafeMutablePointer<Int>?
) -> Int32 {
    guarded(MCN_ERR_SHADER) {
        guard let vertexSource, let fragmentSource, let outPair else { throw SurfaceError("mcn_shader_pair_create: a required pointer is null") }
        let device = context(handle).device
        let options = MTLCompileOptions()
        // Fast math lets the compiler reorder floating point, which shows up as z-fighting between passes
        // of the game's own shaders. Shader pack programs ask for it: they are far heavier and tolerate it.
        options.fastMathEnabled = fastMath != 0
        let vertexLibrary = try compileLibrary(device, String(cString: vertexSource), options)
        let fragmentLibrary = try compileLibrary(device, String(cString: fragmentSource), options)
        guard let vertexFunction = vertexLibrary.makeFunction(name: "main0"),
              let fragmentFunction = fragmentLibrary.makeFunction(name: "main0") else {
            throw SurfaceError("Shader has no main0 entry point")
        }
        outPair.pointee = retained(ShaderPair(vertex: vertexFunction, fragment: fragmentFunction))
    }
}

/// Compiles shader source. A failure is reported by its error lines alone: the compiler lists every warning
/// first, and those can fill the whole message.
private func compileLibrary(_ device: MTLDevice, _ source: String, _ options: MTLCompileOptions) throws -> MTLLibrary {
    do {
        return try device.makeLibrary(source: source, options: options)
    } catch {
        let lines = error.localizedDescription.split(separator: "\n", omittingEmptySubsequences: false)
        var kept: [Substring] = []
        var take = 0
        for line in lines {
            if line.contains(": error:") { take = 3 }
            if take > 0 { kept.append(line); take -= 1 }
        }
        throw SurfaceError(kept.isEmpty ? error.localizedDescription : kept.prefix(24).joined(separator: "\n"))
    }
}

/// Creates the render pipeline state of a shader pair for one set of attachments.
///
/// `layout` is a list of 32-bit integers:
///   colorCount, then per colour target: format, writeMask, blendEnabled, rgbOp, alphaOp, srcRgb, dstRgb,
///   srcAlpha, dstAlpha; depthFormat; depthCompare; depthWrite;
///   bufferCount, then per vertex buffer: bufferIndex, stride, perInstance, stepRate;
///   attributeCount, then per attribute: location, bufferIndex, offset, format.
/// Blend factors, blend operations, compare functions and write masks use Metal's raw values.
@_cdecl("mcn_pipeline_create")
public func mcn_pipeline_create(
    _ handle: Int, _ shaderPair: Int, _ layout: UnsafePointer<Int32>?, _ layoutCount: Int32, _ name: UnsafePointer<CChar>?,
    _ outPipeline: UnsafeMutablePointer<Int>?
) -> Int32 {
    guarded(MCN_ERR_SHADER) {
        guard shaderPair != 0, let layout, let outPipeline else {
            throw SurfaceError("mcn_pipeline_create: a required pointer is null")
        }
        let ctx = context(handle)
        let shaders = Unmanaged<ShaderPair>.fromOpaque(UnsafeRawPointer(bitPattern: shaderPair)!).takeUnretainedValue()
        var cursor = 0
        func next() throws -> Int32 {
            guard cursor < Int(layoutCount) else { throw SurfaceError("mcn_pipeline_create: layout is truncated") }
            defer { cursor += 1 }
            return layout[cursor]
        }

        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.label = label(name)
        descriptor.vertexFunction = shaders.vertex
        descriptor.fragmentFunction = shaders.fragment

        let colorCount = Int(try next())
        for index in 0..<colorCount {
            let format = try next()
            let writeMask = try next()
            let blendEnabled = try next()
            let rgbOp = try next(), alphaOp = try next()
            let srcRgb = try next(), dstRgb = try next(), srcAlpha = try next(), dstAlpha = try next()
            let attachment = descriptor.colorAttachments[index]!
            guard format >= 0 else { continue }
            guard let pixelFormat = pixelFormat(code: format) else { throw SurfaceError("Colour target format \(format) is not available in Metal") }
            attachment.pixelFormat = pixelFormat
            attachment.writeMask = MTLColorWriteMask(rawValue: UInt(truncatingIfNeeded: writeMask) & 0xF)
            if blendEnabled != 0 {
                guard let rgbOperation = MTLBlendOperation(rawValue: UInt(truncatingIfNeeded: rgbOp)),
                      let alphaOperation = MTLBlendOperation(rawValue: UInt(truncatingIfNeeded: alphaOp)),
                      let sourceRgb = MTLBlendFactor(rawValue: UInt(truncatingIfNeeded: srcRgb)),
                      let destinationRgb = MTLBlendFactor(rawValue: UInt(truncatingIfNeeded: dstRgb)),
                      let sourceAlpha = MTLBlendFactor(rawValue: UInt(truncatingIfNeeded: srcAlpha)),
                      let destinationAlpha = MTLBlendFactor(rawValue: UInt(truncatingIfNeeded: dstAlpha)) else {
                    throw SurfaceError("Unknown blend state value")
                }
                attachment.isBlendingEnabled = true
                attachment.rgbBlendOperation = rgbOperation
                attachment.alphaBlendOperation = alphaOperation
                attachment.sourceRGBBlendFactor = sourceRgb
                attachment.destinationRGBBlendFactor = destinationRgb
                attachment.sourceAlphaBlendFactor = sourceAlpha
                attachment.destinationAlphaBlendFactor = destinationAlpha
            }
        }

        let depthFormatCode = try next()
        let depthCompare = try next()
        let depthWrite = try next()
        var depthState: MTLDepthStencilState?
        if depthFormatCode >= 0 {
            guard let depthFormat = pixelFormat(code: depthFormatCode) else { throw SurfaceError("Depth format \(depthFormatCode) is not available in Metal") }
            descriptor.depthAttachmentPixelFormat = depthFormat
            if depthFormat == .depth32Float_stencil8 { descriptor.stencilAttachmentPixelFormat = depthFormat }
            guard let compare = MTLCompareFunction(rawValue: UInt(truncatingIfNeeded: depthCompare)) else { throw SurfaceError("Unknown depth compare value") }
            let depthDescriptor = MTLDepthStencilDescriptor()
            depthDescriptor.depthCompareFunction = compare
            depthDescriptor.isDepthWriteEnabled = depthWrite != 0
            depthState = ctx.device.makeDepthStencilState(descriptor: depthDescriptor)
        }

        let vertexDescriptor = MTLVertexDescriptor()
        let bufferCount = Int(try next())
        for _ in 0..<bufferCount {
            let bufferIndex = Int(try next())
            let stride = Int(try next())
            let perInstance = try next()
            let stepRate = Int(try next())
            let bufferLayout = vertexDescriptor.layouts[bufferIndex]!
            bufferLayout.stride = stride
            bufferLayout.stepFunction = perInstance != 0 ? .perInstance : .perVertex
            bufferLayout.stepRate = perInstance != 0 ? max(1, stepRate) : 1
        }
        let attributeCount = Int(try next())
        for _ in 0..<attributeCount {
            let location = Int(try next())
            let bufferIndex = Int(try next())
            let offset = Int(try next())
            let formatCode = try next()
            guard let format = vertexFormat(code: formatCode) else { throw SurfaceError("Vertex format \(formatCode) is not available in Metal") }
            let attribute = vertexDescriptor.attributes[location]!
            attribute.format = format
            attribute.offset = offset
            attribute.bufferIndex = bufferIndex
        }
        if attributeCount > 0 { descriptor.vertexDescriptor = vertexDescriptor }

        let state = try ctx.device.makeRenderPipelineState(descriptor: descriptor)
        outPipeline.pointee = retained(RenderPipeline(state: state, depthState: depthState))
    }
}

// MARK: - Render passes

/// Starts a render pass. `colors` holds `colorCount` texture handles (0 for an unused slot). Bit i of
/// `clearMask` clears colour target i to `clearColors[4 * i ...]`.
@_cdecl("mcn_pass_begin")
public func mcn_pass_begin(
    _ handle: Int, _ colorCount: Int32, _ colors: UnsafePointer<Int>?, _ clearMask: Int32,
    _ clearColors: UnsafePointer<Float>?, _ depth: Int, _ clearDepth: Int32, _ depthValue: Double,
    _ name: UnsafePointer<CChar>?
) -> Int32 {
    guarded {
        let ctx = context(handle)
        guard ctx.render == nil else { throw SurfaceError("A render pass is already open") }
        ctx.endBlit()
        let descriptor = MTLRenderPassDescriptor()
        var width = 0
        var height = 0
        if let colors {
            for index in 0..<Int(colorCount) where colors[index] != 0 {
                let texture: MTLTexture = object(colors[index])
                let attachment = descriptor.colorAttachments[index]!
                attachment.texture = texture
                attachment.storeAction = .store
                if clearMask & (1 << Int32(index)) != 0, let clearColors {
                    attachment.loadAction = .clear
                    attachment.clearColor = MTLClearColor(
                        red: Double(clearColors[index * 4]), green: Double(clearColors[index * 4 + 1]),
                        blue: Double(clearColors[index * 4 + 2]), alpha: Double(clearColors[index * 4 + 3])
                    )
                } else {
                    attachment.loadAction = .load
                }
                width = texture.width
                height = texture.height
            }
        }
        if depth != 0 {
            let texture: MTLTexture = object(depth)
            descriptor.depthAttachment.texture = texture
            descriptor.depthAttachment.storeAction = .store
            descriptor.depthAttachment.loadAction = clearDepth != 0 ? .clear : .load
            descriptor.depthAttachment.clearDepth = depthValue
            if texture.pixelFormat == .depth32Float_stencil8 {
                descriptor.stencilAttachment.texture = texture
                descriptor.stencilAttachment.loadAction = .load
                descriptor.stencilAttachment.storeAction = .store
            }
            if width == 0 {
                width = texture.width
                height = texture.height
            }
        }
        guard width > 0 else { throw SurfaceError("A render pass needs at least one attachment") }
        ctx.time(descriptor, name: label(name))
        guard let encoder = try ctx.commandBuffer().makeRenderCommandEncoder(descriptor: descriptor) else {
            throw SurfaceError("Could not create a render encoder")
        }
        encoder.label = label(name)
        // Clip space is flipped in the vertex shaders, so "clockwise" here is what the game calls front facing.
        encoder.setFrontFacing(.clockwise)
        ctx.render = encoder
        ctx.passWidth = width
        ctx.passHeight = height
        ctx.skipDraws = false
    }
}

@_cdecl("mcn_pass_end")
public func mcn_pass_end(_ handle: Int) -> Int32 {
    let ctx = context(handle)
    autoreleasepool {
        ctx.render?.endEncoding()
        ctx.render = nil
    }
    return MCN_OK
}

/// `cull`: 0 none, 1 back faces. `wireframe`: draw triangle edges only.
@_cdecl("mcn_pass_set_pipeline")
public func mcn_pass_set_pipeline(
    _ handle: Int, _ pipeline: Int, _ cull: Int32, _ wireframe: Int32, _ depthBias: Float, _ depthBiasSlope: Float
) -> Int32 {
    guard let encoder = context(handle).render else { return MCN_ERR_RENDER }
    let compiled = Unmanaged<RenderPipeline>.fromOpaque(UnsafeRawPointer(bitPattern: pipeline)!).takeUnretainedValue()
    encoder.setRenderPipelineState(compiled.state)
    if let depthState = compiled.depthState { encoder.setDepthStencilState(depthState) }
    encoder.setCullMode(cull != 0 ? .back : .none)
    encoder.setTriangleFillMode(wireframe != 0 ? .lines : .fill)
    encoder.setDepthBias(depthBias, slopeScale: depthBiasSlope, clamp: 0)
    return MCN_OK
}

@_cdecl("mcn_pass_set_scissor")
public func mcn_pass_set_scissor(_ handle: Int, _ x: Int32, _ y: Int32, _ width: Int32, _ height: Int32) -> Int32 {
    let ctx = context(handle)
    guard let encoder = ctx.render else { return MCN_ERR_RENDER }
    // Metal rejects rectangles that leave the render target, and has no empty rectangle.
    let left = max(0, Int(x)), top = max(0, Int(y))
    let right = min(ctx.passWidth, Int(x) + Int(width)), bottom = min(ctx.passHeight, Int(y) + Int(height))
    if right <= left || bottom <= top {
        ctx.skipDraws = true
        return MCN_OK
    }
    ctx.skipDraws = false
    encoder.setScissorRect(MTLScissorRect(x: left, y: top, width: right - left, height: bottom - top))
    return MCN_OK
}

/// Binds a buffer to the same index of the vertex and the fragment stage.
@_cdecl("mcn_pass_set_buffer")
public func mcn_pass_set_buffer(_ handle: Int, _ index: Int32, _ buffer: Int, _ offset: Int64) -> Int32 {
    guard let encoder = context(handle).render else { return MCN_ERR_RENDER }
    let metalBuffer: MTLBuffer = object(buffer)
    encoder.setVertexBuffer(metalBuffer, offset: Int(offset), index: Int(index))
    encoder.setFragmentBuffer(metalBuffer, offset: Int(offset), index: Int(index))
    return MCN_OK
}

@_cdecl("mcn_pass_set_vertex_buffer")
public func mcn_pass_set_vertex_buffer(_ handle: Int, _ index: Int32, _ buffer: Int, _ offset: Int64) -> Int32 {
    guard let encoder = context(handle).render else { return MCN_ERR_RENDER }
    if buffer == 0 {
        encoder.setVertexBuffer(nil, offset: 0, index: Int(index))
    } else {
        encoder.setVertexBuffer(object(buffer, as: MTLBuffer.self), offset: Int(offset), index: Int(index))
    }
    return MCN_OK
}

/// Binds a texture (and its sampler, unless 0) to the same index of the vertex and the fragment stage.
@_cdecl("mcn_pass_set_texture")
public func mcn_pass_set_texture(_ handle: Int, _ index: Int32, _ texture: Int, _ sampler: Int) -> Int32 {
    guard let encoder = context(handle).render else { return MCN_ERR_RENDER }
    let metalTexture: MTLTexture = object(texture)
    encoder.setVertexTexture(metalTexture, index: Int(index))
    encoder.setFragmentTexture(metalTexture, index: Int(index))
    // Metal has 16 sampler slots; textures past them are sampled with a sampler of a lower index.
    if sampler != 0 && index < 16 {
        let metalSampler: MTLSamplerState = object(sampler)
        encoder.setVertexSamplerState(metalSampler, index: Int(index))
        encoder.setFragmentSamplerState(metalSampler, index: Int(index))
    }
    return MCN_OK
}

/// `primitive` is Metal's raw primitive type: 0 point, 1 line, 2 line strip, 3 triangle, 4 triangle strip.
@_cdecl("mcn_pass_draw")
public func mcn_pass_draw(
    _ handle: Int, _ primitive: Int32, _ firstVertex: Int32, _ vertexCount: Int32, _ instanceCount: Int32, _ firstInstance: Int32
) -> Int32 {
    let ctx = context(handle)
    guard let encoder = ctx.render else { return MCN_ERR_RENDER }
    if ctx.skipDraws || vertexCount <= 0 || instanceCount <= 0 { return MCN_OK }
    encoder.drawPrimitives(
        type: MTLPrimitiveType(rawValue: UInt(truncatingIfNeeded: primitive)) ?? .triangle, vertexStart: Int(firstVertex),
        vertexCount: Int(vertexCount), instanceCount: Int(instanceCount), baseInstance: Int(firstInstance)
    )
    return MCN_OK
}

/// `wideIndices`: 0 for 16-bit indices, 1 for 32-bit. `indexOffset` is in bytes.
@_cdecl("mcn_pass_draw_indexed")
public func mcn_pass_draw_indexed(
    _ handle: Int, _ primitive: Int32, _ indexCount: Int32, _ wideIndices: Int32, _ indexBuffer: Int, _ indexOffset: Int64,
    _ instanceCount: Int32, _ baseVertex: Int32, _ firstInstance: Int32
) -> Int32 {
    let ctx = context(handle)
    guard let encoder = ctx.render else { return MCN_ERR_RENDER }
    if ctx.skipDraws || indexCount <= 0 || instanceCount <= 0 { return MCN_OK }
    encoder.drawIndexedPrimitives(
        type: MTLPrimitiveType(rawValue: UInt(truncatingIfNeeded: primitive)) ?? .triangle, indexCount: Int(indexCount),
        indexType: wideIndices != 0 ? .uint32 : .uint16, indexBuffer: object(indexBuffer, as: MTLBuffer.self),
        indexBufferOffset: Int(indexOffset), instanceCount: Int(instanceCount), baseVertex: Int(baseVertex),
        baseInstance: Int(firstInstance)
    )
    return MCN_OK
}

/// Clears a rectangle of the open render pass, which must have exactly one colour target (`colorFormat`)
/// and, if `depthFormat` is not -1, a depth target. The caller must set its pipeline again afterwards.
@_cdecl("mcn_pass_clear_rect")
public func mcn_pass_clear_rect(
    _ handle: Int, _ colorFormat: Int32, _ depthFormat: Int32, _ red: Float, _ green: Float, _ blue: Float, _ alpha: Float,
    _ depth: Float, _ x: Int32, _ y: Int32, _ width: Int32, _ height: Int32
) -> Int32 {
    guarded {
        let ctx = context(handle)
        guard let encoder = ctx.render else { throw SurfaceError("No render pass is open") }
        guard let color = pixelFormat(code: colorFormat) else { throw SurfaceError("Clear colour format \(colorFormat) is not available in Metal") }
        let depthPixelFormat = depthFormat >= 0 ? (pixelFormat(code: depthFormat) ?? .invalid) : .invalid
        let pipeline = try ctx.clearPipeline(color: color, depth: depthPixelFormat)
        _ = mcn_pass_set_scissor(handle, x, y, width, height)
        if ctx.skipDraws { return }
        encoder.setRenderPipelineState(pipeline.state)
        if let depthState = pipeline.depthState { encoder.setDepthStencilState(depthState) }
        encoder.setCullMode(.none)
        encoder.setTriangleFillMode(.fill)
        encoder.setDepthBias(0, slopeScale: 0, clamp: 0)
        var params: (Float, Float, Float, Float, Float, Float, Float, Float) = (red, green, blue, alpha, depth, 0, 0, 0)
        encoder.setVertexBytes(&params, length: MemoryLayout.size(ofValue: params), index: 0)
        encoder.setFragmentBytes(&params, length: MemoryLayout.size(ofValue: params), index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    }
}

// MARK: - Copies

@_cdecl("mcn_copy_buffer")
public func mcn_copy_buffer(_ handle: Int, _ source: Int, _ sourceOffset: Int64, _ target: Int, _ targetOffset: Int64, _ size: Int64) -> Int32 {
    guarded {
        guard size > 0 else { return }
        try context(handle).blitEncoder().copy(
            from: object(source, as: MTLBuffer.self), sourceOffset: Int(sourceOffset),
            to: object(target, as: MTLBuffer.self), destinationOffset: Int(targetOffset), size: Int(size)
        )
    }
}

@_cdecl("mcn_copy_buffer_to_texture")
public func mcn_copy_buffer_to_texture(
    _ handle: Int, _ source: Int, _ sourceOffset: Int64, _ bytesPerRow: Int32, _ width: Int32, _ height: Int32,
    _ texture: Int, _ slice: Int32, _ level: Int32, _ destX: Int32, _ destY: Int32
) -> Int32 {
    guarded {
        guard width > 0, height > 0 else { return }
        try context(handle).blitEncoder().copy(
            from: object(source, as: MTLBuffer.self), sourceOffset: Int(sourceOffset), sourceBytesPerRow: Int(bytesPerRow),
            sourceBytesPerImage: 0, sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
            to: object(texture, as: MTLTexture.self), destinationSlice: Int(slice), destinationLevel: Int(level),
            destinationOrigin: MTLOrigin(x: Int(destX), y: Int(destY), z: 0)
        )
    }
}

@_cdecl("mcn_copy_texture_to_buffer")
public func mcn_copy_texture_to_buffer(
    _ handle: Int, _ texture: Int, _ level: Int32, _ x: Int32, _ y: Int32, _ width: Int32, _ height: Int32,
    _ target: Int, _ targetOffset: Int64, _ bytesPerRow: Int32
) -> Int32 {
    guarded {
        guard width > 0, height > 0 else { return }
        let source: MTLTexture = object(texture)
        // A combined depth/stencil texture can only be read one aspect at a time; the game wants depth.
        let options: MTLBlitOption = source.pixelFormat == .depth32Float_stencil8 ? .depthFromDepthStencil : []
        try context(handle).blitEncoder().copy(
            from: source, sourceSlice: 0, sourceLevel: Int(level), sourceOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0),
            sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
            to: object(target, as: MTLBuffer.self), destinationOffset: Int(targetOffset), destinationBytesPerRow: Int(bytesPerRow),
            destinationBytesPerImage: 0, options: options
        )
    }
}

@_cdecl("mcn_copy_texture")
public func mcn_copy_texture(
    _ handle: Int, _ source: Int, _ target: Int, _ level: Int32, _ sourceX: Int32, _ sourceY: Int32,
    _ width: Int32, _ height: Int32, _ destX: Int32, _ destY: Int32
) -> Int32 {
    guarded {
        guard width > 0, height > 0 else { return }
        try context(handle).blitEncoder().copy(
            from: object(source, as: MTLTexture.self), sourceSlice: 0, sourceLevel: Int(level),
            sourceOrigin: MTLOrigin(x: Int(sourceX), y: Int(sourceY), z: 0),
            sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
            to: object(target, as: MTLTexture.self), destinationSlice: 0, destinationLevel: Int(level),
            destinationOrigin: MTLOrigin(x: Int(destX), y: Int(destY), z: 0)
        )
    }
}

/// Fills the smaller mip levels of a texture from its base level.
@_cdecl("mcn_generate_mipmaps")
public func mcn_generate_mipmaps(_ handle: Int, _ texture: Int) -> Int32 {
    guarded {
        let target: MTLTexture = object(texture)
        guard target.mipmapLevelCount > 1 else { return }
        try context(handle).blitEncoder().generateMipmaps(for: target)
    }
}

/// Clears one mip level of a colour texture (0 to skip) and/or a depth texture (0 to skip).
@_cdecl("mcn_clear_textures")
public func mcn_clear_textures(
    _ handle: Int, _ color: Int, _ red: Float, _ green: Float, _ blue: Float, _ alpha: Float,
    _ depth: Int, _ depthValue: Double, _ level: Int32
) -> Int32 {
    guarded {
        let ctx = context(handle)
        guard ctx.render == nil else { throw SurfaceError("A clear was requested inside a render pass") }
        ctx.endBlit()
        let descriptor = MTLRenderPassDescriptor()
        if color != 0 {
            let attachment = descriptor.colorAttachments[0]!
            attachment.texture = object(color, as: MTLTexture.self)
            attachment.level = Int(level)
            attachment.loadAction = .clear
            attachment.storeAction = .store
            attachment.clearColor = MTLClearColor(red: Double(red), green: Double(green), blue: Double(blue), alpha: Double(alpha))
        }
        if depth != 0 {
            let texture: MTLTexture = object(depth)
            descriptor.depthAttachment.texture = texture
            descriptor.depthAttachment.level = Int(level)
            descriptor.depthAttachment.loadAction = .clear
            descriptor.depthAttachment.storeAction = .store
            descriptor.depthAttachment.clearDepth = depthValue
            if texture.pixelFormat == .depth32Float_stencil8 {
                descriptor.stencilAttachment.texture = texture
                descriptor.stencilAttachment.level = Int(level)
                descriptor.stencilAttachment.loadAction = .load
                descriptor.stencilAttachment.storeAction = .store
            }
        }
        guard let encoder = try ctx.commandBuffer().makeRenderCommandEncoder(descriptor: descriptor) else {
            throw SurfaceError("Could not create a render encoder for a clear")
        }
        encoder.label = "Clear"
        encoder.endEncoding()
    }
}

// MARK: - Submission

/// Commits the commands encoded so far. `outIndex` receives the number of this submit.
@_cdecl("mcn_submit")
public func mcn_submit(_ handle: Int, _ outIndex: UnsafeMutablePointer<Int64>?) -> Int32 {
    let ctx = context(handle)
    guard ctx.render == nil else {
        recordError("mcn_submit: a render pass is still open")
        return MCN_ERR_RENDER
    }
    autoreleasepool {
        let index = ctx.submit()
        outIndex?.pointee = Int64(index)
    }
    return MCN_OK
}

/// Returns 1 once the GPU has finished submit `index`, or 0 if `timeoutNanos` passed first.
@_cdecl("mcn_wait_submit")
public func mcn_wait_submit(_ handle: Int, _ index: Int64, _ timeoutNanos: Int64) -> Int32 {
    context(handle).waitForSubmit(UInt64(index), timeoutNanos: timeoutNanos) ? 1 : 0
}

// MARK: - Window surface

/// Attaches a Metal layer to the content view of `nsWindow` (an `NSWindow*`). Release the surface with
/// `mcn_surface_destroy` before the window is destroyed.
@_cdecl("mcn_surface_create")
public func mcn_surface_create(_ nsWindow: Int, _ handle: Int, _ outSurface: UnsafeMutablePointer<Int>?) -> Int32 {
    guarded(MCN_ERR_INVALID_ARGUMENT) {
        guard let outSurface, nsWindow != 0 else { throw SurfaceError("mcn_surface_create: bad arguments") }
        guard Thread.isMainThread else { throw SurfaceError("mcn_surface_create must be called on the main thread") }
        guard let window = Unmanaged<AnyObject>.fromOpaque(UnsafeRawPointer(bitPattern: nsWindow)!).takeUnretainedValue() as? NSWindow,
              let view = window.contentView else {
            throw SurfaceError("The pointer is not an NSWindow with a content view")
        }
        outSurface.pointee = retained(WindowSurface(view: view, device: context(handle).device))
    }
}

/// Detaches the layer from the window and releases the surface. The handle is invalid afterwards.
@_cdecl("mcn_surface_destroy")
public func mcn_surface_destroy(_ handle: Int) -> Int32 {
    guard handle != 0 else { return MCN_OK }
    guard Thread.isMainThread else {
        recordError("mcn_surface_destroy must be called on the main thread")
        return MCN_ERR_WRONG_THREAD
    }
    autoreleasepool {
        let owned = Unmanaged<WindowSurface>.fromOpaque(UnsafeRawPointer(bitPattern: handle)!)
        owned.takeUnretainedValue().detach()
        owned.release()
    }
    return MCN_OK
}

private func surface(_ handle: Int) -> WindowSurface {
    Unmanaged<WindowSurface>.fromOpaque(UnsafeRawPointer(bitPattern: handle)!).takeUnretainedValue()
}

/// Sets the size of the images the game presents, in pixels, and whether presenting waits for the display.
@_cdecl("mcn_surface_configure")
public func mcn_surface_configure(_ handle: Int, _ width: Int32, _ height: Int32, _ vsync: Int32) -> Int32 {
    guarded {
        let target = surface(handle)
        target.currentDrawable = nil
        // A drawable fetched before this point has the old size.
        target.pump?.stop()
        target.pump = nil
        target.layer.contentsScale = target.view.window?.backingScaleFactor ?? 1.0
        target.layer.drawableSize = CGSize(width: Int(max(1, width)), height: Int(max(1, height)))
        target.layer.displaySyncEnabled = vsync != 0
        CATransaction.flush()
        if vsync == 0 { target.pump = DrawablePump(layer: target.layer) }
    }
}

@_cdecl("mcn_surface_acquire")
public func mcn_surface_acquire(_ handle: Int) -> Int32 {
    guarded {
        let target = surface(handle)
        // Without vsync the drawable is picked up when presenting, if one is ready by then.
        if target.pump != nil { return }
        guard let drawable = target.layer.nextDrawable() else { throw SurfaceError("The layer returned no drawable") }
        target.currentDrawable = drawable
    }
}

/// Draws `texture` over the whole drawable and schedules it to be shown with the next submit. With vsync
/// off this does nothing when the window server has not handed out a drawable yet.
@_cdecl("mcn_surface_blit")
public func mcn_surface_blit(_ handle: Int, _ contextHandle: Int, _ texture: Int) -> Int32 {
    guarded {
        let target = surface(handle)
        let ctx = context(contextHandle)
        guard ctx.render == nil else { throw SurfaceError("Presenting inside a render pass") }
        let drawable: CAMetalDrawable
        if let pump = target.pump {
            // No drawable ready: this frame is not shown.
            guard let ready = pump.take() else { return }
            drawable = ready
        } else {
            guard let acquired = target.currentDrawable else { throw SurfaceError("No drawable has been acquired") }
            drawable = acquired
        }
        ctx.endBlit()
        let pipeline = try ctx.presentPipeline(target: target.layer.pixelFormat)
        let descriptor = MTLRenderPassDescriptor()
        descriptor.colorAttachments[0].texture = drawable.texture
        descriptor.colorAttachments[0].loadAction = .dontCare
        descriptor.colorAttachments[0].storeAction = .store
        let commands = try ctx.commandBuffer()
        guard let encoder = commands.makeRenderCommandEncoder(descriptor: descriptor) else {
            throw SurfaceError("Could not create a render encoder for presenting")
        }
        encoder.label = "Present"
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(object(texture, as: MTLTexture.self), index: 0)
        encoder.setFragmentSamplerState(ctx.nearestSampler, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        commands.present(drawable)
        ctx.countTimedFrame()
        target.currentDrawable = nil
    }
}
