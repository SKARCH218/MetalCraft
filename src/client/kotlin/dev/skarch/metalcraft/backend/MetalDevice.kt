package dev.skarch.metalcraft.backend

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.preprocessor.GlslPreprocessor
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.systems.CommandEncoderBackend
import com.mojang.blaze3d.systems.DeviceFeatures
import com.mojang.blaze3d.systems.DeviceInfo
import com.mojang.blaze3d.systems.DeviceLimits
import com.mojang.blaze3d.systems.DeviceType
import com.mojang.blaze3d.systems.GpuDeviceBackend
import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.systems.GpuSurfaceBackend
import com.mojang.blaze3d.systems.HintsAndWorkarounds
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.native.Mcn
import dev.skarch.metalcraft.pipeline.ShaderPipeline
import dev.skarch.metalcraft.upscale.Upscaler
import net.minecraft.client.renderer.ShaderDefines
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFWNativeCocoa
import org.lwjgl.system.MemoryUtil
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.OptionalDouble
import java.util.function.Supplier

/** Blaze3D's device, implemented on Metal. Created by [MetalBackend]; used from the render thread only. */
internal class MetalDevice(private val defaultShaderSource: ShaderSource) : GpuDeviceBackend {
    /** Handle of the native context: the Metal device, its command queue and the frame being recorded. */
    val context: Long

    private val info: DeviceInfo
    private val shaderCompiler = MetalShaderCompiler()
    private val pipelines = IdentityHashMap<RenderPipeline, MetalPipeline>()
    private val spirvCache = HashMap<ShaderKey, ByteBuffer?>()

    // Out-parameters for native calls. Render thread only, so one set is enough.
    private val scratch = Arena.global()
    private val handleOut: MemorySegment = scratch.allocate(JAVA_LONG)
    private val contentsOut: MemorySegment = scratch.allocate(JAVA_LONG)

    val encoder: MetalCommandEncoder

    init {
        Mcn.check(Mcn.deviceCreate.invokeExact(handleOut) as Int)
        context = handleOut.get(JAVA_LONG, 0)

        val name: String
        val maxBufferLength: Long
        val maxTextureSize: Int
        Arena.ofConfined().use { arena ->
            val nameBuffer = arena.allocate(256)
            val limits = arena.allocate(JAVA_LONG, 3)
            Mcn.check(Mcn.deviceInfo.invokeExact(context, nameBuffer, 256, limits) as Int)
            name = nameBuffer.getString(0)
            maxBufferLength = limits.getAtIndex(JAVA_LONG, 0)
            maxTextureSize = limits.getAtIndex(JAVA_LONG, 1).toInt()
        }
        info = DeviceInfo(
            name,
            "Apple",
            "Metal (MetalCraft)",
            // Depth runs from 0 to 1, as in Vulkan.
            true,
            MetalBackend.NAME,
            1.0f,
            DeviceLimits(16, UNIFORM_OFFSET_ALIGNMENT, maxTextureSize, maxBufferLength, 0, MetalCommandEncoder.MAX_COLOR_TARGETS),
            // shaderDrawParameters, multiDrawDirectInterleaved, multiDrawDirectSeparate, multiDrawIndirect,
            // drawIndirect, nonZeroFirstInstance, persistentMapping
            DeviceFeatures(false, false, false, false, false, true, true),
            emptySet(),
            HintsAndWorkarounds(false, false),
            DeviceType.INTEGRATED,
        )
        encoder = MetalCommandEncoder(this)
        current = this
    }

    override fun getDeviceInfo(): DeviceInfo = info

    override fun createSurface(windowHandle: Long): GpuSurfaceBackend {
        Mcn.check(Mcn.surfaceCreate.invokeExact(GLFWNativeCocoa.glfwGetCocoaWindow(windowHandle), context, handleOut) as Int)
        return MetalGpuSurface(this, handleOut.get(JAVA_LONG, 0))
    }

    override fun createCommandEncoder(): CommandEncoderBackend = encoder

    // ---- resources --------------------------------------------------------------------------------------

    class NativeBuffer(val handle: Long, val contents: Long)

    /**
     * Allocates at least [requestedSize] bytes, rounded up to 16. Metal pads shader structs to their
     * alignment, so a uniform block of 12 bytes is read as 16; the padding has to exist in the buffer.
     */
    fun createNativeBuffer(requestedSize: Long, label: String?): NativeBuffer {
        val size = (maxOf(requestedSize, 1L) + 15L) and -16L
        if (label == null) {
            Mcn.check(Mcn.bufferCreate.invokeExact(context, size, MemorySegment.NULL, handleOut, contentsOut) as Int)
        } else {
            Arena.ofConfined().use { arena ->
                Mcn.check(Mcn.bufferCreate.invokeExact(context, size, arena.allocateFrom(label), handleOut, contentsOut) as Int)
            }
        }
        return NativeBuffer(handleOut.get(JAVA_LONG, 0), contentsOut.get(JAVA_LONG, 0))
    }

    fun release(handle: Long) {
        Mcn.release.invokeExact(handle) as Int
    }

    override fun createBuffer(label: Supplier<String>?, usage: Int, size: Long): GpuBuffer {
        // Metal has no empty buffers; the game does create some. createNativeBuffer rounds the size up.
        val native = createNativeBuffer(size, null)
        return MetalDirectBuffer(this, native.handle, native.contents, usage, size)
    }

    override fun createBuffer(label: Supplier<String>?, usage: Int, data: ByteBuffer): GpuBuffer {
        val size = data.remaining().toLong()
        val native = createNativeBuffer(size, null)
        // Nothing can have drawn from a buffer that did not exist yet, so the bytes go straight in.
        if (size > 0) MemoryUtil.memCopy(MemoryUtil.memAddress(data), native.contents, size)
        return MetalDirectBuffer(this, native.handle, native.contents, usage or GpuBuffer.USAGE_COPY_DST, size)
    }

    override fun createTexture(label: Supplier<String>?, usage: Int, format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture =
        createTexture(null as String?, usage, format, width, height, depthOrLayers, mipLevels)

    override fun createTexture(label: String?, usage: Int, format: GpuFormat, width: Int, height: Int, depthOrLayers: Int, mipLevels: Int): GpuTexture {
        val cube = usage and GpuTexture.USAGE_CUBEMAP_COMPATIBLE != 0
        var nativeUsage = 0
        if (usage and GpuTexture.USAGE_TEXTURE_BINDING != 0) nativeUsage = nativeUsage or 1
        if (usage and GpuTexture.USAGE_RENDER_ATTACHMENT != 0) nativeUsage = nativeUsage or 2
        Mcn.check(
            Mcn.textureCreate.invokeExact(
                context, MetalFormats.code(format), width, height, depthOrLayers, mipLevels, nativeUsage, if (cube) 1 else 0,
                MemorySegment.NULL, handleOut,
            ) as Int,
        )
        return MetalTexture(this, handleOut.get(JAVA_LONG, 0), usage, label ?: "", format, width, height, depthOrLayers, mipLevels)
    }

    override fun createTextureView(texture: GpuTexture): GpuTextureView = createTextureView(texture, 0, texture.mipLevels)

    override fun createTextureView(texture: GpuTexture, baseMipLevel: Int, mipLevels: Int): GpuTextureView {
        val metalTexture = texture as MetalTexture
        Mcn.check(
            Mcn.textureViewCreate.invokeExact(metalTexture.handle, if (metalTexture.isCube) 1 else 0, baseMipLevel, mipLevels, handleOut) as Int,
        )
        return MetalTextureView(this, metalTexture, handleOut.get(JAVA_LONG, 0), baseMipLevel, mipLevels)
    }

    override fun createSampler(
        addressModeU: AddressMode, addressModeV: AddressMode, minFilter: FilterMode, magFilter: FilterMode, maxAnisotropy: Int, maxLod: OptionalDouble,
    ): GpuSampler {
        // Same rule as the Vulkan backend: a sampler limited to the base level picks mips without blending.
        val lod = maxLod.orElse(1000.0)
        Mcn.check(
            Mcn.samplerCreate.invokeExact(
                context, if (addressModeU == AddressMode.REPEAT) 0 else 1, if (addressModeV == AddressMode.REPEAT) 0 else 1,
                if (minFilter == FilterMode.NEAREST) 0 else 1, if (magFilter == FilterMode.NEAREST) 0 else 1,
                if (lod > 0.25) 1 else 0, maxAnisotropy, maxOf(0.25f, lod.toFloat()), handleOut,
            ) as Int,
        )
        return MetalSampler(this, handleOut.get(JAVA_LONG, 0), addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod)
    }

    // ---- pipelines --------------------------------------------------------------------------------------

    private data class ShaderKey(val id: Identifier, val type: ShaderType, val defines: ShaderDefines)

    override fun precompilePipeline(pipeline: RenderPipeline, shaderSource: ShaderSource?): CompiledRenderPipeline =
        pipelines.getOrPut(pipeline) { compilePipeline(pipeline, shaderSource ?: defaultShaderSource) }

    fun getOrCompilePipeline(pipeline: RenderPipeline): MetalPipeline =
        pipelines.getOrPut(pipeline) { compilePipeline(pipeline, defaultShaderSource) }

    private fun compilePipeline(pipeline: RenderPipeline, source: ShaderSource): MetalPipeline {
        val vertex = spirv(pipeline.vertexShader, ShaderType.VERTEX, pipeline.shaderDefines, source)
        val fragment = spirv(pipeline.fragmentShader, ShaderType.FRAGMENT, pipeline.shaderDefines, source)
        if (vertex == null || fragment == null) {
            val which = if (vertex == null) "vertex shader ${pipeline.vertexShader}" else "fragment shader ${pipeline.fragmentShader}"
            LOGGER.error("Couldn't compile pipeline {}: {} was invalid", pipeline.location, which)
            return MetalPipeline(this, pipeline, null)
        }
        return try {
            MetalPipeline(this, pipeline, shaderCompiler.translate(pipeline, vertex, fragment))
        } catch (e: ShaderException) {
            LOGGER.error("Couldn't compile pipeline {}: {}", pipeline.location, e.message)
            MetalPipeline(this, pipeline, null)
        }
    }

    /** SPIR-V for one shader, or null if it has no source or does not compile. Cached, failures included. */
    private fun spirv(id: Identifier, type: ShaderType, defines: ShaderDefines, source: ShaderSource): ByteBuffer? =
        spirvCache.getOrPut(ShaderKey(id, type, defines)) {
            val glsl = source.get(id, type)
            if (glsl == null) {
                LOGGER.error("Couldn't find source for {} shader ({})", type, id)
                return@getOrPut null
            }
            try {
                shaderCompiler.compileSpirv(id.toDebugFileName(), GlslPreprocessor.injectDefines(glsl, defines), type)
            } catch (e: ShaderException) {
                LOGGER.error("Couldn't compile {} shader {}: {}", type, id, e.message)
                null
            }
        }

    override fun clearPipelineCache() {
        for (pipeline in pipelines.values) pipeline.destroy()
        pipelines.clear()
        for (spirv in spirvCache.values) if (spirv != null) MemoryUtil.memFree(spirv)
        spirvCache.clear()
    }

    // ---- diagnostics ------------------------------------------------------------------------------------

    override fun getLastDebugMessages(): List<String> = emptyList()

    override fun isDebuggingEnabled(): Boolean = false

    override fun createTimestampQueryPool(size: Int): GpuQueryPool = MetalQueryPool(size)

    override fun getTimestampNow(): Long = System.nanoTime()

    override fun close() {
        ShaderPipeline.deviceClosing()
        Upscaler.deviceClosing()
        current = null
        // Pipelines queue their native objects for release; destroying the encoder then releases them.
        clearPipelineCache()
        encoder.destroy()
        shaderCompiler.close()
        Mcn.check(Mcn.deviceDestroy.invokeExact(context) as Int)
    }

    companion object {
        /** The device the game is rendering with, or null when it runs on another backend. */
        @JvmField
        var current: MetalDevice? = null

        /** Metal aligns shader structs to at most 16 bytes, so uniform data may start at any multiple of 16. */
        private const val UNIFORM_OFFSET_ALIGNMENT = 16
    }
}
