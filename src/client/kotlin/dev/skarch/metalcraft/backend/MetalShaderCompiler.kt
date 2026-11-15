package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.preprocessor.GlslPreprocessor
import com.mojang.blaze3d.shaders.ShaderType
import net.minecraft.client.renderer.ShaderDefines
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.util.spvc.SpvcReflectedResource
import java.nio.ByteBuffer

internal class ShaderException(message: String) : Exception(message)

/**
 * One resource of a pipeline. [index] is its Metal binding index: uniform blocks are numbered among the
 * buffers, textures and texel buffers among the textures (a texture's sampler has the same index).
 */
internal class BindingEntry(val kind: Int, val name: String, val texelFormat: GpuFormat?, val index: Int) {
    companion object {
        const val UNIFORM_BUFFER = 0
        const val TEXTURE = 1
        const val TEXEL_BUFFER = 2
    }
}

internal class TranslatedShaders(val entries: Array<BindingEntry>, val vertexMsl: String, val fragmentMsl: String)

/**
 * Turns the game's GLSL into Metal Shading Language: GLSL to SPIR-V with shaderc, SPIR-V to MSL with
 * SPIRV-Cross. Both libraries ship with the game for its Vulkan backend.
 *
 * Bindings follow the Vulkan backend's scheme: every uniform block, texture and texel buffer a pipeline
 * uses gets one index, shared by both shader stages. Vertex attributes are numbered in the order of the
 * pipeline's vertex format, and what the vertex shader passes on is matched to the fragment shader by name.
 */
internal class MetalShaderCompiler : AutoCloseable {
    private val compiler = Shaderc.shaderc_compiler_initialize()
    private val options = Shaderc.shaderc_compile_options_initialize()
    private val globalDefines = ShaderDefines.builder()
        .define("gl_VertexID", "gl_VertexIndex")
        .define("gl_InstanceID", "gl_InstanceIndex")
        .build()

    init {
        // Vulkan 1.2 flavoured SPIR-V, the same input the game's own Vulkan backend produces.
        Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2)
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true)
        Shaderc.shaderc_compile_options_set_auto_map_locations(options, true)
        // Debug info keeps the variable names the reflection below relies on.
        Shaderc.shaderc_compile_options_set_generate_debug_info(options)
        Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero)
    }

    /** Compiles GLSL to SPIR-V. The caller owns the returned buffer and frees it with `MemoryUtil.memFree`. */
    fun compileSpirv(fileName: String, glsl: String, type: ShaderType): ByteBuffer {
        val source = MemoryUtil.memUTF8(GlslPreprocessor.injectDefines(glsl, globalDefines), false)
        val name = MemoryUtil.memUTF8(fileName)
        val entryPoint = MemoryUtil.memUTF8("main")
        val kind = if (type == ShaderType.FRAGMENT) Shaderc.shaderc_glsl_fragment_shader else Shaderc.shaderc_glsl_vertex_shader
        val result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, name, entryPoint, options)
        try {
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw ShaderException("Couldn't parse GLSL: " + Shaderc.shaderc_result_get_error_message(result))
            }
            val bytes = Shaderc.shaderc_result_get_bytes(result) ?: throw ShaderException("shaderc returned no SPIR-V")
            val copy = MemoryUtil.memAlloc(bytes.remaining())
            MemoryUtil.memCopy(bytes, copy)
            return copy
        } finally {
            Shaderc.shaderc_result_release(result)
            MemoryUtil.memFree(entryPoint)
            MemoryUtil.memFree(name)
            MemoryUtil.memFree(source)
        }
    }

    fun translate(pipeline: RenderPipeline, vertexSpirv: ByteBuffer, fragmentSpirv: ByteBuffer): TranslatedShaders {
        val vertex = Stage(vertexSpirv)
        try {
            val fragment = Stage(fragmentSpirv)
            try {
                val entries = ArrayList<BindingEntry>()
                addEntries(entries, vertex, pipeline)
                addEntries(entries, fragment, pipeline)
                val bufferCount = entries.count { it.kind == BindingEntry.UNIFORM_BUFFER }
                if (bufferCount > MAX_UNIFORM_BUFFERS) throw ShaderException("Too many uniform blocks for Metal: $bufferCount")
                if (entries.size - bufferCount > MAX_TEXTURES) throw ShaderException("Too many textures for Metal: ${entries.size - bufferCount}")

                assignVertexInputs(vertex, pipeline)
                val outputNames = ArrayList<String>(vertex.outputs.size)
                vertex.outputs.forEachIndexed { index, output ->
                    Spvc.spvc_compiler_set_decoration(vertex.compiler, output.id, DECORATION_LOCATION, index)
                    outputNames.add(output.name)
                }
                for (input in fragment.inputs) {
                    val location = outputNames.indexOf(input.name)
                    if (location < 0) {
                        throw ShaderException("Shader expects input variables which are not being provided: [${input.name}]")
                    }
                    Spvc.spvc_compiler_set_decoration(fragment.compiler, input.id, DECORATION_LOCATION, location)
                }
                assignBindings(vertex, entries)
                assignBindings(fragment, entries)
                return TranslatedShaders(entries.toTypedArray(), vertex.toMsl(flipY = true), fragment.toMsl(flipY = false))
            } finally {
                fragment.close()
            }
        } finally {
            vertex.close()
        }
    }

    /**
     * Translates a shader pair that is not one of the game's pipelines (Flywheel's instancing programs).
     * Uniform and storage blocks go to the buffer index [bufferIndex] gives for their name, textures are
     * numbered in the order they are found, and vertex inputs take their place in [attributes].
     */
    fun translateStandalone(
        vertexSpirv: ByteBuffer, fragmentSpirv: ByteBuffer, bufferIndex: (String) -> Int, attributes: List<String>,
    ): TranslatedShaders {
        val vertex = Stage(vertexSpirv, usedOnly = true)
        try {
            val fragment = Stage(fragmentSpirv, usedOnly = true)
            try {
                val entries = ArrayList<BindingEntry>()
                for (stage in arrayOf(vertex, fragment)) {
                    for (buffer in stage.uniformBuffers + stage.storageBuffers) {
                        if (entries.none { it.kind == BindingEntry.UNIFORM_BUFFER && it.name == buffer.name }) {
                            entries.add(BindingEntry(BindingEntry.UNIFORM_BUFFER, buffer.name, null, bufferIndex(buffer.name)))
                        }
                    }
                    for (image in stage.images) {
                        if (entries.none { it.kind == BindingEntry.TEXTURE && it.name == image.name }) {
                            entries.add(BindingEntry(BindingEntry.TEXTURE, image.name, null, textureCount(entries)))
                        }
                    }
                }
                if (textureCount(entries) > MAX_TEXTURES) throw ShaderException("Too many textures for Metal: ${textureCount(entries)}")
                for (input in vertex.inputs) {
                    val location = attributes.indexOf(input.name)
                    if (location < 0) throw ShaderException("Shader expects input variables which are not being provided: [${input.name}]")
                    Spvc.spvc_compiler_set_decoration(vertex.compiler, input.id, DECORATION_LOCATION, location)
                }
                val outputNames = vertex.outputs.map { it.name }
                vertex.outputs.forEachIndexed { index, output -> Spvc.spvc_compiler_set_decoration(vertex.compiler, output.id, DECORATION_LOCATION, index) }
                for (input in fragment.inputs) {
                    val location = outputNames.indexOf(input.name)
                    if (location < 0) throw ShaderException("Shader expects input variables which are not being provided: [${input.name}]")
                    Spvc.spvc_compiler_set_decoration(fragment.compiler, input.id, DECORATION_LOCATION, location)
                }
                for (stage in arrayOf(vertex, fragment)) {
                    for (resource in stage.uniformBuffers + stage.storageBuffers) {
                        bind(stage, resource, entries.first { it.kind == BindingEntry.UNIFORM_BUFFER && it.name == resource.name })
                    }
                    for (resource in stage.images) {
                        bind(stage, resource, entries.first { it.kind == BindingEntry.TEXTURE && it.name == resource.name })
                    }
                }
                return TranslatedShaders(entries.toTypedArray(), vertex.toMsl(flipY = true), fragment.toMsl(flipY = false))
            } finally {
                fragment.close()
            }
        } finally {
            vertex.close()
        }
    }

    override fun close() {
        Shaderc.shaderc_compile_options_release(options)
        Shaderc.shaderc_compiler_release(compiler)
    }

    private fun addEntries(entries: ArrayList<BindingEntry>, stage: Stage, pipeline: RenderPipeline) {
        val uniforms = BindGroupLayout.flattenUniforms(pipeline.bindGroupLayouts)
        val samplers = BindGroupLayout.flattenSamplers(pipeline.bindGroupLayouts)
        for (buffer in stage.uniformBuffers) {
            if (uniforms.none { it.name() == buffer.name }) throw ShaderException("Unable to find shader defined uniform (${buffer.name})")
            if (entries.none { it.kind == BindingEntry.UNIFORM_BUFFER && it.name == buffer.name }) {
                entries.add(BindingEntry(BindingEntry.UNIFORM_BUFFER, buffer.name, null, entries.count { it.kind == BindingEntry.UNIFORM_BUFFER }))
            }
        }
        for (image in stage.images) {
            val uniform = uniforms.firstOrNull { it.name() == image.name }
            if (uniform != null) {
                if (image.dimension != DIMENSION_BUFFER) throw ShaderException("UTB (${image.name}) must have type of SpvDimBuffer")
                if (entries.none { it.kind == BindingEntry.TEXEL_BUFFER && it.name == image.name }) {
                    entries.add(BindingEntry(BindingEntry.TEXEL_BUFFER, image.name, uniform.gpuFormat(), textureCount(entries)))
                }
            } else {
                if (image.name !in samplers) throw ShaderException("Unable to find shader defined uniform (${image.name})")
                if (image.dimension != DIMENSION_2D && image.dimension != DIMENSION_CUBE) {
                    throw ShaderException("Sampled texture (${image.name}) must have type of SpvDim2D or SpvDimCube")
                }
                if (entries.none { it.kind == BindingEntry.TEXTURE && it.name == image.name }) {
                    entries.add(BindingEntry(BindingEntry.TEXTURE, image.name, null, textureCount(entries)))
                }
            }
        }
    }

    private fun textureCount(entries: List<BindingEntry>): Int = entries.count { it.kind != BindingEntry.UNIFORM_BUFFER }

    private fun assignVertexInputs(vertex: Stage, pipeline: RenderPipeline) {
        val remaining = vertex.inputs.mapTo(HashSet()) { it.name }
        var location = 0
        var previousName: String? = null
        for (format in pipeline.vertexFormatBindings) {
            if (format == null) continue
            for (element in format.elements) {
                val input = vertex.inputs.firstOrNull { it.name == element.name() } ?: continue
                // A matrix attribute spans several elements with one name; only its first column is declared.
                if (element.name() != previousName) {
                    Spvc.spvc_compiler_set_decoration(vertex.compiler, input.id, DECORATION_LOCATION, location)
                    remaining.remove(element.name())
                }
                location++
                previousName = element.name()
            }
        }
        if (remaining.isNotEmpty()) throw ShaderException("Shader expects input variables which are not being provided: $remaining")
    }

    private fun assignBindings(stage: Stage, entries: List<BindingEntry>) {
        for (resource in stage.uniformBuffers) {
            bind(stage, resource, entries.firstOrNull { it.kind == BindingEntry.UNIFORM_BUFFER && it.name == resource.name })
        }
        for (resource in stage.images) {
            bind(stage, resource, entries.firstOrNull { it.kind != BindingEntry.UNIFORM_BUFFER && it.name == resource.name })
        }
    }

    private fun bind(stage: Stage, resource: Resource, entry: BindingEntry?) {
        if (entry == null) throw ShaderException("No binding for ${resource.name}")
        Spvc.spvc_compiler_set_decoration(stage.compiler, resource.id, DECORATION_DESCRIPTOR_SET, 0)
        Spvc.spvc_compiler_set_decoration(stage.compiler, resource.id, DECORATION_BINDING, entry.index)
    }

    private class Resource(val id: Int, val name: String, val dimension: Int)

    /**
     * One shader stage loaded into SPIRV-Cross, with the resources it declares, or with [usedOnly] only
     * those its code really uses (large shaders declare many more textures than Metal has room for).
     */
    private class Stage(spirv: ByteBuffer, usedOnly: Boolean = false) : AutoCloseable {
        val context: Long
        val compiler: Long
        val uniformBuffers: List<Resource>
        val storageBuffers: List<Resource>
        val images: List<Resource>
        val inputs: List<Resource>
        val outputs: List<Resource>

        init {
            MemoryStack.stackPush().use { stack ->
                val pointer = stack.callocPointer(1)
                if (Spvc.spvc_context_create(pointer) != 0) throw ShaderException("Couldn't create spvc context")
                context = pointer.get(0)
                try {
                    expect(Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(), (spirv.remaining() / 4).toLong(), pointer), "Couldn't parse spirv")
                    val ir = pointer.get(0)
                    expect(
                        Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer),
                        "Couldn't create compiler",
                    )
                    compiler = pointer.get(0)
                    if (usedOnly) {
                        expect(Spvc.spvc_compiler_get_active_interface_variables(compiler, pointer), "Couldn't find the used variables")
                        val active = pointer.get(0)
                        expect(Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, pointer, active), "Couldn't create resource list")
                    } else {
                        expect(Spvc.spvc_compiler_create_shader_resources(compiler, pointer), "Couldn't create resource list")
                    }
                    val resources = pointer.get(0)
                    if (usedOnly) {
                        expect(Spvc.spvc_compiler_get_active_interface_variables(compiler, pointer), "Couldn't find the used variables")
                        expect(Spvc.spvc_compiler_set_enabled_interface_variables(compiler, pointer.get(0)), "Couldn't limit the interface")
                    }
                    uniformBuffers = list(stack, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, false)
                    storageBuffers = list(stack, resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, false)
                    images = list(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, true)
                    inputs = list(stack, resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, false)
                    outputs = list(stack, resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, false)
                } catch (t: Throwable) {
                    Spvc.spvc_context_destroy(context)
                    throw t
                }
            }
        }

        private fun list(stack: MemoryStack, resources: Long, type: Int, withDimension: Boolean): List<Resource> {
            val listPointer = stack.callocPointer(1)
            val countPointer = stack.callocPointer(1)
            expect(Spvc.spvc_resources_get_resource_list_for_type(resources, type, listPointer, countPointer), "Couldn't list shader resources")
            val count = countPointer.get(0).toInt()
            if (count == 0) return emptyList()
            val reflected = SpvcReflectedResource.create(listPointer.get(0), count)
            return List(count) { index ->
                val resource = reflected.get(index)
                val dimension = if (withDimension) {
                    Spvc.spvc_type_get_image_dimension(Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id()))
                } else {
                    -1
                }
                Resource(resource.id(), resource.nameString(), dimension)
            }
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
                expect(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true), "texture buffers")
                // Metal's clip space has y pointing up. The game stores images like Vulkan does, so flip.
                expect(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, flipY), "vertex flip")
                expect(Spvc.spvc_compiler_install_compiler_options(compiler, options), "Couldn't install compiler options")
                expect(Spvc.spvc_compiler_compile(compiler, pointer), "Couldn't generate MSL")
                return MemoryUtil.memUTF8(pointer.get(0))
            }
        }

        private fun expect(result: Int, what: String) {
            if (result != 0) throw ShaderException("$what: ${Spvc.spvc_context_get_last_error_string(context)}")
        }

        override fun close() {
            Spvc.spvc_context_destroy(context)
        }
    }

    private companion object {
        // SPIR-V decoration and image dimension numbers.
        const val DECORATION_LOCATION = 30
        const val DECORATION_BINDING = 33
        const val DECORATION_DESCRIPTOR_SET = 34
        const val DIMENSION_2D = 1
        const val DIMENSION_CUBE = 3
        const val DIMENSION_BUFFER = 5

        /** Metal has 31 buffer slots per stage; the top ones are left to vertex buffers. */
        const val MAX_UNIFORM_BUFFERS = 16

        /** Metal allows 16 samplers per stage. */
        const val MAX_TEXTURES = 16

        /** MSL 2.3 (macOS 11), encoded as major * 10000 + minor * 100. The mod's minimum is macOS 14. */
        const val MSL_VERSION = 20300
    }
}
