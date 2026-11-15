package dev.skarch.metalcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.skarch.metalcraft.backend.BackendSelector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @ModifyExpressionValue(
        method = "<init>",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/main/GameConfig$GameData;forcedGraphicsApi:Lnet/minecraft/client/PreferredGraphicsApi;"
        )
    )
    private @Nullable PreferredGraphicsApi metalcraft$overrideForcedGraphicsApi(@Nullable PreferredGraphicsApi original) {
        return BackendSelector.overrideForcedApi(original);
    }
}
