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
 * Without the property, the Graphics API choice in the video settings decides: "Metal", OpenGL or Vulkan.
 * "Default" uses Metal when `config/metalcraft.properties` says so (which it does unless changed).
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

    /** Whether this run starts on Metal: asked for by the property or the config, and Sodium is not in the way. */
    @JvmStatic
    fun metalRequested(): Boolean = metalRequested

    private val metalRequested: Boolean by lazy {
        val requested = System.getProperty(PROPERTY)?.trim()?.lowercase()
        val useMetal = if (requested != null) {
            requested == "metal"
        } else {
            // The Graphics API choice in the video settings: "Metal" (added by this mod), the game's own
            // renderers, or "Default", which follows config/metalcraft.properties.
            when (videoSetting()) {
                "metal" -> true
                "opengl", "vulkan" -> false
                else -> MetalCraftConfig.useMetalRenderer
            }
        }
        // Sodium 0.9 draws chunks with its own OpenGL or Vulkan calls, not through Blaze3D, so it cannot run
        // on another backend. Starting Metal next to it would crash on the first frame.
        if (useMetal && FabricLoader.getInstance().isModLoaded("sodium")) {
            LOGGER.warn("Sodium is installed and only supports Minecraft's OpenGL and Vulkan renderers. The Metal renderer stays off")
            false
        } else {
            useMetal
        }
    }

    /**
     * The saved Graphics API choice, read from options.txt directly: this is needed before the game has
     * loaded its options.
     */
    private fun videoSetting(): String? = try {
        val file = FabricLoader.getInstance().gameDir.resolve("options.txt")
        if (!java.nio.file.Files.exists(file)) null
        else java.nio.file.Files.readAllLines(file).firstOrNull { it.startsWith("preferredGraphicsBackend:") }
            ?.substringAfter(':')?.trim()?.trim('"')?.lowercase()
    } catch (e: java.io.IOException) {
        null
    }

    /**
     * Called once from the `Minecraft` constructor with the backends the game is about to try, in order.
     * Metal goes first; if it cannot start, the game moves on to the rest of the list.
     */
    @JvmStatic
    fun backendsToTry(original: Array<GpuBackend>): Array<GpuBackend> {
        if (!metalRequested()) return original
        LOGGER.info("Starting on the Metal renderer")
        return arrayOf<GpuBackend>(MetalBackend(), *original)
    }
}
