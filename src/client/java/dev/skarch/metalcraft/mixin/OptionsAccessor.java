package dev.skarch.metalcraft.mixin;

import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Options.class)
public interface OptionsAccessor {
    @Accessor("preferredGraphicsBackendFromStartup")
    void metalcraft$setGraphicsApiFromStartup(PreferredGraphicsApi api);
}
