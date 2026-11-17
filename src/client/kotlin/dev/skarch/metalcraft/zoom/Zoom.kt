package dev.skarch.metalcraft.zoom

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import kotlin.math.exp
import kotlin.math.pow

/**
 * Hold-to-zoom: while the key is down the field of view narrows smoothly, the mouse slows down by the same
 * amount, and the scroll wheel changes how far it zooms. Unbinding the key in Controls turns it off.
 */
object Zoom {
    private const val DEFAULT_LEVEL = 4.0
    private const val MIN_LEVEL = 1.5
    private const val MAX_LEVEL = 40.0
    private const val SCROLL_STEP = 1.25

    /** How fast the view follows the target, per second. */
    private const val SPEED = 14.0

    private var key: KeyMapping? = null
    private var level = DEFAULT_LEVEL

    /** The field of view is divided by this: 1 when not zooming. */
    private var current = 1.0
    private var lastNanos = 0L

    fun register() {
        key = KeyMappingHelper.registerKeyMapping(KeyMapping("key.metalcraft.zoom", GLFW.GLFW_KEY_C, KeyMapping.Category.MISC))
    }

    private fun held(): Boolean = key?.isDown == true && Minecraft.getInstance().gui.screen() == null

    /** Called once per frame with the game's field of view; returns the one to use. */
    @JvmStatic
    fun apply(fov: Float): Float {
        val now = System.nanoTime()
        val seconds = if (lastNanos == 0L) 0.0 else ((now - lastNanos) / 1e9).coerceIn(0.0, 0.1)
        lastNanos = now
        val holding = held()
        // Each press starts again from the default distance.
        if (!holding && current < 1.001) level = DEFAULT_LEVEL
        val target = if (holding) level else 1.0
        current += (target - current) * (1.0 - exp(-SPEED * seconds))
        if (!holding && current < 1.001) current = 1.0
        return (fov / current).toFloat()
    }

    /** What mouse movement is multiplied by, so that turning feels the same on screen. */
    @JvmStatic
    fun sensitivity(): Double = 1.0 / current

    /** The scroll wheel while zooming changes the distance. True when the scroll was used up. */
    @JvmStatic
    fun scroll(amount: Double): Boolean {
        if (amount == 0.0 || !held()) return false
        level = (level * SCROLL_STEP.pow(amount.coerceIn(-3.0, 3.0))).coerceIn(MIN_LEVEL, MAX_LEVEL)
        return true
    }
}
