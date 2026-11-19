package dev.skarch.metalcraft.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.skarch.metalcraft.MetalCraft.LOGGER
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The options of a converted shader pack, as its `options.json` lists them (written by the pack converter):
 * what the pack offers, how its own settings screens are laid out, and the values in use.
 *
 * A converted pack is fixed Metal shader code, so changing an option means converting the pack again from
 * its original. That is done by the converter (`metalcraft-porter.jar`, a tool separate from the mod), which
 * [PackConversion] runs.
 */
internal class PackOptions private constructor(val packId: String, private val folder: Path, json: JsonObject) {
    class Option(val name: String, val isSwitch: Boolean, val default: String, val values: List<String>)

    /** The original pack (zip or folder) the converter reads. */
    val source: String = json.get("source").asString
    private val port: JsonObject = json.getAsJsonObject("port") ?: JsonObject()
    val columns: Int = json.get("columns")?.asInt ?: 2

    val options: Map<String, Option> = json.getAsJsonObject("options").entrySet().associate { (name, value) ->
        val option = value.asJsonObject
        name to Option(
            name, option.get("type").asString == "bool", option.get("default").asString,
            option.getAsJsonArray("values")?.map { it.asString } ?: listOf("false", "true"),
        )
    }

    /** Screen name ("" for the first one) to what it lists: option names, `[other screen]`, `<profile>`, `<empty>`. */
    val screens: Map<String, List<String>> = json.getAsJsonObject("screens").entrySet().associate { (name, items) ->
        name to items.asJsonArray.map { it.asString }
    }

    val profiles: Map<String, Map<String, String>> = LinkedHashMap<String, Map<String, String>>().also { result ->
        for ((name, values) in json.getAsJsonObject("profiles").entrySet()) {
            result[name] = values.asJsonObject.entrySet().associate { it.key to it.value.asString }
        }
    }

    private val english: JsonObject? = json.getAsJsonObject("lang")?.getAsJsonObject("en_us")
    private val korean: JsonObject? = json.getAsJsonObject("lang")?.getAsJsonObject("ko_kr")

    /** Values that differ from the pack's defaults. */
    private val values = HashMap<String, String>()
    private val initial: Map<String, String>

    init {
        port.getAsJsonObject("options")?.entrySet()?.forEach { (name, value) -> values[name] = value.asString }
        initial = HashMap(values)
    }

    /** The first screen's items; a pack without screens of its own lists every option. */
    fun items(screen: String): List<String> = screens[screen] ?: if (screen.isEmpty()) options.keys.sorted() else emptyList()

    fun value(name: String): String = values[name] ?: options[name]?.default ?: ""

    fun set(name: String, value: String) {
        // Packs write the same number in different ways (1.0, 1.00): the default stays the default.
        if (value == options[name]?.default) values.remove(name) else values[name] = value
    }

    fun next(option: Option): String {
        val choices = if (option.isSwitch) listOf("false", "true") else option.values
        if (choices.isEmpty()) return value(option.name)
        return choices[(choices.indexOf(value(option.name)) + 1).mod(choices.size)]
    }

    fun reset() = values.clear()

    fun changed(): Boolean = values != initial

    /** The profile whose settings are all in use, or null. */
    fun profile(): String? = profiles.entries.firstOrNull { (_, settings) -> settings.all { (name, value) -> same(value(name), value) } }?.key

    fun applyProfile(name: String) {
        for ((option, value) in profiles[name] ?: return) if (option in options) set(option, value)
    }

    /** The pack's label for a key such as `option.SHADOW_QUALITY`, in the game's language where the pack has it. */
    fun label(key: String, language: String, fallback: String): String =
        (if (language == "ko_kr") korean?.get(key)?.asString else null) ?: english?.get(key)?.asString ?: fallback

    /** Where the converted pack is written: its own folder, or the game's pack folder for one inside the mod. */
    fun outputFolder(): Path =
        if (folder.fileSystem == FileSystems.getDefault()) folder else FabricLoader.getInstance().gameDir.resolve("shaderpacks").resolve(packId)

    /** The port settings with the current option values, as a file the converter reads. */
    fun writePort(): Path {
        val merged = port.deepCopy()
        merged.add("options", JsonObject().also { for ((name, value) in values) it.addProperty(name, value) })
        val file = Files.createTempFile("metalcraft-port-", ".json")
        Files.writeString(file, GsonBuilder().setPrettyPrinting().create().toJson(merged))
        return file
    }

    private fun same(a: String, b: String): Boolean = a == b || (a.toDoubleOrNull() != null && a.toDoubleOrNull() == b.toDoubleOrNull())

    companion object {
        /** The options of the pack in [folder], or null for a pack without any (not converted, or by an older converter). */
        fun load(packId: String, folder: Path): PackOptions? {
            val file = folder.resolve("options.json")
            if (!Files.isRegularFile(file)) return null
            return try {
                PackOptions(packId, folder, JsonParser.parseString(Files.readString(file)).asJsonObject)
            } catch (e: Exception) {
                LOGGER.warn("Shader pack '{}': options.json could not be read: {}", packId, e.message)
                null
            }
        }
    }
}

/** Converts a pack again with other option values, in the background, by running the converter jar. */
internal object PackConversion {
    /** Null while idle or running; after a run, null for success or what went wrong. */
    @Volatile
    var error: String? = null
        private set

    @Volatile
    var running = false
        private set

    /** The converter: named by `-Dmetalcraft.porter`, next to the game's packs, or brought along inside the mod. */
    fun porter(): Path? {
        System.getProperty("metalcraft.porter")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { Files.isRegularFile(it) }?.let { return it }
        val game = FabricLoader.getInstance().gameDir
        for (candidate in listOf(game.resolve(JAR_NAME), game.resolve("shaderpacks").resolve(JAR_NAME), game.resolve("config").resolve(JAR_NAME))) {
            if (Files.isRegularFile(candidate)) return candidate
        }
        val bundled = PackConversion::class.java.getResourceAsStream("/$JAR_NAME") ?: return null
        return bundled.use {
            val target = Files.createTempFile("metalcraft-porter-", ".jar")
            target.toFile().deleteOnExit()
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            target
        }
    }

    fun start(options: PackOptions) {
        if (running) return
        val jar = porter()
        if (jar == null) {
            error = "metalcraft-porter.jar was not found"
            return
        }
        running = true
        error = null
        val thread = Thread({
            try {
                val port = options.writePort()
                val java = ProcessHandle.current().info().command().orElse("java")
                val process = ProcessBuilder(
                    java, "-XstartOnFirstThread", "-jar", jar.toString(), options.source, options.outputFolder().toString(), port.toString(),
                ).directory(FabricLoader.getInstance().gameDir.toAbsolutePath().toFile()).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val code = process.waitFor()
                Files.deleteIfExists(port)
                if (code != 0) {
                    LOGGER.error("Converting shader pack '{}' failed:\n{}", options.packId, output.takeLast(4000))
                    error = output.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "the converter failed (exit code $code)"
                } else {
                    LOGGER.info("Shader pack '{}' converted with its new options", options.packId)
                }
            } catch (e: Exception) {
                LOGGER.error("Converting shader pack '{}' failed", options.packId, e)
                error = e.message ?: e.javaClass.simpleName
            } finally {
                running = false
            }
        }, "MetalCraft pack conversion")
        thread.isDaemon = true
        thread.start()
    }

    private const val JAR_NAME = "metalcraft-porter.jar"
}
