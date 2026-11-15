package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.GLFWErrorCapture
import com.mojang.blaze3d.shaders.GpuDebugOptions
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.systems.BackendCreationException
import com.mojang.blaze3d.systems.GpuDevice
import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.native.NativeBridge
import org.lwjgl.glfw.GLFW
import java.util.Locale

/**
 * The Metal entry in the game's list of graphics backends, next to Mojang's OpenGL and Vulkan ones.
 *
 * Milestone M1 state: the window is created without an OpenGL context and the Metal layer is proven to work
 * on it ([MetalWindowProbe]). There is no `GpuDevice` yet, so [createDevice] then reports a failure and the
 * game carries on with the next backend in the list. That fallback is also what must happen whenever Metal
 * cannot start.
 */
class MetalBackend : MetalBackendBase() {
    override fun getName(): String = NAME

    override fun setWindowHints() {
        // Like the Vulkan backend: a plain window, no OpenGL context.
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API)
    }

    override fun onWindowCreationFailed(error: GLFWErrorCapture.Error?) {
        val detail = if (error != null) String.format(Locale.ROOT, "GLFW_ERROR: 0x%X", error.error()) else "Failed to create window for Metal"
        throw BackendCreationException(detail, BackendCreationException.Reason.GLFW_ERROR)
    }

    override fun createDevice(
        window: Long,
        defaultShaderSource: ShaderSource,
        debugOptions: GpuDebugOptions,
        criticalShaderLoader: Runnable,
    ): GpuDevice {
        if (!NativeBridge.initialize()) {
            throw BackendCreationException("The MetalCraft native library could not be loaded", BackendCreationException.Reason.OTHER)
        }
        try {
            MetalWindowProbe.run(window)
        } catch (t: Throwable) {
            LOGGER.error("Metal window probe failed", t)
            throw BackendCreationException("Metal could not draw to the game window: ${t.message}", BackendCreationException.Reason.OTHER)
        }
        throw BackendCreationException(
            "The Metal renderer is not implemented yet (the window probe succeeded)",
            BackendCreationException.Reason.OTHER,
        )
    }

    companion object {
        const val NAME = "Metal"
    }
}
