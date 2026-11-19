package dev.skarch.metalcraft.tools

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.util.spvc.SpvcReflectedResource
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.system.exitProcess

/*
 * Porting aid for shader packs (CLAUDE.md section 4, M7). It reads the GLSL of an Iris/OptiFine pack and
 * writes the first version of a MetalCraft pack: one `.metal` file per shader stage plus `pack.json`.
 *
 *   GLSL (compatibility profile, loose uniforms)
 *     -> includes inlined, old built-ins mapped onto MetalCraft's vertex data and uniform block   (this file)
 *     -> SPIR-V                                                                                     (shaderc)
 *     -> Metal Shading Language                                                                     (SPIRV-Cross)
 *
 * The pack's options stay at their defaults. The output is a starting point that is fixed up by hand or,
 * preferably, by teaching this tool; it is not something the mod does at run time.
 *
 * Conventions of the generated programs (the engine side is `pipeline/ShaderPipeline.kt`):
 *  - Every plain uniform of the pack becomes a member of one block, `PackUniforms`, with the same layout in
 *    all programs. `pack.json` lists the members and their offsets; the engine fills them by name.
 *  - Textures keep their GLSL names. `pack.json` lists, per program, which texture sits at which index.
 *  - Positions, depth and images follow OpenGL as the pack expects. The game's depth buffer holds 1 - (OpenGL
 *    depth), so a world vertex shader ends by converting its depth and `gl_FragCoord.z` is converted back.
 *  - Programs that draw the world write to the union of their render targets, attached after the game's own
 *    image (which they leave alone). `mc_alphaTestRef` in the `DrawUniforms` block is the alpha test.
 */

private enum class Stage(val extension: String, val define: String) { VERTEX("vsh", "VERTEX_SHADER"), FRAGMENT("fsh", "FRAGMENT_SHADER") }

/** What a program draws, which decides where the old fixed-function inputs come from. */
private enum class Kind(val world: Boolean) {
    /** Chunk sections, with MetalCraft's extended terrain vertex. */
    TERRAIN(true),

    /** Entities, block entities and items: the game's entity vertex. */
    ENTITY(true),

    /** Particles: the game's particle vertex. */
    PARTICLE(true),

    /** The sun and the moon: position and texture coordinate only. */
    CELESTIAL(true),

    /**
     * Flywheel instances (Create's moving parts). Only a vertex template is written: the engine appends
     * Flywheel's own vertex code, which supplies the vertex, at run time.
     */
    FLYWHEEL(true),
    SHADOW(false),
    FULLSCREEN(false),
}

private class Uniform(val type: String, val name: String, val offset: Int)

private class ConvertException(message: String) : Exception(message)

private val STD140 = mapOf(
    // type to (size, alignment)
    "bool" to (4 to 4), "int" to (4 to 4), "float" to (4 to 4),
    "vec2" to (8 to 8), "ivec2" to (8 to 8),
    "vec3" to (12 to 16), "ivec3" to (12 to 16),
    "vec4" to (16 to 16), "ivec4" to (16 to 16),
    "mat3" to (48 to 16), "mat4" to (64 to 16),
)

private val UNIFORM_LINE = Regex("""^\s*uniform\s+(bool|int|float|i?vec[234]|mat[34])\s+([^;]+);.*$""")
private val OPTION_LINE = Regex("""^(\s*#define\s+(\w+)\s+)(\S+)(\s*//\s*\[.*)$""")
private val INTERFACE_LINE = Regex(
    """^\s*((?:(?:flat|smooth|noperspective|centroid|invariant)\s+)*)(in|out)\s+((?:(?:flat|smooth|noperspective|centroid|highp|mediump|lowp)\s+)*)(\w+)\s+([^;(){}=]+);\s*$""",
)
private val CONSTANT_LINE = Regex("""(?m)^[ \t]*(?:const[ \t]+\w+[ \t]+(\w+)[ \t]*=|(?:(?:flat|smooth|noperspective|centroid|in|out)[ \t]+)*(?:bool|int|float|i?vec[234]|mat[34])[ \t]+(\w+)[ \t]*[=;])""")
private val NEWER_BUILTINS = listOf("tanh", "sinh", "cosh", "asinh", "acosh", "atanh", "round", "roundEven", "trunc", "isnan", "isinf", "fma", "inverse", "transpose", "determinant", "modf")

/** Names Metal's shading language has taken: a pack's own function or variable of that name gets another. */
private val METAL_NAMES = listOf("bias", "length_squared", "distance_squared", "saturate", "rsqrt", "fmod", "fmin", "fmax", "select", "powr", "exp10", "sinpi", "cospi", "tanpi", "popcount", "rotate", "level", "gradient2d", "min_lod_clamp",
    // C++ keywords the translation to Metal lets through.
    "new", "delete", "typename", "operator", "friend", "virtual", "auto", "register", "try", "catch", "throw", "and", "or", "not", "xor", "nullptr", "constexpr")
private val STRUCT = Regex("""struct\s+(\w+)\s*\{([^}]*)}""")
private val UNDECLARED = Regex("""'(\w+)' : undeclared identifier""")
private val MATRIX_TYPE = Regex("""d?mat(\d)(?:x\d)?""")

/** A fragment output a pack declares itself instead of writing gl_FragData. */
private val PACK_OUTPUT = Regex("""(?m)^[ \t]*layout\s*\(\s*location\s*=\s*(\d+)\s*\)\s*out\s+(\w+)\s+(\w+)\s*;""")
private val INCLUDE_LINE = Regex("""^\s*#include\s+"([^"]+)"""")
private val PACK_ATTRIBUTE = Regex("""^\s*(?:attribute|in)\s+((?:[iu]?vec[234])|float|int)\s+(mc_Entity|mc_midTexCoord|at_tangent|at_midBlock)\s*;.*$""")
private val TARGETS_COMMENT = Regex("""/\*\s*(DRAWBUFFERS|RENDERTARGETS)\s*:\s*([0-9,\s]+?)\s*\*/""")
private val TARGETS_MARKER = Regex("""const int mc_targets((?:_\d+)+) = 0;""")
private val FRAG_DATA = Regex("""gl_FragData\s*\[\s*(\d+)\s*]""")

private const val BUFFER_NAME = """(colortex\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])"""
private val BUFFER_FORMAT = Regex("""const\s+int\s+${BUFFER_NAME}Format\s*=\s*(\w+)\s*;""")
private val BUFFER_CLEAR = Regex("""const\s+bool\s+${BUFFER_NAME}Clear\s*=\s*(true|false)\s*;""")
private val BUFFER_USE = Regex("""uniform\s+sampler2D\s+$BUFFER_NAME\s*;""")
private val DRAW_BUFFERS = Regex("""DRAWBUFFERS:\s*([0-7]+)""")
private val RENDER_TARGETS = Regex("""RENDERTARGETS:\s*([0-9, ]+)""")
private val SHADER_FILE = Regex("""\.(glsl|fsh|vsh|csh|gsh|inc)$""")

private val CUSTOM_UNIFORM = Regex("""(uniform|variable)\.(\w+)\.(\w+)\s*=\s*(.+)""")
private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

/** Uniforms every shader loader provides, with their types: the ones pack expressions refer to. */
private val STANDARD_UNIFORMS = mapOf(
    "frameCounter" to "int", "frameTime" to "float", "frameTimeCounter" to "float", "worldTime" to "int", "worldDay" to "int",
    "moonPhase" to "int", "sunAngle" to "float", "shadowAngle" to "float", "rainStrength" to "float", "wetness" to "float",
    "viewWidth" to "float", "viewHeight" to "float", "aspectRatio" to "float", "near" to "float", "far" to "float",
    "eyeAltitude" to "float", "isEyeInWater" to "int", "blindness" to "float", "darknessFactor" to "float",
    "darknessLightFactor" to "float", "nightVision" to "float", "screenBrightness" to "float", "playerMood" to "float",
    "eyeBrightness" to "ivec2", "eyeBrightnessSmooth" to "ivec2", "cameraPosition" to "vec3", "previousCameraPosition" to "vec3",
    "sunPosition" to "vec3", "moonPosition" to "vec3", "shadowLightPosition" to "vec3", "upPosition" to "vec3",
    "skyColor" to "vec3", "fogColor" to "vec3", "fogStart" to "float", "fogEnd" to "float", "centerDepthSmooth" to "float",
    "heldItemId" to "int", "heldItemId2" to "int", "heldBlockLightValue" to "int", "heldBlockLightValue2" to "int",
    "hideGUI" to "bool", "renderStage" to "int", "constantMood" to "float", "thunderStrength" to "float",
    "gbufferModelView" to "mat4", "gbufferModelViewInverse" to "mat4", "gbufferProjection" to "mat4",
    "gbufferProjectionInverse" to "mat4", "gbufferPreviousModelView" to "mat4", "gbufferPreviousProjection" to "mat4",
    "shadowModelView" to "mat4", "shadowModelViewInverse" to "mat4", "shadowProjection" to "mat4",
    "shadowProjectionInverse" to "mat4",
)

/** What stands in for a program a pack leaves out, most specific first (the order shader loaders use). */
private val FALLBACKS = linkedMapOf(
    "gbuffers_textured" to listOf("gbuffers_basic"),
    "gbuffers_terrain" to listOf("gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
    "gbuffers_water" to listOf("gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
    "gbuffers_entities" to listOf("gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"),
    "gbuffers_skytextured" to listOf("gbuffers_textured", "gbuffers_basic"),
    "gbuffers_skybasic" to listOf("gbuffers_basic"),
)

/** The names colour buffers 0 to 7 had before they were called colortex. */
private val LEGACY_BUFFERS = mapOf(
    "gcolor" to 0, "gdepth" to 1, "gnormal" to 2, "composite" to 3, "gaux1" to 4, "gaux2" to 5, "gaux3" to 6, "gaux4" to 7,
)
private val SHADOW_CONSTANT = Regex("""const\s+(?:int|float)\s+(shadowMapResolution|shadowDistance|sunPathRotation)\s*=\s*(-?[0-9.]+)\s*;""")

/** Members the engine needs in the block besides the pack's own uniforms. */
private val ENGINE_UNIFORMS = listOf("vec4" to "mc_fog", "mat4" to "mc_bob", "vec4" to "mc_fogColor")

/** Uniforms the converter's own code refers to. They are in every pack's block, declared by the pack or not. */
private val PRELUDE_UNIFORMS = listOf("gbufferModelView", "gbufferProjection", "shadowModelView", "shadowProjection")

private const val UNIFORM_BLOCK = "PackUniforms"
private const val SKY_PROGRAM = "gbuffers_skybasic"
private const val FLYWHEEL_SOURCE = "gbuffers_entities"

/** The pack's program folders: the Overworld (and every other dimension), the Nether, the End. */
private val DIMENSION_FOLDERS = listOf("world0", "world-1", "world1")

/** The game version the pack is converted for, as Iris numbers it (1.21.11). */
private const val MC_VERSION = 12111
private val PROPERTY_DIRECTIVE = Regex("""#(ifdef|ifndef|if|elif|else|endif|define|undef)\b(.*)""")
private val CONDITION_TOKEN = Regex("""\d+|\w+|&&|\|\||[=!<>]=|[-!<>()]""")
private val COMPARISONS = setOf("==", "!=", ">=", "<=", ">", "<")
private val PROGRAM_ENABLED = Regex("""program\.([\w/-]+)\.enabled\s*=\s*(.+)""")
private val TEXTURE_PROPERTY = Regex("""(texture|customTexture)\.([\w.]+)\s*=\s*(.+)""")
private val BLEND_PROPERTY = Regex("""blend\.(\w+)(?:\.(colortex\d+|gcolor|gdepth|gnormal|composite|gaux[1-4]))?\s*=\s*(.+)""")
private val BLEND_FACTORS = mapOf(
    "ZERO" to 0, "ONE" to 1, "SRC_COLOR" to 2, "ONE_MINUS_SRC_COLOR" to 3, "SRC_ALPHA" to 4, "ONE_MINUS_SRC_ALPHA" to 5,
    "DST_COLOR" to 6, "ONE_MINUS_DST_COLOR" to 7, "DST_ALPHA" to 8, "ONE_MINUS_DST_ALPHA" to 9, "SRC_ALPHA_SATURATE" to 10,
)
private val SWITCH_LINE = Regex("""^(\s*)(//\s*)?#define\s+(\w+)(\s*(?://.*)?)$""")
private val SIZE_PROPERTY = Regex("""size\.buffer\.(colortex\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])\s*=\s*([\d.]+)\s+([\d.]+)""")
private val MIPMAP_CONSTANT = Regex("""const\s+bool\s+(\w+)MipmapEnabled\s*=\s*true\s*;""")
private val WORD = Regex("""\b[A-Za-z_]\w*\b""")
private val OPTION_DEFINE = Regex("""\s*(//)?\s*#define\s+(\w+)[ \t]*([^\s/]*).*""")
private val OPTION_FILES = setOf("glsl", "fsh", "vsh", "csh", "gsh", "h", "inc", "settings", "frag", "vert")

/**
 * `<pack> [output folder] [port settings json]`. The pack is a shader pack zip, its unpacked folder or the
 * `shaders` folder inside it. Without an output folder the result goes to `shaderpacks/<pack name>`.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0].isEmpty()) {
        System.err.println("usage: <pack zip or folder> [output folder] [port settings json]")
        exitProcess(2)
    }
    val given = Path.of(args[0]).toAbsolutePath().normalize()
    if (!given.exists()) {
        System.err.println("$given does not exist")
        exitProcess(2)
    }
    val source = shadersFolder(if (given.isRegularFile()) unzip(given) else given)
    if (source == null) {
        System.err.println("No shaders folder found in $given")
        exitProcess(2)
    }
    val packName = given.name.removeSuffix(".zip").lowercase().replace(Regex("§.|[^a-z0-9]+"), "-").trim('-').ifEmpty { "pack" }
    val output = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of("shaderpacks", packName)
    // Port settings: the file given, or the ones this tool brings for the pack (by the output folder's name).
    val port = args.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(Files.readString(Path.of(it))).asJsonObject }
        ?: Converter::class.java.getResourceAsStream("/ports/${output.name}.json")?.use { JsonParser.parseString(String(it.readAllBytes())).asJsonObject }
        ?: JsonObject()
    println("Converting $source -> ${output.toAbsolutePath().normalize()}")
    val converter = Converter(source, output.toAbsolutePath().normalize(), port)
    exitProcess(if (converter.run()) 0 else 1)
}

/** The folder that holds the programs: the path itself, or a `shaders` folder up to two levels below it. */
private fun shadersFolder(root: Path): Path? {
    if (root.name == "shaders") return root
    return Files.walk(root, 3).use { paths -> paths.filter { Files.isDirectory(it) && it.name == "shaders" }.findFirst().orElse(null) }
}

private fun unzip(zip: Path): Path {
    val target = Files.createTempDirectory("metalcraft-pack")
    java.util.zip.ZipFile(zip.toFile()).use { file ->
        for (entry in file.entries()) {
            val path = target.resolve(entry.name).normalize()
            if (!path.startsWith(target)) continue
            if (entry.isDirectory) {
                Files.createDirectories(path)
            } else {
                Files.createDirectories(path.parent)
                file.getInputStream(entry).use { Files.copy(it, path) }
            }
        }
    }
    return target
}

private class Converter(private val source: Path, private val output: Path, private val port: JsonObject) {
    private val uniformTypes = LinkedHashMap<String, String>()
    /** The uniforms the program being assembled declares itself. */
    private var assembling = HashSet<String>()
    private val compiler = Shaderc.shaderc_compiler_initialize()
    private val options = Shaderc.shaderc_compile_options_initialize()

    init {
        Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2)
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true)
        Shaderc.shaderc_compile_options_set_auto_map_locations(options, true)
        // Keeps the names the reflection relies on, and readable MSL.
        Shaderc.shaderc_compile_options_set_generate_debug_info(options)
        Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero)
    }

    fun run(): Boolean {
        // Put every program of every dimension together first: that collects the uniforms, whose block has
        // the same layout everywhere.
        val dimensions = LinkedHashMap<String, List<Program>>()
        val skipped = sortedSetOf<String>()
        for (folder in DIMENSION_FOLDERS) {
            // A dimension's programs come from its folder; what is missing there is taken from the pack's root.
            val dimension = source.resolve(folder)
            val names = (programNames(dimension) + programNames(source)).toSortedSet()
            if (names.isEmpty()) continue
            val programs = ArrayList<Program>()
            // A program the pack does not have is stood in for by a more general one of its own.
            val files = LinkedHashMap<String, String>()
            for (name in names) files[name] = name
            for ((name, fallbacks) in FALLBACKS) {
                if (name !in files) fallbacks.firstOrNull { it in names }?.let { files[name] = it }
            }
            for ((name, file) in files) {
                val kind = kindOf(name)
                if (file in disabledPrograms || "$folder/$file" in disabledPrograms) {
                    println("off     $folder/$name (disabled by the pack's options)")
                } else if (kind == null) {
                    skipped.add(name)
                } else {
                    assembling = HashSet()
                    val vertex = assemble(dimension, file, Stage.VERTEX)
                    val fragment = assemble(dimension, file, Stage.FRAGMENT)
                    programs.add(Program(name, kind, vertex, fragment, assembling))
                }
            }
            dimensions[folder] = programs
        }
        if (dimensions.isEmpty()) throw ConvertException("$source has none of the folders $DIMENSION_FOLDERS")
        val custom = customUniforms()
        // An expression can use a standard uniform no shader declares. It joins the block so the engine has it.
        for (definition in custom) {
            for (word in IDENTIFIER.findAll(definition.get("expression").asString)) {
                STANDARD_UNIFORMS[word.value]?.let { uniformTypes.putIfAbsent(word.value, it) }
            }
        }
        val uniforms = layoutUniforms()

        Files.createDirectories(output)
        var failures = 0
        var total = 0
        val manifestDimensions = JsonObject()
        for ((folder, programs) in dimensions) {
            println("---- $folder")
            total += programs.size
            val (dimensionManifest, failed) = convertDimension(folder, programs, uniforms)
            failures += failed
            manifestDimensions.add(folder, dimensionManifest)
        }

        val manifest = JsonObject()
        manifest.addProperty("format", 2)
        manifest.addProperty("primaryTarget", true)
        manifest.addProperty("name", port.get("name")?.asString ?: source.parent.name)
        manifest.add("buffers", buffers())
        manifest.add("textures", textures())
        blockIds()?.let { manifest.addProperty("blockIds", it) }
        itemIds()?.let { manifest.addProperty("itemIds", it) }
        port.get("separateAo")?.let { manifest.add("separateAo", it) }
        manifest.add("dimensions", manifestDimensions)
        manifest.add("customUniforms", JsonArray().also { array -> custom.forEach(array::add) })
        manifest.addProperty("uniformBlockSize", uniforms.size)
        manifest.add("uniforms", JsonArray().also { array ->
            for (uniform in uniforms.members) {
                array.add(JsonObject().also {
                    it.addProperty("name", uniform.name)
                    it.addProperty("type", uniform.type)
                    it.addProperty("offset", uniform.offset)
                })
            }
        })
        Files.writeString(output.resolve("pack.json"), GsonBuilder().setPrettyPrinting().create().toJson(manifest))
        println("converted ${total - failures} of $total programs, ${uniforms.members.size} uniforms (${uniforms.size} bytes)")
        if (skipped.isNotEmpty()) println("not handled yet: $skipped")
        return failures == 0
    }

    /** Converts the programs of one dimension into `<output>/<folder>/` and returns its part of the manifest. */
    private fun convertDimension(folder: String, programs: List<Program>, uniforms: UniformLayout): Pair<JsonObject, Int> {
        val directory = output.resolve(folder)
        val debug = directory.resolve("_glsl")
        Files.createDirectories(debug)
        var failures = 0
        val finished = LinkedHashMap<Program, Pair<Finished, Finished>>()
        for (program in programs) {
            try {
                finished[program] = finish(program, Stage.VERTEX, program.vertex, uniforms) to finish(program, Stage.FRAGMENT, program.fragment, uniforms)
            } catch (e: ConvertException) {
                failures++
                report(program, e)
            }
        }
        // World programs share one set of attachments: the game's image, then every target any of them writes.
        val gbufferTargets = finished.filter { it.key.kind.world }.flatMap { it.value.second.targets }.toSortedSet().toList()
        // One buffer is the first attachment of the world passes (where the game's image would be); the other
        // buffers they write follow it. It is colortex0 unless leaving that out is what makes the rest fit.
        val primary = if (0 in gbufferTargets || gbufferTargets.size < MAX_ATTACHMENTS) 0 else gbufferTargets.first()
        val extraTargets = gbufferTargets.filter { it != primary }

        val manifestPrograms = JsonObject()
        val shadowConstants = HashMap<String, String>()
        for ((program, firstStages) in finished) {
            var stages = firstStages
            var attempt = 0
            while (true) try {
                val targets = stages.second.targets
                val locations = when {
                    !program.kind.world -> targets.indices.toList()
                    else -> targets.map { if (it == primary) 0 else 1 + extraTargets.indexOf(it) }
                }
                val linked = link(stages.first.text, stages.second.text)
                val vertexGlsl = linked.first
                val own = stages.second.ownOutputs
                var fragmentGlsl = linked.second.replaceFirst(
                    "#version 450\n",
                    "#version 450\n" + locations.withIndex().filter { it.index !in own }
                        .joinToString("") { (i, location) -> "layout(location = $location) out vec4 mc_FragData_$i;\n" },
                )
                for (index in own.keys) fragmentGlsl = fragmentGlsl.replace("MC_LOCATION_$index)", "${locations[index]})")
                for (match in SHADOW_CONSTANT.findAll(fragmentGlsl)) shadowConstants.putIfAbsent(match.groupValues[1], match.groupValues[2])
                Files.writeString(debug.resolve("${program.name}.vsh.glsl"), vertexGlsl)
                Files.writeString(debug.resolve("${program.name}.fsh.glsl"), fragmentGlsl)
                val vertexSpirv = compile("${program.name}.vsh", vertexGlsl, Stage.VERTEX)
                try {
                    val fragmentSpirv = compile("${program.name}.fsh", fragmentGlsl, Stage.FRAGMENT)
                    try {
                        val translated = translate(program, vertexSpirv, fragmentSpirv)
                        Files.writeString(directory.resolve("${program.name}.vsh.metal"), translated.vertexMsl)
                        Files.writeString(directory.resolve("${program.name}.fsh.metal"), translated.fragmentMsl)
                        val entry = JsonObject()
                        entry.addProperty("kind", program.kind.name.lowercase())
                        entry.addProperty("vertex", "$folder/${program.name}.vsh.metal")
                        entry.addProperty("fragment", "$folder/${program.name}.fsh.metal")
                        entry.add("textures", JsonArray().also { array ->
                            for (texture in translated.textures) {
                                array.add(JsonObject().also {
                                    it.addProperty("name", texture)
                                    // A sampler2DShadow: the engine binds it with a comparing sampler.
                                    it.addProperty("compare", Regex("depth2d<float> $texture\\b").containsMatchIn(translated.vertexMsl + translated.fragmentMsl))
                                    it.addProperty("volume", Regex("texture3d<float> $texture\\b").containsMatchIn(translated.vertexMsl + translated.fragmentMsl))
                                })
                            }
                        })
                        entry.add("targets", JsonArray().also { array -> targets.forEach(array::add) })
                        port.getAsJsonObject("blend")?.get(program.name)?.let { entry.add("blend", it) }
                        val mipmaps = MIPMAP_CONSTANT.findAll(fragmentGlsl).mapNotNull { LEGACY_BUFFERS[it.groupValues[1]] ?: it.groupValues[1].removePrefix("colortex").toIntOrNull() }.toSortedSet()
                        if (mipmaps.isNotEmpty()) entry.add("mipmaps", JsonArray().also { array -> mipmaps.forEach(array::add) })
                        packBlend["$folder/${program.name}"]?.let { blend ->
                            // "all" present (even empty, for off): the pack sets the whole program's blending.
                            if ("all" in blend) entry.addProperty("blendOff", true)
                            entry.add("blendFunctions", JsonObject().also { functions ->
                                for ((buffer, factors) in blend) functions.add(buffer, JsonArray().also { array -> factors.forEach(array::add) })
                            })
                        }
                        port.getAsJsonObject("alphaTest")?.get(program.name)?.let { entry.add("alphaTest", it) }
                        manifestPrograms.add(program.name, entry)
                        println("ok      ${program.name}  textures=${translated.textures}  targets=$targets")
                    } finally {
                        MemoryUtil.memFree(fragmentSpirv)
                    }
                } finally {
                    MemoryUtil.memFree(vertexSpirv)
                }
                break
            } catch (e: ConvertException) {
                // A pack may use a standard uniform a stage never declares (a driver that tolerates it, or a
                // declaration another stage has): give the program the engine's and try again.
                val missing = UNDECLARED.findAll(e.message ?: "").map { it.groupValues[1] }
                    .filter { name -> name !in program.uniformNames && uniforms.members.any { it.name == name } }.toSet()
                if (missing.isEmpty() || attempt++ >= 3) {
                    failures++
                    report(program, e)
                    break
                }
                program.uniformNames.addAll(missing)
                try {
                    stages = finish(program, Stage.VERTEX, program.vertex, uniforms) to finish(program, Stage.FRAGMENT, program.fragment, uniforms)
                } catch (again: ConvertException) {
                    failures++
                    report(program, again)
                    break
                }
            }
        }

        val manifest = JsonObject()
        manifest.add("gbuffers", JsonArray().also { array -> gbufferTargets.forEach(array::add) })
        manifest.addProperty("primaryBuffer", primary)
        manifest.add("shadow", JsonObject().also {
            it.addProperty("resolution", shadowConstants["shadowMapResolution"]?.toInt() ?: 1024)
            it.addProperty("distance", shadowConstants["shadowDistance"]?.toFloat() ?: 160f)
            it.addProperty("sunPathRotation", shadowConstants["sunPathRotation"]?.toFloat() ?: 0f)
        })
        manifest.add("passes", JsonArray().also { array ->
            manifestPrograms.keySet().filter { kindOf(it) == Kind.FULLSCREEN && it != SKY_PROGRAM }.sortedBy(::passOrder).forEach(array::add)
        })
        if (manifestPrograms.has(SKY_PROGRAM)) manifest.addProperty("sky", SKY_PROGRAM)
        // Flywheel instances are drawn with the entity program's fragment stage and a vertex stage the
        // engine completes at run time, so both are kept as GLSL.
        val entities = programs.firstOrNull { it.name == FLYWHEEL_SOURCE }
        if (entities != null && manifestPrograms.has(FLYWHEEL_SOURCE)) {
            try {
                val template = finish(Program("flywheel", Kind.FLYWHEEL, entities.vertex, entities.fragment, entities.uniformNames), Stage.VERTEX, entities.vertex, uniforms)
                Files.writeString(debug.resolve("flywheel.vsh.glsl"), template.text)
                manifest.add("flywheel", JsonObject().also {
                    it.addProperty("program", FLYWHEEL_SOURCE)
                    it.addProperty("vertex", "$folder/_glsl/flywheel.vsh.glsl")
                    it.addProperty("fragment", "$folder/_glsl/$FLYWHEEL_SOURCE.fsh.glsl")
                })
            } catch (e: ConvertException) {
                failures++
                report(entities, e)
            }
        }
        manifest.add("programs", manifestPrograms)
        return manifest to failures
    }

    /**
     * The uniforms the pack computes with expressions (`uniform.<type>.<name> = ...` and `variable.<type>.<name>
     * = ...` in shaders.properties), in the order the pack lists them. The engine evaluates them every frame.
     */
    private fun customUniforms(): List<JsonObject> {
        val file = source.resolve("shaders.properties")
        if (!file.exists()) return emptyList()
        val result = ArrayList<JsonObject>()
        val seen = HashSet<String>()
        // A trailing backslash continues a line.
        // A trailing backslash continues a line, but not into a preprocessor directive.
        val joined = Files.readString(file).replace(Regex("""\\[ \t]*\r?\n(?![ \t]*#)\s*"""), " ").replace(Regex("""\\[ \t]*\r?\n"""), "\n")
        val lines = activeLines(joined.lines(), HashMap(packDefines))
        for (line in lines) {
            val match = CUSTOM_UNIFORM.matchEntire(line.trim()) ?: continue
            val name = match.groupValues[3]
            if (!seen.add(name)) continue
            result.add(
                JsonObject().apply {
                    addProperty("name", name)
                    addProperty("type", match.groupValues[2])
                    addProperty("variable", match.groupValues[1] == "variable")
                    addProperty("expression", match.groupValues[4].trim())
                },
            )
        }
        return result
    }

    private fun programNames(folder: Path): List<String> {
        if (!Files.isDirectory(folder)) return emptyList()
        return Files.list(folder).use { files ->
            files.filter { it.isRegularFile() && it.name.endsWith(".fsh") }.map { it.name.removeSuffix(".fsh") }.toList()
        }
    }

    private fun report(program: Program, error: ConvertException) {
        println("FAILED  ${program.name}\n${error.message!!.lines().take(12).joinToString("\n") { "          $it" }}")
    }

    /** prepare, prepare1, ... then deferred, deferred1, ... then composite, composite1, ... then final. */
    private fun passOrder(program: String): Int {
        val group = if (program.startsWith("prepare")) -1 else if (program.startsWith("deferred")) 0 else if (program.startsWith("composite")) 1 else 2
        return group * 1000 + (program.dropWhile { !it.isDigit() }.toIntOrNull() ?: 0)
    }

    /** The colour buffers the pack declares in comments and constants (Iris reads them the same way). */
    private fun buffers(): JsonObject {
        val text = Files.walk(source).use { files ->
            files.filter { it.isRegularFile() && SHADER_FILE.containsMatchIn(it.name) }.sorted().map { Files.readString(it) }.toList().joinToString("\n")
        }
        val buffers = sortedMapOf<Int, JsonObject>()
        fun index(name: String): Int = LEGACY_BUFFERS[name] ?: name.removePrefix("colortex").toInt()
        // Every buffer a program reads or writes exists, declared or not. Undeclared ones are RGBA8 and cleared.
        for (match in BUFFER_USE.findAll(text)) buffers.getOrPut(index(match.groupValues[1])) { JsonObject() }
        for (match in DRAW_BUFFERS.findAll(text)) for (digit in match.groupValues[1]) buffers.getOrPut(digit - '0') { JsonObject() }
        for (match in RENDER_TARGETS.findAll(text)) {
            for (target in match.groupValues[1].split(',')) target.trim().toIntOrNull()?.let { buffers.getOrPut(it) { JsonObject() } }
        }
        for (match in BUFFER_FORMAT.findAll(text)) {
            buffers.getOrPut(index(match.groupValues[1])) { JsonObject() }.addProperty("format", match.groupValues[2])
        }
        for (match in BUFFER_CLEAR.findAll(text)) {
            buffers[index(match.groupValues[1])]?.addProperty("clear", match.groupValues[2] == "true")
        }
        buffers.getOrPut(0) { JsonObject() }
        for (buffer in buffers.values) if (!buffer.has("format")) buffer.addProperty("format", "RGBA8")
        // size.buffer.<name> = <width> <height>: whole numbers are pixels, fractions are parts of the view.
        val properties = source.resolve("shaders.properties")
        if (properties.exists()) {
            for (line in activeLines(properties, HashMap(packDefines))) {
                val match = SIZE_PROPERTY.matchEntire(line) ?: continue
                val buffer = buffers.getOrPut(index(match.groupValues[1])) { JsonObject().also { it.addProperty("format", "RGBA8") } }
                val (w, h) = match.groupValues[2] to match.groupValues[3]
                if ('.' in w || '.' in h) {
                    buffer.addProperty("scale", w.toFloat())
                    buffer.addProperty("scaleY", h.toFloat())
                } else {
                    buffer.addProperty("width", w.toInt())
                    buffer.addProperty("height", h.toInt())
                }
            }
        }
        val result = JsonObject()
        for ((index, buffer) in buffers) {
            if (index > 15) continue
            port.getAsJsonObject("scales")?.get("colortex$index")?.let { buffer.add("scale", it) }
            result.add("colortex$index", buffer)
        }
        return result
    }

    /**
     * The pack's block ID table with its version conditions resolved (`#if MC_VERSION >= n`, `#else`, `#endif`;
     * every other line starting with `#` is a comment). Returns the name of the file written, or null.
     */
    private fun blockIds(): String? = idTable("block.properties", "block.", "blocks.properties")

    /** The pack's item IDs (`heldItemId`), resolved the same way. */
    private fun itemIds(): String? = idTable("item.properties", "item.", "items.properties")

    private fun idTable(name: String, prefix: String, outputName: String): String? {
        val file = source.resolve(name)
        if (!file.exists()) return null
        val result = StringBuilder()
        val macros = HashMap(packDefines)
        // A trailing backslash continues an entry on the next line.
        val joined = Files.readString(file).replace(Regex("""\\[ \t]*\r?\n(?![ \t]*#)\s*"""), " ").replace(Regex("""\\[ \t]*\r?\n"""), "\n")
        for (line in activeLines(joined.lines(), macros)) {
            if (!line.startsWith(prefix)) continue
            var text = line
            // Macros may expand to other macros (Photon's tag lists).
            repeat(4) { text = WORD.replace(text) { macros[it.value] ?: it.value } }
            result.append(text).append('\n')
        }
        Files.writeString(output.resolve(outputName), result.toString())
        return outputName
    }

    /** The lines of a property file its `#if` blocks leave in, trimmed. [macros] collects the file's `#define`s. */
    private fun activeLines(file: Path, macros: MutableMap<String, String>): List<String> = activeLines(Files.readAllLines(file), macros)

    private fun activeLines(lines: List<String>, macros: MutableMap<String, String>): List<String> {
        val result = ArrayList<String>()
        // One frame per open #if: [the enclosing block is active, a branch was already taken, this branch is active].
        val frames = ArrayDeque<BooleanArray>()
        fun active() = frames.all { it[2] }
        fun open(condition: Boolean) {
            val outer = active()
            frames.addLast(booleanArrayOf(outer, outer && condition, outer && condition))
        }
        for (line in lines) {
            val trimmed = line.trim()
            val directive = PROPERTY_DIRECTIVE.matchEntire(trimmed)
            when {
                directive != null -> {
                    val argument = directive.groupValues[2].trim()
                    when (directive.groupValues[1]) {
                        "ifdef" -> open(argument in macros)
                        "ifndef" -> open(argument !in macros)
                        "if" -> open(Condition(argument, macros).evaluate() != 0L)
                        "elif" -> frames.lastOrNull()?.let {
                            val now = it[0] && !it[1] && Condition(argument, macros).evaluate() != 0L
                            it[2] = now
                            it[1] = it[1] || now
                        }
                        "else" -> frames.lastOrNull()?.let {
                            it[2] = it[0] && !it[1]
                            it[1] = true
                        }
                        "endif" -> frames.removeLastOrNull()
                        "define" -> if (active()) {
                            val parts = argument.split(Regex("\\s+"), 2)
                            macros[parts[0]] = parts.getOrElse(1) { "" }
                        }
                        "undef" -> if (active()) macros.remove(argument)
                    }
                }
                trimmed.startsWith("#") || trimmed.isEmpty() -> {}
                active() -> result.add(trimmed)
            }
        }
        return result
    }

    /**
     * The programs the pack switches off (`program.<name>.enabled = <condition on its options>` in
     * shaders.properties), as `<folder>/<name>` or just `<name>`.
     */
    private val disabledPrograms: Set<String> by lazy {
        val file = source.resolve("shaders.properties")
        val result = HashSet<String>()
        if (file.exists()) {
            val macros = HashMap(packDefines)
            for (line in activeLines(file, macros)) {
                val match = PROGRAM_ENABLED.matchEntire(line) ?: continue
                if (Condition(match.groupValues[2], macros, flags = true).evaluate() == 0L) result.add(match.groupValues[1])
            }
        }
        result
    }

    /**
     * The blending the pack sets per program (`blend.<program> = off` or four factors, `blend.<program>.<buffer>
     * = ...`): `<folder>/<program>` to buffer index (`all` for every target) and Metal's factor codes. A program
     * named here blends nothing else.
     */
    private val packBlend: Map<String, Map<String, List<Int>>> by lazy {
        val result = HashMap<String, HashMap<String, List<Int>>>()
        val file = source.resolve("shaders.properties")
        if (file.exists()) {
            for (line in activeLines(file, HashMap(packDefines))) {
                val match = BLEND_PROPERTY.matchEntire(line) ?: continue
                val value = match.groupValues[3].trim()
                for (folder in DIMENSION_FOLDERS) {
                    val entry = result.getOrPut("$folder/${match.groupValues[1]}") { HashMap() }
                    val buffer = match.groupValues[2].ifEmpty { "all" }
                    val key = if (buffer == "all") buffer else (LEGACY_BUFFERS[buffer] ?: buffer.removePrefix("colortex").toInt()).toString()
                    // No factors: that buffer (or with "all" the program) does not blend.
                    if (value.equals("off", ignoreCase = true)) {
                        entry[key] = emptyList()
                        continue
                    }
                    val factors = value.split(Regex("\\s+")).map { BLEND_FACTORS[it.uppercase()] }
                    if (factors.size != 4 || factors.any { it == null }) {
                        println("warning: blend setting not understood: $line")
                        continue
                    }
                    entry[key] = factors.map { it!! }
                }
            }
        }
        result
    }

    /**
     * The macros a pack's property files can test: the defaults of its options (`#define NAME value` anywhere in
     * the pack, `//#define NAME` meaning off), the port's overrides and the game version.
     */
    private val packDefines: Map<String, String> by lazy {
        val defines = HashMap<String, String>()
        val off = HashSet<String>()
        Files.walk(source).use { files ->
            for (file in files) {
                if (!Files.isRegularFile(file) || file.name.substringAfterLast('.') !in OPTION_FILES) continue
                val lines = try { Files.readAllLines(file) } catch (e: java.io.IOException) { continue }
                for (line in lines) {
                    val match = OPTION_DEFINE.matchEntire(line) ?: continue
                    if (match.groupValues[1].isEmpty()) defines.putIfAbsent(match.groupValues[2], match.groupValues[3])
                    else off.add(match.groupValues[2])
                }
            }
        }
        for (name in off) defines.remove(name)
        port.getAsJsonObject("options")?.entrySet()?.forEach { (name, value) ->
            when (value.asString) {
                "false" -> defines.remove(name)
                "true" -> defines[name] = ""
                else -> defines[name] = value.asString
            }
        }
        defines["MC_VERSION"] = MC_VERSION.toString()
        // What Iris defines for every file, as far as packs test it in their property files.
        for (name in listOf("IS_IRIS", "MC_OS_MAC", "MC_GL_VENDOR_APPLE", "MC_GL_RENDERER_APPLE", "MC_NORMAL_MAP", "MC_SPECULAR_MAP")) defines[name] = ""
        defines["IRIS_VERSION"] = "11000"
        defines
    }

    /** A preprocessor condition: integers, macros, `defined`, `!`, comparisons, `&&`, `||` and parentheses. */
    private class Condition(text: String, private val macros: Map<String, String>, private val flags: Boolean = false) {
        private val tokens = CONDITION_TOKEN.findAll(text).map { it.value }.toList()
        private var at = 0

        fun evaluate(): Long = or()

        private fun peek() = tokens.getOrNull(at)
        private fun or(): Long {
            var value = and()
            while (peek() == "||") { at++; val right = and(); value = if (value != 0L || right != 0L) 1 else 0 }
            return value
        }
        private fun and(): Long {
            var value = compare()
            while (peek() == "&&") { at++; val right = compare(); value = if (value != 0L && right != 0L) 1 else 0 }
            return value
        }
        private fun compare(): Long {
            val left = unary()
            val operator = peek()
            if (operator !in COMPARISONS) return left
            at++
            val right = unary()
            val result = when (operator) {
                "==" -> left == right
                "!=" -> left != right
                ">=" -> left >= right
                "<=" -> left <= right
                ">" -> left > right
                else -> left < right
            }
            return if (result) 1 else 0
        }
        private fun unary(): Long {
            val token = tokens.getOrNull(at++) ?: return 0
            return when {
                token == "!" -> if (unary() == 0L) 1 else 0
                token == "-" -> -unary()
                token == "(" -> or().also { at++ }
                token == "defined" -> {
                    val parenthesised = peek() == "("
                    if (parenthesised) at++
                    val name = tokens.getOrNull(at++)
                    if (parenthesised) at++
                    if (name in macros) 1 else 0
                }
                token[0].isDigit() -> token.toLongOrNull() ?: 0
                token == "true" -> 1
                token == "false" -> 0
                // In shaders.properties a name stands for whether the option is on.
                flags -> if (token in macros) 1 else 0
                else -> macros[token]?.trim()?.toLongOrNull() ?: 0
            }
        }
    }

    /**
     * Image files the pack binds in place of buffers (`texture.<stage>.<buffer>`, `texture.noise` and
     * `customTexture.<name>` in shaders.properties, or the port's `textures`, which win). They are copied next
     * to the converted shaders. Keys are `<stage>.<buffer>`, `noisetex` or the sampler's name.
     */
    private fun textures(): JsonObject {
        val result = JsonObject()
        fun add(key: String, file: String, raw: List<String>, repeat: Boolean?, linear: Boolean?) {
            val entry = JsonObject()
            var blur = raw.isNotEmpty()
            var clamp = false
            if (file.contains(':')) {
                // A texture of the game: the engine reads it from the resource packs.
                entry.addProperty("file", file)
            } else {
                val path = source.resolve(file.removePrefix("/"))
                if (!path.exists()) {
                    println("warning: $key names $file, which the pack does not have")
                    return
                }
                val target = output.resolve("textures").resolve(path.name)
                Files.createDirectories(target.parent)
                Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                entry.addProperty("file", "textures/" + target.name)
                val meta = path.resolveSibling(path.name + ".mcmeta")
                if (meta.exists()) {
                    val text = Files.readString(meta)
                    Regex(""""blur"\s*:\s*(true|false)""").find(text)?.let { blur = it.groupValues[1] == "true" }
                    Regex(""""clamp"\s*:\s*(true|false)""").find(text)?.let { clamp = it.groupValues[1] == "true" }
                }
            }
            entry.addProperty("repeat", repeat ?: !clamp)
            entry.addProperty("linear", linear ?: blur)
            if (raw.isNotEmpty()) {
                // TEXTURE_3D RGB8 64 64 64 RGB UNSIGNED_BYTE: type, internal format, size, pixel format, pixel type.
                val sizes = raw.drop(2).takeWhile { it.toIntOrNull() != null }.map { it.toInt() }
                val rest = raw.drop(2 + sizes.size)
                if (raw[0] !in setOf("TEXTURE_2D", "TEXTURE_3D") || sizes.size < 2 || rest.size < 2) {
                    println("warning: $key has a texture definition that is not understood: $raw")
                    return
                }
                entry.addProperty("volume", raw[0] == "TEXTURE_3D")
                entry.addProperty("format", raw[1])
                entry.addProperty("width", sizes[0])
                entry.addProperty("height", sizes[1])
                entry.addProperty("depth", sizes.getOrElse(2) { 1 })
                entry.addProperty("pixel", rest[0])
                entry.addProperty("pixelType", rest[1])
            }
            result.add(key, entry)
        }
        port.getAsJsonObject("textures")?.entrySet()?.forEach { (name, value) ->
            add(name, value.asJsonObject.get("file").asString, emptyList(), value.asJsonObject.get("repeat")?.asBoolean ?: false, true)
        }
        val properties = source.resolve("shaders.properties")
        if (properties.exists()) {
            for (line in activeLines(properties, HashMap(packDefines))) {
                val match = TEXTURE_PROPERTY.matchEntire(line) ?: continue
                val words = match.groupValues[3].trim().split(Regex("\\s+"))
                val key = when {
                    match.groupValues[1] == "customTexture" -> match.groupValues[2]
                    match.groupValues[2] == "noise" -> "noisetex"
                    // texture.deferred.colortex6.1: a trailing number is not part of the name.
                    else -> match.groupValues[2].replace(Regex("""\.\d+$"""), "")
                }
                if (port.getAsJsonObject("textures")?.has(key) == true) continue
                add(key, words[0], words.drop(1), null, null)
            }
        }
        return result
    }

    private class Program(val name: String, val kind: Kind, val vertex: String, val fragment: String, val uniformNames: MutableSet<String>)

    private fun kindOf(program: String): Kind? = when {
        program == "shadow" -> Kind.SHADOW
        program == "gbuffers_terrain" || program == "gbuffers_water" -> Kind.TERRAIN
        program == "gbuffers_entities" || program == "gbuffers_entities_translucent" -> Kind.ENTITY
        program == "gbuffers_textured" -> Kind.PARTICLE
        program == "gbuffers_skytextured" -> Kind.CELESTIAL
        program.startsWith("prepare") || program.startsWith("deferred") || program.startsWith("composite") || program == "final" -> Kind.FULLSCREEN
        // The sky only depends on the view direction, so it is drawn as a full-screen pass before the world.
        program == SKY_PROGRAM -> Kind.FULLSCREEN
        else -> null
    }

    // ---- putting a program's GLSL together ------------------------------------------------------------------

    /** The stage's source with its includes inlined, the pack's plain uniforms collected and taken out. */
    private fun assemble(dimension: Path, program: String, stage: Stage): String {
        val file = dimension.resolve("$program.${stage.extension}").takeIf { it.exists() } ?: source.resolve("$program.${stage.extension}")
        if (!file.exists()) throw ConvertException("$file does not exist")
        val text = StringBuilder()
        inline(file, text, 0)
        val result = StringBuilder(text.length)
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("#version") || trimmed.startsWith("#extension") -> result.append('\n')
                PACK_ATTRIBUTE.matches(line) -> {
                    val (type, name) = PACK_ATTRIBUTE.matchEntire(line)!!.destructured
                    if (type != "vec4") result.append("#undef $name\n#define $name $type(mc_in_$name)")
                    result.append('\n')
                }
                else -> {
                    val uniform = UNIFORM_LINE.matchEntire(line)
                    if (uniform != null) {
                        for (declarator in splitTopLevel(uniform.groupValues[2])) {
                            val name = declarator.substringBefore('=').trim()
                            assembling.add(name)
                            val previous = uniformTypes.put(name, uniform.groupValues[1])
                            if (previous != null && previous != uniform.groupValues[1]) {
                                throw ConvertException("uniform $name is declared as $previous and as ${uniform.groupValues[1]}")
                            }
                        }
                        result.append('\n')
                    } else {
                        result.append(TARGETS_COMMENT.replace(line) { match ->
                            val list = match.groupValues[2].trim()
                            val targets = if (match.groupValues[1] == "DRAWBUFFERS") list.map { it.toString() } else list.split(',').map { it.trim() }
                            "const int mc_targets_${targets.joinToString("_")} = 0;"
                        }).append('\n')
                    }
                }
            }
        }
        return result.toString()
    }

    /**
     * A pack option is a `#define NAME value //[choices]` line. The port's `options` object replaces the
     * value of the ones it names; all others keep the pack's default.
     */
    private fun option(line: String): String {
        val options = port.getAsJsonObject("options") ?: return line
        // An on/off option is a `#define NAME` line, commented out when off.
        SWITCH_LINE.matchEntire(line)?.let { switch ->
            val wanted = options.get(switch.groupValues[3])?.asString ?: return line
            return when (wanted) {
                "true" -> switch.groupValues[1] + "#define " + switch.groupValues[3] + switch.groupValues[4]
                "false" -> switch.groupValues[1] + "//#define " + switch.groupValues[3] + switch.groupValues[4]
                else -> line
            }
        }
        val match = OPTION_LINE.find(line) ?: return line
        val value = options.get(match.groupValues[2]) ?: return line
        return match.groupValues[1] + value.asString + match.groupValues[4]
    }

    private fun inline(file: Path, into: StringBuilder, depth: Int) {
        if (depth > 16) throw ConvertException("#include nesting is too deep at $file")
        for (line in Files.readAllLines(file)) {
            val include = INCLUDE_LINE.find(line)
            if (include == null) {
                into.append(option(line)).append('\n')
                continue
            }
            val path = include.groupValues[1]
            val target = if (path.startsWith("/")) source.resolve(path.substring(1)) else file.parent.resolve(path)
            if (!target.exists()) throw ConvertException("$file includes $path, which does not exist")
            inline(target.normalize(), into, depth + 1)
        }
    }

    private fun splitTopLevel(list: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in list.withIndex()) {
            when (c) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    parts.add(list.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(list.substring(start))
        return parts
    }

    private class UniformLayout(val members: List<Uniform>, val size: Int, val glsl: String)

    private fun layoutUniforms(): UniformLayout {
        val members = ArrayList<Uniform>()
        var offset = 0
        val glsl = StringBuilder("layout(std140) uniform $UNIFORM_BLOCK {\n")
        for (name in PRELUDE_UNIFORMS) uniformTypes.putIfAbsent(name, "mat4")
        for ((type, name) in ENGINE_UNIFORMS + uniformTypes.map { it.value to it.key }) {
            val (size, alignment) = STD140[type] ?: throw ConvertException("uniform $name has unsupported type $type")
            offset = (offset + alignment - 1) / alignment * alignment
            members.add(Uniform(type, name, offset))
            glsl.append("    ").append(type).append(' ').append(name).append(";\n")
            offset += size
        }
        glsl.append("};\n")
        return UniformLayout(members, (offset + 15) / 16 * 16, glsl.toString())
    }

    /**
     * The uniform block as [program] sees it. Every program gets the same layout, but members the program
     * did not declare are given other names: the program may use those names for something of its own.
     */
    private fun blockFor(program: Program, uniforms: UniformLayout, constants: Set<String> = emptySet()): String {
        val glsl = StringBuilder("layout(std140) uniform $UNIFORM_BLOCK {\n")
        for (member in uniforms.members) {
            val own = member.name !in constants &&
                (member.name in program.uniformNames || member.name in PRELUDE_UNIFORMS || ENGINE_UNIFORMS.any { it.second == member.name })
            glsl.append("    ").append(member.type).append(' ').append(if (own) member.name else "mc_other_${member.name}").append(";\n")
        }
        return glsl.append("};\n").toString()
    }

    /** [ownOutputs]: draw buffer index to type and name, for outputs the pack declares with a layout itself. */
    private class Finished(val text: String, val targets: List<Int>, val ownOutputs: Map<Int, Pair<String, String>> = emptyMap())

    /** Adds the prelude, runs the preprocessor and declares what the old built-ins stood for. */
    private fun finish(program: Program, stage: Stage, body: String, uniforms: UniformLayout, constants: Set<String> = emptySet()): Finished {
        val prelude = StringBuilder()
        prelude.append("#version 450\n").append(STANDARD_MACROS).append(blockFor(program, uniforms, constants)).append(COMMON_PRELUDE)
        // GLSL 1.20 spelling, still used by older packs.
        prelude.append(if (stage == Stage.VERTEX) "#define varying out\n" else "#define varying in\n#define gl_FragColor gl_FragData[0]\n")
        if (stage == Stage.FRAGMENT) prelude.append(FRAGMENT_TEXTURE_FUNCTIONS)
        if (stage == Stage.VERTEX) {
            prelude.append(
                when (program.kind) {
                    Kind.TERRAIN -> TERRAIN_INPUTS + matrices("gbufferModelView", "gbufferProjection")
                    // The game sways the view in its projection; here the sway (mc_bob) belongs to the view.
                    Kind.ENTITY -> DYNAMIC_TRANSFORMS + ENTITY_INPUTS + matrices("mc_bob * ModelViewMat", "gbufferProjection")
                    Kind.PARTICLE -> DYNAMIC_TRANSFORMS + PARTICLE_INPUTS + matrices("mc_bob * ModelViewMat", "gbufferProjection")
                    Kind.CELESTIAL -> DYNAMIC_TRANSFORMS + CELESTIAL_INPUTS + matrices("mc_bob * ModelViewMat", "gbufferProjection")
                    Kind.FLYWHEEL -> FLYWHEEL_INPUTS + matrices("gbufferModelView", "gbufferProjection")
                    Kind.SHADOW -> TERRAIN_INPUTS + matrices("shadowModelView", "shadowProjection")
                    Kind.FULLSCREEN -> FULLSCREEN_INPUTS + matrices("mat4(1.0)", "mat4(2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, -1.0, 0.0, 1.0)")
                },
            )
        } else if (program.kind.world) {
            prelude.append(WORLD_FRAGMENT_PRELUDE)
        }
        if (program.kind != Kind.FULLSCREEN) prelude.append(DRAW_UNIFORMS)
        prelude.append("#define main mc_main\n")
        val before = if (stage == Stage.VERTEX && program.kind == Kind.FLYWHEEL) "    mc_flywheel();\n" else ""
        val epilogue = "\n#undef main\nvoid main() {\n$before    mc_main();\n" + when {
            stage == Stage.VERTEX && program.kind.world -> "    gl_Position.z = 0.5 * (gl_Position.w - gl_Position.z);\n"
            stage == Stage.VERTEX && program.kind == Kind.SHADOW -> "    gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);\n"
            // The alpha test OpenGL used to do after the shader.
            stage == Stage.FRAGMENT && program.kind != Kind.FULLSCREEN -> "    if (!(gl_FragData[0].a > mc_alphaTestRef)) discard;\n"
            else -> ""
        } + "}\n"
        var text = preprocess("${program.name}.${stage.extension}", prelude.toString() + body + epilogue, stage)
        // Functions that became built-ins after the GLSL the pack was written in: the pack's own keep working
        // under another name.
        for (name in NEWER_BUILTINS) {
            if (!text.contains(name)) continue
            if (Regex("""(?m)^[ \t]*(?:float|vec[234]|mat[234]|int|bool)[ \t]+$name[ \t]*\([^;{]*\)\s*\{""").containsMatchIn(text)) {
                text = text.replace(Regex("""\b$name\b"""), "mc_pack_$name")
            }
        }
        for (name in METAL_NAMES) {
            if (text.contains(name)) text = text.replace(Regex("""\b$name\b"""), "mc_pack_$name")
        }
        // A name can be a uniform under one pack option and a constant under another. Where the constant is
        // what survived the preprocessor, the uniform gives up the name.
        if (constants.isEmpty()) {
            // Only declarations outside every function count: a local may shadow a uniform.
            val candidates = CONSTANT_LINE.findAll(text).filter { it.groupValues[1].ifEmpty { it.groupValues[2] } in program.uniformNames }.toList()
            val clashing = HashSet<String>()
            if (candidates.isNotEmpty()) {
                var depth = 0
                var at = 0
                for (match in candidates) {
                    while (at < match.range.first) {
                        when (text[at]) { '{' -> depth++; '}' -> depth-- }
                        at++
                    }
                    if (depth == 0) clashing.add(match.groupValues[1].ifEmpty { match.groupValues[2] })
                }
            }
            if (clashing.isNotEmpty()) return finish(program, stage, body, uniforms, clashing)
        }

        var targets = listOf(0)
        if (stage == Stage.FRAGMENT) {
            val markers = TARGETS_MARKER.findAll(text).toList()
            if (markers.isNotEmpty()) targets = markers.last().groupValues[1].trim('_').split('_').map { it.toInt() }
            text = TARGETS_MARKER.replace(text, "")
            // A program that writes no output at all (an empty main, say) has no targets. The alpha test added
            // above is the one mention of gl_FragData that is not the pack's.
            val mentions = FRAG_DATA.findAll(text).count() - (if (program.kind != Kind.FULLSCREEN) 1 else 0)
            if (markers.isEmpty() && mentions == 0 && !PACK_OUTPUT.containsMatchIn(text)) {
                text = text.replace(Regex("""[ \t]*if \(!\s*\(gl_FragData\s*\[\s*0\s*]\.a > mc_alphaTestRef\)\) discard;\n"""), "")
                return Finished(text, emptyList())
            }
            val used = FRAG_DATA.findAll(text).map { it.groupValues[1].toInt() }.toSortedSet()
            if (used.isNotEmpty() && used.last() >= targets.size) {
                throw ConvertException("writes gl_FragData[${used.last()}] but declares only ${targets.size} targets")
            }
            text = FRAG_DATA.replace(text) { "mc_FragData_${it.groupValues[1]}" }
            // Outputs the pack declares itself keep their names; their locations are filled in once the
            // attachments are known.
            val own = LinkedHashMap<Int, Pair<String, String>>()
            val widened = StringBuilder()
            text = PACK_OUTPUT.replace(text) {
                val index = it.groupValues[1].toInt()
                val type = it.groupValues[2]
                val name = it.groupValues[3]
                // An output past the end of the pack's target list goes nowhere.
                if (markers.isNotEmpty() && index >= targets.size) return@replace "$type $name;"
                own[index] = type to name
                // Metal wants an output as wide as its attachment: narrower ones are filled up at the end.
                val fill = when (type) { "float" -> ", 0.0, 0.0, 1.0"; "vec2" -> ", 0.0, 1.0"; "vec3" -> ", 1.0"; else -> null }
                if (fill == null) {
                    "layout(location = MC_LOCATION_$index) out $type $name;"
                } else {
                    widened.append("    mc_Out_$index = vec4($name$fill);\n")
                    "layout(location = MC_LOCATION_$index) out vec4 mc_Out_$index; $type $name;"
                }
            }
            if (widened.isNotEmpty()) {
                val end = text.lastIndexOf('}')
                text = text.substring(0, end) + widened + text.substring(end)
            }
            if (own.isNotEmpty()) {
                // Without a directive the outputs go to the buffers of the same numbers.
                if (markers.isEmpty()) targets = (0..own.keys.max()).toList()
                if (own.keys.max() >= targets.size) throw ConvertException("declares output ${own.keys.max()} but only ${targets.size} targets")
                // The alpha test reads the first output, whatever the pack calls it.
                val first = own[0]
                text = when {
                    first == null -> text
                    first.first == "vec4" -> text.replace("mc_FragData_0", first.second)
                    else -> text.replace(Regex("""[ \t]*if \(!\s*\(mc_FragData_0\.a > mc_alphaTestRef\)\) discard;\n"""), "")
                }
            }
            return Finished(text, targets, own)
        }
        return Finished(text, targets)
    }

    /**
     * Gives what the vertex stage hands to the fragment stage explicit locations by name. OpenGL matches them
     * by name; left alone they would be numbered in the order each stage happens to declare them.
     */
    private fun link(vertex: String, fragment: String): Pair<String, String> {
        val locations = LinkedHashMap<String, Int>()
        var next = 0
        // A struct takes one location per member (more for matrices and arrays).
        val structs = HashMap<String, Int>()
        fun slotsOf(type: String): Int = structs[type] ?: MATRIX_TYPE.matchEntire(type)?.groupValues?.get(1)?.toInt() ?: 1
        for (match in STRUCT.findAll(vertex)) {
            var slots = 0
            for (member in match.groupValues[2].split(';')) {
                val words = member.trim().split(Regex("\\s+"), 2)
                if (words.size < 2) continue
                for (declarator in splitTopLevel(words[1])) {
                    val count = declarator.substringAfter('[', "").substringBefore(']').trim().toIntOrNull() ?: 1
                    slots += count * slotsOf(words[0])
                }
            }
            structs[match.groupValues[1]] = slots
        }
        fun rewrite(text: String, direction: String): String {
            val result = StringBuilder(text.length + 1024)
            for (line in text.lineSequence()) {
                val match = if (line.contains("layout")) null else INTERFACE_LINE.matchEntire(line)
                if (match == null || match.groupValues[2] != direction) {
                    result.append(line).append('\n')
                    continue
                }
                val qualifiers = (match.groupValues[1] + match.groupValues[3]).trim()
                val type = match.groupValues[4]
                for (declarator in splitTopLevel(match.groupValues[5])) {
                    val name = declarator.substringBefore('[').trim()
                    val count = if (declarator.contains('[')) {
                        declarator.substringAfter('[').substringBefore(']').trim().toIntOrNull()
                            ?: throw ConvertException("cannot size the array in '${line.trim()}'")
                    } else {
                        1
                    }
                    // A matrix takes one location per column: mat3, mat2x2, mat4x3 ...
                    val slots = count * slotsOf(type)
                    val location = locations.getOrPut(name) { next.also { next += slots } }
                    result.append("layout(location = ").append(location).append(") ")
                    if (qualifiers.isNotEmpty()) result.append(qualifiers).append(' ')
                    result.append(direction).append(' ').append(type).append(' ').append(declarator.trim()).append(";\n")
                }
            }
            return result.toString()
        }
        val linkedVertex = rewrite(vertex, "out")
        return linkedVertex to rewrite(fragment, "in")
    }

    private fun matrices(modelView: String, projection: String) =
        "#define gl_ModelViewMatrix ($modelView)\n#define gl_ProjectionMatrix ($projection)\n" +
            "#define gl_NormalMatrix mat3($modelView)\n#define ftransform() (($projection) * ($modelView) * gl_Vertex)\n" +
            "#define gl_ModelViewProjectionMatrix (($projection) * ($modelView))\n"

    // ---- shaderc ----------------------------------------------------------------------------------------------

    private fun preprocess(fileName: String, glsl: String, stage: Stage): String {
        val result = invoke(fileName, glsl, stage, true)
        try {
            return MemoryUtil.memUTF8(Shaderc.shaderc_result_get_bytes(result) ?: throw ConvertException("shaderc returned no text"))
        } finally {
            Shaderc.shaderc_result_release(result)
        }
    }

    /** GLSL to SPIR-V. The caller frees the returned buffer with `MemoryUtil.memFree`. */
    private fun compile(fileName: String, glsl: String, stage: Stage): ByteBuffer {
        val result = invoke(fileName, glsl, stage, false)
        try {
            val bytes = Shaderc.shaderc_result_get_bytes(result) ?: throw ConvertException("shaderc returned no SPIR-V")
            val copy = MemoryUtil.memAlloc(bytes.remaining())
            MemoryUtil.memCopy(bytes, copy)
            return copy
        } finally {
            Shaderc.shaderc_result_release(result)
        }
    }

    private fun invoke(fileName: String, glsl: String, stage: Stage, preprocessOnly: Boolean): Long {
        val text = MemoryUtil.memUTF8(glsl, false)
        val name = MemoryUtil.memUTF8(fileName)
        val entryPoint = MemoryUtil.memUTF8("main")
        val kind = if (stage == Stage.FRAGMENT) Shaderc.shaderc_glsl_fragment_shader else Shaderc.shaderc_glsl_vertex_shader
        try {
            val result = if (preprocessOnly) {
                Shaderc.shaderc_compile_into_preprocessed_text(compiler, text, kind, name, entryPoint, options)
            } else {
                Shaderc.shaderc_compile_into_spv(compiler, text, kind, name, entryPoint, options)
            }
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                val message = Shaderc.shaderc_result_get_error_message(result)
                Shaderc.shaderc_result_release(result)
                throw ConvertException(message ?: "shaderc failed without a message")
            }
            return result
        } finally {
            MemoryUtil.memFree(entryPoint)
            MemoryUtil.memFree(name)
            MemoryUtil.memFree(text)
        }
    }

    // ---- SPIRV-Cross ------------------------------------------------------------------------------------------

    private class Translated(val vertexMsl: String, val fragmentMsl: String, val textures: List<String>)

    private fun translate(program: Program, vertexSpirv: ByteBuffer, fragmentSpirv: ByteBuffer): Translated {
        val vertex = SpirvStage(vertexSpirv)
        try {
            val fragment = SpirvStage(fragmentSpirv)
            try {
                // Textures get one index each, shared by both stages, in the order they are first used.
                val textures = ArrayList<String>()
                for (image in vertex.images + fragment.images) if (image.name !in textures) textures.add(image.name)
                if (textures.size > MAX_TEXTURES) throw ConvertException("uses ${textures.size} textures, Metal allows $MAX_TEXTURES: $textures")
                for (stage in listOf(vertex, fragment)) {
                    for (buffer in stage.uniformBuffers) {
                        val index = BUFFER_INDICES[buffer.name] ?: throw ConvertException("unknown uniform block ${buffer.name}")
                        stage.bind(buffer, index)
                    }
                    for (image in stage.images) stage.bind(image, textures.indexOf(image.name))
                }
                // What the vertex stage passes on already has locations matched by name (see link()).
                val outputNames = vertex.outputs.map { it.name }
                for (input in fragment.inputs) {
                    if (input.name !in outputNames) println("warning ${program.name}: the fragment stage reads '${input.name}', which the vertex stage does not write")
                }
                return Translated(vertex.toMsl(flipY = true), fragment.toMsl(flipY = false), textures)
            } finally {
                fragment.close()
            }
        } finally {
            vertex.close()
        }
    }

    private class Resource(val id: Int, val name: String)

    /** One shader stage loaded into SPIRV-Cross, with the resources it really uses. */
    private class SpirvStage(spirv: ByteBuffer) : AutoCloseable {
        val context: Long
        val compiler: Long
        val uniformBuffers: List<Resource>
        val images: List<Resource>
        val inputs: List<Resource>
        val outputs: List<Resource>

        init {
            MemoryStack.stackPush().use { stack ->
                val pointer = stack.callocPointer(1)
                if (Spvc.spvc_context_create(pointer) != 0) throw ConvertException("Couldn't create spvc context")
                context = pointer.get(0)
                try {
                    expect(Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(), (spirv.remaining() / 4).toLong(), pointer), "Couldn't parse SPIR-V")
                    val ir = pointer.get(0)
                    expect(
                        Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer),
                        "Couldn't create compiler",
                    )
                    compiler = pointer.get(0)
                    expect(Spvc.spvc_compiler_get_active_interface_variables(compiler, pointer), "Couldn't find the used variables")
                    val active = pointer.get(0)
                    expect(Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, pointer, active), "Couldn't create resource list")
                    val resources = pointer.get(0)
                    expect(Spvc.spvc_compiler_set_enabled_interface_variables(compiler, active), "Couldn't limit the interface")
                    uniformBuffers = list(stack, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER)
                    images = list(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE)
                    inputs = list(stack, resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT)
                    outputs = list(stack, resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT)
                } catch (t: Throwable) {
                    Spvc.spvc_context_destroy(context)
                    throw t
                }
            }
        }

        private fun list(stack: MemoryStack, resources: Long, type: Int): List<Resource> {
            val listPointer = stack.callocPointer(1)
            val countPointer = stack.callocPointer(1)
            expect(Spvc.spvc_resources_get_resource_list_for_type(resources, type, listPointer, countPointer), "Couldn't list shader resources")
            val count = countPointer.get(0).toInt()
            if (count == 0) return emptyList()
            val reflected = SpvcReflectedResource.create(listPointer.get(0), count)
            return List(count) { index -> reflected.get(index).let { Resource(it.id(), it.nameString()) } }
        }

        fun decorate(resource: Resource, decoration: Int, value: Int) {
            Spvc.spvc_compiler_set_decoration(compiler, resource.id, decoration, value)
        }

        fun bind(resource: Resource, index: Int) {
            decorate(resource, DECORATION_DESCRIPTOR_SET, 0)
            decorate(resource, DECORATION_BINDING, index)
        }

        fun toMsl(flipY: Boolean): String {
            MemoryStack.stackPush().use { stack ->
                val pointer = stack.callocPointer(1)
                expect(Spvc.spvc_compiler_create_compiler_options(compiler, pointer), "Couldn't create compiler options")
                val options = pointer.get(0)
                expect(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION), "MSL version")
                expect(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS), "MSL platform")
                // Binding numbers set above become the Metal buffer, texture and sampler indices as they are.
                expect(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true), "decoration binding")
                // The game stores images like Vulkan does (row 0 at the bottom of the picture), so flip.
                expect(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, flipY), "vertex flip")
                expect(Spvc.spvc_compiler_install_compiler_options(compiler, options), "Couldn't install compiler options")
                expect(Spvc.spvc_compiler_compile(compiler, pointer), "Couldn't generate MSL")
                return MemoryUtil.memUTF8(pointer.get(0))
            }
        }

        private fun expect(result: Int, what: String) {
            if (result != 0) throw ConvertException("$what: ${Spvc.spvc_context_get_last_error_string(context)}")
        }

        override fun close() {
            Spvc.spvc_context_destroy(context)
        }
    }

    private companion object {
        const val DECORATION_LOCATION = 30
        const val DECORATION_BINDING = 33
        const val DECORATION_DESCRIPTOR_SET = 34

        /** Metal allows 31 textures per stage. Past 16 the engine makes textures share samplers. */
        const val MAX_TEXTURES = 31

        /** Metal allows 8 colour attachments per pass. */
        const val MAX_ATTACHMENTS = 8

        /** MSL 2.3 (macOS 11), encoded as major * 10000 + minor * 100. */
        const val MSL_VERSION = 20300

        /** Metal buffer index of each uniform block: the pack's own and the game's (see pack_header.metal). */
        val BUFFER_INDICES = mapOf(UNIFORM_BLOCK to 0, "DrawUniforms" to 1, "ChunkSection" to 2, "DynamicTransforms" to 2, "Globals" to 3)

        /** What Iris defines for every shader. The pack is converted as if it ran on Iris on a Mac. */
        val STANDARD_MACROS = """
            #define MC_VERSION $MC_VERSION
            #define MC_GL_VERSION 410
            #define MC_GLSL_VERSION 410
            #define MC_OS_MAC
            #define MC_GL_VENDOR_APPLE
            #define MC_GL_RENDERER_APPLE
            #define MC_RENDER_QUALITY 1.0
            #define MC_SHADOW_QUALITY 1.0
            #define MC_HAND_DEPTH 0.125
            #define IS_IRIS
            #define MC_NORMAL_MAP
            #define MC_SPECULAR_MAP
            #define IRIS_VERSION 11000
            #define MC_RENDER_STAGE_NONE 0
            #define MC_RENDER_STAGE_SKY 1
            #define MC_RENDER_STAGE_SUNSET 2
            #define MC_RENDER_STAGE_CUSTOM_SKY 3
            #define MC_RENDER_STAGE_SUN 4
            #define MC_RENDER_STAGE_MOON 5
            #define MC_RENDER_STAGE_STARS 6
            #define MC_RENDER_STAGE_VOID 7
            #define MC_RENDER_STAGE_TERRAIN_SOLID 8
            #define MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED 9
            #define MC_RENDER_STAGE_TERRAIN_CUTOUT 10
            #define MC_RENDER_STAGE_ENTITIES 11
            #define MC_RENDER_STAGE_BLOCK_ENTITIES 12
            #define MC_RENDER_STAGE_DESTROY 13
            #define MC_RENDER_STAGE_OUTLINE 14
            #define MC_RENDER_STAGE_DEBUG 15
            #define MC_RENDER_STAGE_HAND_SOLID 16
            #define MC_RENDER_STAGE_TERRAIN_TRANSLUCENT 17
            #define MC_RENDER_STAGE_TRIPWIRE 18
            #define MC_RENDER_STAGE_PARTICLES 19
            #define MC_RENDER_STAGE_CLOUDS 20
            #define MC_RENDER_STAGE_RAIN_SNOW 21
            #define MC_RENDER_STAGE_WORLD_BORDER 22
            #define MC_RENDER_STAGE_HAND_TRANSLUCENT 23
        """.trimIndent() + "\n"

        val COMMON_PRELUDE = """
            // The old texture functions, as functions of our own rather than macros onto the new ones: packs may
            // name a sampler "texture". (texture2D itself is a type name in this GLSL version.)
            vec4 mc_texture2D(sampler2D s, vec2 c) { return texture(s, c); }
            vec4 mc_texture2DLod(sampler2D s, vec2 c, float lod) { return textureLod(s, c, lod); }
            vec4 mc_texture2DGrad(sampler2D s, vec2 c, vec2 dx, vec2 dy) { return textureGrad(s, c, dx, dy); }
            vec4 mc_texture2DProj(sampler2D s, vec3 c) { return textureProj(s, c); }
            vec4 mc_texture2DProj(sampler2D s, vec4 c) { return textureProj(s, c); }
            vec4 mc_texture3D(sampler3D s, vec3 c) { return texture(s, c); }
            vec4 mc_shadow2D(sampler2DShadow s, vec3 c) { return vec4(texture(s, c)); }
            vec4 mc_shadow2DLod(sampler2DShadow s, vec3 c, float lod) { return vec4(textureLod(s, c, lod)); }
            #define texture2DLod mc_texture2DLod
            #define texture2DGrad mc_texture2DGrad
            #define texture2DGradARB mc_texture2DGrad
            #define texture2DProj mc_texture2DProj
            #define texture2D mc_texture2D
            #define texture3D mc_texture3D
            #define texelFetch2D texelFetch
            #define shadow2DLod mc_shadow2DLod
            #define shadow2D mc_shadow2D
            #define attribute in
            // The vertex attributes Iris adds; a pack may declare them narrower than vec4.
            #define mc_Entity mc_in_mc_Entity
            #define mc_midTexCoord mc_in_mc_midTexCoord
            #define at_tangent mc_in_at_tangent
            #define at_midBlock mc_in_at_midBlock
            // A reserved word here, a plain name in the GLSL the packs are written in.
            #define sampler mc_sampler_
            #define gl_VertexID gl_VertexIndex
            // Lightmap coordinates arrive as 0..240 and leave as 1/32..31/32, as with OpenGL's texture matrix.
            const mat4 mc_TextureMatrix[2] = mat4[2](mat4(1.0), mat4(1.0 / 256.0, 0.0, 0.0, 0.0, 0.0, 1.0 / 256.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0 / 32.0, 1.0 / 32.0, 0.0, 1.0));
            #define gl_TextureMatrix mc_TextureMatrix
            // OpenGL's fixed-function fog: mc_fog is start, end, 1 / (end - start), density.
            struct mc_FogParameters { vec4 color; float start; float end; float scale; float density; };
            #define gl_Fog mc_FogParameters(mc_fogColor, mc_fog.x, mc_fog.y, mc_fog.z, mc_fog.w)
        """.trimIndent() + "\n"

        /** The overloads with a level-of-detail bias, which only fragment shaders have. */
        val FRAGMENT_TEXTURE_FUNCTIONS = """
            vec4 mc_texture2D(sampler2D s, vec2 c, float bias) { return texture(s, c, bias); }
            vec4 mc_texture3D(sampler3D s, vec3 c, float bias) { return texture(s, c, bias); }
        """.trimIndent() + "\n"

        /** The game's terrain vertex (see `pipeline/TerrainVertexFormat.kt`) behind OpenGL's fixed attributes. */
        val TERRAIN_INPUTS = """
            layout(std140) uniform ChunkSection { mat4 ModelViewMat; float ChunkVisibility; ivec2 TextureSize; ivec3 ChunkPosition; };
            layout(std140) uniform Globals { ivec3 CameraBlockPos; vec3 CameraOffset; vec2 ScreenSize; float GlintAlpha; float GameTime; int MenuBlurRadius; int UseRgss; };
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            layout(location = 2) in vec2 UV0;
            layout(location = 3) in ivec2 UV2;
            layout(location = 4) in vec4 McNormal;
            layout(location = 5) in vec4 McTangent;
            layout(location = 6) in ivec2 McEntity;
            layout(location = 7) in vec2 McMidTexCoord;
            #define gl_Vertex vec4(Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset, 1.0)
            #define gl_Normal (McNormal.xyz)
            #define gl_Color Color
            #define gl_MultiTexCoord0 vec4(UV0, 0.0, 1.0)
            #define gl_MultiTexCoord1 vec4(vec2(UV2), 0.0, 1.0)
            #define mc_in_mc_Entity vec4(vec2(McEntity), 0.0, 0.0)
            #define mc_in_mc_midTexCoord vec4(McMidTexCoord, 0.0, 1.0)
            #define mc_in_at_tangent McTangent
            #define mc_in_at_midBlock vec4(0.0)
        """.trimIndent() + "\n"

        /** The block the game fills for everything that is not terrain. */
        val DYNAMIC_TRANSFORMS = """
            layout(std140) uniform DynamicTransforms { mat4 ModelViewMat; vec4 ColorModulator; vec3 ModelOffset; mat4 TextureMat; };
        """.trimIndent() + "\n"

        /** The game's entity vertex. It has no tangent, block ID or sprite centre. */
        val ENTITY_INPUTS = """
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            layout(location = 2) in vec2 UV0;
            layout(location = 3) in ivec2 UV1;
            layout(location = 4) in ivec2 UV2;
            layout(location = 5) in vec3 Normal;
            #define gl_Vertex vec4(Position, 1.0)
            #define gl_Normal Normal
            #define gl_Color (Color * ColorModulator)
            #define gl_MultiTexCoord0 vec4(UV0, 0.0, 1.0)
            #define gl_MultiTexCoord1 vec4(vec2(UV2), 0.0, 1.0)
            #define mc_in_mc_Entity vec4(-1.0)
            #define mc_in_mc_midTexCoord vec4(UV0, 0.0, 1.0)
            #define mc_in_at_tangent vec4(1.0, 0.0, 0.0, 1.0)
            #define mc_in_at_midBlock vec4(0.0)
        """.trimIndent() + "\n"

        val PARTICLE_INPUTS = """
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            layout(location = 2) in vec4 Color;
            layout(location = 3) in ivec2 UV2;
            #define gl_Vertex vec4(Position, 1.0)
            #define gl_Normal vec3(0.0, 1.0, 0.0)
            #define gl_Color (Color * ColorModulator)
            #define gl_MultiTexCoord0 vec4(UV0, 0.0, 1.0)
            #define gl_MultiTexCoord1 vec4(vec2(UV2), 0.0, 1.0)
            #define mc_in_mc_Entity vec4(-1.0)
            #define mc_in_mc_midTexCoord vec4(UV0, 0.0, 1.0)
            #define mc_in_at_tangent vec4(1.0, 0.0, 0.0, 1.0)
            #define mc_in_at_midBlock vec4(0.0)
        """.trimIndent() + "\n"

        val CELESTIAL_INPUTS = """
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            #define gl_Vertex vec4(Position, 1.0)
            #define gl_Normal vec3(0.0, 1.0, 0.0)
            #define gl_Color ColorModulator
            #define gl_MultiTexCoord0 vec4(UV0, 0.0, 1.0)
            #define gl_MultiTexCoord1 vec4(240.0, 240.0, 0.0, 1.0)
            #define mc_in_mc_Entity vec4(-1.0)
            #define mc_in_mc_midTexCoord gl_MultiTexCoord0
            #define mc_in_at_tangent vec4(1.0, 0.0, 0.0, 1.0)
            #define mc_in_at_midBlock vec4(0.0)
        """.trimIndent() + "\n"

        /**
         * The vertex as Flywheel's vertex code leaves it: position relative to the camera, world axes.
         * `mc_flywheel()` is defined by the engine and fills these before the pack's code runs.
         */
        val FLYWHEEL_INPUTS = """
            vec4 mc_flwVertex;
            vec3 mc_flwNormal;
            vec4 mc_flwColor;
            vec2 mc_flwTexCoord;
            vec2 mc_flwLight;
            void mc_flywheel();
            #define gl_Vertex mc_flwVertex
            #define gl_Normal mc_flwNormal
            #define gl_Color mc_flwColor
            #define gl_MultiTexCoord0 vec4(mc_flwTexCoord, 0.0, 1.0)
            #define gl_MultiTexCoord1 vec4(mc_flwLight, 0.0, 1.0)
            #define mc_in_mc_Entity vec4(-1.0)
            #define mc_in_mc_midTexCoord vec4(mc_flwTexCoord, 0.0, 1.0)
            #define mc_in_at_tangent vec4(1.0, 0.0, 0.0, 1.0)
            #define mc_in_at_midBlock vec4(0.0)
        """.trimIndent() + "\n"

        /** One triangle that covers the target, in place of the full-screen quad OpenGL packs are given. */
        val FULLSCREEN_INPUTS = """
            // A quad of two triangles, as OpenGL loaders draw it: packs that render at a reduced size scale it
            // in the vertex stage and count on nothing being drawn outside.
            const vec2 mc_corners[6] = vec2[6](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, 1.0), vec2(1.0, 0.0), vec2(1.0, 1.0));
            // The quad's corners are 0..1, as OptiFine and Iris hand them over; the projection maps them to the screen.
            #define gl_Vertex vec4(mc_corners[gl_VertexIndex], 0.0, 1.0)
            #define gl_MultiTexCoord0 vec4(mc_corners[gl_VertexIndex], 0.0, 1.0)
            #define gl_MultiTexCoord1 vec4(0.0, 0.0, 0.0, 1.0)
            #define gl_Color vec4(1.0)
            #define gl_Normal vec3(0.0, 0.0, 1.0)
            #define mc_in_mc_Entity vec4(-1.0)
            #define mc_in_mc_midTexCoord gl_MultiTexCoord0
            #define mc_in_at_tangent vec4(1.0, 0.0, 0.0, 1.0)
            #define mc_in_at_midBlock vec4(0.0)
        """.trimIndent() + "\n"

        val WORLD_FRAGMENT_PRELUDE = """
            #define gl_FragCoord vec4(gl_FragCoord.xy, 1.0 - gl_FragCoord.z, gl_FragCoord.w)
        """.trimIndent() + "\n"

        /** What changes from one kind of draw to the next. */
        val DRAW_UNIFORMS = """
            layout(std140) uniform DrawUniforms { float mc_alphaTestRef; };
        """.trimIndent() + "\n"
    }
}
