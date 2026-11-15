package dev.skarch.metalcraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.skarch.metalcraft.pipeline.ShaderPipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the shader pack engine where the world part of a frame starts and ends. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    private static final String RENDER_LEVEL_CALL =
        "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
            + "Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;"
            + "Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V";

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_LEVEL_CALL))
    private void metalcraft$beforeWorld(
        DeltaTracker deltaTracker, CallbackInfo ci, @Local CameraRenderState cameraState, @Local Matrix4f projectionMatrix
    ) {
        ShaderPipeline.beginWorld(cameraState, projectionMatrix);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_LEVEL_CALL, shift = At.Shift.AFTER))
    private void metalcraft$afterWorld(DeltaTracker deltaTracker, CallbackInfo ci) {
        ShaderPipeline.endWorld();
    }
}
