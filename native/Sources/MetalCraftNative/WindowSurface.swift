import AppKit
import Metal
import QuartzCore
import UniformTypeIdentifiers

/// A `CAMetalLayer` attached to the content view of the game's window, plus what is needed to draw into it.
///
/// All methods must run on the main thread: the game renders on it on macOS, and AppKit requires it.
final class WindowSurface {
    let device: MTLDevice
    let queue: MTLCommandQueue
    let layer: CAMetalLayer
    let view: NSView
    private let hadLayer: Bool
    private var trianglePipeline: MTLRenderPipelineState?
    private var lastCommands: MTLCommandBuffer?

    init(window: NSWindow, view: NSView, device: MTLDevice, queue: MTLCommandQueue) {
        self.device = device
        self.queue = queue
        self.view = view
        hadLayer = view.wantsLayer

        layer = CAMetalLayer()
        layer.device = device
        layer.pixelFormat = .bgra8Unorm
        // Screenshots copy the drawable with a blit, which a framebuffer-only texture does not allow.
        layer.framebufferOnly = false
        layer.isOpaque = true

        // Same attachment GLFW itself uses for a Vulkan surface: the layer replaces the view's backing layer.
        view.layer = layer
        view.wantsLayer = true
        syncSize()
        // Nothing pumps the run loop while the backend starts, so push the layer change to the window server now.
        CATransaction.flush()
    }

    /// Matches the drawable to the view's size in pixels. Call after a resize, a display change or a
    /// fullscreen switch; calling it every frame is cheap.
    @discardableResult
    func syncSize() -> CGSize {
        let scale = view.window?.backingScaleFactor ?? 1.0
        let bounds = view.bounds.size
        let size = CGSize(width: max(1, (bounds.width * scale).rounded()), height: max(1, (bounds.height * scale).rounded()))
        if layer.contentsScale != scale { layer.contentsScale = scale }
        if layer.drawableSize != size { layer.drawableSize = size }
        return size
    }

    func setTriangleShader(source: String) throws {
        let library = try device.makeLibrary(source: source, options: nil)
        guard let vertex = library.makeFunction(name: "probe_vertex"),
              let fragment = library.makeFunction(name: "probe_fragment") else {
            throw SurfaceError("Shader source must define probe_vertex and probe_fragment")
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.label = "MetalCraft window probe"
        descriptor.vertexFunction = vertex
        descriptor.fragmentFunction = fragment
        descriptor.colorAttachments[0].pixelFormat = layer.pixelFormat
        trianglePipeline = try device.makeRenderPipelineState(descriptor: descriptor)
    }

    /// Clears the next drawable, draws the probe triangle and presents. With `capturePath` the finished frame
    /// is also written there as a PNG before it is shown.
    func drawTestFrame(clear: MTLClearColor, capturePath: String?) throws {
        guard let pipeline = trianglePipeline else { throw SurfaceError("No triangle shader has been set") }
        syncSize()
        guard let drawable = layer.nextDrawable() else { throw SurfaceError("The layer returned no drawable") }
        guard let commands = queue.makeCommandBuffer() else { throw SurfaceError("Could not create a command buffer") }

        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = drawable.texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[0].clearColor = clear
        guard let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else {
            throw SurfaceError("Could not create a render encoder")
        }
        encoder.setRenderPipelineState(pipeline)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()

        if let capturePath {
            // Copy the frame into CPU-visible memory in the same command buffer: the drawable's own storage
            // is not guaranteed to be readable.
            let texture = drawable.texture
            let bytesPerRow = texture.width * 4
            guard let pixels = device.makeBuffer(length: bytesPerRow * texture.height, options: .storageModeShared),
                  let blit = commands.makeBlitCommandEncoder() else {
                throw SurfaceError("Could not set up the frame capture")
            }
            blit.copy(
                from: texture, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0),
                sourceSize: MTLSize(width: texture.width, height: texture.height, depth: 1),
                to: pixels, destinationOffset: 0, destinationBytesPerRow: bytesPerRow,
                destinationBytesPerImage: bytesPerRow * texture.height
            )
            blit.endEncoding()
            commands.present(drawable)
            commands.commit()
            commands.waitUntilCompleted()
            if let error = commands.error { throw SurfaceError("Rendering failed: \(error.localizedDescription)") }
            try writePNG(bgra: pixels.contents(), width: texture.width, height: texture.height, path: capturePath)
        } else {
            commands.present(drawable)
            commands.commit()
        }
        lastCommands = commands
    }

    func detach() {
        // Let the GPU finish the last frame before the layer and its window go away.
        lastCommands?.waitUntilCompleted()
        lastCommands = nil
        view.layer = nil
        view.wantsLayer = hadLayer
        CATransaction.flush()
    }
}

struct SurfaceError: Error {
    let message: String
    init(_ message: String) { self.message = message }
}

private func writePNG(bgra pixels: UnsafeMutableRawPointer, width: Int, height: Int, path: String) throws {
    // The pixels are BGRA; describe them as such instead of swapping bytes.
    let bitmapInfo = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue
    let image = CGContext(
        data: pixels, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: bitmapInfo
    )?.makeImage()
    let url = URL(fileURLWithPath: path)
    guard let image,
          let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)
    else { throw SurfaceError("Could not create \(path)") }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { throw SurfaceError("Could not write \(path)") }
}

// MARK: - C ABI

private func surface(_ handle: UnsafeMutableRawPointer?) -> WindowSurface? {
    guard let handle else {
        recordError("Surface handle is null")
        return nil
    }
    return Unmanaged<WindowSurface>.fromOpaque(handle).takeUnretainedValue()
}

private func onMainThread(_ function: String) -> Bool {
    if Thread.isMainThread { return true }
    recordError("\(function) must be called on the main thread")
    return false
}

/// Attaches a Metal layer to the content view of `nsWindow` (an `NSWindow*`) and returns an owning handle
/// through `outHandle`. Release it with `mcn_surface_destroy`.
@_cdecl("mcn_surface_create")
public func mcn_surface_create(
    _ nsWindow: UnsafeMutableRawPointer?, _ outHandle: UnsafeMutablePointer<UnsafeMutableRawPointer?>?
) -> Int32 {
    guard let nsWindow, let outHandle else {
        recordError("mcn_surface_create: window or output pointer is null")
        return MCN_ERR_INVALID_ARGUMENT
    }
    guard onMainThread("mcn_surface_create") else { return MCN_ERR_WRONG_THREAD }
    guard let window = Unmanaged<AnyObject>.fromOpaque(nsWindow).takeUnretainedValue() as? NSWindow,
          let view = window.contentView else {
        recordError("mcn_surface_create: the pointer is not an NSWindow with a content view")
        return MCN_ERR_INVALID_ARGUMENT
    }
    guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
        recordError("No Metal device available")
        return MCN_ERR_METAL_UNAVAILABLE
    }
    autoreleasepool {
        let created = WindowSurface(window: window, view: view, device: device, queue: queue)
        outHandle.pointee = Unmanaged.passRetained(created).toOpaque()
    }
    return MCN_OK
}

/// Detaches the layer from the window and releases the surface. The handle is invalid afterwards.
@_cdecl("mcn_surface_destroy")
public func mcn_surface_destroy(_ handle: UnsafeMutableRawPointer?) -> Int32 {
    guard let handle else { return MCN_OK }
    guard onMainThread("mcn_surface_destroy") else { return MCN_ERR_WRONG_THREAD }
    let owned = Unmanaged<WindowSurface>.fromOpaque(handle)
    autoreleasepool {
        owned.takeUnretainedValue().detach()
        owned.release()
    }
    return MCN_OK
}

/// Resizes the drawable to the window's current size in pixels and reports that size.
@_cdecl("mcn_surface_sync_size")
public func mcn_surface_sync_size(
    _ handle: UnsafeMutableRawPointer?, _ outWidth: UnsafeMutablePointer<Int32>?, _ outHeight: UnsafeMutablePointer<Int32>?
) -> Int32 {
    guard let surface = surface(handle) else { return MCN_ERR_INVALID_ARGUMENT }
    guard onMainThread("mcn_surface_sync_size") else { return MCN_ERR_WRONG_THREAD }
    let size = surface.syncSize()
    outWidth?.pointee = Int32(size.width)
    outHeight?.pointee = Int32(size.height)
    return MCN_OK
}

/// Compiles the MSL source (NUL-terminated UTF-8) that provides `probe_vertex` and `probe_fragment`.
@_cdecl("mcn_surface_set_probe_shader")
public func mcn_surface_set_probe_shader(_ handle: UnsafeMutableRawPointer?, _ source: UnsafePointer<CChar>?) -> Int32 {
    guard let surface = surface(handle) else { return MCN_ERR_INVALID_ARGUMENT }
    guard let source else {
        recordError("mcn_surface_set_probe_shader: source is null")
        return MCN_ERR_INVALID_ARGUMENT
    }
    guard onMainThread("mcn_surface_set_probe_shader") else { return MCN_ERR_WRONG_THREAD }
    do {
        try surface.setTriangleShader(source: String(cString: source))
        return MCN_OK
    } catch let error as SurfaceError {
        recordError(error.message)
    } catch {
        recordError("Shader compilation failed: \(error.localizedDescription)")
    }
    return MCN_ERR_SHADER
}

/// Draws one frame: a clear to the given colour and the probe triangle. `capturePath` (NUL-terminated UTF-8,
/// may be null) additionally saves the frame as a PNG.
@_cdecl("mcn_surface_draw_probe_frame")
public func mcn_surface_draw_probe_frame(
    _ handle: UnsafeMutableRawPointer?, _ red: Float, _ green: Float, _ blue: Float, _ capturePath: UnsafePointer<CChar>?
) -> Int32 {
    guard let surface = surface(handle) else { return MCN_ERR_INVALID_ARGUMENT }
    guard onMainThread("mcn_surface_draw_probe_frame") else { return MCN_ERR_WRONG_THREAD }
    do {
        try autoreleasepool {
            try surface.drawTestFrame(
                clear: MTLClearColor(red: Double(red), green: Double(green), blue: Double(blue), alpha: 1),
                capturePath: capturePath.map { String(cString: $0) }
            )
        }
        return MCN_OK
    } catch let error as SurfaceError {
        recordError(error.message)
    } catch {
        recordError("Drawing failed: \(error.localizedDescription)")
    }
    return MCN_ERR_RENDER
}
