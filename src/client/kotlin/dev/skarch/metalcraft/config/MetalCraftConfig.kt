package dev.skarch.metalcraft.config

import dev.skarch.metalcraft.MetalCraft.LOGGER
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Settings kept in `config/metalcraft.properties`. The file is created with the defaults on first start.
 * The renderer choice takes effect the next time the game starts; the shader pack can change while playing.
 */
object MetalCraftConfig {
    private const val FILE_NAME = "metalcraft.properties"
    private const val RENDERER = "renderer"
    private const val SHADER_PACK = "shaderpack"

    private val file: Path = FabricLoader.getInstance().configDir.resolve(FILE_NAME)

    /** Whether the game should start on the Metal renderer. Off means Mojang's own renderer is used. */
    val useMetalRenderer: Boolean

    /** Folder name of the selected shader pack, or "off". */
    @Volatile
    var shaderPack: String

    init {
        val values = HashMap<String, String>()
        try {
            if (Files.isRegularFile(file)) {
                for (line in Files.readAllLines(file)) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val separator = trimmed.indexOf('=')
                    if (separator > 0) values[trimmed.substring(0, separator).trim()] = trimmed.substring(separator + 1).trim()
                }
            }
        } catch (e: IOException) {
            LOGGER.warn("Could not read {}, using default settings", file, e)
        }
        val renderer = values.getOrDefault(RENDERER, "metal").lowercase()
        if (renderer != "metal" && renderer != "vanilla") {
            LOGGER.warn("Unknown {}={} in {} (expected metal or vanilla), using metal", RENDERER, renderer, FILE_NAME)
        }
        useMetalRenderer = renderer != "vanilla"
        shaderPack = values.getOrDefault(SHADER_PACK, "off")
        if (!Files.isRegularFile(file)) save()
    }

    /** Writes the current settings to the file. Failure is logged, not thrown. */
    fun save() {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                "# MetalCraft settings.\n" +
                    "# renderer: metal (render with Apple Metal) or vanilla (Minecraft's own renderer). Applies at the next start.\n" +
                    "$RENDERER=${if (useMetalRenderer) "metal" else "vanilla"}\n" +
                    "# shaderpack: off, or the folder name of a pack in shaderpacks/. Can also be switched in game (key: K by default).\n" +
                    "$SHADER_PACK=$shaderPack\n",
            )
        } catch (e: IOException) {
            LOGGER.warn("Could not write {}", file, e)
        }
    }
}
