package dev.skarch.metalcraft.gametest

/** Frame-time statistics for one measurement window. Times are milliseconds. */
class FrameStats(
    val frames: Int,
    val seconds: Double,
    val averageFps: Double,
    val onePercentLowFps: Double,
    val averageFrameTimeMs: Double,
    val p99FrameTimeMs: Double,
    val maxFrameTimeMs: Double,
)

/**
 * Collects frame durations on the render thread without allocating.
 *
 * [start] and [stop] must run on the render thread too (through `runOnClient`), so no frame is recorded while
 * the window is being opened or closed.
 */
object FrameRecorder {
    // 2^20 frames cover a 30 second window up to roughly 35,000 FPS; frames beyond that are dropped and counted.
    private const val CAPACITY = 1 shl 20

    private val durations = LongArray(CAPACITY)
    private var count = 0
    private var dropped = 0
    private var recording = false

    @JvmStatic
    fun record(frameDurationNanos: Long) {
        if (!recording) return
        if (count < CAPACITY) durations[count++] = frameDurationNanos else dropped++
    }

    fun start() {
        count = 0
        dropped = 0
        recording = true
    }

    fun stop(): FrameStats {
        recording = false
        check(dropped == 0) { "Frame recorder overflowed: $dropped frames were not recorded" }
        check(count > 0) { "No frames were rendered during the measurement window" }

        val sorted = durations.copyOf(count)
        sorted.sort()
        var totalNanos = 0L
        for (d in sorted) totalNanos += d

        // "1% low" is the average frame rate of the slowest 1% of frames.
        val slowCount = maxOf(1, count / 100)
        var slowNanos = 0L
        for (i in count - slowCount until count) slowNanos += sorted[i]

        // Nearest-rank percentile: the smallest value that at least 99% of the frames do not exceed.
        val p99Index = (Math.ceil(count * 0.99).toInt() - 1).coerceIn(0, count - 1)
        return FrameStats(
            frames = count,
            seconds = totalNanos / 1e9,
            averageFps = count / (totalNanos / 1e9),
            onePercentLowFps = slowCount / (slowNanos / 1e9),
            averageFrameTimeMs = totalNanos / 1e6 / count,
            p99FrameTimeMs = sorted[p99Index] / 1e6,
            maxFrameTimeMs = sorted[count - 1] / 1e6,
        )
    }
}
