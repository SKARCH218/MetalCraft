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
            Button.builder(Component.translatable("metalcraft.shaders.pack_options")) { openPackOptions() }.width(SMALL_BUTTON_WIDTH).build(),
        )
        buttons.addChild(
            Button.builder(Component.translatable("metalcraft.shaders.open_folder")) { openFolder() }.width(BUTTON_WIDTH + 20).build(),
        )
        buttons.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(SMALL_BUTTON_WIDTH).build())

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

    /** The selected pack's own settings, if it was converted with them. */
    private fun openPackOptions() {
        val id = ShaderPipeline.selectedId()
        val folder = ShaderPipeline.packFolder(id)
        val options = if (folder == null) null else PackOptions.load(id, folder)
        if (options == null) {
            minecraft.gui.setScreen(
                net.minecraft.client.gui.screens.AlertScreen(
                    { minecraft.gui.setScreen(this) }, Component.translatable("metalcraft.shaders.pack_options"),
                    Component.translatable("metalcraft.pack_options.none"),
                ),
            )
            return
        }
        minecraft.gui.setScreen(PackOptionsScreen(this, options, "", Component.translatable("metalcraft.pack_options.title", id)))
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

    companion object {
        /** The settings screen of the selected pack's own options, or null when it has none. */
        @JvmStatic
        fun packOptionsScreen(parent: Screen): Screen? {
            val id = ShaderPipeline.selectedId()
            val options = ShaderPipeline.packFolder(id)?.let { PackOptions.load(id, it) } ?: return null
            return PackOptionsScreen(parent, options, "", Component.translatable("metalcraft.pack_options.title", id))
        }

        /**
         * Sets one option of the selected pack ([value] null for its default) and converts the pack again, as
         * leaving the options screen does. False when the pack has no such option.
         */
        @JvmStatic
        fun applyPackOption(name: String, value: String?): Boolean {
            val id = ShaderPipeline.selectedId()
            val options = ShaderPipeline.packFolder(id)?.let { PackOptions.load(id, it) } ?: return false
            val option = options.options[name] ?: return false
            options.set(name, value ?: option.default)
            if (!options.changed()) return true
            PackConversion.start(options)
            Minecraft.getInstance().gui.setScreen(ConvertingScreen(null))
            return true
        }

        private const val OFF = "off"
        private const val FOOTER_HEIGHT = 84
        private const val BUTTON_WIDTH = 150
        private const val SMALL_BUTTON_WIDTH = 100
        private const val MIN_SCALE = 50
        private const val SCALE_STEP = 5
        private const val NOTE_COLOR = -4539718
        private val UPSCALE_MODES = listOf("off", "spatial", "temporal")
    }
}
