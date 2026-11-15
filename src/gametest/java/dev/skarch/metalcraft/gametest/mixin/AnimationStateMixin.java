package dev.skarch.metalcraft.gametest.mixin;

import dev.skarch.metalcraft.gametest.Determinism;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rewinds animated textures (water, lava, fire) to their first frame while a screenshot is being prepared. */
@Mixin(SpriteContents.AnimationState.class)
public class AnimationStateMixin {
    @Shadow
    private int frame;
    @Shadow
    private int subFrame;
    @Shadow
    private boolean isDirty;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void metalcraft$holdFirstFrame(CallbackInfo ci) {
        if (Determinism.holdAnimations) {
            this.frame = 0;
            this.subFrame = 0;
            // Dirty makes the atlas redraw the sprite, so the first frame is really on screen.
            this.isDirty = true;
            ci.cancel();
        }
    }
}
