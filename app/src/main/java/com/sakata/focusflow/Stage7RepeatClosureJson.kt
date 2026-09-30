package com.sakata.focusflow

/**
 * 阶段 7.4 · 严格 JSON 文本层（**内部**，仅供本模块族使用）。
 *
 * 为什么不用 `org.json`：`JSONObject` 会把整数读成 `Double`、对 `optLong` 做截断，
 * 无法满足「整数无损、拒绝小数截断与溢出」的要求。这里只实现本模块需要的最小面：
 * 对象、数组、字符串、整数、布尔、null，**不支持浮点**——出现小数点、指数或数字字符串一律拒绝。
 *
 * 编码是规范化的：对象键按传入顺序书写、所有键都显式出现（可空字段写 `null`），
 * 不使用任何「裸分隔符拼接」的摘要。
 */
internal object StrictJson {

    sealed interface Value {
        data class Obj(val entries: List<Pair<String, Value>>) : Value
        data class Arr(val items: List<Value>) : Value
        data class Num(val value: Long) : Value
        data class Str(val value: String) : Value
        data class Bool(val value: Boolean) : Value
        data object Null : Value
    }

    sealed interface ParseResult {
        data class Ok(val value: Value) : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }

    // ---------- 写 ----------

    fun write(value: Value): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(out: StringBuilder, value: Value) {
        when (value) {
            is Value.Obj -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, child) ->
                    if (index > 0) out.append(',')
                    writeString(out, key)
                    out.append(':')
                    writeTo(out, child)
                }
                out.append('}')
            }
            is Value.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, child ->
                    if (index > 0) out.append(',')
                    writeTo(out, child)
                }
                out.append(']')
            }
            is Value.Num -> out.append(value.value.toString())
            is Value.Str -> writeString(out, value.value)
            is Value.Bool -> out.append(if (value.value) "true" else "false")
            Value.Null -> out.append("null")
        }
    }

    private fun writeString(out: StringBuilder, raw: String) {
        out.append('"')
        raw.forEach { ch ->
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') out.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else out.append(ch)
            }
        }
        out.append('"')
    }

    // ---------- 读 ----------

    fun parse(text: String): ParseResult {
        val reader = Reader(text)
        return try {
            reader.skipWhitespace()
            val value = reader.readValue()
            reader.skipWhitespace()
            if (!reader.atEnd()) return ParseResult.Invalid("trailing content at ${reader.position}")
            ParseResult.Ok(value)
        } catch (failure: Malformed) {
            ParseResult.Invalid(failure.message ?: "malformed json")
        }
    }

    private class Malformed(message: String) : RuntimeException(message)

    private class Reader(private val text: String) {
        var position: Int = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isJsonWhitespace()) position++
        }

        fun readValue(): Value {
            if (atEnd()) throw Malformed("unexpected end of input")
            return when (val ch = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> Value.Str(readString())
                't' -> { expect("true"); Value.Bool(true) }
                'f' -> { expect("false"); Value.Bool(false) }
                'n' -> { expect("null"); Value.Null }
                else -> {
                    if (ch == '-' || ch.isDigit()) Value.Num(readLong()) else throw Malformed("unexpected '$ch' at $position")
                }
            }
        }

        private fun readObject(): Value.Obj {
            expect("{")
            skipWhitespace()
            val entries = mutableListOf<Pair<String, Value>>()
            val seen = mutableSetOf<String>()
            if (peek() == '}') { position++; return Value.Obj(entries) }
            while (true) {
                skipWhitespace()
                val key = readString()
                if (!seen.add(key)) throw Malformed("duplicate key \"$key\"")
                skipWhitespace()
                expect(":")
                skipWhitespace()
                entries += key to readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { position++; continue }
                    '}' -> { position++; return Value.Obj(entries) }
                    else -> throw Malformed("expected ',' or '}' at $position")
                }
            }
        }

        private fun readArray(): Value.Arr {
            expect("[")
            skipWhitespace()
            val items = mutableListOf<Value>()
            if (peek() == ']') { position++; return Value.Arr(items) }
            while (true) {
                skipWhitespace()
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { position++; continue }
                    ']' -> { position++; return Value.Arr(items) }
                    else -> throw Malformed("expected ',' or ']' at $position")
                }
            }
        }

        private fun readString(): String {
            expect("\"")
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw Malformed("unterminated string")
                when (val ch = text[position++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) throw Malformed("unterminated escape")
                        when (val escape = text[position++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw Malformed("short unicode escape")
                                val hex = text.substring(position, position + 4)
                                if (hex.length != 4 || hex.any { !it.isHexDigit() }) {
                                    throw Malformed("bad unicode escape \"\\u$hex\"")
                                }
                                out.append(hex.toInt(16).toChar())
                                position += 4
                            }
                            else -> throw Malformed("bad escape \"\\$escape\"")
                        }
                    }
                    else -> {
                        if (ch < ' ') throw Malformed("raw control character in string at ${position - 1}")
                        out.append(ch)
                    }
                }
            }
        }

        /** 只接受十进制整数：不接受小数点、指数、前导零，也不接受被引号包起来的数字。 */
        private fun readLong(): Long {
            val start = position
            if (peek() == '-') position++
            val digitsStart = position
            while (!atEnd() && text[position].isDigit()) position++
            if (position == digitsStart) throw Malformed("expected digits at $start")
            val literal = text.substring(start, position)
            when {
                literal == "-0" -> throw Malformed("negative zero is not a valid integer")
                literal.length > 1 && literal[0] == '0' -> throw Malformed("leading zeros in \"$literal\"")
                literal.length > 1 && literal[1] == '0' && literal[0] == '-' -> throw Malformed("leading zeros in \"$literal\"")
            }
            if (!atEnd() && (text[position] == '.' || text[position] == 'e' || text[position] == 'E')) {
                throw Malformed("non-integer number at $start")
            }
            return literal.toLongOrNull() ?: throw Malformed("integer out of range: \"$literal\"")
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else text[position]

        private fun expect(literal: String) {
            if (!text.startsWith(literal, position)) throw Malformed("expected \"$literal\" at $position")
            position += literal.length
        }
    }

    private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
