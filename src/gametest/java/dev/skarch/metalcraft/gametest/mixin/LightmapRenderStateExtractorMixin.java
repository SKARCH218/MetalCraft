package dev.skarch.metalcraft.gametest.mixin;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block light flickers by a random walk; pinning it keeps torch-lit pixels identical between runs. */
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapRenderStateExtractorMixin {
    @Shadow
    private float blockLightFlicker;

    @Inject(method = "tick", at = @At("TAIL"))
    private void metalcraft$pinFlicker(CallbackInfo ci) {
        this.blockLightFlicker = 0.0F;
    }
}
