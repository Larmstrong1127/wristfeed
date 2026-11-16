package io.github.larmstrong1127.wristfeed.core

/**
 * A tiny, strict RFC 8259 JSON reader so `core` stays free of Android (org.json is only
 * provided on-device, and its JVM stub throws in unit tests).
 *
 * Output types: Map<String, Any?> (object), List<Any?> (array), String, Long (integers
 * without fraction/exponent that fit in a Long), Double (other numbers), Boolean, null.
 * Keeping integers as Long lets the parser tell `"wristfeed": 1` from `1.5`.
 */
class JsonParseException(message: String) : Exception(message)

object MiniJson {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val r = Reader(text)
        r.skipWs()
        val v = r.value(0)
        r.skipWs()
        if (!r.atEnd()) throw JsonParseException("trailing data at ${r.pos}")
        return v
    }

    private class Reader(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun fail(msg: String): Nothing = throw JsonParseException("$msg at $pos")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            if (atEnd()) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("bad literal")
            pos += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            pos++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("expected key")
                val k = str()
                skipWs()
                if (atEnd() || s[pos] != ':') fail("expected ':'")
                pos++
                skipWs()
                m[k] = value(depth + 1) // duplicate keys: last wins
                skipWs()
                if (atEnd()) fail("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            pos++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                if (atEnd()) fail("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return l }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad unicode escape")
                                sb.append(hex.toInt(16).toChar()) // surrogate pairs arrive as two escapes and join naturally
                                pos += 4
                            }
                            else -> fail("bad escape '$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            if (atEnd()) fail("bad number")
            if (s[pos] == '0') pos++
            else if (s[pos] in '1'..'9') while (pos < s.length && s[pos].isDigit()) pos++
            else fail("bad number")
            var integral = true
            if (pos < s.length && s[pos] == '.') {
                integral = false; pos++
                if (atEnd() || !s[pos].isDigit()) fail("bad fraction")
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                integral = false; pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (atEnd() || !s[pos].isDigit()) fail("bad exponent")
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            val text = s.substring(start, pos)
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDouble()
        }
    }
}
