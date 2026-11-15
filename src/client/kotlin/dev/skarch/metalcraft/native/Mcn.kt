package dev.skarch.metalcraft.native

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * Downcall handles for the renderer part of the native library (`Renderer.swift`).
 *
 * Handles to native objects are passed as `Long`. Every function returns an `Int` status, 0 on success;
 * pass it to [check] unless the call cannot fail. Only touch this object after [NativeBridge.initialize]
 * succeeded.
 */
internal object Mcn {
    private val linker = Linker.nativeLinker()
    private val L = JAVA_LONG
    private val I = JAVA_INT
    private val F = JAVA_FLOAT
    private val D = JAVA_DOUBLE
    private val A = ADDRESS

    @JvmField val deviceCreate = bind("mcn_device_create", A)
    @JvmField val deviceDestroy = bind("mcn_device_destroy", L)
    @JvmField val deviceInfo = bind("mcn_device_info", L, A, I, A)
    @JvmField val release = bind("mcn_release", L)

    @JvmField val bufferCreate = bind("mcn_buffer_create", L, L, A, A, A)
    @JvmField val textureCreate = bind("mcn_texture_create", L, I, I, I, I, I, I, I, A, A)
    @JvmField val textureViewCreate = bind("mcn_texture_view_create", L, I, I, I, A)
    @JvmField val texelBufferCreate = bind("mcn_texel_buffer_create", L, I, L, L, I, A)
    @JvmField val samplerCreate = bind("mcn_sampler_create", L, I, I, I, I, I, I, F, A)
    @JvmField val shaderPairCreate = bind("mcn_shader_pair_create", L, A, A, A)
    @JvmField val pipelineCreate = bind("mcn_pipeline_create", L, L, A, I, A, A)

    @JvmField val passBegin = bind("mcn_pass_begin", L, I, A, I, A, L, I, D, A)
    @JvmField val passEnd = bind("mcn_pass_end", L)
    @JvmField val passSetPipeline = bindFast("mcn_pass_set_pipeline", L, L, I, I, F, F)
    @JvmField val passSetScissor = bindFast("mcn_pass_set_scissor", L, I, I, I, I)
    @JvmField val passSetBuffer = bindFast("mcn_pass_set_buffer", L, I, L, L)
    @JvmField val passSetVertexBuffer = bindFast("mcn_pass_set_vertex_buffer", L, I, L, L)
    @JvmField val passSetTexture = bindFast("mcn_pass_set_texture", L, I, L, L)
    @JvmField val passDraw = bindFast("mcn_pass_draw", L, I, I, I, I, I)
    @JvmField val passDrawIndexed = bindFast("mcn_pass_draw_indexed", L, I, I, I, L, L, I, I, I)
    @JvmField val passClearRect = bind("mcn_pass_clear_rect", L, I, I, F, F, F, F, F, I, I, I, I)

    @JvmField val copyBuffer = bind("mcn_copy_buffer", L, L, L, L, L, L)
    @JvmField val copyBufferToTexture = bind("mcn_copy_buffer_to_texture", L, L, L, I, I, I, L, I, I, I, I)
    @JvmField val copyTextureToBuffer = bind("mcn_copy_texture_to_buffer", L, L, I, I, I, I, I, L, L, I)
    @JvmField val copyTexture = bind("mcn_copy_texture", L, L, L, I, I, I, I, I, I, I)
    @JvmField val clearTextures = bind("mcn_clear_textures", L, L, F, F, F, F, L, D, I)

    @JvmField val upscalerCreate = bind("mcn_upscaler_create", L, I, I, I, I, I, A)
    @JvmField val upscalerEncode = bind("mcn_upscaler_encode", L, L, L, L, L, F, F, I, L)

    @JvmField val submit = bind("mcn_submit", L, A)
    @JvmField val waitSubmit = bind("mcn_wait_submit", L, L, L)

    @JvmField val surfaceCreate = bind("mcn_surface_create", L, L, A)
    @JvmField val surfaceConfigure = bind("mcn_surface_configure", L, I, I, I)
    @JvmField val surfaceAcquire = bind("mcn_surface_acquire", L)
    @JvmField val surfaceBlit = bind("mcn_surface_blit", L, L, L)
    @JvmField val surfaceDestroy = bind("mcn_surface_destroy", L)

    /** Throws [NativeException] with the native error message unless [code] is 0. */
    fun check(code: Int) {
        if (code != 0) throw NativeException(code, NativeBridge.lastError())
    }

    private fun bind(name: String, vararg arguments: MemoryLayout): MethodHandle = bind(name, arguments, false)

    /**
     * For the small per-draw functions: skips the thread state transition of a normal downcall. The native
     * side must return quickly and must not call back into Java.
     */
    private fun bindFast(name: String, vararg arguments: MemoryLayout): MethodHandle = bind(name, arguments, true)

    private fun bind(name: String, arguments: Array<out MemoryLayout>, fast: Boolean): MethodHandle {
        val symbol = NativeLibrary.lookup.find(name).orElseThrow { UnsatisfiedLinkError("Native symbol $name not found") }
        val descriptor = FunctionDescriptor.of(JAVA_INT, *arguments)
        return if (fast) linker.downcallHandle(symbol, descriptor, Linker.Option.critical(false)) else linker.downcallHandle(symbol, descriptor)
    }
}
