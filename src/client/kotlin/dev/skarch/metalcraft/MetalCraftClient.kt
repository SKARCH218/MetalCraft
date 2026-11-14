package dev.skarch.metalcraft

import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.native.NativeBridge
import net.fabricmc.api.ClientModInitializer

class MetalCraftClient : ClientModInitializer {

    override fun onInitializeClient() {
        if (!NativeBridge.initialize()) {
            LOGGER.warn("Native bridge unavailable, Metal rendering stays disabled")
            return
        }
        try {
            LOGGER.info("Native bridge ready (Java {}): {}", Runtime.version(), NativeBridge.describe())
        } catch (t: Throwable) {
            LOGGER.error("Native bridge self-check failed", t)
        }
    }
}
