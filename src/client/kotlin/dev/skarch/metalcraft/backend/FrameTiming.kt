package dev.skarch.metalcraft.backend

import dev.skarch.metalcraft.MetalCraft.LOGGER

/**
 * Where the render thread waits inside the backend, logged every [REPORT_EVERY] frames when the game runs
 * with `-Dmetalcraft.debug.timing=true`. Off by default; the counters cost nothing then.
 */
internal object FrameTiming {
    @JvmField val enabled: Boolean = java.lang.Boolean.getBoolean("metalcraft.debug.timing")

    /**
     * With METALCRAFT_GPU_TIMING=1 in the environment the native side measures GPU time per render pass and
     * prints it every few seconds. Passes are then given their names, which costs a lookup per pass.
     */
    @JvmField val gpuPasses: Boolean = System.getenv("METALCRAFT_GPU_TIMING") == "1"
    private val passNames = HashMap<String, java.lang.foreign.MemorySegment>()

    // The descriptor keeps its label private; a debugging run can afford the reflection.
    private val labelField by lazy {
        com.mojang.blaze3d.systems.RenderPassDescriptor::class.java.getDeclaredField("label").apply { isAccessible = true }
    }

    fun passName(descriptor: com.mojang.blaze3d.systems.RenderPassDescriptor): java.lang.foreign.MemorySegment {
        val name = (labelField.get(descriptor) as java.util.function.Supplier<*>).get().toString()
        return passNames.getOrPut(name) { java.lang.foreign.Arena.global().allocateFrom(name) }
    }

    const val ACQUIRE = 0
    const val COMMIT = 1
    const val WAIT_GPU = 2
    private val names = arrayOf("acquire drawable", "commit", "wait for GPU")
    private const val REPORT_EVERY = 500

    private val nanos = LongArray(names.size)
    private var frames = 0

    fun add(what: Int, startNanos: Long) {
        nanos[what] += System.nanoTime() - startNanos
    }

    fun frameDone() {
        if (++frames < REPORT_EVERY) return
        val report = StringBuilder("Backend waits per frame:")
        for (i in names.indices) {
            report.append(' ').append(names[i]).append(' ').append(String.format("%.3f", nanos[i] / 1e6 / frames)).append(" ms;")
            nanos[i] = 0
        }
        frames = 0
        LOGGER.info(report.toString())
    }
}
