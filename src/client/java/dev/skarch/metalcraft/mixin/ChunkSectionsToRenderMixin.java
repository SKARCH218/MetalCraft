package dev.skarch.metalcraft.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.skarch.metalcraft.pipeline.ShaderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the shader pack engine when the opaque world is complete: right before translucent terrain is drawn. */
@Mixin(ChunkSectionsToRender.class)
public class ChunkSectionsToRenderMixin {
    @Inject(method = "renderGroup", at = @At("HEAD"))
    private void metalcraft$beforeGroup(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        ShaderPipeline.beforeChunkGroup(group);
    }
}
