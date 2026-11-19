package dev.skarch.metalcraft.pipeline

import com.mojang.blaze3d.buffers.GpuBuffer
import dev.skarch.metalcraft.backend.MetalDevice
import dev.skarch.metalcraft.backend.MetalRenderPass
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * What entities cast into a converted pack's shadow map. The game draws entities once, for the camera, and
 * its models cannot be replayed for the light's view, so each entity stands in as boxes: the player (who is
 * not drawn at all in first person) as head, body, arms and legs, every other entity as its bounding box.
 *
 * The boxes are terrain vertices (`TerrainVertexFormat`), so the pack's shadow program draws them as it draws
 * blocks. They are rebuilt every frame into one buffer that lives as long as the pack.
 *
 * Only the faces turned away from the light are drawn. The model is inside its boxes, so it is nearer to the
 * light than what the shadow map holds for it and does not end up in its own shadow.
 */
internal class ShadowCasters(private val device: MetalDevice) {
    private val bytes: ByteBuffer = MemoryUtil.memAlloc(MAX_BOXES * BOX_VERTICES * TerrainVertexFormat.SIZE)
    private val vertices: GpuBuffer =
        device.createBuffer({ "MetalCraft shadow casters" }, GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST, bytes.capacity().toLong())

    // The block the game fills per chunk section: model-view, visibility, atlas size, section origin.
    private val sectionBytes: ByteBuffer = MemoryUtil.memCalloc(SECTION_SIZE)
    private val section: GpuBuffer =
        device.createBuffer({ "MetalCraft shadow caster origin" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, SECTION_SIZE.toLong())

    private var vertexCount = 0
    private var at = 0L

    // The texel every vertex samples: the middle of a block texture without transparent parts.
    private var u = 0f
    private var v = 0f

    // The direction towards the light, in world space.
    private var lightX = 0f
    private var lightY = 1f
    private var lightZ = 0f

    /**
     * Collects this frame's casters around [camera]. [distance] is how far the shadow map reaches,
     * [shadowModelView] the light's view of the world.
     */
    fun update(minecraft: Minecraft, camera: CameraRenderState, distance: Float, shadowModelView: org.joml.Matrix4f) {
        // The light looks down its negative z axis: that axis, in world terms, is the third row of its rotation.
        lightX = shadowModelView.m02()
        lightY = shadowModelView.m12()
        lightZ = shadowModelView.m22()
        vertexCount = 0
        at = MemoryUtil.memAddress(bytes)
        val atlas = minecraft.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS) as? TextureAtlas ?: return
        val sprite = atlas.getSprite(OPAQUE_SPRITE)
        u = (sprite.u0 + sprite.u1) * 0.5f
        v = (sprite.v0 + sprite.v1) * 0.5f

        // Positions are stored relative to the block the camera is in, like the game's own terrain.
        val originX = floor(camera.pos.x)
        val originY = floor(camera.pos.y)
        val originZ = floor(camera.pos.z)
        val limit = distance * distance

        val player = minecraft.player
        if (player != null && minecraft.options.cameraType.isFirstPerson && !player.isInvisible && !player.isSpectator) {
            // In first person the camera is at the player's eyes.
            val feetX = (camera.pos.x - originX).toFloat()
            val feetY = (camera.pos.y - player.eyeHeight - originY).toFloat()
            val feetZ = (camera.pos.z - originZ).toFloat()
            val angle = Math.toRadians(180.0 - player.yBodyRot)
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            val scale = player.bbHeight / 2f
            for (i in 0 until HUMANOID.size step 6) {
                box(
                    feetX, feetY, feetZ, c, s,
                    HUMANOID[i] * scale, HUMANOID[i + 1] * scale, HUMANOID[i + 2] * scale,
                    HUMANOID[i + 3] * scale, HUMANOID[i + 4] * scale, HUMANOID[i + 5] * scale,
                )
            }
        }

        val states = minecraft.gameRenderer.gameRenderState().levelRenderState.entityRenderStates
        for (i in 0 until states.size) {
            if (vertexCount + BOX_VERTICES > MAX_BOXES * BOX_VERTICES) break
            val state = states[i]
            if (state.isInvisible || state.distanceToCameraSq > limit || state.boundingBoxWidth < MIN_SIZE || state.boundingBoxHeight < MIN_SIZE) continue
            val half = state.boundingBoxWidth * 0.5f
            box(
                (state.x - originX).toFloat(), (state.y - originY).toFloat(), (state.z - originZ).toFloat(), 1f, 0f,
                -half, 0f, -half, half, state.boundingBoxHeight, half,
            )
        }
        if (vertexCount == 0) return

        sectionBytes.putFloat(0, 1f).putFloat(20, 1f).putFloat(40, 1f).putFloat(60, 1f)
        sectionBytes.putFloat(64, 1f)
        sectionBytes.putInt(72, 1024).putInt(76, 1024)
        sectionBytes.putInt(80, originX.toInt()).putInt(84, originY.toInt()).putInt(88, originZ.toInt())
        device.encoder.writeToBuffer(section.slice(), sectionBytes)
        bytes.position(0).limit(vertexCount * TerrainVertexFormat.SIZE)
        device.encoder.writeToBuffer(vertices.slice(0, bytes.remaining().toLong()), bytes)
        bytes.clear()
    }

    /** Draws the casters with the shadow program [pass] already has set. */
    fun draw(pass: MetalRenderPass) {
        if (vertexCount == 0) return
        pass.setUniform("ChunkSection", section.slice())
        pass.setVertexBuffer(0, vertices.slice())
        pass.draw(vertexCount, 1, 0, 0)
    }

    fun close() {
        vertices.close()
        section.close()
        MemoryUtil.memFree(bytes)
        MemoryUtil.memFree(sectionBytes)
    }

    /** A box from (x0, y0, z0) to (x1, y1, z1), turned about the vertical axis ([c], [s]) and moved to the base point. */
    private fun box(
        baseX: Float, baseY: Float, baseZ: Float, c: Float, s: Float,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
    ) {
        if (vertexCount + BOX_VERTICES > MAX_BOXES * BOX_VERTICES) return
        for (face in 0 until 6) {
            val faceX = NORMALS[face * 3]
            val faceY = NORMALS[face * 3 + 1]
            val faceZ = NORMALS[face * 3 + 2]
            if ((faceX * c + faceZ * s) * lightX + faceY * lightY + (-faceX * s + faceZ * c) * lightZ >= 0f) continue
            // The underside is lifted a little off the ground the entity stands on.
            val lift = if (face == BOTTOM) GROUND_CLEARANCE else 0f
            val corners = FACES[face]
            for (k in TRIANGLES) {
                val corner = corners[k]
                val x = if (corner and 1 != 0) x1 else x0
                val y = if (corner and 2 != 0) y1 else y0
                val z = if (corner and 4 != 0) z1 else z0
                MemoryUtil.memPutFloat(at, baseX + x * c + z * s)
                MemoryUtil.memPutFloat(at + 4, baseY + y + lift)
                MemoryUtil.memPutFloat(at + 8, baseZ - x * s + z * c)
                MemoryUtil.memPutInt(at + 12, -1) // white
                MemoryUtil.memPutFloat(at + 16, u)
                MemoryUtil.memPutFloat(at + 20, v)
                MemoryUtil.memPutShort(at + 24, 240)
                MemoryUtil.memPutShort(at + 26, 240)
                val nx = NORMALS[face * 3]
                val nz = NORMALS[face * 3 + 2]
                MemoryUtil.memPutByte(at + 28, ((nx * c + nz * s) * 127f).toInt().toByte())
                MemoryUtil.memPutByte(at + 29, (NORMALS[face * 3 + 1] * 127f).toInt().toByte())
                MemoryUtil.memPutByte(at + 30, ((-nx * s + nz * c) * 127f).toInt().toByte())
                MemoryUtil.memPutByte(at + 31, 0)
                MemoryUtil.memPutInt(at + 32, TANGENT)
                // No block ID: packs treat it as an ordinary solid block.
                MemoryUtil.memPutShort(at + 36, -1)
                MemoryUtil.memPutShort(at + 38, 0)
                MemoryUtil.memPutFloat(at + 40, u)
                MemoryUtil.memPutFloat(at + 44, v)
                at += TerrainVertexFormat.SIZE
            }
            vertexCount += 6
        }
    }

    private companion object {
        const val MAX_BOXES = 512
        const val BOX_VERTICES = 36
        const val SECTION_SIZE = 96
        const val MIN_SIZE = 0.2f
        const val BOTTOM = 2
        const val GROUND_CLEARANCE = 0.03f

        /** Tangent (1, 0, 0) with handedness 1, as four signed bytes. */
        const val TANGENT = 0x7F00007F

        val OPAQUE_SPRITE: Identifier = Identifier.withDefaultNamespace("block/stone")

        // Corners are numbered by bits: 1 = far in x, 2 = far in y, 4 = far in z.
        val FACES = arrayOf(
            intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3), intArrayOf(0, 4, 5, 1),
            intArrayOf(2, 3, 7, 6), intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5),
        )
        val NORMALS = floatArrayOf(-1f, 0f, 0f, 1f, 0f, 0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, -1f, 0f, 0f, 1f)
        val TRIANGLES = intArrayOf(0, 1, 2, 0, 2, 3)

        /**
         * The player's shape for a height of 2: legs, body, arms, head, each as two corners (x is across the
         * shoulders, z front to back).
         */
        val HUMANOID = floatArrayOf(
            -0.25f, 0f, -0.125f, 0f, 0.75f, 0.125f,
            0f, 0f, -0.125f, 0.25f, 0.75f, 0.125f,
            -0.25f, 0.75f, -0.125f, 0.25f, 1.5f, 0.125f,
            -0.5f, 0.75f, -0.125f, -0.25f, 1.5f, 0.125f,
            0.25f, 0.75f, -0.125f, 0.5f, 1.5f, 0.125f,
            -0.25f, 1.5f, -0.25f, 0.25f, 2f, 0.25f,
        )
    }
}
