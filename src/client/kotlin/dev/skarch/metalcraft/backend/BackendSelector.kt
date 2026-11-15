package dev.skarch.metalcraft.backend

import dev.skarch.metalcraft.MetalCraft.LOGGER
import net.minecraft.client.PreferredGraphicsApi

/**
 * Pins the renderer from `-Dmetalcraft.backend=opengl|vulkan|metal` so the same test run can be repeated on
 * every backend. Without the property the game's own launch argument and video setting stay in charge.
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
            "metal" -> {
                // The Metal backend arrives with milestone M2; until then the request cannot be honoured.
                LOGGER.warn("-D{}=metal requested, but the Metal backend is not implemented yet. Using the default renderer", PROPERTY)
                fromLaunchArgument
            }
            else -> {
                LOGGER.warn("Ignoring unknown -D{}={} (expected opengl, vulkan or metal)", PROPERTY, requested)
                fromLaunchArgument
            }
        }
    }
}
