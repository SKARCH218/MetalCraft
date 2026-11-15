package dev.skarch.metalcraft.gametest.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Clouds drift with the world age, which differs from run to run. The harness always draws them at age zero. */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @ModifyVariable(method = "addCloudsPass", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private long metalcraft$pinCloudTime(long gameTime) {
        return 0L;
    }

    @ModifyVariable(method = "addCloudsPass", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float metalcraft$pinCloudPartialTicks(float partialTicks) {
        return 0.0F;
    }
}
