package com.bestspace.vault.core

/**
 * JSON ขนาดเล็ก (ไม่พึ่งไลบรารีภายนอก) – อ่าน/เขียนรูปแบบเดียวกับ json ของ Python
 * object → MutableMap<String, Any?> (เรียงตามลำดับเดิม), array → MutableList<Any?>,
 * ตัวเลขจำนวนเต็ม → Long, ทศนิยม → Double, true/false, null, string
 * เก็บทุกช่องที่อ่านมา (แม้ไม่รู้จัก) แล้วเขียนกลับครบ → ใช้ตู้ร่วมกับแอปคอมได้ไม่เสียข้อมูล
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("JSON: ข้อมูลเกินที่ตำแหน่ง ${p.i}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): MutableMap<String, Any?> =
        parse(text) as? MutableMap<String, Any?> ?: throw IllegalArgumentException("JSON: ไม่ใช่ object")

    fun write(v: Any?): String = StringBuilder().also { w(it, v) }.toString()

    private fun w(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> sb.append(if (v.isFinite()) (if (v == Math.floor(v) && Math.abs(v) < 1e15) "%.1f".format(java.util.Locale.ROOT, v) else v.toString()) else "null")
            is Float -> w(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(", ")
                    first = false
                    str(sb, k.toString()); sb.append(": "); w(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(", ")
                    first = false
                    w(sb, x)
                }
                sb.append(']')
            }
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun err(m: String): Nothing = throw IllegalArgumentException("JSON: $m ที่ตำแหน่ง $i")

        fun value(): Any? {
            ws()
            if (i >= s.length) err("สิ้นสุดก่อนเวลา")
            return when (val c = s[i]) {
                '{' -> objectV()
                '[' -> arrayV()
                '"' -> stringV()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                'N' -> lit("NaN", Double.NaN)
                else -> if (c == '-' || c.isDigit()) number() else err("ตัวอักษรไม่ถูกต้อง '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) err("คาดว่าเป็น $word")
            i += word.length
            return v
        }

        fun objectV(): MutableMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') err("คาดว่าเป็นชื่อช่อง")
                val k = stringV()
                ws()
                if (i >= s.length || s[i] != ':') err("คาดว่าเป็น ':'")
                i++
                m[k] = value()
                ws()
                if (i >= s.length) err("object ไม่ปิด")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> err("คาดว่าเป็น ',' หรือ '}'")
                }
            }
        }

        fun arrayV(): MutableList<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                if (i >= s.length) err("array ไม่ปิด")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> err("คาดว่าเป็น ',' หรือ ']'")
                }
            }
        }

        fun stringV(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) err("string ไม่ปิด")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) err("escape ไม่สมบูรณ์")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) err("\\u ไม่สมบูรณ์")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> err("escape ไม่รู้จัก '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isFloat = false
            if (i < s.length && s[i] == '.') { isFloat = true; i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isFloat = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val t = s.substring(st, i)
            return if (isFloat) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }
    }
}

/** ตัวช่วยอ่านค่าจาก Map แบบปลอดภัย */
@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): MutableMap<String, Any?> = this as? MutableMap<String, Any?> ?: LinkedHashMap()
fun Any?.asLong(): Long = when (this) { is Number -> this.toLong(); is String -> this.toLongOrNull() ?: 0L; else -> 0L }
fun Any?.asDouble(): Double = when (this) { is Number -> this.toDouble(); is String -> this.toDoubleOrNull() ?: 0.0; else -> 0.0 }
fun Any?.asStr(): String? = this as? String
