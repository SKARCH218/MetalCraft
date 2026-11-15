package dev.skarch.metalcraft.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.skarch.metalcraft.pipeline.TerrainExtrasTarget;
import dev.skarch.metalcraft.pipeline.TerrainVertexFormat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Lets the section compiler hooks complete the terrain vertices a block has just added. */
@Mixin(BufferBuilder.class)
public class BufferBuilderMixin implements TerrainExtrasTarget {
    @Shadow private int vertices;
    @Shadow private long vertexPointer;
    @Shadow @Final private VertexFormat format;

    /** How many vertices already have their extra attributes. */
    @Unique private int metalcraft$completeVertices;

    @Override
    public void metalcraft$fillExtras(int entityId) {
        int fresh = this.vertices - this.metalcraft$completeVertices;
        if (fresh <= 0 || this.format != TerrainVertexFormat.FORMAT) {
            return;
        }
        // Vertices are stored back to back, so the new ones end at the last vertex written.
        long first = this.vertexPointer - (long) (fresh - 1) * TerrainVertexFormat.SIZE;
        TerrainVertexFormat.fill(first, fresh, entityId);
        this.metalcraft$completeVertices = this.vertices;
    }
}
