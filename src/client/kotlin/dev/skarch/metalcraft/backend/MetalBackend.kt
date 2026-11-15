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
 * The game creates its window without an OpenGL context and asks [createDevice] for the renderer. Any
 * failure to start is reported as a [BackendCreationException], on which the game carries on with the next
 * backend in its list instead of crashing.
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
            return GpuDevice(MetalDevice(defaultShaderSource), criticalShaderLoader)
        } catch (t: Throwable) {
            LOGGER.error("Could not start the Metal renderer", t)
            MetalDevice.current = null
            throw BackendCreationException("Metal could not be started: ${t.message}", BackendCreationException.Reason.OTHER)
        }
    }

    companion object {
        const val NAME = "Metal"
    }
}
