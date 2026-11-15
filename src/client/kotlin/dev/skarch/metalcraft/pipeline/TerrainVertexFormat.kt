package dev.skarch.metalcraft.pipeline

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.skarch.metalcraft.backend.BackendSelector
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.world.level.block.state.BlockState
import org.lwjgl.system.MemoryUtil

/** Implemented by `BufferBuilder` (through a mixin) so the vertices a block just added can be completed. */
interface TerrainExtrasTarget {
    /** Fills the extra attributes of every vertex added since the last call. */
    fun `metalcraft$fillExtras`(entityId: Int)
}

/**
 * The terrain vertex layout while the Metal renderer is in use: the game's own block vertex followed by what
 * shader packs need and the game does not store (the `at_tangent`, `mc_Entity` and `mc_midTexCoord` of the
 * Iris pack format).
 *
 * ```
 *  0  Position       3 floats      (game)
 * 12  Color          4 bytes       (game)
 * 16  UV0            2 floats      (game)
 * 24  UV2            2 shorts      (game)
 * 28  McNormal       4 signed bytes, normalised: face normal, w unused
 * 32  McTangent      4 signed bytes, normalised: tangent along +u, w is the handedness (1 or -1)
 * 36  McEntity       2 shorts: the pack's ID of the block (-1 without one), second unused
 * 40  McMidTexCoord  2 floats: atlas coordinate of the middle of the quad
 * ```
 * The game's terrain shaders only declare the first four attributes and ignore the rest. The extra ones are
 * written per quad once the game has stored a block's vertices; see [fill].
 */
object TerrainVertexFormat {
    /** Decided once at start-up: section meshes and the terrain pipelines have to agree for the whole run. */
    @JvmField
    val enabled: Boolean = BackendSelector.metalRequested()

    const val SIZE = 48
    private const val POSITION = 0L
    private const val UV = 16L
    private const val NORMAL = 28L
    private const val TANGENT = 32L
    private const val ENTITY = 36L
    private const val MID_TEX_COORD = 40L

    @JvmField
    val FORMAT: VertexFormat = run {
        val builder = VertexFormat.builder(0)
        for (element in DefaultVertexFormat.BLOCK.elements) builder.addAttribute(element.name(), element.format())
        val format = builder
            .addAttribute("McNormal", GpuFormat.RGBA8_SNORM)
            .addAttribute("McTangent", GpuFormat.RGBA8_SNORM)
            .addAttribute("McEntity", GpuFormat.RG16_SINT)
            .addAttribute("McMidTexCoord", GpuFormat.RG32_FLOAT)
            .build()
        check(DefaultVertexFormat.BLOCK.vertexSize == NORMAL.toInt() && format.vertexSize == SIZE) {
            "The game's block vertex layout changed: ${DefaultVertexFormat.BLOCK}"
        }
        format
    }

    /** The vertex format pack programs that draw terrain are compiled against. */
    @JvmStatic
    fun current(): VertexFormat = if (enabled) FORMAT else DefaultVertexFormat.BLOCK

    /** Called for every pipeline snippet and pipeline the game defines: the terrain ones get the longer vertex. */
    @JvmStatic
    fun apply(builder: RenderPipeline.Builder, vertexShader: String): RenderPipeline.Builder =
        if (enabled && vertexShader == "core/terrain") builder.withVertexBinding(0, FORMAT) else builder

    /** Completes the vertices [state] just added to the section's layers. Runs on the section build threads. */
    @JvmStatic
    fun fillLayers(layers: Map<ChunkSectionLayer, BufferBuilder>, state: BlockState) {
        if (!enabled || layers.isEmpty()) return
        val id = BlockIds.of(state)
        for (builder in layers.values) (builder as TerrainExtrasTarget).`metalcraft$fillExtras`(id)
    }

    /** Writes the extra attributes of [count] vertices (whole quads) starting at address [first]. */
    @JvmStatic
    fun fill(first: Long, count: Int, entityId: Int) {
        var quad = first
        val end = first + (count / 4) * 4L * SIZE
        while (quad < end) {
            fillQuad(quad, entityId)
            quad += 4L * SIZE
        }
    }

    private fun fillQuad(p: Long, entityId: Int) {
        val v1 = p + SIZE
        val v2 = v1 + SIZE
        val v3 = v2 + SIZE
        val x0 = MemoryUtil.memGetFloat(p + POSITION)
        val y0 = MemoryUtil.memGetFloat(p + POSITION + 4)
        val z0 = MemoryUtil.memGetFloat(p + POSITION + 8)
        val u0 = MemoryUtil.memGetFloat(p + UV)
        val w0 = MemoryUtil.memGetFloat(p + UV + 4)
        val u1 = MemoryUtil.memGetFloat(v1 + UV)
        val w1 = MemoryUtil.memGetFloat(v1 + UV + 4)
        val u2 = MemoryUtil.memGetFloat(v2 + UV)
        val w2 = MemoryUtil.memGetFloat(v2 + UV + 4)
        val u3 = MemoryUtil.memGetFloat(v3 + UV)
        val w3 = MemoryUtil.memGetFloat(v3 + UV + 4)

        // Edges from the first corner, and the two diagonals for the normal (also right for bent quads).
        val e1x = MemoryUtil.memGetFloat(v1 + POSITION) - x0
        val e1y = MemoryUtil.memGetFloat(v1 + POSITION + 4) - y0
        val e1z = MemoryUtil.memGetFloat(v1 + POSITION + 8) - z0
        val e2x = MemoryUtil.memGetFloat(v2 + POSITION) - x0
        val e2y = MemoryUtil.memGetFloat(v2 + POSITION + 4) - y0
        val e2z = MemoryUtil.memGetFloat(v2 + POSITION + 8) - z0
        val dx = MemoryUtil.memGetFloat(v3 + POSITION) - MemoryUtil.memGetFloat(v1 + POSITION)
        val dy = MemoryUtil.memGetFloat(v3 + POSITION + 4) - MemoryUtil.memGetFloat(v1 + POSITION + 4)
        val dz = MemoryUtil.memGetFloat(v3 + POSITION + 8) - MemoryUtil.memGetFloat(v1 + POSITION + 8)
        var nx = e2y * dz - e2z * dy
        var ny = e2z * dx - e2x * dz
        var nz = e2x * dy - e2y * dx
        val normalLength = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
        if (normalLength > 1e-12f) {
            nx /= normalLength
            ny /= normalLength
            nz /= normalLength
        } else {
            nx = 0f
            ny = 1f
            nz = 0f
        }

        // The tangent points where u grows on the surface, the bitangent where v grows.
        val du1 = u1 - u0
        val dv1 = w1 - w0
        val du2 = u2 - u0
        val dv2 = w2 - w0
        val det = du1 * dv2 - du2 * dv1
        var tx = e1x
        var ty = e1y
        var tz = e1z
        var handedness = 1f
        if (Math.abs(det) > 1e-14f) {
            tx = (e1x * dv2 - e2x * dv1) / det
            ty = (e1y * dv2 - e2y * dv1) / det
            tz = (e1z * dv2 - e2z * dv1) / det
            val bx = (e2x * du1 - e1x * du2) / det
            val by = (e2y * du1 - e1y * du2) / det
            val bz = (e2z * du1 - e1z * du2) / det
            val cx = ny * tz - nz * ty
            val cy = nz * tx - nx * tz
            val cz = nx * ty - ny * tx
            if (cx * bx + cy * by + cz * bz < 0f) handedness = -1f
        }
        val along = tx * nx + ty * ny + tz * nz
        tx -= nx * along
        ty -= ny * along
        tz -= nz * along
        val tangentLength = Math.sqrt((tx * tx + ty * ty + tz * tz).toDouble()).toFloat()
        if (tangentLength > 1e-12f) {
            tx /= tangentLength
            ty /= tangentLength
            tz /= tangentLength
        } else {
            tx = 1f
            ty = 0f
            tz = 0f
        }

        val normal = pack(nx, ny, nz, 0f)
        val tangent = pack(tx, ty, tz, handedness)
        val midU = (u0 + u1 + u2 + u3) * 0.25f
        val midV = (w0 + w1 + w2 + w3) * 0.25f
        var vertex = p
        for (i in 0 until 4) {
            MemoryUtil.memPutInt(vertex + NORMAL, normal)
            MemoryUtil.memPutInt(vertex + TANGENT, tangent)
            MemoryUtil.memPutShort(vertex + ENTITY, entityId.toShort())
            MemoryUtil.memPutShort(vertex + ENTITY + 2, 0)
            MemoryUtil.memPutFloat(vertex + MID_TEX_COORD, midU)
            MemoryUtil.memPutFloat(vertex + MID_TEX_COORD + 4, midV)
            vertex += SIZE
        }
    }

    /** Four signed normalised bytes, x in the lowest byte (Apple Silicon is little-endian). */
    private fun pack(x: Float, y: Float, z: Float, w: Float): Int =
        snorm(x) or (snorm(y) shl 8) or (snorm(z) shl 16) or (snorm(w) shl 24)

    private fun snorm(value: Float): Int = Math.round(value.coerceIn(-1f, 1f) * 127f) and 0xFF
}
