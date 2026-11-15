package dev.skarch.metalcraft.config

import dev.skarch.metalcraft.MetalCraft.LOGGER
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.file.Files
import java.util.Properties

/**
 * Settings kept in `config/metalcraft.properties`. The file is created with the defaults on first start.
 * Changes take effect the next time the game starts.
 */
object MetalCraftConfig {
    private const val FILE_NAME = "metalcraft.properties"
    private const val RENDERER = "renderer"

    /** Whether the game should start on the Metal renderer. Off means Mojang's own renderer is used. */
    val useMetalRenderer: Boolean

    init {
        val file = FabricLoader.getInstance().configDir.resolve(FILE_NAME)
        val properties = Properties()
        try {
            if (Files.isRegularFile(file)) {
                Files.newBufferedReader(file).use(properties::load)
            } else {
                Files.createDirectories(file.parent)
                Files.writeString(
                    file,
                    "# MetalCraft settings. Changes take effect the next time the game starts.\n" +
                        "# renderer: metal (render with Apple Metal) or vanilla (Minecraft's own renderer)\n" +
                        "$RENDERER=metal\n",
                )
            }
        } catch (e: IOException) {
            LOGGER.warn("Could not read or create {}, using default settings", file, e)
        }
        val renderer = properties.getProperty(RENDERER, "metal").trim().lowercase()
        if (renderer != "metal" && renderer != "vanilla") {
            LOGGER.warn("Unknown {}={} in {} (expected metal or vanilla), using metal", RENDERER, renderer, FILE_NAME)
        }
        useMetalRenderer = renderer != "vanilla"
    }
}
