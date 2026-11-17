package dev.skarch.metalcraft.mixin;

import dev.skarch.metalcraft.zoom.Zoom;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void metalcraft$slowWhileZooming(double mousea, CallbackInfo ci) {
        double scale = Zoom.sensitivity();
        this.accumulatedDX *= scale;
        this.accumulatedDY *= scale;
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void metalcraft$zoomScroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        if (Zoom.scroll(yoffset)) ci.cancel();
    }
}
