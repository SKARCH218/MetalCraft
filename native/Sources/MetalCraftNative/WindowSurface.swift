import AppKit
import Metal
import QuartzCore

/// A `CAMetalLayer` attached to the content view of the game's window.
///
/// Everything except [DrawablePump] must run on the main thread: the game renders on it on macOS, and
/// AppKit requires it.
final class WindowSurface {
    let layer: CAMetalLayer
    let view: NSView
    private let hadLayer: Bool
    /// The drawable acquired for the frame in progress (vsync on).
    var currentDrawable: CAMetalDrawable?
    /// Fetches drawables in the background while the display is not being waited for (vsync off).
    var pump: DrawablePump?

    init(view: NSView, device: MTLDevice) {
        self.view = view
        hadLayer = view.wantsLayer

        layer = CAMetalLayer()
        layer.device = device
        layer.pixelFormat = .bgra8Unorm
        layer.framebufferOnly = true
        layer.isOpaque = true
        // The game's colours are sRGB encoded; saying so lets the system convert them for the display.
        layer.colorspace = CGColorSpace(name: CGColorSpace.sRGB)
        layer.contentsScale = view.window?.backingScaleFactor ?? 1.0

        // Same attachment GLFW itself uses for a Vulkan surface: the layer replaces the view's backing layer.
        view.layer = layer
        view.wantsLayer = true
        // Nothing pumps the run loop while the backend starts, so push the layer change to the window server now.
        CATransaction.flush()
    }

    func detach() {
        currentDrawable = nil
        pump?.stop()
        pump = nil
        view.layer = nil
        view.wantsLayer = hadLayer
        CATransaction.flush()
    }
}

struct SurfaceError: Error {
    let message: String
    init(_ message: String) { self.message = message }
}

/// Keeps one drawable ready so the render thread never waits for the window server.
///
/// `CAMetalLayer.nextDrawable()` blocks until the compositor hands an image back, which happens at most once
/// per display refresh. With vsync off the game must not be slowed down to that rate: frames for which no
/// drawable is ready are rendered but not shown, which is all a composited window can do anyway.
final class DrawablePump {
    private let layer: CAMetalLayer
    private let lock = NSLock()
    // Starts at zero and is signalled once below: a dispatch semaphore must not be freed below its initial value.
    private let wanted = DispatchSemaphore(value: 0)
    private let finished = DispatchSemaphore(value: 0)
    private var ready: CAMetalDrawable?
    private var running = true

    init(layer: CAMetalLayer) {
        self.layer = layer
        let thread = Thread { [self] in
            run()
            finished.signal()
        }
        thread.name = "MetalCraft drawable pump"
        thread.qualityOfService = .userInteractive
        thread.start()
        wanted.signal()
    }

    private func run() {
        while true {
            wanted.wait()
            lock.lock()
            let keepGoing = running
            lock.unlock()
            if !keepGoing { return }
            autoreleasepool {
                // Returns nil after a one second timeout, for example while the window is hidden.
                let drawable = layer.nextDrawable()
                lock.lock()
                if running, let drawable {
                    ready = drawable
                } else if running {
                    wanted.signal()
                }
                lock.unlock()
            }
        }
    }

    /// The ready drawable, if there is one. Taking it starts the fetch of the next.
    func take() -> CAMetalDrawable? {
        lock.lock()
        let drawable = ready
        ready = nil
        lock.unlock()
        if drawable != nil { wanted.signal() }
        return drawable
    }

    /// Ends the background thread and waits for it, so the layer can be reconfigured without the thread
    /// still using it. A drawable it was holding is given back.
    func stop() {
        lock.lock()
        running = false
        ready = nil
        lock.unlock()
        wanted.signal()
        // The thread is at most inside one nextDrawable call, which gives up after a second.
        _ = finished.wait(timeout: .now() + 1.5)
    }
}
