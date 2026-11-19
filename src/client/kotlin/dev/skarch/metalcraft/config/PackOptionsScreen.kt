package dev.skarch.metalcraft.config

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * One of a shader pack's own settings screens, laid out as the pack describes it: options that cycle through
 * their values when clicked, buttons into further screens, the profile selector.
 *
 * Nothing changes while the screens are open. Leaving the first one with changed values converts the pack
 * again ([ConvertingScreen]).
 */
internal class PackOptionsScreen(lastScreen: Screen, private val pack: PackOptions, private val screen: String, title: Component) :
    OptionsSubScreen(lastScreen, Minecraft.getInstance().options, title) {
    private val language: String = Minecraft.getInstance().languageManager.selected

    override fun addOptions() {
        val widgets = ArrayList<AbstractWidget>()
        for (item in pack.items(screen)) {
            widgets.add(
                when {
                    item == "<empty>" || item == "*" -> blank()
                    item == "<profile>" -> profileButton()
                    item.startsWith("[") && item.endsWith("]") -> screenButton(item.substring(1, item.length - 1))
                    else -> pack.options[item]?.let(::optionButton) ?: blank()
                },
            )
        }
        // Rows of blanks only take up room.
        val rows = widgets.chunked(2).filter { row -> row.any { it !is StringWidget } }
        list?.addSmall(rows.flatMap { if (it.size == 2) it else it + blank() })
    }

    override fun addFooter() {
        val buttons = layout.addToFooter(LinearLayout.horizontal().spacing(8))
        buttons.addChild(
            Button.builder(Component.translatable("metalcraft.pack_options.reset")) {
                pack.reset()
                rebuildWidgets()
            }.width(150).build(),
        )
        buttons.addChild(Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(150).build())
    }

    override fun onClose() {
        if (screen.isEmpty() && pack.changed()) {
            PackConversion.start(pack)
            minecraft.gui.setScreen(ConvertingScreen(lastScreen))
        } else {
            minecraft.gui.setScreen(lastScreen)
        }
    }

    private fun blank(): AbstractWidget = StringWidget(0, 0, WIDTH, 20, Component.empty(), font)

    private fun optionButton(option: PackOptions.Option): AbstractWidget =
        Button.builder(optionMessage(option)) { button ->
            pack.set(option.name, pack.next(option))
            button.message = optionMessage(option)
        }.width(WIDTH).build()

    private fun optionMessage(option: PackOptions.Option): Component {
        val name = pack.label("option.${option.name}", language, option.name)
        val value = pack.value(option.name)
        val shown = if (option.isSwitch) {
            (if (value == "true") CommonComponents.OPTION_ON else CommonComponents.OPTION_OFF).string
        } else {
            pack.label("value.${option.name}.$value", language, value)
        }
        // A changed value stands out.
        return Component.literal("$name: ${if (value == option.default) "" else "§e"}$shown")
    }

    private fun screenButton(name: String): AbstractWidget {
        val label = pack.label("screen.$name", language, name)
        return Button.builder(Component.literal("$label...")) {
            minecraft.gui.setScreen(PackOptionsScreen(this, pack, name, Component.literal(label)))
        }.width(WIDTH).build()
    }

    private fun profileButton(): AbstractWidget =
        Button.builder(profileMessage()) {
            val names = pack.profiles.keys.toList()
            if (names.isNotEmpty()) {
                pack.applyProfile(names[(names.indexOf(pack.profile()) + 1).mod(names.size)])
                // Every option on this screen may have changed with it.
                rebuildWidgets()
            }
        }.width(WIDTH).build()

    private fun profileMessage(): Component {
        val current = pack.profile()
        val shown = if (current == null) Component.translatable("metalcraft.pack_options.custom").string else pack.label("profile.$current", language, current)
        return Component.translatable("metalcraft.pack_options.profile", shown)
    }

    private companion object {
        const val WIDTH = 150
    }
}

/** Shown while the converter runs. Returns to [lastScreen] and reloads the pack when it is done. */
internal class ConvertingScreen(private val lastScreen: Screen?) : Screen(Component.translatable("metalcraft.pack_options.converting")) {
    private var message: StringWidget? = null
    private var failed = false

    override fun init() {
        val text = StringWidget(0, height / 2 - 20, width, 20, title, font)
        message = addRenderableWidget(text)
        if (failed) addDone()
    }

    override fun tick() {
        if (failed || PackConversion.running) return
        val error = PackConversion.error
        if (error == null) {
            minecraft.gui.setScreen(lastScreen)
            minecraft.reloadResourcePacks()
        } else {
            failed = true
            message?.message = Component.translatable("metalcraft.pack_options.failed", error)
            addDone()
        }
    }

    override fun shouldCloseOnEsc(): Boolean = failed

    // The world keeps running behind it: the conversion takes a few seconds.
    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        minecraft.gui.setScreen(lastScreen)
    }

    private fun addDone() {
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(width / 2 - 75, height / 2 + 10, 150, 20).build())
    }
}
