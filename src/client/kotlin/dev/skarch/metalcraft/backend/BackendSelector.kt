package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.systems.GpuBackend
import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.config.MetalCraftConfig
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.PreferredGraphicsApi

/**
 * Decides which renderer the game starts on.
 *
 * `-Dmetalcraft.backend=opengl|vulkan|metal` pins it, so the same test run can be repeated on every backend.
 * Without the property, Metal is used when `config/metalcraft.properties` says so (the default), and the
 * game's own launch argument and video setting decide what comes after it.
 */
object BackendSelector {
    const val PROPERTY = "metalcraft.backend"

    /** Called once from the `Minecraft` constructor with the value of the `--graphicsBackend` launch argument. */
    @JvmStatic
    fun overrideForcedApi(fromLaunchArgument: PreferredGraphicsApi?): PreferredGraphicsApi? {
        val requested = System.getProperty(PROPERTY)?.trim()?.lowercase() ?: return fromLaunchArgument
        return when (requested) {
            "opengl" -> PreferredGraphicsApi.OPENGL
            "vulkan" -> PreferredGraphicsApi.VULKAN
            // Metal is not one of the game's own choices: [backendsToTry] puts it in front of the game's list.
            "metal" -> fromLaunchArgument
            else -> {
                LOGGER.warn("Ignoring unknown -D{}={} (expected opengl, vulkan or metal)", PROPERTY, requested)
                fromLaunchArgument
            }
        }
    }

    /**
     * Called once from the `Minecraft` constructor with the backends the game is about to try, in order.
     * Metal goes first; if it cannot start, the game moves on to the rest of the list.
     */
    @JvmStatic
    fun backendsToTry(original: Array<GpuBackend>): Array<GpuBackend> {
        val requested = System.getProperty(PROPERTY)?.trim()?.lowercase()
        val useMetal = if (requested != null) requested == "metal" else MetalCraftConfig.useMetalRenderer
        if (!useMetal) return original
        // Sodium 0.9 draws chunks with its own OpenGL or Vulkan calls, not through Blaze3D, so it cannot run
        // on another backend. Starting Metal next to it would crash on the first frame.
        if (FabricLoader.getInstance().isModLoaded("sodium")) {
            LOGGER.warn("Sodium is installed and only supports Minecraft's OpenGL and Vulkan renderers. The Metal renderer stays off")
            return original
        }
        LOGGER.info("Starting on the Metal renderer")
        return arrayOf<GpuBackend>(MetalBackend(), *original)
    }
}
