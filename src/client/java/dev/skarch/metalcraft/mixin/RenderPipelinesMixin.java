package dev.skarch.metalcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReceiver;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.skarch.metalcraft.pipeline.TerrainVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Gives the terrain pipelines the longer vertex that shader packs need (see TerrainVertexFormat). */
@Mixin(RenderPipelines.class)
public class RenderPipelinesMixin {
    @ModifyReceiver(
        method = "<clinit>",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;withVertexShader(Ljava/lang/String;)"
                + "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"
        )
    )
    private static RenderPipeline.Builder metalcraft$terrainVertexFormat(RenderPipeline.Builder builder, String vertexShader) {
        return TerrainVertexFormat.apply(builder, vertexShader);
    }
}
