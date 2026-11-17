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
 *   "blockIds": "blocks.properties",                     // optional: IDs for mc_Entity, see BlockIds
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
internal class ShaderPack private constructor(val id: String, private val root: Path, private val manifest: JsonObject) {
    /** True for a pack written by the GLSL converter (`"format": 2`); the fields below are then unused. */
    val isConverted: Boolean = manifest.get("format")?.asInt == 2
    private val json: JsonObject = if (isConverted) JsonObject() else manifest
    private val convertedSpecs = HashMap<String, ConvertedSpec>()

    init {
        // Reading the default dimension now rejects a broken manifest while packs are listed.
        if (isConverted) converted("world0")
    }

    /** The converted pack's programs for a dimension folder (`world0`, `world-1`, `world1`). */
    fun converted(dimension: String): ConvertedSpec = convertedSpecs.getOrPut(dimension) { ConvertedSpec(manifest, dimension) }

    val displayName: String = manifest.get("name")?.asString ?: id
    val includes: List<String> = json.getAsJsonArray("include")?.map { it.asString } ?: emptyList()
    val buffers: Map<String, BufferSpec>
    val gbufferTargets: List<String> = json.getAsJsonArray("gbuffers")?.map { it.asString } ?: emptyList()
    val shadowResolution: Int
    val shadowDistance: Float
    val shadow: ProgramFiles?
    val terrain: ProgramFiles?
    val passes: List<PassSpec>

    /** File with the pack's block IDs (see [BlockIds]), or null. */
    val blockIdsFile: String? = manifest.get("blockIds")?.asString
    /** Optional: `item.<id>=<item names>` lines, the IDs the pack sees in `heldItemId`. */
    val itemIdsFile: String? = manifest.get("itemIds")?.asString

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
    fun read(file: String): String = Files.readString(path(file))

    /** A file in the pack folder. */
    fun path(file: String): Path {
        val path = root.resolve(file).normalize()
        require(path.startsWith(root.normalize())) { "'$file' is outside the pack folder" }
        return path
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
            // Development runs point this at the project's own shaderpacks folder.
            val extra = System.getProperty("metalcraft.shaderpacks.dir")?.takeIf { it.isNotBlank() }?.let(Path::of)
            for (root in listOfNotNull(builtIn, FabricLoader.getInstance().gameDir.resolve("shaderpacks"), extra)) {
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

/**
 * The manifest of a pack produced by the GLSL converter (`src/tools`, CLAUDE.md section 4 M7). The converter
 * writes it; see `ShaderPackConverter.kt` for the conventions of the programs it describes.
 */
internal class ConvertedSpec(json: JsonObject, dimension: String) {
    // What differs between dimensions. A pack without programs for one uses those of the Overworld.
    private val part: JsonObject = json.getAsJsonObject("dimensions").let { it.getAsJsonObject(dimension) ?: it.getAsJsonObject("world0") }

    class Buffer(val index: Int, val format: GpuFormat, val clear: Boolean, val scale: Float)
    class Texture(val name: String, val compare: Boolean)
    class Program(
        val name: String, val kind: String, val vertex: String, val fragment: String, val textures: List<Texture>,
        val targets: IntArray, val blend: IntArray, val alphaTest: Float,
    )
    class Uniform(val name: String, val type: String, val offset: Int)
    class Image(val file: String, val repeat: Boolean)

    val buffers: Map<Int, Buffer> = json.getAsJsonObject("buffers").entrySet().associate { (name, value) ->
        val index = name.removePrefix("colortex").toInt()
        val buffer = value.asJsonObject
        index to Buffer(index, format(buffer.get("format").asString), buffer.get("clear")?.asBoolean ?: true, buffer.get("scale")?.asFloat ?: 1f)
    }
    val gbuffers: IntArray = part.getAsJsonArray("gbuffers").map { it.asInt }.toIntArray()
    /** The pack's colortex0 is the first attachment of the world passes, in place of the game image. */
    val primaryTarget: Boolean = json.get("primaryTarget")?.asBoolean ?: false
    val shadowResolution: Int = part.getAsJsonObject("shadow").get("resolution").asInt
    val shadowDistance: Float = part.getAsJsonObject("shadow").get("distance").asFloat
    val sunPathRotation: Float = part.getAsJsonObject("shadow").get("sunPathRotation").asFloat
    val images: Map<String, Image> = json.getAsJsonObject("textures").entrySet().associate { (name, value) ->
        name to Image(value.asJsonObject.get("file").asString, value.asJsonObject.get("repeat").asBoolean)
    }
    val passes: List<String> = part.getAsJsonArray("passes").map { it.asString }

    /** GLSL of the program Flywheel instances are drawn with (see `ConvertedPack.flywheel`), or null. */
    class Flywheel(val program: String, val vertex: String, val fragment: String)

    val flywheel: Flywheel? = part.getAsJsonObject("flywheel")?.let {
        Flywheel(it.get("program").asString, it.get("vertex").asString, it.get("fragment").asString)
    }

    /** The program that draws the sky as a full-screen pass before the world, or null. */
    val sky: String? = part.get("sky")?.asString
    val programs: Map<String, Program> = part.getAsJsonObject("programs").entrySet().associate { (name, value) ->
        val program = value.asJsonObject
        name to Program(
            name, program.get("kind").asString, program.get("vertex").asString, program.get("fragment").asString,
            program.getAsJsonArray("textures").map { Texture(it.asJsonObject.get("name").asString, it.asJsonObject.get("compare").asBoolean) },
            program.getAsJsonArray("targets").map { it.asInt }.toIntArray(),
            program.getAsJsonArray("blend")?.map { it.asInt }?.toIntArray() ?: IntArray(0),
            program.get("alphaTest")?.asFloat ?: -1f,
        )
    }
    /** Whether the pack wants ambient occlusion in the alpha of terrain colours instead of multiplied in. */
    val separateAo: Boolean = json.get("separateAo")?.asBoolean ?: false
    val uniformBlockSize: Int = json.get("uniformBlockSize").asInt
    /** Uniforms the pack computes with expressions, see [CustomUniforms]. */
    val customUniforms: List<CustomUniforms.Definition> = json.getAsJsonArray("customUniforms")?.map {
        val entry = it.asJsonObject
        CustomUniforms.Definition(
            entry.get("name").asString, entry.get("type").asString, entry.get("variable").asBoolean, entry.get("expression").asString,
        )
    } ?: emptyList()
    val uniforms: List<Uniform> = json.getAsJsonArray("uniforms").map {
        val uniform = it.asJsonObject
        Uniform(uniform.get("name").asString, uniform.get("type").asString, uniform.get("offset").asInt)
    }

    init {
        require(shadowResolution in 256..8192) { "Shadow resolution $shadowResolution is outside 256..8192" }
        require(shadowDistance >= 8f && shadowDistance <= 1024f) { "Shadow distance $shadowDistance is outside 8..1024" }
        require(uniformBlockSize in 16..65536) { "Uniform block size $uniformBlockSize is not plausible" }
        val extra = if (primaryTarget) gbuffers.count { it != 0 } else gbuffers.size
        require(extra <= 7) { "The world programs write more buffers than a pass can hold (8 with the first)" }
        for (index in gbuffers) require(index in buffers) { "World programs write colortex$index, which is not declared" }
    }

    private companion object {
        /** OpenGL names as packs write them. Metal has no three-channel formats, so those get a fourth. */
        /** OpenGL's name for a buffer format. Three-channel formats Metal lacks get a fourth channel. */
        fun format(name: String): GpuFormat = when (name) {
            "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT
            "RGBA8", "RGB8", "RGBA", "RGB", "RGB5_A1", "RGBA4", "RGB5", "RGBA2", "R3_G3_B2" -> GpuFormat.RGBA8_UNORM
            "RGBA8_SNORM", "RGB8_SNORM" -> GpuFormat.RGBA8_SNORM
            "RGBA16F", "RGB16F", "RGB9_E5" -> GpuFormat.RGBA16_FLOAT
            "RGBA32F", "RGB32F" -> GpuFormat.RGBA32_FLOAT
            "RGBA16", "RGB16", "RGBA12", "RGB12" -> GpuFormat.RGBA16_UNORM
            "RGBA16_SNORM", "RGB16_SNORM" -> GpuFormat.RGBA16_SNORM
            "RG16F" -> GpuFormat.RG16_FLOAT
            "RG32F" -> GpuFormat.RG32_FLOAT
            "RG16" -> GpuFormat.RG16_UNORM
            "RG16_SNORM" -> GpuFormat.RG16_SNORM
            "RG8" -> GpuFormat.RG8_UNORM
            "RG8_SNORM" -> GpuFormat.RG8_SNORM
            "R16F" -> GpuFormat.R16_FLOAT
            "R32F" -> GpuFormat.R32_FLOAT
            "R16" -> GpuFormat.R16_UNORM
            "R16_SNORM" -> GpuFormat.R16_SNORM
            "R8" -> GpuFormat.R8_UNORM
            "R8_SNORM" -> GpuFormat.R8_SNORM
            "RGB10_A2", "RGB10" -> GpuFormat.RGB10A2_UNORM
            "R8UI" -> GpuFormat.R8_UINT
            "R8I" -> GpuFormat.R8_SINT
            "RG8UI" -> GpuFormat.RG8_UINT
            "RG8I" -> GpuFormat.RG8_SINT
            "RGBA8UI", "RGB8UI" -> GpuFormat.RGBA8_UINT
            "RGBA8I", "RGB8I" -> GpuFormat.RGBA8_SINT
            "R16UI" -> GpuFormat.R16_UINT
            "R16I" -> GpuFormat.R16_SINT
            "RG16UI" -> GpuFormat.RG16_UINT
            "RG16I" -> GpuFormat.RG16_SINT
            "RGBA16UI", "RGB16UI" -> GpuFormat.RGBA16_UINT
            "RGBA16I", "RGB16I" -> GpuFormat.RGBA16_SINT
            "R32UI" -> GpuFormat.R32_UINT
            "R32I" -> GpuFormat.R32_SINT
            "RG32UI" -> GpuFormat.RG32_UINT
            "RG32I" -> GpuFormat.RG32_SINT
            "RGBA32UI", "RGB32UI" -> GpuFormat.RGBA32_UINT
            "RGBA32I", "RGB32I" -> GpuFormat.RGBA32_SINT
            else -> throw IllegalArgumentException("Unknown buffer format '$name'")
        }
    }
}
