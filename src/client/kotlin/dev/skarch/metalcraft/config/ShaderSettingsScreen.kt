package dev.skarch.metalcraft.config

import dev.skarch.metalcraft.backend.MetalDevice
import dev.skarch.metalcraft.pipeline.ShaderPipeline
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import java.nio.file.Files

/**
 * The shader settings: the list of installed packs (a click switches at once) and the MetalFX settings,
 * which apply when the screen is closed. Opened from the video settings and with a key.
 */
class ShaderSettingsScreen(private val lastScreen: Screen?) : Screen(Component.translatable("metalcraft.shaders.title")) {
    private val layout = HeaderAndFooterLayout(this, 33, FOOTER_HEIGHT)
    private var list: PackList? = null

    override fun init() {
        layout.addTitleHeader(title, font)
        list = layout.addToContents(PackList(minecraft))

        val footer = layout.addToFooter(LinearLayout.vertical()).spacing(4)
        footer.defaultCellSetting().alignHorizontallyCenter()
        val upscaling = footer.addChild(LinearLayout.horizontal().spacing(8))
        upscaling.addChild(
            CycleButton.builder<String>({ Component.translatable("metalcraft.shaders.upscale.$it") }, MetalCraftConfig.upscale)
                .withValues(UPSCALE_MODES)
                .create(0, 0, BUTTON_WIDTH, 20, Component.translatable("metalcraft.shaders.upscale")) { _, value ->
                    MetalCraftConfig.upscale = value
                    MetalCraftConfig.save()
                },
        )
        upscaling.addChild(RenderScaleSlider())
        footer.addChild(
            StringWidget(Component.translatable("metalcraft.shaders.active", dev.skarch.metalcraft.upscale.Upscaler.describe()).withColor(NOTE_COLOR), font),
        )
        val buttons = footer.addChild(LinearLayout.horizontal().spacing(8))
        buttons.addChild(
            Button.builder(Component.translatable("metalcraft.shaders.open_folder")) { openFolder() }.width(BUTTON_WIDTH).build(),
        )
        buttons.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(BUTTON_WIDTH).build())

        layout.visitWidgets { addRenderableWidget(it) }
        repositionElements()
    }

    override fun repositionElements() {
        layout.arrangeElements()
        list?.updateSize(width, layout)
    }

    /** Done applies the MetalFX settings and reloads resources, as F3+T does. */
    override fun onClose() {
        minecraft.gui.setScreen(lastScreen)
        if (MetalDevice.current != null) {
            dev.skarch.metalcraft.upscale.Upscaler.reconfigure()
            minecraft.reloadResourcePacks()
        }
    }

    private fun openFolder() {
        val folder = FabricLoader.getInstance().gameDir.resolve("shaderpacks")
        Files.createDirectories(folder)
        Util.getPlatform().openPath(folder)
    }

    private inner class RenderScaleSlider : AbstractSliderButton(
        0, 0, BUTTON_WIDTH, 20, Component.empty(), (MetalCraftConfig.renderScale - MIN_SCALE) / (100.0 - MIN_SCALE),
    ) {
        init {
            updateMessage()
        }

        private fun percent(): Int = MIN_SCALE + Math.round(value * (100 - MIN_SCALE) / SCALE_STEP).toInt() * SCALE_STEP

        override fun updateMessage() {
            message = Component.translatable("metalcraft.shaders.render_scale", percent())
        }

        override fun applyValue() {
            MetalCraftConfig.renderScale = percent()
            MetalCraftConfig.save()
        }
    }

    private inner class PackList(minecraft: Minecraft) :
        ObjectSelectionList<PackList.Entry>(minecraft, this@ShaderSettingsScreen.width, this@ShaderSettingsScreen.height - 33 - FOOTER_HEIGHT, 33, 18) {
        init {
            val current = ShaderPipeline.selectedId()
            add(Entry(OFF, Component.translatable("metalcraft.shaders.off")), current)
            if (MetalDevice.current != null) {
                for ((id, name) in ShaderPipeline.refreshPacks()) add(Entry(id, Component.literal(name)), current)
            }
            selected?.let { centerScrollOn(it) }
        }

        private fun add(entry: Entry, current: String) {
            addEntry(entry)
            if (entry.id == current) setSelected(entry)
        }

        override fun getRowWidth(): Int = super.getRowWidth() + 50

        inner class Entry(val id: String, private val name: Component) : ObjectSelectionList.Entry<Entry>() {
            override fun extractContent(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, a: Float) {
                graphics.centeredText(font, name, this@PackList.width / 2, contentYMiddle - 9 / 2, -1)
            }

            override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
                this@PackList.setSelected(this)
                ShaderPipeline.selectPack(id)
                return super.mouseClicked(event, doubleClick)
            }

            override fun getNarration(): Component = Component.translatable("narrator.select", name)
        }
    }

    private companion object {
        const val OFF = "off"
        const val FOOTER_HEIGHT = 84
        const val BUTTON_WIDTH = 150
        const val MIN_SCALE = 50
        const val SCALE_STEP = 5
        const val NOTE_COLOR = -4539718
        val UPSCALE_MODES = listOf("off", "spatial", "temporal")
    }
}
