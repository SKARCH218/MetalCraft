package dev.skarch.metalcraft.flywheel

import com.mojang.blaze3d.shaders.ShaderType
import com.zurrtum.create.client.flywheel.api.instance.InstanceType
import com.zurrtum.create.client.flywheel.api.material.MaterialShaders
import com.zurrtum.create.client.flywheel.backend.BackendConfig
import com.zurrtum.create.client.flywheel.backend.MaterialShaderIndices
import com.zurrtum.create.client.flywheel.backend.compile.ContextShader
import com.zurrtum.create.client.flywheel.backend.compile.FlwPrograms
import com.zurrtum.create.client.flywheel.backend.compile.component.BufferTextureInstanceComponent
import com.zurrtum.create.client.flywheel.backend.compile.component.InstanceStructComponent
import com.zurrtum.create.client.flywheel.backend.compile.component.UberShaderComponent
import com.zurrtum.create.client.flywheel.backend.compile.core.Compilation
import com.zurrtum.create.client.flywheel.backend.glsl.ShaderSources
import com.zurrtum.create.client.flywheel.backend.glsl.SourceComponent
import com.zurrtum.create.client.flywheel.backend.glsl.generate.FnSignature
import com.zurrtum.create.client.flywheel.backend.glsl.generate.GlslExpr
import com.zurrtum.create.client.flywheel.lib.material.CutoutShaders
import com.zurrtum.create.client.flywheel.lib.util.ResourceUtil
import dev.skarch.metalcraft.backend.MetalDevice
import dev.skarch.metalcraft.backend.TranslatedShaders
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import org.lwjgl.system.MemoryUtil

/** Which of Flywheel's shader pieces make up a program. Two keys that are equal give the same shaders. */
internal data class FlwShaderKey(
    val type: InstanceType<*>, val context: ContextShader, val light: Identifier, val shaders: MaterialShaders, val cutout: Boolean,
    // The fog and cutout parts switch over every shader registered so far, so they change when one is added.
    val fogCount: Int, val cutoutCount: Int,
)

/**
 * Builds the shaders of Flywheel's instancing programs for Metal. Flywheel puts a program together from GLSL
 * pieces (instance type, material, fog, light, cutout and its own glue); the same pieces are put together
 * here with Flywheel's own classes, adapted to what the GLSL-to-Metal translation needs, and translated.
 *
 * What changes on the way: plain uniforms move into a block (`FlwDraw`), and the buffer textures Flywheel
 * reads instances and light from become storage buffers, which Metal binds like any other buffer.
 */
internal class FlwShaders(resources: ResourceManager) {
    private val sources = ShaderSources(resources)
    private val cache = HashMap<FlwShaderKey, TranslatedShaders>()

    init {
        // Registering a new fog or cutout shader makes Flywheel rebuild its own programs from these sources,
        // which its OpenGL start-up would have set.
        if (FlwPrograms.SOURCES == null) FlwPrograms.SOURCES = sources
    }

    fun get(device: MetalDevice, key: FlwShaderKey): TranslatedShaders = cache.getOrPut(key) {
        val vertex = adapt(
            assemble("VERTEX_SHADER", key, false) {
                listOf(
                    sources.get(ResourceUtil.rl("internal/api_impl.vert")),
                    InstanceStructComponent(key.type),
                    sources.get(key.type.vertexShader()),
                    sources.get(key.shaders.vertexSource()),
                    sources.get(ResourceUtil.rl("internal/vertex_input.vert")),
                    BufferTextureInstanceComponent(key.type),
                    sources.get(ResourceUtil.rl("internal/instancing/main.vert")),
                )
            },
        )
        val fragment = adapt(
            assemble("FRAGMENT_SHADER", key, key.cutout) {
                listOf(
                    sources.get(ResourceUtil.rl("internal/api_impl.frag")),
                    sources.get(key.shaders.fragmentSource()),
                    sources.get(ResourceUtil.rl("internal/components_header.frag")),
                    fogComponent(),
                    sources.get(key.light),
                    if (key.cutout) cutoutComponent() else sources.get(CutoutShaders.OFF.source()),
                    sources.get(ResourceUtil.rl("internal/instancing/main.frag")),
                )
            },
        )
        val name = key.type.vertexShader().path
        val vertexSpirv = device.shaderCompiler.compileSpirv("flywheel/$name.vsh", vertex, ShaderType.VERTEX)
        try {
            val fragmentSpirv = device.shaderCompiler.compileSpirv("flywheel/$name.fsh", fragment, ShaderType.FRAGMENT)
            try {
                device.shaderCompiler.translateStandalone(vertexSpirv, fragmentSpirv, ::bufferIndex, ATTRIBUTES)
            } finally {
                MemoryUtil.memFree(fragmentSpirv)
            }
        } finally {
            MemoryUtil.memFree(vertexSpirv)
        }
    }

    private fun fogComponent(): SourceComponent = UberShaderComponent.builder(ResourceUtil.rl("fog"))
        .materialSources(MaterialShaderIndices.fogSources().all())
        .adapt(FnSignature.create().returnType("vec4").name("flw_fogFilter").arg("vec4", "color").build(), GlslExpr.variable("color"))
        .switchOn(GlslExpr.variable("_flw_uberFogIndex")).build(sources)

    private fun cutoutComponent(): SourceComponent = UberShaderComponent.builder(ResourceUtil.rl("cutout"))
        .materialSources(MaterialShaderIndices.cutoutSources().all())
        .adapt(FnSignature.create().returnType("bool").name("flw_discardPredicate").arg("vec4", "color").build(), GlslExpr.boolLiteral(false))
        .switchOn(GlslExpr.variable("_flw_uberCutoutIndex")).build(sources)

    /** The pieces of one stage in Flywheel's order, each preceded by what it includes. */
    private fun assemble(stageDefine: String, key: FlwShaderKey, discard: Boolean, components: () -> List<SourceComponent>): String {
        val compilation = Compilation()
        compilation.define(stageDefine)
        key.context.onCompile(compilation)
        BackendConfig.INSTANCE.lightSmoothness().onCompile(compilation)
        if (discard) compilation.define("_FLW_USE_DISCARD")
        val ordered = LinkedHashSet<SourceComponent>()
        for (component in components()) {
            includeDepthFirst(ordered, component)
            ordered.add(component)
        }
        for (component in ordered) compilation.appendComponent(component)
        return FULL_SOURCE.get(compilation).toString()
    }

    private fun includeDepthFirst(into: MutableSet<SourceComponent>, component: SourceComponent) {
        for (include in component.included()) includeDepthFirst(into, include)
        into.addAll(component.included())
    }

    private fun adapt(glsl: String): String {
        val body = StringBuilder(glsl.length)
        for (line in glsl.lineSequence()) {
            when {
                line.trimStart().startsWith("#extension") -> {}
                LOOSE_UNIFORM.matches(line) -> {}
                line.contains("uniform usamplerBuffer") -> {}
                else -> body.append(line).append('\n')
            }
        }
        var text = body.toString()
            .replace("texelFetch(_flw_lightLut, int(index)).r", "_flw_lightLutData[index]")
            .replace("texelFetch(_flw_lightSections, int(index)).r", "_flw_lightSectionData[index]")
        text = indexInstances(text)
        return "#version 450\n$BLOCKS$text"
    }

    /** `texelFetch(_flw_instances, <expr>)` becomes `_flw_instanceTexels[<expr>]`. */
    private fun indexInstances(text: String): String {
        val call = "texelFetch(_flw_instances,"
        val out = StringBuilder(text.length)
        var position = 0
        while (true) {
            val start = text.indexOf(call, position)
            if (start < 0) break
            out.append(text, position, start)
            var depth = 1
            var end = start + call.length
            while (depth > 0) {
                when (text[end]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                end++
            }
            out.append("_flw_instanceTexels[").append(text, start + call.length, end - 1).append(']')
            position = end
        }
        out.append(text, position, text.length)
        return out.toString()
    }

    companion object {
        const val DRAW_BLOCK = "FlwDraw"
        const val INSTANCE_BLOCK = "FlwInstances"
        const val LIGHT_LUT_BLOCK = "FlwLightLut"
        const val LIGHT_SECTIONS_BLOCK = "FlwLightSections"

        /** Size of [DRAW_BLOCK] in bytes; the offsets of its members are in `MetalDrawManager.writeDraw`. */
        const val DRAW_BLOCK_SIZE = 128

        /** Vertex inputs of Flywheel's internal mesh format, in the order of `FlwMesh.FORMAT`. */
        val ATTRIBUTES = listOf("_flw_aPos", "_flw_aColor", "_flw_aTexCoord", "_flw_aOverlay", "_flw_aLight", "_flw_aNormal")

        private val BUFFERS = listOf(
            "_FlwFrameUniforms", "_FlwFogUniforms", "_FlwOptionsUniforms", "_FlwPlayerUniforms", "_FlwLevelUniforms",
            DRAW_BLOCK, INSTANCE_BLOCK, LIGHT_LUT_BLOCK, LIGHT_SECTIONS_BLOCK,
        )

        fun bufferIndex(name: String): Int = BUFFERS.indexOf(name).also { require(it >= 0) { "Unknown Flywheel uniform block $name" } }

        private val LOOSE_UNIFORM = Regex("""^\s*uniform\s+(bool|int|uint|float|[iu]?vec[234]|mat[34])\s+[^;]*;.*$""")

        // What Flywheel sets with glUniform per draw, and the buffers it reads through buffer textures.
        private val BLOCKS = """
            layout(std140) uniform $DRAW_BLOCK {
                uvec2 _flw_packedMaterial;
                int _flw_baseInstance;
                uint _flw_baseVertex;
                mat4 _flw_modelMatrixUniform;
                mat3 _flw_normalMatrixUniform;
            };
            layout(std430) readonly buffer $INSTANCE_BLOCK { uvec4 _flw_instanceTexels[]; };
            layout(std430) readonly buffer $LIGHT_LUT_BLOCK { uint _flw_lightLutData[]; };
            layout(std430) readonly buffer $LIGHT_SECTIONS_BLOCK { uint _flw_lightSectionData[]; };
        """.trimIndent() + "\n"

        // Flywheel keeps the text it has put together private; it is only handed to OpenGL.
        private val FULL_SOURCE = Compilation::class.java.getDeclaredField("fullSource").apply { isAccessible = true }
    }
}
