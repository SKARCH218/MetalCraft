package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.systems.GpuBackend
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
            // Metal is not one of the game's own choices: [backendsToTry] puts it in front of the default list.
            "metal" -> fromLaunchArgument
            else -> {
                LOGGER.warn("Ignoring unknown -D{}={} (expected opengl, vulkan or metal)", PROPERTY, requested)
                fromLaunchArgument
            }
        }
    }

    /** Called once from the `Minecraft` constructor with the backends the game is about to try, in order. */
    @JvmStatic
    fun backendsToTry(original: Array<GpuBackend>): Array<GpuBackend> {
        if (System.getProperty(PROPERTY)?.trim()?.lowercase() != "metal") return original
        LOGGER.info("-D{}=metal: trying the Metal backend first", PROPERTY)
        return arrayOf<GpuBackend>(MetalBackend(), *original)
    }
}
