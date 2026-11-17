package dev.skarch.metalcraft

import net.fabricmc.loader.api.FabricLoader

import dev.skarch.metalcraft.MetalCraft.LOGGER
import dev.skarch.metalcraft.backend.MetalDevice
import dev.skarch.metalcraft.native.NativeBridge
import dev.skarch.metalcraft.pipeline.ShaderPipeline
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

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
        registerShaderPackKey()
        dev.skarch.metalcraft.zoom.Zoom.register()
        registerShaderSettings()
        // Create draws its moving parts through Flywheel; give it a backend that works on Metal.
        if (FabricLoader.getInstance().isModLoaded("create")) {
            try {
                dev.skarch.metalcraft.flywheel.MetalFlywheel.register()
            } catch (t: Throwable) {
                LOGGER.error("Could not register the Flywheel backend for Metal; Create will use its plain renderers", t)
            }
        }
    }

    /** The shader settings screen opens with a key and from a button on the video settings screen. */
    private fun registerShaderSettings() {
        val key = KeyMappingHelper.registerKeyMapping(KeyMapping("key.metalcraft.shader_settings", GLFW.GLFW_KEY_O, KeyMapping.Category.MISC))
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (key.consumeClick()) {
                if (client.gui.screen() == null) client.gui.setScreen(dev.skarch.metalcraft.config.ShaderSettingsScreen(null))
            }
        }
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register { client, screen, width, _ ->
            if (screen is net.minecraft.client.gui.screens.options.VideoSettingsScreen) {
                net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(
                    net.minecraft.client.gui.components.Button.builder(Component.translatable("metalcraft.shaders.button")) {
                        client.gui.setScreen(dev.skarch.metalcraft.config.ShaderSettingsScreen(screen))
                    }.bounds(width - 106, 6, 100, 20).build(),
                )
            }
        }
    }

    /** One key steps through the installed shader packs and "Off". */
    private fun registerShaderPackKey() {
        val key = KeyMappingHelper.registerKeyMapping(KeyMapping("key.metalcraft.cycle_shaderpack", GLFW.GLFW_KEY_K, KeyMapping.Category.MISC))
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (key.consumeClick()) {
                val message = if (MetalDevice.current == null) {
                    Component.translatable("metalcraft.shaderpack.needs_metal")
                } else {
                    Component.translatable("metalcraft.shaderpack.selected", ShaderPipeline.cyclePack())
                }
                client.gui.hud.setOverlayMessage(message, false)
            }
        }
    }
}
