package dev.skarch.metalcraft.backend

import dev.skarch.metalcraft.MetalCraft.LOGGER

/**
 * Where the render thread waits inside the backend, logged every [REPORT_EVERY] frames when the game runs
 * with `-Dmetalcraft.debug.timing=true`. Off by default; the counters cost nothing then.
 */
internal object FrameTiming {
    @JvmField val enabled: Boolean = java.lang.Boolean.getBoolean("metalcraft.debug.timing")

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
