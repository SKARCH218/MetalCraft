package dev.skarch.metalcraft.gametest

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import kotlin.math.cos
import kotlin.math.sin

/** Where the player stands and looks for a scene. [y] is the feet position. */
class Viewpoint(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

/**
 * Server-side searches that turn "a cave" or "open space in the Nether" into fixed coordinates.
 * They are deterministic for a given world seed, so every backend gets the same viewpoint.
 */
internal object WorldProbe {
    private const val POCKET_CELLS = 2500
    private const val POCKET_HALF_WIDTH = 20
    private const val POCKET_HALF_HEIGHT = 10
    private const val VIEW_DISTANCE = 48

    /**
     * Finds the closest large air pocket without sky access around ([centerX], [centerZ]) and returns a
     * viewpoint standing on its floor, looking along its longest open direction.
     */
    fun findCavern(level: ServerLevel, centerX: Int, centerZ: Int, radius: Int, minY: Int, maxY: Int): Viewpoint? {
        val columns = ArrayList<IntArray>()
        for (dx in -radius..radius step 8) {
            for (dz in -radius..radius step 8) columns.add(intArrayOf(dx, dz))
        }
        columns.sortBy { it[0] * it[0] + it[1] * it[1] }

        val cursor = BlockPos.MutableBlockPos()
        for (column in columns) {
            for (y in minY..maxY step 6) {
                cursor.set(centerX + column[0], y, centerZ + column[1])
                if (!level.getBlockState(cursor).isAir || level.canSeeSky(cursor)) continue
                if (!isLargePocket(level, cursor.immutable())) continue
                val feet = floorBelow(level, cursor.immutable()) ?: continue
                return Viewpoint(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, longestOpenYaw(level, feet), 5f)
            }
        }
        return null
    }

    /** Places torches on the floor ahead of [view] so the scene has block light that does not depend on the seed. */
    fun placeTorchesAhead(level: ServerLevel, view: Viewpoint, distances: IntArray) {
        val radians = Math.toRadians(view.yaw.toDouble())
        for (distance in distances) {
            val start = BlockPos.containing(view.x - sin(radians) * distance, view.y + 1.0, view.z + cos(radians) * distance)
            if (!level.getBlockState(start).isAir) continue
            val floor = floorBelow(level, start) ?: continue
            level.setBlockAndUpdate(floor, Blocks.TORCH.defaultBlockState())
        }
    }

    private fun isLargePocket(level: ServerLevel, origin: BlockPos): Boolean {
        val visited = LongOpenHashSet()
        val queue = LongArrayFIFOQueue()
        val cursor = BlockPos.MutableBlockPos()
        visited.add(origin.asLong())
        queue.enqueue(origin.asLong())
        while (!queue.isEmpty) {
            if (visited.size >= POCKET_CELLS) return true
            val packed = queue.dequeueLong()
            val x = BlockPos.getX(packed)
            val y = BlockPos.getY(packed)
            val z = BlockPos.getZ(packed)
            for (direction in Direction.entries) {
                val nx = x + direction.stepX
                val ny = y + direction.stepY
                val nz = z + direction.stepZ
                if (nx - origin.x !in -POCKET_HALF_WIDTH..POCKET_HALF_WIDTH) continue
                if (nz - origin.z !in -POCKET_HALF_WIDTH..POCKET_HALF_WIDTH) continue
                if (ny - origin.y !in -POCKET_HALF_HEIGHT..POCKET_HALF_HEIGHT) continue
                val neighbour = BlockPos.asLong(nx, ny, nz)
                if (visited.contains(neighbour)) continue
                if (!level.getBlockState(cursor.set(nx, ny, nz)).isAir) continue
                visited.add(neighbour)
                queue.enqueue(neighbour)
            }
        }
        return false
    }

    /** Lowest air block under [from] that has a solid, dry floor and headroom, or null (lava lake, bottomless drop). */
    private fun floorBelow(level: ServerLevel, from: BlockPos): BlockPos? {
        val cursor = from.mutable()
        repeat(40) {
            val below = level.getBlockState(cursor.below())
            if (!below.isAir) {
                val standable = below.isFaceSturdy(level, cursor.below(), Direction.UP) && below.fluidState.isEmpty && level.getBlockState(cursor.above()).isAir
                return if (standable) cursor.immutable() else null
            }
            cursor.move(Direction.DOWN)
        }
        return null
    }

    private fun longestOpenYaw(level: ServerLevel, feet: BlockPos): Float {
        val cursor = BlockPos.MutableBlockPos()
        var bestYaw = 0f
        var bestLength = -1
        for (step in 0 until 16) {
            val yaw = step * 22.5f - 180f
            val radians = Math.toRadians(yaw.toDouble())
            var length = 0
            while (length < VIEW_DISTANCE) {
                val distance = length + 1
                cursor.set(
                    BlockPos.containing(feet.x + 0.5 - sin(radians) * distance, feet.y + 1.0, feet.z + 0.5 + cos(radians) * distance),
                )
                if (!level.getBlockState(cursor).isAir) break
                length++
            }
            if (length > bestLength) {
                bestLength = length
                bestYaw = yaw
            }
        }
        return bestYaw
    }
}
