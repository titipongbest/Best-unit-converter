package com.bestspace.vault.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** ชนิดไฟล์ / mime – ตรงกับ file_kind, is_text, is_audio, DANGEROUS_EXT ของแอปคอม */
object Mime {
    private val TABLE = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic",
        "heif" to "image/heif", "avif" to "image/avif", "tif" to "image/tiff", "tiff" to "image/tiff",
        "ico" to "image/vnd.microsoft.icon", "svg" to "image/svg+xml",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "3gp" to "video/3gpp", "3g2" to "video/3gpp2", "avi" to "video/x-msvideo",
        "mpeg" to "video/mpeg", "mpg" to "video/mpeg", "ts" to "video/mp2t", "flv" to "video/x-flv", "wmv" to "video/x-ms-wmv",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/x-wav",
        "ogg" to "audio/ogg", "opus" to "audio/opus", "flac" to "audio/flac", "amr" to "audio/amr", "wma" to "audio/x-ms-wma",
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "log" to "text/plain",
        "html" to "text/html", "htm" to "text/html", "css" to "text/css", "xml" to "text/xml", "srt" to "text/plain",
        "ini" to "text/plain", "yaml" to "text/yaml", "yml" to "text/yaml", "sql" to "text/plain",
        "json" to "application/json", "js" to "text/javascript", "py" to "text/x-python",
        "pdf" to "application/pdf", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "rtf" to "application/rtf", "epub" to "application/epub+zip",
        "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "gz" to "application/gzip", "apk" to "application/vnd.android.package-archive",
    )
    val TEXT_EXT = setOf("txt", "md", "csv", "json", "log", "py", "js", "html", "css", "xml", "ini", "yaml", "yml", "sql", "srt")
    val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "rtf", "epub")
    val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma")
    val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

    /** ไฟล์ที่ "เปิด" = "รันโปรแกรม" – ห้ามเปิดจากตู้ (แอปคอม + แพ็กเกจแอป Android) */
    private val DANGEROUS_EXT = setOf(
        "exe", "com", "bat", "cmd", "scr", "pif", "msi", "msp", "msc", "cpl", "hta",
        "js", "jse", "vbs", "vbe", "wsf", "wsh", "ws", "ps1", "psm1", "psd1", "lnk", "url",
        "reg", "jar", "appref-ms", "application", "gadget", "inf", "scf", "sct", "chm",
        "dll", "sys", "ocx", "xll", "library-ms", "settingcontent-ms", "diagcab", "appx",
        "msix", "appxbundle", "msixbundle", "appinstaller", "iso", "img", "vhd", "vhdx",
        "py", "pyw", "pyc", "sh", "bash", "command", "app", "desktop", "run", "bin",
        "pkg", "dmg", "workflow", "action", "terminal", "scpt", "applescript",
        "apk", "apks", "xapk", "apkm", "aab", "dex", "so",
    )

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** mime จากชื่อไฟล์ (เหมือน mimetypes.guess_type) ไม่รู้จัก → ใช้ hint ถ้ามี */
    fun guess(name: String, hint: String?): String =
        TABLE[ext(name)] ?: hint?.takeIf { it.isNotBlank() && it != "application/octet-stream" && '/' in it }
        ?: "application/octet-stream"

    fun isText(name: String, mime: String) = mime.startsWith("text/") || ext(name) in TEXT_EXT
    fun isAudio(name: String, mime: String) = mime.startsWith("audio/") || ext(name) in AUDIO_EXT

    /** "image" / "video" / "doc" / "other" – แท็บกรองของแกลเลอรี */
    fun kind(name: String, mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        isText(name, mime) || ext(name) in DOC_EXT -> "doc"
        else -> "other"
    }

    fun isDangerous(name: String): Boolean {
        val low = name.lowercase().trimEnd(' ', '.')
        return DANGEROUS_EXT.any { low.endsWith(".$it") }
    }

    fun humanSize(n: Long): String {
        var v = n.toDouble()
        for (u in listOf("B", "KB", "MB", "GB")) {
            if (v < 1024 || u == "GB") return if (u == "B") "${n} B" else String.format(java.util.Locale.US, "%.1f %s", v, u)
            v /= 1024
        }
        return "$n B"
    }

    /** ชื่อที่ใช้เป็นชื่อไฟล์/โฟลเดอร์บนดิสก์ได้ทุกระบบ (รวมชื่อต้องห้ามของ Windows) */
    fun safeName(name: String): String {
        var out = name.map { if (it in "<>:\"/\\|?*" || it.code < 32) '_' else it }.joinToString("").trim(' ', '.')
        val stem = out.substringBefore('.').lowercase()
        if (stem in setOf("con", "prn", "aux", "nul") || Regex("^(com|lpt)[0-9]$").matches(stem)) out = "_$out"
        return out.ifEmpty { "file" }
    }

    fun uniqueFile(folder: File, name: String): File {
        var f = File(folder, name)
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val suf = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) { f = File(folder, "$stem ($i)$suf"); i++ }
        return f
    }
}

/** ถอด/เข้ารหัสตัวอักษรของไฟล์ข้อความ – ตรงกับ decode_text ของแอปคอม (UTF-8 / UTF-16 / ไทย TIS-620) */
object TextCodec {
    data class Decoded(val text: String, val enc: String, val crlf: Boolean, val exact: Boolean)

    /** ชื่อ charset ภาษาไทย (cp874) ที่เครื่องนี้รองรับ */
    val THAI: String? = listOf("windows-874", "x-windows-874", "TIS-620", "x-IBM874")
        .firstOrNull { try { Charset.isSupported(it) } catch (e: Exception) { false } }

    private fun strict(b: ByteArray, cs: String, skip: Int = 0): String? = try {
        Charset.forName(cs).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b, skip, b.size - skip)).toString()
    } catch (e: Exception) { null }

    fun decode(b: ByteArray): Decoded {
        var text: String? = null
        var enc = "utf-8"
        var exact = false
        val tries = ArrayList<Pair<String, Int>>()
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) tries.add("utf-8-sig" to 3)
        else if (b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte())))
            tries.add("utf-16" to 0)
        tries.add("utf-8" to 0)
        THAI?.let { tries.add(it to 0) }
        for ((cs, skip) in tries) {
            val real = if (cs == "utf-8-sig") "UTF-8" else cs
            val t = strict(b, real, skip) ?: continue
            text = t; enc = if (cs == THAI) "cp874" else cs; exact = true
            break
        }
        if (text == null || (exact && '\u0000' in text && enc != "utf-16")) {
            text = String(b, Charsets.UTF_8); enc = "utf-8"; exact = false
        }
        val crlf = "\r\n" in text
        if (crlf) text = text.replace("\r\n", "\n")
        return Decoded(text, enc, crlf, exact)
    }

    /** → (ข้อมูล, encoding ที่ใช้จริง) ตัวอักษรที่ TIS-620 ไม่มี (เช่น อีโมจิ) → บันทึกเป็น UTF-8 แทน */
    fun encode(text: String, enc: String, crlf: Boolean): Pair<ByteArray, String> {
        val t = if (crlf) text.replace("\n", "\r\n") else text
        fun enc8(withBom: Boolean): ByteArray {
            val body = t.toByteArray(Charsets.UTF_8)
            if (!withBom) return body
            val out = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
            java.util.Arrays.fill(body, 0)
            return out
        }
        return when (enc) {
            "utf-8-sig" -> enc8(true) to enc
            "utf-16" -> t.toByteArray(Charsets.UTF_16) to enc          // ใส่ BOM เหมือน Python
            "cp874" -> {
                val cs = THAI ?: return enc8(false) to "utf-8"
                try {
                    val bb = Charset.forName(cs).newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(t))
                    ByteArray(bb.remaining()).also { bb.get(it) } to "cp874"
                } catch (e: Exception) { enc8(false) to "utf-8" }
            }
            else -> enc8(false) to "utf-8"
        }
    }
}

/** นับรหัสผิดแบบถาวร เก็บ 2 ที่ (ในตู้ + ที่เก็บของแอป) แล้วใช้ค่าที่มากกว่า – รูปแบบเดียวกับ Lockout ของแอปคอม */
class Lockout(vault: Vault, configDir: File) {
    companion object {
        const val FREE_TRIES = 3          // ผิดได้กี่ครั้งก่อนเริ่มหน่วงเวลา
        const val MAX_WAIT = 3600.0       // หน่วงนานสุด 1 ชั่วโมง
    }

    data class State(var fails: Long = 0, var until: Double = 0.0, var sinceOk: Long = 0, var lastFail: Double = 0.0)

    private val paths: List<File>

    init {
        val vid = try {
            Crypto.hex(Crypto.sha256((vault.meta()["kdf"].asMap()["salt"].asStr() ?: throw Exception()).toByteArray())).substring(0, 16)
        } catch (e: Exception) {
            Crypto.hex(Crypto.sha256(vault.root.path.toByteArray())).substring(0, 16)
        }
        paths = listOf(File(vault.root, "lockout.json"), File(configDir, "lockout-$vid.json"))
    }

    fun load(): State {
        val st = State()
        for (p in paths) {
            try {
                val d = Json.obj(String(p.readBytes(), Charsets.UTF_8))
                st.fails = maxOf(st.fails, d["fails"].asLong())
                st.until = maxOf(st.until, d["until"].asDouble())
                st.sinceOk = maxOf(st.sinceOk, d["since_ok"].asLong())
                st.lastFail = maxOf(st.lastFail, d["last_fail"].asDouble())
            } catch (_: Exception) {}
        }
        st.until = minOf(st.until, Vault.now() + MAX_WAIT)   // กันนาฬิกาเครื่องถูกตั้งล่วงหน้า
        return st
    }

    private fun save(st: State) {
        val data = Json.write(linkedMapOf("fails" to st.fails, "until" to st.until,
            "since_ok" to st.sinceOk, "last_fail" to st.lastFail)).toByteArray()
        for (p in paths) {
            try { p.parentFile?.mkdirs(); Vault.atomicWrite(p, data) } catch (_: Exception) {}
        }
    }

    fun waitLeft(): Double = maxOf(0.0, load().until - Vault.now())

    fun failed(): State {
        val st = load()
        val now = Vault.now()
        st.fails++; st.sinceOk++; st.lastFail = now
        if (st.fails >= FREE_TRIES) st.until = now + minOf(30.0 * Math.pow(2.0, (st.fails - FREE_TRIES).toDouble()), MAX_WAIT)
        save(st)
        return st
    }

    /** คืนสถิติก่อนรีเซ็ต (ไว้แจ้งเตือนว่ามีคนพยายามเดารหัส) */
    fun succeeded(): State {
        val st = load()
        save(State())
        return st
    }
}
