package dev.skarch.metalcraft.backend

import dev.skarch.metalcraft.MetalCraft
import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.native.MetalSurface
import net.fabricmc.loader.api.FabricLoader
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWNativeCocoa
import java.nio.file.Files

/**
 * Milestone M1: attaches a `CAMetalLayer` to the game window and draws a clear colour and one triangle.
 *
 * System properties:
 * - `metalcraft.probe.seconds`: how long to keep drawing (default 1). Raise it to look at the window.
 * - `metalcraft.probe.capture`: path of a PNG to save the first frame to, for the automated check.
 */
object MetalWindowProbe {
    const val SECONDS_PROPERTY = "metalcraft.probe.seconds"
    const val CAPTURE_PROPERTY = "metalcraft.probe.capture"

    // The automated check looks for exactly these values (see the gametest harness).
    const val CLEAR_RED = 0.1f
    const val CLEAR_GREEN = 0.2f
    const val CLEAR_BLUE = 0.4f

    private const val SHADER_PATH = "assets/metalcraft/shaders/window_probe.metal"

    /** Runs on the render thread while the backend is being created. Throws if anything fails. */
    fun run(window: Long) {
        val seconds = System.getProperty(SECONDS_PROPERTY)?.toDoubleOrNull()?.coerceIn(0.0, 600.0) ?: 1.0
        val capturePath = System.getProperty(CAPTURE_PROPERTY)?.takeIf { it.isNotBlank() }

        val framebufferWidth = IntArray(1)
        val framebufferHeight = IntArray(1)
        GLFW.glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight)

        // The game keeps its window hidden until loading has finished; the probe has to be seen on screen.
        GLFW.glfwShowWindow(window)
        try {
            draw(window, seconds, capturePath, framebufferWidth[0], framebufferHeight[0])
        } finally {
            GLFW.glfwHideWindow(window)
        }
    }

    private fun draw(window: Long, seconds: Double, capturePath: String?, framebufferWidth: Int, framebufferHeight: Int) {
        MetalSurface.attach(GLFWNativeCocoa.glfwGetCocoaWindow(window)).use { surface ->
            surface.setProbeShader(loadShader())
            check(surface.width == framebufferWidth && surface.height == framebufferHeight) {
                "Drawable is ${surface.width}x${surface.height} but the window framebuffer is ${framebufferWidth}x$framebufferHeight"
            }

            var frames = 0
            val start = System.nanoTime()
            val end = start + (seconds * 1e9).toLong()
            do {
                // No event polling here: the game's window callbacks are not usable this early. The drawable
                // still follows the window because every frame re-reads the view size.
                surface.drawProbeFrame(CLEAR_RED, CLEAR_GREEN, CLEAR_BLUE, if (frames == 0) capturePath else null)
                frames++
            } while (System.nanoTime() < end)

            val elapsed = (System.nanoTime() - start) / 1e9
            LOGGER.info(
                "Metal window probe: drew {} frames in {} s on a {}x{} drawable{}",
                frames, String.format("%.2f", elapsed), surface.width, surface.height,
                if (capturePath != null) ", first frame saved to $capturePath" else "",
            )
        }
    }

    private fun loadShader(): String {
        val container = FabricLoader.getInstance().getModContainer(MetalCraft.MOD_ID)
            .orElseThrow { IllegalStateException("Mod container '${MetalCraft.MOD_ID}' not found") }
        val path = container.findPath(SHADER_PATH).orElseThrow { IllegalStateException("$SHADER_PATH is missing from the mod") }
        return Files.readString(path)
    }
}
