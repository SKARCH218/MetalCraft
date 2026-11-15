package dev.skarch.metalcraft.native

import dev.skarch.metalcraft.MetalCraft.LOGGER
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

class NativeException(val code: Int, message: String) : RuntimeException("Native call failed ($code): $message")

/**
 * Downcall handles for the `mcn_*` C ABI exported by the Swift library.
 *
 * They are static finals so the JIT can inline the calls. First access loads the dylib, so this object must
 * only be touched through [NativeBridge.initialize], which turns a failed load into a disabled bridge.
 */
private object Symbols {
    private val linker = Linker.nativeLinker()
    private val lookup = NativeLibrary.load()

    @JvmField
    val abiVersion: MethodHandle = bind("mcn_abi_version", FunctionDescriptor.of(JAVA_INT))

    @JvmField
    val describe: MethodHandle = bind("mcn_describe", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))

    @JvmField
    val lastError: MethodHandle = bind("mcn_last_error", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))

    @JvmField
    val surfaceCreate: MethodHandle = bind("mcn_surface_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))

    @JvmField
    val surfaceDestroy: MethodHandle = bind("mcn_surface_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS))

    @JvmField
    val surfaceSyncSize: MethodHandle = bind("mcn_surface_sync_size", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))

    @JvmField
    val surfaceSetProbeShader: MethodHandle =
        bind("mcn_surface_set_probe_shader", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))

    @JvmField
    val surfaceDrawProbeFrame: MethodHandle = bind(
        "mcn_surface_draw_probe_frame",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, ADDRESS),
    )

    private fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name).orElseThrow { UnsatisfiedLinkError("Native symbol $name not found") }
        return linker.downcallHandle(symbol, descriptor)
    }
}

object NativeBridge {
    private const val EXPECTED_ABI = 1
    private const val MESSAGE_CAPACITY = 512

    @Volatile
    var isAvailable = false
        private set

    /** Loads the native library and checks its ABI revision. Never throws. */
    fun initialize(): Boolean {
        if (isAvailable) return true
        try {
            val abi = Symbols.abiVersion.invokeExact() as Int
            if (abi != EXPECTED_ABI) {
                LOGGER.error("Native library ABI mismatch: expected {}, found {}", EXPECTED_ABI, abi)
                return false
            }
            isAvailable = true
        } catch (t: Throwable) {
            LOGGER.error("Failed to load the MetalCraft native library", t)
        }
        return isAvailable
    }

    fun describe(): String {
        checkAvailable()
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(MESSAGE_CAPACITY.toLong())
            val code = Symbols.describe.invokeExact(buffer, MESSAGE_CAPACITY) as Int
            if (code != 0) throw NativeException(code, lastError())
            return buffer.getString(0)
        }
    }

    /** Message recorded by the last failed native call on the calling thread. */
    fun lastError(): String {
        checkAvailable()
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(MESSAGE_CAPACITY.toLong())
            val length = Symbols.lastError.invokeExact(buffer, MESSAGE_CAPACITY) as Int
            return if (length > 0) buffer.getString(0) else "unknown error"
        }
    }

    /** Attaches a Metal layer to the given `NSWindow*` and returns the surface handle. */
    internal fun surfaceCreate(nsWindow: Long): MemorySegment {
        checkAvailable()
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(ADDRESS)
            check(Symbols.surfaceCreate.invokeExact(MemorySegment.ofAddress(nsWindow), out) as Int)
            return out.get(ADDRESS, 0)
        }
    }

    internal fun surfaceDestroy(handle: MemorySegment) {
        check(Symbols.surfaceDestroy.invokeExact(handle) as Int)
    }

    /** Fits the drawable to the window and stores its size in pixels into `size[0]` (width) and `size[1]` (height). */
    internal fun surfaceSyncSize(handle: MemorySegment, size: IntArray) {
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(JAVA_INT, 2)
            check(Symbols.surfaceSyncSize.invokeExact(handle, out, out.asSlice(JAVA_INT.byteSize())) as Int)
            size[0] = out.getAtIndex(JAVA_INT, 0)
            size[1] = out.getAtIndex(JAVA_INT, 1)
        }
    }

    internal fun surfaceSetProbeShader(handle: MemorySegment, source: String) {
        Arena.ofConfined().use { arena ->
            check(Symbols.surfaceSetProbeShader.invokeExact(handle, arena.allocateFrom(source)) as Int)
        }
    }

    internal fun surfaceDrawProbeFrame(handle: MemorySegment, red: Float, green: Float, blue: Float, capturePath: String?) {
        if (capturePath == null) {
            check(Symbols.surfaceDrawProbeFrame.invokeExact(handle, red, green, blue, MemorySegment.NULL) as Int)
            return
        }
        Arena.ofConfined().use { arena ->
            check(Symbols.surfaceDrawProbeFrame.invokeExact(handle, red, green, blue, arena.allocateFrom(capturePath)) as Int)
        }
    }

    private fun check(code: Int) {
        if (code != 0) throw NativeException(code, lastError())
    }

    private fun checkAvailable() {
        check(isAvailable) { "Native bridge is not initialized" }
    }
}
