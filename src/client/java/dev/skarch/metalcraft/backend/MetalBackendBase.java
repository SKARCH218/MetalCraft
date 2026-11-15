package dev.skarch.metalcraft.backend;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import org.jspecify.annotations.Nullable;

/**
 * Java shim for one method Kotlin cannot override safely: {@code GpuBackend.handleWindowCreationErrors} is
 * declared with a non-null parameter but the game passes null when GLFW reported no error. A Kotlin override
 * would have to take a non-null parameter and would throw on that null. Everything else lives in
 * {@code MetalBackend}.
 */
public abstract class MetalBackendBase implements GpuBackend {
    @Override
    public final void handleWindowCreationErrors(GLFWErrorCapture.@Nullable Error error) throws BackendCreationException {
        this.onWindowCreationFailed(error);
    }

    protected abstract void onWindowCreationFailed(GLFWErrorCapture.@Nullable Error error) throws BackendCreationException;
}
