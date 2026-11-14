package dev.skarch.metalcraft.native

import dev.skarch.metalcraft.MetalCraft.LOGGER
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.ADDRESS
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

    private fun checkAvailable() {
        check(isAvailable) { "Native bridge is not initialized" }
    }
}
