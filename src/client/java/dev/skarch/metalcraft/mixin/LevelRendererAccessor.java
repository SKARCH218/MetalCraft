package dev.skarch.metalcraft.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the shader pack engine look up terrain sections the camera is not looking at (shadow casters). */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("viewArea")
    @Nullable ViewArea metalcraft$viewArea();
}
