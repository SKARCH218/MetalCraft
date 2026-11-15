package dev.skarch.metalcraft.gametest

/** Switches read by the harness mixins. Written and read on the render thread only. */
object Determinism {
    /** While true, every animated texture stays on its first frame. */
    @JvmField
    var holdAnimations = false
}
