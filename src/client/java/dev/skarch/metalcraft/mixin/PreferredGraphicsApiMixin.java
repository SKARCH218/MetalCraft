package dev.skarch.metalcraft.mixin;

import net.minecraft.client.PreferredGraphicsApi;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds "Metal" to the Graphics API choices in the video settings. The new constant is appended right after
 * the game fills its list, before the codec and the option read it.
 */
@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixin {
    @Shadow @Final @Mutable private static PreferredGraphicsApi[] $VALUES;

    @Invoker("<init>")
    private static PreferredGraphicsApi metalcraft$create(String name, int ordinal, String serializedName, String key) {
        throw new AssertionError();
    }

    @Inject(
        method = "<clinit>",
        at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC, target = "Lnet/minecraft/client/PreferredGraphicsApi;$VALUES:[Lnet/minecraft/client/PreferredGraphicsApi;", shift = At.Shift.AFTER)
    )
    private static void metalcraft$addMetal(CallbackInfo ci) {
        PreferredGraphicsApi[] values = new PreferredGraphicsApi[$VALUES.length + 1];
        System.arraycopy($VALUES, 0, values, 0, $VALUES.length);
        values[$VALUES.length] = metalcraft$create("METAL", $VALUES.length, "metal", "options.graphicsApi.metal");
        $VALUES = values;
    }
}
