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
private val INCLUDE_LINE = Regex("""^\s*#include\s+"([^"]+)"""")
private val PACK_ATTRIBUTE = Regex("""^\s*(attribute|in)\s+vec[234]\s+(mc_Entity|mc_midTexCoord|at_tangent|at_midBlock)\s*;.*$""")
private val TARGETS_COMMENT = Regex("""/\*\s*(DRAWBUFFERS|RENDERTARGETS)\s*:\s*([0-9,\s]+?)\s*\*/""")
private val TARGETS_MARKER = Regex("""const int mc_targets((?:_\d+)+) = 0;""")
private val FRAG_DATA = Regex("""gl_FragData\s*\[\s*(\d+)\s*]""")

private val BUFFER_FORMAT = Regex("""const\s+int\s+colortex(\d+)Format\s*=\s*(\w+)\s*;""")
private val BUFFER_CLEAR = Regex("""const\s+bool\s+colortex(\d+)Clear\s*=\s*(true|false)\s*;""")
private val SHADOW_CONSTANT = Regex("""const\s+(?:int|float)\s+(shadowMapResolution|shadowDistance|sunPathRotation)\s*=\s*(-?[0-9.]+)\s*;""")

/** Members the engine needs in the block besides the pack's own uniforms. */
private val ENGINE_UNIFORMS = listOf("vec4" to "mc_fog", "mat4" to "mc_bob")

private const val UNIFORM_BLOCK = "PackUniforms"
private const val SKY_PROGRAM = "gbuffers_skybasic"
private const val FLYWHEEL_SOURCE = "gbuffers_entities"

/** The pack's program folders: the Overworld (and every other dimension), the Nether, the End. */
private val DIMENSION_FOLDERS = listOf("world0", "world-1", "world1")

/** The game version the pack is converted for, as Iris numbers it (1.21.11). */
private const val MC_VERSION = 12111
private val VERSION_CONDITION = Regex("""#if\s+MC_VERSION\s*(>=|>|<=|<|==)\s*(\d+)""")

fun main(args: Array<String>) {
    if (args.size < 2 || args[0].isEmpty() || args[1].isEmpty()) {
        System.err.println("usage: convertShaderPack -Ppack.source=<pack>/shaders -Ppack.output=<folder> -Ppack.port=<port settings json>")
        exitProcess(2)
    }
    val port = args.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(Files.readString(Path.of(it))).asJsonObject } ?: JsonObject()
    val converter = Converter(Path.of(args[0]).toAbsolutePath().normalize(), Path.of(args[1]).toAbsolutePath().normalize(), port)
    exitProcess(if (converter.run()) 0 else 1)
}

private class Converter(private val source: Path, private val output: Path, private val port: JsonObject) {
    private val uniformTypes = LinkedHashMap<String, String>()
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
            val dimension = source.resolve(folder)
            if (!dimension.exists()) continue
            val names = Files.list(dimension).use { files ->
                files.filter { it.isRegularFile() && it.name.endsWith(".fsh") }.map { it.name.removeSuffix(".fsh") }.sorted().toList()
            }
            val programs = ArrayList<Program>()
            for (name in names) {
                val kind = kindOf(name)
                if (kind == null) skipped.add(name) else programs.add(Program(name, kind, assemble(dimension, name, Stage.VERTEX), assemble(dimension, name, Stage.FRAGMENT)))
            }
            dimensions[folder] = programs
        }
        if (dimensions.isEmpty()) throw ConvertException("$source has none of the folders $DIMENSION_FOLDERS")
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
        manifest.addProperty("name", port.get("name")?.asString ?: source.parent.name)
        manifest.add("buffers", buffers())
        manifest.add("textures", textures())
        blockIds()?.let { manifest.addProperty("blockIds", it) }
        itemIds()?.let { manifest.addProperty("itemIds", it) }
        port.get("separateAo")?.let { manifest.add("separateAo", it) }
        manifest.add("dimensions", manifestDimensions)
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

        val manifestPrograms = JsonObject()
        val shadowConstants = HashMap<String, String>()
        for ((program, stages) in finished) {
            try {
                val targets = stages.second.targets
                val locations = if (program.kind.world) targets.map { 1 + gbufferTargets.indexOf(it) } else targets.indices.toList()
                val vertexGlsl = stages.first.text
                val fragmentGlsl = stages.second.text.replaceFirst(
                    "#version 450\n", "#version 450\n" + locations.withIndex().joinToString("") { (i, location) -> "layout(location = $location) out vec4 mc_FragData_$i;\n" },
                )
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
                                })
                            }
                        })
                        entry.add("targets", JsonArray().also { array -> targets.forEach(array::add) })
                        port.getAsJsonObject("blend")?.get(program.name)?.let { entry.add("blend", it) }
                        port.getAsJsonObject("alphaTest")?.get(program.name)?.let { entry.add("alphaTest", it) }
                        manifestPrograms.add(program.name, entry)
                        println("ok      ${program.name}  textures=${translated.textures}  targets=$targets")
                    } finally {
                        MemoryUtil.memFree(fragmentSpirv)
                    }
                } finally {
                    MemoryUtil.memFree(vertexSpirv)
                }
            } catch (e: ConvertException) {
                failures++
                report(program, e)
            }
        }

        val manifest = JsonObject()
        manifest.add("gbuffers", JsonArray().also { array -> gbufferTargets.forEach(array::add) })
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
                val template = finish(Program("flywheel", Kind.FLYWHEEL, entities.vertex, entities.fragment), Stage.VERTEX, entities.vertex, uniforms)
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

    private fun report(program: Program, error: ConvertException) {
        println("FAILED  ${program.name}\n${error.message!!.lines().take(12).joinToString("\n") { "          $it" }}")
    }

    /** deferred, deferred1, ... then composite, composite1, ... then final. */
    private fun passOrder(program: String): Int {
        val group = if (program.startsWith("deferred")) 0 else if (program.startsWith("composite")) 1 else 2
        return group * 1000 + (program.dropWhile { !it.isDigit() }.toIntOrNull() ?: 0)
    }

    /** The colour buffers the pack declares in comments and constants (Iris reads them the same way). */
    private fun buffers(): JsonObject {
        val text = Files.walk(source.resolve("lib")).use { files ->
            files.filter { it.isRegularFile() && it.name.endsWith(".glsl") }.sorted().map { Files.readString(it) }.toList().joinToString("\n")
        }
        val buffers = sortedMapOf<Int, JsonObject>()
        for (match in BUFFER_FORMAT.findAll(text)) {
            buffers.getOrPut(match.groupValues[1].toInt()) { JsonObject() }.addProperty("format", match.groupValues[2])
        }
        for (match in BUFFER_CLEAR.findAll(text)) {
            buffers[match.groupValues[1].toInt()]?.addProperty("clear", match.groupValues[2] == "true")
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
        val active = ArrayDeque<Boolean>()
        for (line in Files.readAllLines(file)) {
            val trimmed = line.trim()
            val condition = VERSION_CONDITION.matchEntire(trimmed)
            when {
                condition != null -> {
                    val value = condition.groupValues[2].toInt()
                    active.addLast(
                        when (condition.groupValues[1]) {
                            ">=" -> MC_VERSION >= value
                            ">" -> MC_VERSION > value
                            "<=" -> MC_VERSION <= value
                            "<" -> MC_VERSION < value
                            else -> MC_VERSION == value
                        },
                    )
                }
                trimmed.startsWith("#if") -> throw ConvertException("$name: unsupported condition '$trimmed'")
                trimmed.startsWith("#else") -> active.addLast(!active.removeLast())
                trimmed.startsWith("#endif") -> active.removeLast()
                trimmed.startsWith("#") || trimmed.isEmpty() -> {}
                active.all { it } && trimmed.startsWith(prefix) -> result.append(trimmed).append('\n')
            }
        }
        Files.writeString(output.resolve(outputName), result.toString())
        return outputName
    }

    /** Image files the pack binds in place of buffers; they are copied next to the converted shaders. */
    private fun textures(): JsonObject {
        val result = JsonObject()
        val declared = port.getAsJsonObject("textures") ?: return result
        for ((name, value) in declared.entrySet()) {
            val file = value.asJsonObject.get("file").asString
            val target = output.resolve("textures").resolve(Path.of(file).name)
            Files.createDirectories(target.parent)
            Files.copy(source.resolve(file), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            result.add(name, JsonObject().also {
                it.addProperty("file", "textures/" + target.name)
                it.addProperty("repeat", value.asJsonObject.get("repeat")?.asBoolean ?: false)
            })
        }
        return result
    }

    private class Program(val name: String, val kind: Kind, val vertex: String, val fragment: String)

    private fun kindOf(program: String): Kind? = when {
        program == "shadow" -> Kind.SHADOW
        program == "gbuffers_terrain" || program == "gbuffers_water" -> Kind.TERRAIN
        program == "gbuffers_entities" || program == "gbuffers_entities_translucent" -> Kind.ENTITY
        program == "gbuffers_textured" -> Kind.PARTICLE
        program == "gbuffers_skytextured" -> Kind.CELESTIAL
        program.startsWith("deferred") || program.startsWith("composite") || program == "final" -> Kind.FULLSCREEN
        // The sky only depends on the view direction, so it is drawn as a full-screen pass before the world.
        program == SKY_PROGRAM -> Kind.FULLSCREEN
        else -> null
    }

    // ---- putting a program's GLSL together ------------------------------------------------------------------

    /** The stage's source with its includes inlined, the pack's plain uniforms collected and taken out. */
    private fun assemble(dimension: Path, program: String, stage: Stage): String {
        val file = dimension.resolve("$program.${stage.extension}")
        if (!file.exists()) throw ConvertException("$file does not exist")
        val text = StringBuilder()
        inline(file, text, 0)
        val result = StringBuilder(text.length)
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("#version") || trimmed.startsWith("#extension") -> result.append('\n')
                PACK_ATTRIBUTE.matches(line) -> result.append('\n')
                else -> {
                    val uniform = UNIFORM_LINE.matchEntire(line)
                    if (uniform != null) {
                        for (declarator in splitTopLevel(uniform.groupValues[2])) {
                            val name = declarator.substringBefore('=').trim()
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

    private class Finished(val text: String, val targets: List<Int>)

    /** Adds the prelude, runs the preprocessor and declares what the old built-ins stood for. */
    private fun finish(program: Program, stage: Stage, body: String, uniforms: UniformLayout): Finished {
        val prelude = StringBuilder()
        prelude.append("#version 450\n").append(STANDARD_MACROS).append(uniforms.glsl).append(COMMON_PRELUDE)
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
                    Kind.FULLSCREEN -> FULLSCREEN_INPUTS + matrices("mat4(1.0)", "mat4(1.0)")
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

        var targets = listOf(0)
        if (stage == Stage.FRAGMENT) {
            val markers = TARGETS_MARKER.findAll(text).toList()
            if (markers.isNotEmpty()) targets = markers.last().groupValues[1].trim('_').split('_').map { it.toInt() }
            text = TARGETS_MARKER.replace(text, "")
            val used = FRAG_DATA.findAll(text).map { it.groupValues[1].toInt() }.toSortedSet()
            if (used.isNotEmpty() && used.last() >= targets.size) {
                throw ConvertException("writes gl_FragData[${used.last()}] but declares only ${targets.size} targets")
            }
            text = FRAG_DATA.replace(text) { "mc_FragData_${it.groupValues[1]}" }
        }
        return Finished(text, targets)
    }

    private fun matrices(modelView: String, projection: String) =
        "#define gl_ModelViewMatrix ($modelView)\n#define gl_ProjectionMatrix ($projection)\n" +
            "#define gl_NormalMatrix mat3($modelView)\n#define ftransform() (($projection) * ($modelView) * gl_Vertex)\n"

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
                if (textures.size > MAX_TEXTURES) throw ConvertException("uses ${textures.size} textures, Metal allows $MAX_TEXTURES samplers: $textures")
                for (stage in listOf(vertex, fragment)) {
                    for (buffer in stage.uniformBuffers) {
                        val index = BUFFER_INDICES[buffer.name] ?: throw ConvertException("unknown uniform block ${buffer.name}")
                        stage.bind(buffer, index)
                    }
                    for (image in stage.images) stage.bind(image, textures.indexOf(image.name))
                }
                // What the vertex stage passes on is matched to the fragment stage by name.
                val outputNames = vertex.outputs.map { it.name }
                vertex.outputs.forEachIndexed { index, output -> vertex.decorate(output, DECORATION_LOCATION, index) }
                var spare = outputNames.size
                for (input in fragment.inputs) {
                    val location = outputNames.indexOf(input.name)
                    if (location < 0) println("warning ${program.name}: the fragment stage reads '${input.name}', which the vertex stage does not write")
                    fragment.decorate(input, DECORATION_LOCATION, if (location < 0) spare++ else location)
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

        /** Metal allows 16 samplers per stage. */
        const val MAX_TEXTURES = 16

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
            #define texture2D texture
            #define texture2DLod textureLod
            #define texture2DGrad textureGrad
            #define shadow2D(s, c) vec4(texture(s, c))
            #define attribute in
            #define gl_VertexID gl_VertexIndex
            // Lightmap coordinates arrive as 0..240 and leave as 1/32..31/32, as with OpenGL's texture matrix.
            const mat4 mc_TextureMatrix[2] = mat4[2](mat4(1.0), mat4(1.0 / 256.0, 0.0, 0.0, 0.0, 0.0, 1.0 / 256.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0 / 32.0, 1.0 / 32.0, 0.0, 1.0));
            #define gl_TextureMatrix mc_TextureMatrix
            // OpenGL's fixed-function fog: mc_fog is start, end, 1 / (end - start), density.
            struct mc_FogParameters { float start; float end; float scale; float density; };
            #define gl_Fog mc_FogParameters(mc_fog.x, mc_fog.y, mc_fog.z, mc_fog.w)
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
            #define mc_Entity vec4(vec2(McEntity), 0.0, 0.0)
            #define mc_midTexCoord vec4(McMidTexCoord, 0.0, 1.0)
            #define at_tangent McTangent
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
            #define mc_Entity vec4(-1.0)
            #define mc_midTexCoord vec4(UV0, 0.0, 1.0)
            #define at_tangent vec4(1.0, 0.0, 0.0, 1.0)
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
            #define mc_Entity vec4(-1.0)
            #define mc_midTexCoord vec4(UV0, 0.0, 1.0)
            #define at_tangent vec4(1.0, 0.0, 0.0, 1.0)
        """.trimIndent() + "\n"

        val CELESTIAL_INPUTS = """
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            #define gl_Vertex vec4(Position, 1.0)
            #define gl_Normal vec3(0.0, 1.0, 0.0)
            #define gl_Color ColorModulator
            #define gl_MultiTexCoord0 vec4(UV0, 0.0, 1.0)
            #define gl_MultiTexCoord1 vec4(240.0, 240.0, 0.0, 1.0)
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
            #define mc_Entity vec4(-1.0)
            #define mc_midTexCoord vec4(mc_flwTexCoord, 0.0, 1.0)
            #define at_tangent vec4(1.0, 0.0, 0.0, 1.0)
        """.trimIndent() + "\n"

        /** One triangle that covers the target, in place of the full-screen quad OpenGL packs are given. */
        val FULLSCREEN_INPUTS = """
            #define gl_Vertex vec4(vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2) * 2.0 - 1.0, 0.0, 1.0)
            #define gl_MultiTexCoord0 vec4(vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2), 0.0, 1.0)
            #define gl_MultiTexCoord1 vec4(0.0, 0.0, 0.0, 1.0)
            #define gl_Color vec4(1.0)
            #define gl_Normal vec3(0.0, 0.0, 1.0)
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
