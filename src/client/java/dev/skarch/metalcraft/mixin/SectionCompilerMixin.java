package dev.skarch.metalcraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.BufferBuilder;
import dev.skarch.metalcraft.pipeline.TerrainVertexFormat;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** After each block and fluid is meshed, completes its vertices with what shader packs need. */
@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {
    @Inject(
        method = "compile",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate", shift = At.Shift.AFTER)
    )
    private void metalcraft$afterFluid(
        CallbackInfoReturnable<SectionCompiler.Results> cir,
        @Local Map<ChunkSectionLayer, BufferBuilder> startedLayers,
        @Local FluidState fluidState
    ) {
        TerrainVertexFormat.fillLayers(startedLayers, fluidState.createLegacyBlock());
    }

    @Inject(
        method = "compile",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock", shift = At.Shift.AFTER)
    )
    private void metalcraft$afterBlock(
        CallbackInfoReturnable<SectionCompiler.Results> cir,
        @Local Map<ChunkSectionLayer, BufferBuilder> startedLayers,
        @Local BlockState blockState
    ) {
        TerrainVertexFormat.fillLayers(startedLayers, blockState);
    }
}
