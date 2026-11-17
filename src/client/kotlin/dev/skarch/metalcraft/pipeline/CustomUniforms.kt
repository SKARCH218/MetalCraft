package dev.skarch.metalcraft.pipeline

import dev.skarch.metalcraft.MetalCraft.LOGGER
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

/**
 * The uniforms a pack computes itself: `uniform.<type>.<name> = <expression>` and `variable.<type>.<name> =
 * <expression>` lines of its shaders.properties, carried over by the converter. Expressions use the other
 * uniforms, a handful of functions and a few values about the player and the biome.
 *
 * Everything is parsed when the pack loads. Each node owns the four floats it evaluates into, so a frame
 * allocates nothing.
 */
internal class CustomUniforms(
    definitions: List<Definition>,
    /** Offset and type of every member of the pack's uniform block. */
    private val offsets: (String) -> Int,
    private val types: (String) -> String?,
    private val packId: String,
) {
    class Definition(val name: String, val type: String, val variable: Boolean, val expression: String)

    /** Values that come from the game rather than from the uniform block, set by the engine before [evaluate]. */
    class Inputs {
        var temperature = 0f
        var rainfall = 0f
        var precipitation = 0f
        var biomeName = ""
        var inWater = false
        var inLava = false
        var sneaking = false
        var sprinting = false
        var onGround = false
        var alive = true
        var burning = false
        var hurt = false
        var invisible = false
        var glowing = false
        var riding = false
        var wet = false
    }

    val inputs = Inputs()

    abstract class Node {
        @JvmField val out = FloatArray(4)
        @JvmField var size = 1
        abstract fun eval(frameTime: Float)
    }

    private class Entry(val definition: Definition, val node: Node, val offset: Int, val blockType: String?)

    private val entries = ArrayList<Entry>()
    private val byName = HashMap<String, Node>()
    private val biomeNumbers = HashMap<String, Int>()
    private val unknown = HashSet<String>()
    private var block: ByteBuffer? = null
    private var lastBiome = ""
    private var biomeNumber = 0f

    /** Names the pack's expressions set, so the engine does not report them as missing. */
    val names: List<String>

    init {
        for (definition in definitions) {
            if (definition.name in byName) continue
            try {
                val node = Parser(definition.expression).parse()
                byName[definition.name] = node
                val offset = if (definition.variable) -1 else offsets(definition.name)
                entries.add(Entry(definition, node, offset, if (offset >= 0) types(definition.name) else null))
            } catch (e: IllegalArgumentException) {
                LOGGER.warn("Shader pack '{}': cannot read the expression of {} ({}). It stays 0", packId, definition.name, e.message)
            }
        }
        names = entries.filter { !it.definition.variable }.map { it.definition.name }
    }

    /**
     * Computes every expression in the order the pack lists them and writes the uniforms into [data].
     * Names in [skip] are left alone: the engine has its own value for those.
     */
    fun evaluate(data: ByteBuffer, frameTime: Float, skip: Set<String>) {
        block = data
        if (inputs.biomeName != lastBiome) {
            lastBiome = inputs.biomeName
            biomeNumber = (biomeNumbers[lastBiome] ?: 0).toFloat()
        }
        for (i in 0 until entries.size) {
            val entry = entries[i]
            val node = entry.node
            node.eval(frameTime)
            val at = entry.offset
            if (at < 0 || entry.definition.name in skip) continue
            val out = node.out
            when (entry.blockType) {
                "float" -> data.putFloat(at, out[0])
                "int" -> data.putInt(at, out[0].toInt())
                "bool" -> data.putInt(at, if (out[0] != 0f) 1 else 0)
                "vec2" -> data.putFloat(at, out[0]).putFloat(at + 4, out[1])
                "vec3" -> data.putFloat(at, out[0]).putFloat(at + 4, out[1]).putFloat(at + 8, out[2])
                "vec4" -> data.putFloat(at, out[0]).putFloat(at + 4, out[1]).putFloat(at + 8, out[2]).putFloat(at + 12, out[3])
                else -> {}
            }
        }
    }

    // ---- nodes ---------------------------------------------------------------------------------------------

    private class Constant(value: Float) : Node() {
        init {
            out[0] = value
        }

        override fun eval(frameTime: Float) {}
    }

    /** Another custom uniform or variable; it has been evaluated earlier in the same frame. */
    private class Reference(private val target: Node) : Node() {
        override fun eval(frameTime: Float) {
            size = target.size
            System.arraycopy(target.out, 0, out, 0, 4)
        }
    }

    /** A member of the uniform block, read back as the engine wrote it this frame. */
    private inner class BlockValue(private val offset: Int, private val type: String) : Node() {
        init {
            size = when (type) { "vec2", "ivec2" -> 2; "vec3", "ivec3" -> 3; "vec4" -> 4; else -> 1 }
        }

        override fun eval(frameTime: Float) {
            val data = block ?: return
            if (type == "int" || type == "bool" || type.startsWith("ivec")) {
                for (i in 0 until size) out[i] = data.getInt(offset + i * 4).toFloat()
            } else {
                for (i in 0 until size) out[i] = data.getFloat(offset + i * 4)
            }
        }
    }

    private inner class Input(private val kind: Int) : Node() {
        override fun eval(frameTime: Float) {
            val v = inputs
            out[0] = when (kind) {
                0 -> v.temperature
                1 -> v.rainfall
                2 -> v.precipitation
                3 -> biomeNumber
                4 -> flag(v.inWater)
                5 -> flag(v.inLava)
                6 -> flag(v.sneaking)
                7 -> flag(v.sprinting)
                8 -> flag(v.onGround)
                9 -> flag(v.alive)
                10 -> flag(v.burning)
                11 -> flag(v.hurt)
                12 -> flag(v.invisible)
                13 -> flag(v.glowing)
                14 -> flag(v.riding)
                else -> flag(v.wet)
            }
        }
    }

    private class Component(private val of: Node, private val index: Int) : Node() {
        override fun eval(frameTime: Float) {
            of.eval(frameTime)
            out[0] = of.out[index]
        }
    }

    private class Binary(private val operator: String, private val left: Node, private val right: Node) : Node() {
        override fun eval(frameTime: Float) {
            left.eval(frameTime)
            right.eval(frameTime)
            // A scalar combines with every component of a vector.
            size = maxOf(left.size, right.size)
            for (i in 0 until size) {
                val a = left.out[if (left.size == 1) 0 else i]
                val b = right.out[if (right.size == 1) 0 else i]
                out[i] = when (operator) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> if (b == 0f) 0f else a / b
                    "%" -> if (b == 0f) 0f else a - b * floor(a / b)
                    "<" -> flag(a < b)
                    ">" -> flag(a > b)
                    "<=" -> flag(a <= b)
                    ">=" -> flag(a >= b)
                    "==" -> flag(a == b)
                    "!=" -> flag(a != b)
                    "&&" -> flag(a != 0f && b != 0f)
                    else -> flag(a != 0f || b != 0f)
                }
            }
        }
    }

    private class Unary(private val operator: Char, private val of: Node) : Node() {
        override fun eval(frameTime: Float) {
            of.eval(frameTime)
            size = of.size
            for (i in 0 until size) out[i] = if (operator == '-') -of.out[i] else flag(of.out[i] == 0f)
        }
    }

    private class Vector(private val parts: Array<Node>) : Node() {
        override fun eval(frameTime: Float) {
            var at = 0
            for (part in parts) {
                part.eval(frameTime)
                for (i in 0 until part.size) if (at < 4) out[at++] = part.out[i]
            }
            // vec3(x) repeats one value.
            if (at == 1) for (i in 1 until size) out[i] = out[0]
        }
    }

    /** if(condition, value, [condition, value, ...], otherwise) */
    private class Choice(private val arguments: Array<Node>) : Node() {
        override fun eval(frameTime: Float) {
            var i = 0
            while (i + 1 < arguments.size) {
                arguments[i].eval(frameTime)
                if (arguments[i].out[0] != 0f) return take(arguments[i + 1], frameTime)
                i += 2
            }
            take(arguments[arguments.size - 1], frameTime)
        }

        private fun take(node: Node, frameTime: Float) {
            node.eval(frameTime)
            size = node.size
            System.arraycopy(node.out, 0, out, 0, 4)
        }
    }

    /** smooth([id,] value, [fadeUp, [fadeDown]]): follows the value over about the given number of seconds. */
    private class Smooth(private val value: Node, private val up: Node?, private val down: Node?) : Node() {
        private var started = false

        override fun eval(frameTime: Float) {
            value.eval(frameTime)
            up?.eval(frameTime)
            down?.eval(frameTime)
            val target = value.out[0]
            if (!started) {
                started = true
                out[0] = target
                return
            }
            val upTime = up?.out?.get(0) ?: 1f
            val time = if (target > out[0]) upTime else (down?.out?.get(0) ?: upTime)
            out[0] = if (time <= 0f) target else out[0] + (target - out[0]) * (1f - exp(-frameTime / time))
        }
    }

    private class Function(private val name: String, private val arguments: Array<Node>) : Node() {
        override fun eval(frameTime: Float) {
            for (argument in arguments) argument.eval(frameTime)
            val a = arguments[0].out[0]
            val b = if (arguments.size > 1) arguments[1].out[0] else 0f
            val c = if (arguments.size > 2) arguments[2].out[0] else 0f
            out[0] = when (name) {
                "sin" -> kotlin.math.sin(a)
                "cos" -> kotlin.math.cos(a)
                "tan" -> kotlin.math.tan(a)
                "asin" -> kotlin.math.asin(a)
                "acos" -> kotlin.math.acos(a)
                "atan" -> kotlin.math.atan(a)
                "atan2" -> kotlin.math.atan2(a, b)
                "torad" -> Math.toRadians(a.toDouble()).toFloat()
                "todeg" -> Math.toDegrees(a.toDouble()).toFloat()
                "abs" -> abs(a)
                "floor" -> floor(a)
                "ceil" -> kotlin.math.ceil(a)
                "round" -> Math.round(a).toFloat()
                "frac" -> a - floor(a)
                "sqrt" -> if (a < 0f) 0f else kotlin.math.sqrt(a)
                "exp" -> exp(a)
                "log" -> if (a <= 0f) 0f else kotlin.math.ln(a)
                "signum" -> Math.signum(a)
                "pow" -> Math.pow(a.toDouble(), b.toDouble()).toFloat()
                "fmod" -> if (b == 0f) 0f else a - b * floor(a / b)
                "min" -> minOf(a, b)
                "max" -> maxOf(a, b)
                "clamp" -> a.coerceIn(minOf(b, c), maxOf(b, c))
                "between" -> flag(a >= b && a <= c)
                "equals" -> flag(abs(a - b) <= c)
                "in" -> {
                    var found = false
                    for (i in 1 until arguments.size) if (arguments[i].out[0] == a) found = true
                    flag(found)
                }
                else -> 0f
            }
        }
    }

    // ---- parsing -------------------------------------------------------------------------------------------

    private inner class Parser(private val text: String) {
        private var at = 0

        fun parse(): Node {
            val node = or()
            skipSpace()
            require(at == text.length) { "unexpected '${text.substring(at)}'" }
            return node
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun take(symbol: String): Boolean {
            skipSpace()
            if (!text.startsWith(symbol, at)) return false
            at += symbol.length
            return true
        }

        private fun or(): Node {
            var left = and()
            while (take("||")) left = Binary("||", left, and())
            return left
        }

        private fun and(): Node {
            var left = comparison()
            while (take("&&")) left = Binary("&&", left, comparison())
            return left
        }

        private fun comparison(): Node {
            val left = sum()
            for (operator in COMPARISONS) if (take(operator)) return Binary(operator, left, sum())
            return left
        }

        private fun sum(): Node {
            var left = product()
            while (true) {
                left = when {
                    take("+") -> Binary("+", left, product())
                    take("-") -> Binary("-", left, product())
                    else -> return left
                }
            }
        }

        private fun product(): Node {
            var left = unary()
            while (true) {
                left = when {
                    take("*") -> Binary("*", left, unary())
                    take("/") -> Binary("/", left, unary())
                    take("%") -> Binary("%", left, unary())
                    else -> return left
                }
            }
        }

        private fun unary(): Node {
            if (take("-")) return Unary('-', unary())
            if (take("+")) return unary()
            skipSpace()
            if (at < text.length && text[at] == '!' && !text.startsWith("!=", at)) {
                at++
                return Unary('!', unary())
            }
            var node = primary()
            // .x .y .z .w and .r .g .b .a pick one component.
            while (take(".")) {
                val index = "xyzwrgba".indexOf(text.getOrElse(at) { ' ' })
                require(index >= 0) { "unknown component at ${text.substring(at)}" }
                at++
                node = Component(node, index % 4)
            }
            return node
        }

        private fun primary(): Node {
            skipSpace()
            require(at < text.length) { "the expression ends early" }
            if (take("(")) {
                val inner = or()
                require(take(")")) { "missing )" }
                return inner
            }
            val start = at
            if (text[at].isDigit() || text[at] == '.') {
                while (at < text.length && (text[at].isDigit() || text[at] == '.')) at++
                if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
                    at++
                    if (at < text.length && (text[at] == '-' || text[at] == '+')) at++
                    while (at < text.length && text[at].isDigit()) at++
                }
                val number = text.substring(start, at).toFloat()
                if (at < text.length && (text[at] == 'f' || text[at] == 'F')) at++
                return Constant(number)
            }
            require(text[at].isLetter() || text[at] == '_') { "unexpected '${text[at]}'" }
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
            val name = text.substring(start, at)
            if (take("(")) return call(name)
            return value(name)
        }

        private fun call(name: String): Node {
            val arguments = ArrayList<Node>()
            if (!take(")")) {
                do arguments.add(or()) while (take(","))
                require(take(")")) { "missing ) after $name(" }
            }
            return when (name) {
                "if" -> {
                    require(arguments.size >= 3 && arguments.size % 2 == 1) { "if() needs condition and value pairs and a last value" }
                    Choice(arguments.toTypedArray())
                }
                "smooth" -> {
                    // With four arguments the first is an id, which is not needed here.
                    val rest = if (arguments.size == 4) arguments.subList(1, 4) else arguments
                    require(rest.isNotEmpty()) { "smooth() needs a value" }
                    Smooth(rest[0], rest.getOrNull(1), rest.getOrNull(2))
                }
                "vec2", "vec3", "vec4" -> Vector(arguments.toTypedArray()).also { it.size = name.last() - '0' }
                else -> {
                    require(name in FUNCTIONS) { "unknown function $name" }
                    require(arguments.isNotEmpty()) { "$name() needs an argument" }
                    Function(name, arguments.toTypedArray())
                }
            }
        }

        private fun value(name: String): Node {
            byName[name]?.let { return Reference(it) }
            when (name) {
                "pi" -> return Constant(Math.PI.toFloat())
                "true" -> return Constant(1f)
                "false" -> return Constant(0f)
                "temperature" -> return Input(0)
                "rainfall" -> return Input(1)
                "biome_precipitation" -> return Input(2)
                "biome" -> return Input(3)
                "is_in_water" -> return Input(4)
                "is_in_lava" -> return Input(5)
                "is_sneaking" -> return Input(6)
                "is_sprinting" -> return Input(7)
                "is_on_ground" -> return Input(8)
                "is_alive" -> return Input(9)
                "is_burning" -> return Input(10)
                "is_hurt" -> return Input(11)
                "is_invisible" -> return Input(12)
                "is_glowing" -> return Input(13)
                "is_riding" -> return Input(14)
                "is_wet" -> return Input(15)
                "PPT_NONE" -> return Constant(0f)
                "PPT_RAIN" -> return Constant(1f)
                "PPT_SNOW" -> return Constant(2f)
            }
            // BIOME_<NAME> stands for a number; `biome` is the number of the biome the camera is in.
            if (name.startsWith("BIOME_")) {
                val biome = name.removePrefix("BIOME_").lowercase()
                return Constant(biomeNumbers.getOrPut(biome) { biomeNumbers.size + 1 }.toFloat())
            }
            val offset = offsets(name)
            val type = types(name)
            if (offset >= 0 && type != null) return BlockValue(offset, type)
            if (unknown.add(name)) LOGGER.info("Shader pack '{}': expressions use '{}', which the engine does not provide. It counts as 0", packId, name)
            return Constant(0f)
        }
    }

    private companion object {
        val COMPARISONS = arrayOf("<=", ">=", "==", "!=", "<", ">")
        val FUNCTIONS = setOf(
            "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "torad", "todeg", "abs", "floor", "ceil", "round", "frac",
            "sqrt", "exp", "log", "signum", "pow", "fmod", "min", "max", "clamp", "between", "equals", "in",
        )

        fun flag(value: Boolean): Float = if (value) 1f else 0f
    }
}
