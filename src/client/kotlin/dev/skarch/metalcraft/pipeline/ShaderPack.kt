package dev.skarch.metalcraft.pipeline

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.GpuFormat
import dev.skarch.metalcraft.MetalCraft
import dev.skarch.metalcraft.MetalCraft.LOGGER
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/** A colour buffer a pack declares (`colortexN`). `colortex0` is the game's own image and is not declared. */
internal class BufferSpec(val name: String, val format: GpuFormat, val scale: Float)

/** A vertex and a fragment shader file. */
internal class ProgramFiles(val vertex: String, val fragment: String)

/** One full-screen pass: a fragment shader reading [inputs] and writing [outputs]. */
internal class PassSpec(val stage: String, val fragment: String, val inputs: List<String>, val outputs: List<String>)

/**
 * A shader pack: a folder with `pack.json` and `.metal` files (CLAUDE.md section 3-5).
 *
 * ```json
 * {
 *   "name": "Example",
 *   "include": ["common.metal"],                         // prepended to every shader, after the engine header
 *   "buffers": { "colortex1": { "format": "RGBA16F", "scale": 1.0 } },
 *   "gbuffers": ["colortex1"],                           // extra outputs of the gbuffers programs, after colortex0
 *   "shadow": { "resolution": 2048, "distance": 96 },
 *   "programs": {
 *     "shadow":           { "vertex": "shadow.vsh.metal", "fragment": "shadow.fsh.metal" },
 *     "gbuffers_terrain": { "vertex": "terrain.vsh.metal", "fragment": "terrain.fsh.metal" }
 *   },
 *   "passes": [
 *     { "stage": "deferred",  "fragment": "deferred.fsh.metal", "inputs": ["colortex0", "depthtex0"], "outputs": ["colortex0"] },
 *     { "stage": "final",     "fragment": "final.fsh.metal",    "inputs": ["colortex0"],              "outputs": ["colortex0"] }
 *   ]
 * }
 * ```
 * Passes run in the order listed; `stage` is one of deferred, composite, final.
 */
internal class ShaderPack private constructor(val id: String, private val root: Path, json: JsonObject) {
    val displayName: String = json.get("name")?.asString ?: id
    val includes: List<String> = json.getAsJsonArray("include")?.map { it.asString } ?: emptyList()
    val buffers: Map<String, BufferSpec>
    val gbufferTargets: List<String> = json.getAsJsonArray("gbuffers")?.map { it.asString } ?: emptyList()
    val shadowResolution: Int
    val shadowDistance: Float
    val shadow: ProgramFiles?
    val terrain: ProgramFiles?
    val passes: List<PassSpec>

    init {
        val declared = LinkedHashMap<String, BufferSpec>()
        json.getAsJsonObject("buffers")?.entrySet()?.forEach { (name, value) ->
            require(BUFFER_NAME.matches(name) && name != MAIN_COLOR) { "Buffer '$name' must be colortex1 to colortex15" }
            val spec = value.asJsonObject
            val scale = spec.get("scale")?.asFloat ?: 1f
            require(scale > 0f && scale <= 1f) { "Buffer '$name' has scale $scale, expected 0 < scale <= 1" }
            declared[name] = BufferSpec(name, parseFormat(spec.get("format")?.asString ?: "RGBA8"), scale)
        }
        buffers = declared
        require(gbufferTargets.size <= MAX_COLOR_OUTPUTS - 1) { "At most ${MAX_COLOR_OUTPUTS - 1} gbuffers outputs are possible" }
        require(gbufferTargets.toSet().size == gbufferTargets.size) { "gbuffers lists a buffer twice" }
        for (name in gbufferTargets) {
            val spec = requireNotNull(buffers[name]) { "gbuffers output '$name' is not a declared buffer" }
            require(spec.scale == 1f) { "gbuffers output '$name' must have scale 1" }
        }

        val shadowJson = json.getAsJsonObject("shadow")
        shadowResolution = shadowJson?.get("resolution")?.asInt ?: 2048
        shadowDistance = shadowJson?.get("distance")?.asFloat ?: 96f
        require(shadowResolution in 256..8192) { "Shadow resolution $shadowResolution is outside 256..8192" }
        require(shadowDistance >= 8f && shadowDistance <= 1024f) { "Shadow distance $shadowDistance is outside 8..1024" }

        val programs = json.getAsJsonObject("programs")
        shadow = programs?.getAsJsonObject("shadow")?.let(::programFiles)
        terrain = programs?.getAsJsonObject("gbuffers_terrain")?.let(::programFiles)

        passes = json.getAsJsonArray("passes")?.map { element ->
            val pass = element.asJsonObject
            val stage = pass.get("stage").asString
            require(stage in STAGES) { "Unknown pass stage '$stage' (expected one of $STAGES)" }
            val inputs = pass.getAsJsonArray("inputs")?.map { it.asString } ?: emptyList()
            val outputs = pass.getAsJsonArray("outputs").map { it.asString }
            require(outputs.isNotEmpty() && outputs.size <= MAX_COLOR_OUTPUTS) { "A $stage pass needs 1 to $MAX_COLOR_OUTPUTS outputs" }
            require(outputs.toSet().size == outputs.size) { "A $stage pass lists an output twice" }
            require(inputs.size <= MAX_INPUTS && inputs.toSet().size == inputs.size) { "A $stage pass needs at most $MAX_INPUTS distinct inputs" }
            require(outputs.map { if (it == MAIN_COLOR) 1f else buffers[it]?.scale }.toSet().size == 1) { "The outputs of a $stage pass must have the same scale" }
            require(SHADOW_DEPTH !in inputs || shadow != null) { "A $stage pass reads shadowtex0 but the pack has no shadow program" }
            for (name in outputs) require(name == MAIN_COLOR || name in buffers) { "Pass output '$name' is not a declared buffer" }
            for (name in inputs) {
                require(name == MAIN_COLOR || name == MAIN_DEPTH || name == SHADOW_DEPTH || name in buffers) { "Pass input '$name' is unknown" }
            }
            PassSpec(stage, pass.get("fragment").asString, inputs, outputs)
        } ?: emptyList()
    }

    private fun programFiles(json: JsonObject) = ProgramFiles(json.get("vertex").asString, json.get("fragment").asString)

    /** Text of a file in the pack folder. */
    fun read(file: String): String {
        val path = root.resolve(file).normalize()
        require(path.startsWith(root.normalize())) { "'$file' is outside the pack folder" }
        return Files.readString(path)
    }

    companion object {
        const val MAIN_COLOR = "colortex0"
        const val MAIN_DEPTH = "depthtex0"
        const val SHADOW_DEPTH = "shadowtex0"
        private val BUFFER_NAME = Regex("colortex([0-9]|1[0-5])")
        private val STAGES = setOf("deferred", "composite", "final")
        private const val MAX_COLOR_OUTPUTS = 8
        private const val MAX_INPUTS = 16
        private const val BUILT_IN_ROOT = "assets/metalcraft/shaderpacks"

        private fun parseFormat(name: String): GpuFormat = when (name.uppercase()) {
            "RGBA8" -> GpuFormat.RGBA8_UNORM
            "RGBA16F" -> GpuFormat.RGBA16_FLOAT
            "RGBA16" -> GpuFormat.RGBA16_UNORM
            "RG16F" -> GpuFormat.RG16_FLOAT
            "R16F" -> GpuFormat.R16_FLOAT
            "R8" -> GpuFormat.R8_UNORM
            "R11G11B10F" -> GpuFormat.RG11B10_FLOAT
            "RGB10A2" -> GpuFormat.RGB10A2_UNORM
            else -> throw IllegalArgumentException("Unknown buffer format '$name'")
        }

        /** Packs shipped inside the mod plus the folders in `<game dir>/shaderpacks`. Broken packs are skipped. */
        fun discover(): List<ShaderPack> {
            val found = ArrayList<ShaderPack>()
            val builtIn = FabricLoader.getInstance().getModContainer(MetalCraft.MOD_ID).flatMap { it.findPath(BUILT_IN_ROOT) }.orElse(null)
            for (root in listOfNotNull(builtIn, FabricLoader.getInstance().gameDir.resolve("shaderpacks"))) {
                if (!Files.isDirectory(root)) continue
                val folders = try {
                    Files.list(root).use { it.sorted().toList() }
                } catch (e: java.io.IOException) {
                    LOGGER.error("Could not list shader packs in {}: {}", root, e.message)
                    continue
                }
                run {
                    for (folder in folders) {
                        val manifest = folder.resolve("pack.json")
                        if (!Files.isRegularFile(manifest)) continue
                        val id = folder.fileName.toString().trimEnd('/')
                        if (found.any { it.id == id }) continue
                        try {
                            found.add(ShaderPack(id, folder, JsonParser.parseString(Files.readString(manifest)).asJsonObject))
                        } catch (e: Exception) {
                            LOGGER.error("Shader pack '{}' has an invalid pack.json: {}", id, e.message)
                        }
                    }
                }
            }
            return found
        }
    }
}
