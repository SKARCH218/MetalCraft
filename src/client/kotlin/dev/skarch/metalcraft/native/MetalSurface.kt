package dev.skarch.metalcraft.native

import java.lang.foreign.MemorySegment

/**
 * Owns the native surface: a `CAMetalLayer` attached to the game window. Use it on the render thread only
 * and [close] it before the window is destroyed.
 */
class MetalSurface private constructor(private var handle: MemorySegment) : AutoCloseable {
    private val size = IntArray(2)

    /** Drawable width in pixels as of the last [syncSize]. */
    val width: Int get() = size[0]

    /** Drawable height in pixels as of the last [syncSize]. */
    val height: Int get() = size[1]

    /** Fits the drawable to the window's current size and display scale. */
    fun syncSize() {
        NativeBridge.surfaceSyncSize(handle(), size)
    }

    fun setProbeShader(source: String) {
        NativeBridge.surfaceSetProbeShader(handle(), source)
    }

    /** Clears to the given colour, draws the probe triangle and presents. [capturePath] also saves a PNG. */
    fun drawProbeFrame(red: Float, green: Float, blue: Float, capturePath: String? = null) {
        NativeBridge.surfaceDrawProbeFrame(handle(), red, green, blue, capturePath)
    }

    override fun close() {
        if (handle == MemorySegment.NULL) return
        // Cleared only after a successful destroy, so a failed one can be retried.
        NativeBridge.surfaceDestroy(handle)
        handle = MemorySegment.NULL
    }

    private fun handle(): MemorySegment {
        check(handle != MemorySegment.NULL) { "The Metal surface is already closed" }
        return handle
    }

    companion object {
        /** @param nsWindow the `NSWindow*` of the game window, from `GLFWNativeCocoa.glfwGetCocoaWindow`. */
        fun attach(nsWindow: Long): MetalSurface {
            require(nsWindow != 0L) { "The window has no NSWindow" }
            val surface = MetalSurface(NativeBridge.surfaceCreate(nsWindow))
            try {
                surface.syncSize()
            } catch (t: Throwable) {
                surface.close()
                throw t
            }
            return surface
        }
    }
}
