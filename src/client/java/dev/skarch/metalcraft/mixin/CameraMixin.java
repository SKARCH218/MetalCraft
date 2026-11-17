package dev.skarch.metalcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.skarch.metalcraft.zoom.Zoom;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
public class CameraMixin {
    @ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
    private float metalcraft$zoom(float fov) {
        return Zoom.apply(fov);
    }
}
