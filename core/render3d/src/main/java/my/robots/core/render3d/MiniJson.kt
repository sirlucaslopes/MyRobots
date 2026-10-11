package my.robots.core.render3d

/**
 * Leitor e escritor de JSON mínimo, em Kotlin puro (o `org.json` do Android não roda nos testes
 * do PC). Objetos viram [Map], listas viram [List], números viram [Double].
 */
object MiniJson {

    fun parse(text: String): Any? = Parser(text).run {
        val v = value()
        skipSpace()
        if (pos != text.length) fail("texto sobrando")
        v
    }

    /** Escreve [Map], [List], [String], [Number], [Boolean] e null. */
    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                require(d.isFinite()) { "número inválido no JSON: $d" }
                if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); writeTo(sb, value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, value -> if (i > 0) sb.append(','); writeTo(sb, value) }
                sb.append(']')
            }
            else -> error("tipo sem JSON: ${v::class.simpleName}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun fail(why: String): Nothing = throw IllegalArgumentException("JSON inválido na posição $pos: $why")

        fun skipSpace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipSpace()
            if (pos >= s.length) fail("fim inesperado")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> list()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("caractere '$c'")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, pos)) fail("esperava $w")
            pos += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (s.getOrNull(pos) == '}') { pos++; return out }
            while (true) {
                skipSpace()
                if (s.getOrNull(pos) != '"') fail("esperava nome")
                val k = string()
                skipSpace()
                if (s.getOrNull(pos) != ':') fail("esperava ':'")
                pos++
                out[k] = value()
                skipSpace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> fail("esperava ',' ou '}'")
                }
            }
        }

        private fun list(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipSpace()
            if (s.getOrNull(pos) == ']') { pos++; return out }
            while (true) {
                out += value()
                skipSpace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> fail("esperava ',' ou ']'")
                }
            }
        }

        private fun string(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("texto sem fim")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("escape sem fim")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("\\u curto")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail("número")
        }
    }
}

// atalhos para ler o que o MiniJson devolve
@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
internal fun Any?.arr(): List<Any?> = this as? List<Any?> ?: emptyList()
internal fun Any?.num(): Double? = (this as? Number)?.toDouble()
internal fun Any?.int(): Int? = (this as? Number)?.toInt()
internal fun Any?.str(): String? = this as? String
