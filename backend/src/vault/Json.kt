package vault

// 极简 JSON 编解码（仅覆盖本契约需要的子集：对象/数组/字符串/数字/布尔/null）。
// 选择手写而非引入 kotlinx.serialization / org.json：标准库无 JSON，且契约荷载结构固定、字段已知。
// ponytail: 手写 JSON 仅支持固定子集，未做严格 RFC 8259 全量校验。 | 若荷载结构演变为含任意嵌套/未知字段或需流式处理，改引 kotlinx.serialization（仍是单一依赖）。
fun jsonEncode(value: Any?): String {
    val sb = StringBuilder()
    encodeValue(value, sb)
    return sb.toString()
}

private fun encodeValue(v: Any?, sb: StringBuilder) {
    when (v) {
        null -> sb.append("null")
        is String -> encodeString(v, sb)
        is Boolean -> sb.append(if (v) "true" else "false")
        is Number -> sb.append(v.toString())
        is Map<*, *> -> {
            sb.append('{')
            var first = true
            for ((k, value) in v) {
                if (!first) sb.append(',')
                encodeString(k.toString(), sb)
                sb.append(':')
                encodeValue(value, sb)
                first = false
            }
            sb.append('}')
        }
        is List<*> -> encodeList(v, sb)
        is Array<*> -> encodeList(v.toList(), sb)
        else -> encodeString(v.toString(), sb) // ponytail: 未知类型退化为字符串，仅兜底。 | 若需序列化任意对象，应引入序列化库。
    }
}

private fun encodeList(list: List<*>, sb: StringBuilder) {
    sb.append('[')
    var first = true
    for (e in list) {
        if (!first) sb.append(',')
        encodeValue(e, sb)
        first = false
    }
    sb.append(']')
}

private fun encodeString(s: String, sb: StringBuilder) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000c' -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
}

fun jsonDecode(str: String): Any? {
    val p = JsonParser(str)
    p.skipWs()
    val v = p.parseValue()
    p.skipWs()
    checkThat(p.isEnd()) { "JSON 末尾存在多余字符" }
    return v
}

private class JsonParser(val s: String) {
    var i = 0
    fun isEnd() = i >= s.length
    fun skipWs() { while (i < s.length && s[i] in " \t\n\r") i++ }

    fun parseValue(): Any? {
        skipWs()
        checkThat(!isEnd()) { "JSON 意外结束" }
        return when (s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBool()
            'n' -> parseNull()
            else -> parseNumber()
        }
    }

    fun parseObject(): MutableMap<String, Any?> {
        expect('{')
        val m = mutableMapOf<String, Any?>()
        skipWs()
        if (peek() == '}') { i++; return m }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            m[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> { i++; continue }
                '}' -> { i++; break }
                else -> throw IllegalArgumentException("JSON 对象期望 , 或 } (位置 $i)")
            }
        }
        return m
    }

    fun parseArray(): MutableList<Any?> {
        expect('[')
        val l = mutableListOf<Any?>()
        skipWs()
        if (peek() == ']') { i++; return l }
        while (true) {
            l.add(parseValue())
            skipWs()
            when (peek()) {
                ',' -> { i++; continue }
                ']' -> { i++; break }
                else -> throw IllegalArgumentException("JSON 数组期望 , 或 ] (位置 $i)")
            }
        }
        return l
    }

    fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            checkThat(!isEnd()) { "JSON 字符串未闭合" }
            val c = s[i++]
            when {
                c == '"' -> break
                c == '\\' -> {
                    checkThat(!isEnd()) { "JSON 转义不完整" }
                    val e = s[i++]
                    when (e) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> {
                            val hex = s.substring(i, i + 4)
                            i += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw IllegalArgumentException("JSON 非法转义 \\$e")
                    }
                }
                c.code < 0x20 -> throw IllegalArgumentException("JSON 字符串含控制字符")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun parseNumber(): Number {
        val start = i
        while (i < s.length && s[i] in "0123456789+-.eE") i++
        val num = s.substring(start, i)
        return if (num.contains('.') || num.contains('e') || num.contains('E')) num.toDouble() else num.toLong()
    }

    fun parseBool(): Boolean {
        if (s.startsWith("true", i)) { i += 4; return true }
        if (s.startsWith("false", i)) { i += 5; return false }
        throw IllegalArgumentException("JSON 非法布尔值")
    }

    fun parseNull(): Nothing? {
        if (s.startsWith("null", i)) { i += 4; return null }
        throw IllegalArgumentException("JSON 非法 null")
    }

    fun peek() = if (isEnd()) throw IllegalArgumentException("JSON 意外结束") else s[i]
    fun expect(c: Char) {
        skipWs()
        checkThat(!isEnd() && s[i] == c) { "JSON 期望 '$c' (位置 $i)" }
        i++
    }
}

// 自检：JSON 往返 + 转义（含引号/反斜杠/换行的密码场景）
fun jsonSelfTest() {
    val samples = listOf(
        mapOf("password" to "a\"b\\c\nd", "website" to "https://x", "notes" to ""),
        mapOf("n" to 3L, "f" to 1.5, "b" to true, "nil" to null,
            "arr" to listOf(1L, 2L, "x"), "obj" to mapOf("k" to "v"))
    )
    for (s in samples) {
        val round = jsonDecode(jsonEncode(s)) as Map<*, *>
        checkThat(round == s) { "JSON 往返不相等: $s vs $round" }
    }
    val embedded = mapOf("password" to "quote\"and\\slash")
    val rt = jsonDecode(jsonEncode(embedded)) as Map<*, *>
    checkThat(rt["password"] == "quote\"and\\slash") { "JSON 转义还原失败" }
}
