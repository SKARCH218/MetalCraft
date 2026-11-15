package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.systems.CommandEncoderBackend
import com.mojang.blaze3d.systems.GpuSurface
import com.mojang.blaze3d.systems.GpuSurfaceBackend
import com.mojang.blaze3d.systems.SurfaceException
import com.mojang.blaze3d.textures.GpuTextureView
import dev.skarch.metalcraft.native.Mcn
import dev.skarch.metalcraft.native.NativeException

/** The game window as a render destination: a `CAMetalLayer` on the window's content view. */
internal class MetalGpuSurface(private val device: MetalDevice, private var handle: Long) : GpuSurfaceBackend {
    override fun configure(config: GpuSurface.Configuration) {
        val vsync = config.presentMode() == GpuSurface.PresentMode.FIFO || config.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED
        call("configure the window surface") { Mcn.surfaceConfigure.invokeExact(handle, config.width(), config.height(), if (vsync) 1 else 0) as Int }
    }

    // The layer follows the window by itself; the game reconfigures the surface when its size changes.
    override fun isSuboptimal(): Boolean = false

    override fun acquireNextTexture() {
        val start = if (FrameTiming.enabled) System.nanoTime() else 0L
        call("get the next window image") { Mcn.surfaceAcquire.invokeExact(handle) as Int }
        if (FrameTiming.enabled) FrameTiming.add(FrameTiming.ACQUIRE, start)
    }

    override fun blitFromTexture(commandEncoder: CommandEncoderBackend, textureView: GpuTextureView) {
        // The image is drawn scaled to the whole window and shown when the frame is submitted.
        Mcn.check(Mcn.surfaceBlit.invokeExact(handle, device.context, (textureView as MetalTextureView).handle) as Int)
    }

    override fun present() {
        // Already scheduled by blitFromTexture, as part of the frame's command buffer.
    }

    override fun close() {
        if (handle == 0L) return
        Mcn.check(Mcn.surfaceDestroy.invokeExact(handle) as Int)
        handle = 0L
    }

    override fun supportedPresentModes(): Collection<GpuSurface.PresentMode> = PRESENT_MODES

    private inline fun call(what: String, native: () -> Int) {
        try {
            Mcn.check(native())
        } catch (e: NativeException) {
            throw SurfaceException("Failed to $what: ${e.message}")
        }
    }

    private companion object {
        val PRESENT_MODES = listOf(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO)
    }
}
