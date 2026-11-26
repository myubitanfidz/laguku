package com.laguku.player.data

/**
 * Compact pure-Kotlin JSON parser and serializer supporting objects, arrays, strings,
 * numbers (Long/Double), booleans, and null. Works identically on Android and JVM tests.
 */
object SimpleJson {

    fun stringify(value: Any?): String {
        val sb = StringBuilder(256)
        writeValue(value, sb)
        return sb.toString()
    }

    fun parseObject(json: String): Map<String, Any?>? {
        val parsed = parse(json)
        @Suppress("UNCHECKED_CAST")
        return parsed as? Map<String, Any?>
    }

    fun parse(json: String): Any? {
        if (json.isBlank()) return null
        return Parser(json).parseRoot()
    }

    private fun writeValue(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long -> sb.append(value.toString())
            is Float -> {
                val f = if (value.isFinite()) value else 0f
                sb.append(f.toString())
            }
            is Double -> {
                val d = if (value.isFinite()) value else 0.0
                sb.append(d.toString())
            }
            is Number -> sb.append(value.toString())
            is String -> writeEscapedString(value, sb)
            is FloatArray -> {
                sb.append('[')
                for (i in value.indices) {
                    if (i > 0) sb.append(',')
                    val f = if (value[i].isFinite()) value[i] else 0f
                    sb.append(f.toString())
                }
                sb.append(']')
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (k == null) continue
                    if (!first) sb.append(',')
                    first = false
                    writeEscapedString(k.toString(), sb)
                    sb.append(':')
                    writeValue(v, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(item, sb)
                }
                sb.append(']')
            }
            else -> writeEscapedString(value.toString(), sb)
        }
    }

    private fun writeEscapedString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (i in s.indices) {
            when (val c = s[i]) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        val hex = c.code.toString(16).padStart(4, '0')
                        sb.append(hex)
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseRoot(): Any? {
            skipWhitespace()
            if (pos >= src.length) return null
            val v = readValue()
            skipWhitespace()
            return v
        }

        private fun readValue(): Any? {
            skipWhitespace()
            if (pos >= src.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected char '${src[pos]}' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (pos < src.length) {
                skipWhitespace()
                if (pos >= src.length || src[pos] != '"') {
                    throw IllegalArgumentException("Expected string key at $pos")
                }
                val key = readString()
                skipWhitespace()
                if (pos >= src.length || src[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' at $pos")
                }
                pos++
                val value = readValue()
                map[key] = value
                skipWhitespace()
                if (pos >= src.length) throw IllegalArgumentException("Unterminated object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
            throw IllegalArgumentException("Unterminated object")
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (pos < src.length) {
                list.add(readValue())
                skipWhitespace()
                if (pos >= src.length) throw IllegalArgumentException("Unterminated array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
            throw IllegalArgumentException("Unterminated array")
        }

        private fun readString(): String {
            pos++ // opening '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) throw IllegalArgumentException("Bad unicode escape")
                            val code = src.substring(pos, pos + 4).toInt(16)
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Invalid escape \\$esc")
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun readLiteral(expected: String, result: Any?): Any? {
            if (!src.startsWith(expected, pos)) {
                throw IllegalArgumentException("Expected $expected at $pos")
            }
            pos += expected.length
            return result
        }

        private fun readNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            var isFloat = false
            if (pos < src.length && src[pos] == '.') {
                isFloat = true
                pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)
            return if (isFloat) {
                token.toDouble()
            } else {
                token.toLongOrNull() ?: token.toDouble()
            }
        }

        private fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\r', '\n' -> pos++
                    else -> return
                }
            }
        }
    }

    fun asDouble(v: Any?, default: Double = 0.0): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: default
        else -> default
    }

    fun asLong(v: Any?, default: Long = 0L): Long = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: default
        else -> default
    }

    fun asInt(v: Any?, default: Int = 0): Int = asLong(v, default.toLong()).toInt()

    fun asBoolean(v: Any?, default: Boolean = false): Boolean = when (v) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        else -> default
    }
}
