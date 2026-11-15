package dev.skarch.metalcraft.gametest.mixin;

import dev.skarch.metalcraft.gametest.FrameRecorder;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The game reports the duration of every presented frame here, whether or not the F3 overlay is open. */
@Mixin(DebugScreenOverlay.class)
public class DebugScreenOverlayMixin {
    @Inject(method = "logFrameDuration", at = @At("HEAD"))
    private void metalcraft$recordFrame(long frameDurationNanos, CallbackInfo ci) {
        FrameRecorder.record(frameDurationNanos);
    }
}
