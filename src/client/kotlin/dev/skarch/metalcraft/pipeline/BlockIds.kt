package dev.skarch.metalcraft.pipeline

import dev.skarch.metalcraft.MetalCraft.LOGGER
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

/**
 * The IDs the selected shader pack gives to blocks (`mc_Entity.x` of the Iris pack format). They are stored
 * in every terrain vertex when a section is built, so sections have to be rebuilt when the table changes.
 *
 * The table is a text file in the pack, one ID per line followed by the blocks it covers:
 * ```
 * 10005=short_grass fern minecraft:poppy
 * 10009=oak_leaves:waterlogged=false create:shaft:axis=y
 * ```
 * A block may name a namespace (default `minecraft`) and property values that must match. `block.10005=` is
 * accepted as well, a trailing `\` continues a line and `#` starts a comment. The first line that covers a
 * block state wins. Unknown blocks are skipped: packs list blocks of game versions and mods that are absent.
 */
object BlockIds {
    const val NONE = -1

    @Volatile
    private var ids: Reference2IntOpenHashMap<BlockState> = empty()
    private var source: String? = null

    /** The pack's ID of [state], or [NONE]. Called from the section build threads; the table is never modified. */
    @JvmStatic
    fun of(state: BlockState): Int = ids.getInt(state)

    /** Replaces the table with the one in [text] (null for none). Returns whether anything changed. */
    fun load(text: String?): Boolean {
        if (text == source) return false
        source = text
        val table = if (text == null) empty() else parse(text)
        val changed = table != ids
        ids = table
        return changed
    }

    private fun empty() = Reference2IntOpenHashMap<BlockState>().apply { defaultReturnValue(NONE) }

    private fun parse(text: String): Reference2IntOpenHashMap<BlockState> {
        val table = empty()
        var unknown = 0
        for (line in text.replace("\\\r\n", " ").replace("\\\n", " ").lineSequence()) {
            val content = line.substringBefore('#').trim()
            val separator = content.indexOf('=')
            if (separator <= 0) continue
            val id = content.substring(0, separator).trim().removePrefix("block.").toIntOrNull()
            if (id == null || id !in Short.MIN_VALUE..Short.MAX_VALUE) {
                LOGGER.warn("Shader pack block table: '{}' is not a usable ID", content.substring(0, separator))
                continue
            }
            for (entry in content.substring(separator + 1).split(' ', '\t')) {
                if (entry.isEmpty()) continue
                if (!add(table, id, entry)) unknown++
            }
        }
        if (unknown > 0) LOGGER.debug("Shader pack block table: {} entries name blocks or properties that do not exist", unknown)
        return table
    }

    private fun add(table: Reference2IntOpenHashMap<BlockState>, id: Int, entry: String): Boolean {
        val parts = entry.split(':')
        val names = parts.filter { '=' !in it }
        val conditions = parts.filter { '=' in it }
        val identifier = when (names.size) {
            1 -> Identifier.tryParse("minecraft:${names[0]}")
            2 -> Identifier.tryParse("${names[0]}:${names[1]}")
            else -> null
        } ?: return false
        val block = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null) ?: return false
        val definition = block.stateDefinition
        val required = ArrayList<Pair<Property<*>, String>>(conditions.size)
        for (condition in conditions) {
            val property = definition.getProperty(condition.substringBefore('=')) ?: return false
            required.add(property to condition.substringAfter('='))
        }
        for (state in definition.possibleStates) {
            if (required.all { (property, value) -> valueName(state, property) == value } && !table.containsKey(state)) {
                table.put(state, id)
            }
        }
        return true
    }

    private fun <T : Comparable<T>> valueName(state: BlockState, property: Property<T>): String = property.getName(state.getValue(property))
}
