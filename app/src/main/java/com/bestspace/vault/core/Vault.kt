package com.bestspace.vault.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.CharBuffer
import java.nio.file.Files
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

open class VaultException(msg: String) : Exception(msg)
class WrongPassword(msg: String = "รหัสผ่านไม่ถูกต้อง") : VaultException(msg)
open class CorruptData(msg: String) : VaultException(msg)
class BadKeyTag(msg: String) : CorruptData(msg)
class KeyfileMissing(val fp: String) : VaultException("ไม่พบไฟล์กุญแจของตู้นี้ (ลายนิ้วมือ $fp)")
class RemoteRekey : VaultException(
    "ตู้นี้ถูกเปลี่ยนรหัสผ่านจากเครื่องอื่นระหว่างที่เปิดอยู่ – ล็อคแล้วเปิดใหม่ด้วยรหัสใหม่ " +
        "(การเปลี่ยนแปลงล่าสุดของเครื่องนี้ยังไม่ถูกบันทึก)")

typealias Progress = ((String) -> Unit)?

/** ผลการรวมไฟล์ขัดแย้งจากโปรแกรมซิงค์ (ตรงกับ merge_conflicts ของแอปคอม) */
data class ConflictReport(
    var merged: Int = 0, var gained: Int = 0,
    val unmergeable: MutableList<String> = ArrayList(),
    val vaultConflicts: MutableList<String> = ArrayList(),
    var cleaned: Int = 0,
)

/** ข้อมูลของ "คนอื่น" ที่กำลังเปิดตู้อยู่ */
data class OtherSession(val sameDevice: Boolean, val ageSec: Double, val since: Double)

/** ไฟล์กุญแจ bestspace.key – รูปแบบเดียวกับแอปคอม */
object Keyfiles {
    const val MAGIC = "BESTSPACE-KEY-v1"

    fun fp(secret: ByteArray): String =
        Crypto.hex(Crypto.sha256("bestspace-keyfile-fp".toByteArray(), secret)).substring(0, 16)

    fun parse(text: String): ByteArray? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.size < 2 || lines[0] != MAGIC) return null
        return try { Crypto.unb64(lines[1]).takeIf { it.size == 32 } } catch (e: Exception) { null }
    }

    fun read(f: File): ByteArray? = try {
        if (f.length() > 64 * 1024) null else parse(String(f.readBytes(), Charsets.UTF_8))
    } catch (e: Exception) { null }

    fun text(secret: ByteArray): String =
        "$MAGIC\n${Crypto.b64(secret)}\n# fingerprint ${fp(secret)}\n" +
            "# อย่าเก็บไฟล์นี้ไว้ที่เดียวกับโฟลเดอร์ .Bestunit หรือไฟล์สำรองของตู้\n"

    /** เขียนไฟล์กุญแจใหม่ (ไม่ทับไฟล์เดิม) */
    fun write(f: File, secret: ByteArray): File {
        f.parentFile?.mkdirs()
        if (!f.createNewFile()) throw VaultException("มีไฟล์ ${f.name} อยู่แล้ว")
        FileOutputStream(f).use { it.write(text(secret).toByteArray(Charsets.UTF_8)); it.fd.sync() }
        return f
    }

    private fun candidates(dir: File?): List<File> =
        dir?.listFiles { x -> x.isFile && x.name.startsWith("bestspace") && x.name.endsWith(".key") }
            ?.sortedBy { it.name } ?: emptyList()

    fun find(dir: File?, fp: String): ByteArray? {
        for (f in candidates(dir)) {
            val sec = read(f) ?: continue
            if (fp(sec) == fp) return sec
        }
        return null
    }

    fun pathOf(dir: File?, fp: String): File? = candidates(dir).firstOrNull { read(it)?.let { s -> fp(s) == fp } == true }

    /** วางกุญแจลง dir โดยไม่ทับกุญแจอื่น (bestspace.key หรือ bestspace-<fp>.key) */
    fun install(dir: File, secret: ByteArray): File {
        val fp = fp(secret)
        pathOf(dir, fp)?.let { return it }
        val main = File(dir, "bestspace.key")
        return write(if (!main.exists()) main else File(dir, "bestspace-$fp.key"), secret)
    }
}

/**
 * ตู้ BestSpace – รูปแบบไฟล์เดียวกับแอปคอม (bestspace_core.py) ทุกประการ
 *   vault.json   kdf (scrypt) + master key ที่ห่อด้วยรหัสผ่าน (+ไฟล์กุญแจ)
 *   index.enc    "BSI1" + nonce + AES-GCM(master, JSON ดัชนี)
 *   files/<id>.bsf  เนื้อไฟล์ เข้ารหัสทีละก้อน 1 MiB    files/<id>.bst  ภาพย่อ
 */
class Vault(val root: File, val keyDir: File?) {
    companion object {
        val FILE_MAGIC = "BSF1".toByteArray()
        val INDEX_MAGIC = "BSI1".toByteArray()
        val SHARE_MAGIC = "BSX1".toByteArray()
        val THUMB_MAGIC = "BST1".toByteArray()
        const val CHUNK = 1024 * 1024
        const val TAG = 16
        const val TOMBSTONE_KEEP = 20000
        const val SESSION_STALE = 300.0
        private val AAD_MASTER = "bestspace-master-v1".toByteArray()
        private val AAD_INDEX = "bestspace-index-v1".toByteArray()
        private val AAD_FKEY = "bestspace-fkey-v1".toByteArray()
        private val CANON_BLOB = Regex("^[0-9a-f]{32}\\.(bsf|bst)$")
        private val rng = SecureRandom()

        /** แทน pid ของแอปคอม: เลขสุ่มประจำ process นี้ */
        val PROCESS_TAG: Long = (rng.nextInt(Int.MAX_VALUE - 1) + 1).toLong()

        fun now(): Double = System.currentTimeMillis() / 1000.0
        fun isDir(id: String) = id.startsWith("dir-")

        fun newId(): ByteArray {
            val u = UUID.randomUUID()
            val b = java.nio.ByteBuffer.allocate(16)
            b.putLong(u.mostSignificantBits); b.putLong(u.leastSignificantBits)
            return b.array()
        }

        /** รวมดัชนี 2 ชุด – ตรงกับ merge_index ของแอปคอม */
        fun mergeIndex(mine: MutableMap<String, Any?>, other: MutableMap<String, Any?>): Pair<MutableMap<String, Any?>, Int> {
            val tomb = LinkedHashMap<String, Any?>(other["tombstones"].asMap())
            for ((k, t) in mine["tombstones"].asMap()) tomb[k] = maxOf(t.asLong(), tomb[k].asLong())
            val files = LinkedHashMap<String, Any?>(other["files"].asMap()).apply { putAll(mine["files"].asMap()) }
            val dirs = LinkedHashMap<String, Any?>(other["folders"].asMap()).apply { putAll(mine["folders"].asMap()) }
            files.keys.removeAll(tomb.keys); dirs.keys.removeAll(tomb.keys)
            val gained = (files.keys - mine["files"].asMap().keys).size + (dirs.keys - mine["folders"].asMap().keys).size
            val tombOut: MutableMap<String, Any?> = if (tomb.size > TOMBSTONE_KEEP)
                LinkedHashMap(tomb.entries.sortedByDescending { it.value.asLong() }.take(TOMBSTONE_KEEP).associate { it.key to it.value })
            else tomb
            val out = LinkedHashMap(mine)
            out["files"] = files; out["folders"] = dirs; out["tombstones"] = tombOut
            GenStore.merge(out, other)
            return out to gained
        }

        /** ชื่อไฟล์ที่โปรแกรมซิงค์สร้างตอนขัดแย้ง → "index" / "vault" / "junk" / null */
        fun conflictKind(name: String): String? {
            val low = name.lowercase()
            if (low.endsWith(".tmp") || low.endsWith(".next") || low.endsWith(".bspart")) return null
            for ((base, ext, kind) in listOf(Triple("index", ".enc", "index"), Triple("vault", ".json", "vault"),
                Triple("lockout", ".json", "junk"), Triple("session", ".lock", "junk"))) {
                if (low.startsWith(base) && low.endsWith(ext) && low != base + ext) return kind
            }
            return null
        }

        /** ชื่อไฟล์ที่ปลอดภัย: ตัด path / ตัวอักษรควบคุม (กัน path traversal จากไฟล์ .bsx) */
        fun cleanName(n: String): String {
            val base = n.replace('\\', '/').substringAfterLast('/')
            val c = base.filter { it.code >= 32 && it !in "<>:\"|?*" }.trim(' ', '.')
            return c.take(255).ifEmpty { "file" }
        }

        private fun aadChunk(fid: ByteArray, idx: Int, final: Boolean): ByteArray =
            FILE_MAGIC + fid + Crypto.be32(idx) + byteArrayOf(if (final) 1 else 0)

        private fun readFully(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = input.read(buf, got, n - got)
                if (r < 0) break
                got += r
            }
            return if (got == n) buf else buf.copyOf(got).also { Crypto.wipe(buf) }
        }

        /** เข้ารหัสทีละก้อน (เหมือน encrypt_stream) → จำนวนไบต์ที่เข้ารหัสจริง */
        fun encryptStream(key: ByteArray, input: InputStream, out: OutputStream, fid: ByteArray,
                          total: Long = 0, progress: Progress = null): Long {
            val prefix = Crypto.random(8)
            out.write(FILE_MAGIC); out.write(prefix)
            var cur = readFully(input, CHUNK)
            var idx = 0
            var done = 0L
            var lastPct = -1L
            while (true) {
                val nxt = readFully(input, CHUNK)
                val final = nxt.isEmpty()
                val ct = Crypto.gcmEncrypt(key, prefix + Crypto.be32(idx), cur, aadChunk(fid, idx, final))
                out.write(Crypto.be32(ct.size)); out.write(ct)
                done += cur.size
                if (progress != null && total > 0) {
                    val pct = minOf(100L, done * 100 / total)
                    if (pct != lastPct) { lastPct = pct; progress("$pct%") }
                }
                Crypto.wipe(cur)
                if (final) return done
                cur = nxt; idx++
            }
        }

        /** ถอดรหัสไฟล์ .bsf ตั้งแต่ตำแหน่ง start ถึงท้ายไฟล์ (เหมือน decrypt_stream) – ข้อมูลถูกแก้/ตัด = CorruptData */
        fun decryptFile(key: ByteArray, f: File, start: Long, fid: ByteArray, write: (ByteArray) -> Unit) {
            RandomAccessFile(f, "r").use { raf ->
                val end = raf.length()
                raf.seek(start)
                val hdr = ByteArray(12)
                if (end - start < 12) throw CorruptData("ไม่ใช่ไฟล์ BestSpace")
                raf.readFully(hdr)
                if (!hdr.copyOfRange(0, 4).contentEquals(FILE_MAGIC)) throw CorruptData("ไม่ใช่ไฟล์ BestSpace")
                val prefix = hdr.copyOfRange(4, 12)
                var idx = 0
                while (true) {
                    if (raf.filePointer + 4 > end) throw CorruptData("ไฟล์ถูกตัดหรือเสียหาย")
                    val n = raf.readInt()
                    if (n < TAG || n > CHUNK + TAG) throw CorruptData("ไฟล์เสียหาย")
                    if (raf.filePointer + n > end) throw CorruptData("ไฟล์ถูกตัดหรือเสียหาย")
                    val ct = ByteArray(n)
                    raf.readFully(ct)
                    val final = raf.filePointer == end
                    val pt = try { Crypto.gcmDecrypt(key, prefix + Crypto.be32(idx), ct, aadChunk(fid, idx, final)) }
                    catch (e: Crypto.BadTag) { throw CorruptData("ตรวจสอบความถูกต้องไม่ผ่าน (ไฟล์ถูกแก้ไขหรือเสียหาย)") }
                    write(pt)
                    Crypto.wipe(pt)
                    if (final) return
                    idx++
                }
            }
        }

        private fun utf8(chars: CharArray): ByteArray {
            val bb = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
            val raw = bb.array()
            val len = bb.limit()
            return if (raw != null && bb.arrayOffset() == 0 && len == raw.size) raw
            else ByteArray(len).also { bb.get(it); if (raw != null) Crypto.wipe(raw) }
        }

        fun deriveKey(password: CharArray, salt: ByteArray, kdf: Map<String, Any?>, progress: ((Float) -> Unit)? = null): ByteArray {
            val n = kdf["n"].asLong(); val r = kdf["r"].asLong(); val p = kdf["p"].asLong()
            // ตรวจพารามิเตอร์ กันไฟล์ .bsx ปลอมที่ตั้งค่าให้กินแรมจนเครื่องค้าง
            if (kdf["name"] != "scrypt" || n < (1L shl 14) || n > (1L shl 20) || n and (n - 1) != 0L)
                throw CorruptData("พารามิเตอร์ KDF ไม่ถูกต้อง")
            if (r !in 1..16 || p !in 1..4 || salt.size < 16) throw CorruptData("พารามิเตอร์ KDF ไม่ถูกต้อง")
            if (128L * n * r > 512L * 1024 * 1024) throw CorruptData("พารามิเตอร์ KDF ใช้หน่วยความจำมากเกินไป")
            val pw = utf8(password)
            try { return Crypto.scrypt(pw, salt, n.toInt(), r.toInt(), p.toInt(), 32, progress) } finally { Crypto.wipe(pw) }
        }

        fun deriveKey(password: String, salt: ByteArray, kdf: Map<String, Any?>, progress: ((Float) -> Unit)? = null): ByteArray {
            val c = password.toCharArray()
            try { return deriveKey(c, salt, kdf, progress) } finally { Crypto.wipe(c) }
        }

        fun hasValidTmpMeta(dir: File): Boolean {
            val tmp = File(dir, "vault.json.tmp")
            if (!tmp.isFile) return false
            return try {
                Json.obj(String(tmp.readBytes(), Charsets.UTF_8)).containsKey("master")
            } catch (_: Exception) { false }
        }

        private fun kdfDefault(): LinkedHashMap<String, Any?> = linkedMapOf("name" to "scrypt", "n" to 131072L, "r" to 8L, "p" to 1L)

        fun atomicWrite(f: File, data: ByteArray) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            FileOutputStream(tmp).use { it.write(data); it.fd.sync() }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) { tmp.delete(); throw VaultException("บันทึก ${f.name} ไม่สำเร็จ") }
            }
            fsyncDir(f.parentFile)
        }

        /** ให้การ rename ถูกบันทึกถาวร (ไฟดับแล้วดัชนีไม่ย้อนกลับ) – ทำได้เท่าที่ระบบยอม */
        fun fsyncDir(d: File?) {
            if (d == null) return
            try {
                java.nio.channels.FileChannel.open(d.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Throwable) {}
        }

        private const val NAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private fun randomName(n: Int): String {
            val b = Crypto.random(maxOf(1, n))
            return String(CharArray(b.size) { NAME_CHARS[(b[it].toInt() and 0xff) % NAME_CHARS.length] })
        }

        /** เขียนทับเนื้อไฟล์ทั้งไฟล์ด้วยข้อมูลสุ่ม + sync (ไฟล์ยังอยู่ ขนาดเท่าเดิม) → true = เขียนทับครบ */
        fun overwriteInPlace(f: File): Boolean = try {
            RandomAccessFile(f, "rw").use { raf ->
                var left = raf.length()
                val buf = ByteArray(minOf(CHUNK.toLong(), maxOf(1L, left)).toInt())
                while (left > 0) {
                    val n = minOf(left, buf.size.toLong()).toInt()
                    rng.nextBytes(buf)
                    raf.write(buf, 0, n); left -= n
                }
                raf.fd.sync()
            }
            true
        } catch (e: Exception) { false }

        /** ลบแบบทำลายทิ้ง (เหมือน secure_delete): เขียนทับสุ่ม → ตัดขนาด → เปลี่ยนชื่อสุ่ม 3 รอบ → ลบ
         *  ไม่ตาม symlink • คืน true เฉพาะเมื่อเขียนทับเนื้อไฟล์ครบ "และ" ลบได้ (ไม่รายงานว่าสำเร็จถ้าแค่ลบได้)
         *  ข้อจำกัด: หน่วยความจำแฟลช (wear-leveling / f2fs เขียนที่ใหม่) อาจยังเก็บข้อมูลเก่า */
        fun secureDelete(f: File): Boolean {
            val p = f.toPath()
            if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
            if (Files.isSymbolicLink(p) || !Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { f.delete(); return false }
            var cur = f
            var overwritten = false
            try {
                RandomAccessFile(f, "rw").use { raf ->
                    var left = raf.length()
                    val buf = ByteArray(minOf(CHUNK.toLong(), maxOf(1L, left)).toInt())
                    while (left > 0) {
                        val n = minOf(left, buf.size.toLong()).toInt()
                        rng.nextBytes(buf)
                        raf.write(buf, 0, n); left -= n
                    }
                    raf.fd.sync()
                    overwritten = true
                    raf.setLength(0)
                    raf.fd.sync()
                }
                repeat(3) {
                    val nxt = File(cur.parentFile, randomName(f.name.length))
                    if (!nxt.exists() && cur.renameTo(nxt)) cur = nxt
                }
            } catch (_: Exception) {
            } finally {
                cur.delete()
                if (cur != f) f.delete()
            }
            return overwritten && !cur.exists() && !f.exists()
        }

        /** แทนที่ไฟล์สำคัญ (vault.json) แบบกันไฟดับ + เขียนทับของเดิมก่อน:
         *  เขียน .tmp → sync → เขียนทับเนื้อไฟล์เดิมด้วยข้อมูลสุ่ม → rename .tmp ทับ
         *  (ถ้าเครื่องดับหลังเขียนทับ ก่อน rename → meta() กู้จาก .tmp ให้) */
        fun replaceSecure(f: File, data: ByteArray) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            FileOutputStream(tmp).use { it.write(data); it.fd.sync() }
            if (f.exists()) overwriteInPlace(f)
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) throw VaultException("บันทึก ${f.name} ไม่สำเร็จ")
            }
        }
    }

    val filesDir = File(root, "files")
    val metaFile = File(root, "vault.json")
    val indexFile = File(root, "index.enc")
    private val sessionFile = File(root, "session.lock")
    private var master: ByteArray? = null
    var index: MutableMap<String, Any?>? = null
        private set
    private var diskSig: String? = null
    var externalMerges = 0
        private set
    var device: String? = null
    /** บันทึกงานที่ค้าง (แอปตั้งให้) – ถูกขัดจังหวะกลางการนำเข้า ครั้งหน้าเก็บกวาดให้ */
    var journal: Journal? = null
    /** ตรวจตู้ถูกย้อนเวอร์ชัน (แอปตั้งให้) • rollback = (รุ่นบนดิสก์, รุ่นที่เคยเห็น) หลังปลดล็อค */
    var genStore: GenStore? = null
    @Volatile var rollback: Pair<Long, Long>? = null
        private set
    @Volatile var indexRevision: Long = 0L
        private set

    val isUnlocked get() = master != null
    fun exists() = metaFile.exists() || hasValidTmpMeta(root)
    fun meta(): MutableMap<String, Any?> {
        try {
            val m = Json.obj(String(metaFile.readBytes(), Charsets.UTF_8))
            if (m.containsKey("master")) return m
            throw CorruptData("vault.json เสียหาย")
        } catch (e: Exception) {
            // เครื่องดับระหว่างเปลี่ยนรหัส (เขียนทับ/ลบ vault.json เดิมแล้ว แต่ยังไม่ได้ rename ไฟล์ใหม่) → กู้จาก .tmp
            val tmp = File(root, "vault.json.tmp")
            val m = try { if (tmp.isFile) Json.obj(String(tmp.readBytes(), Charsets.UTF_8)) else null } catch (_: Exception) { null }
            if (m != null && m.containsKey("master")) {
                if (!tmp.renameTo(metaFile)) {
                    metaFile.delete()
                    if (!tmp.renameTo(metaFile)) throw e
                }
                fsyncDir(root)
                return m
            }
            throw e
        }
    }
    fun needsKeyfile(): Boolean = try { meta().containsKey("keyfile") } catch (e: Exception) { false }
    fun keyfileFp(): String? = try { meta()["keyfile"].asMap()["fp"].asStr() } catch (e: Exception) { null }

    private fun need(): ByteArray = master ?: throw VaultException("ตู้ถูกล็อคอยู่")

    // ------------------------------------------------------------------ ไฟล์กุญแจ
    private fun secretFor(meta: Map<String, Any?>): ByteArray? {
        val kf = meta["keyfile"] ?: return null
        val fp = kf.asMap()["fp"].asStr() ?: ""
        return Keyfiles.find(keyDir, fp) ?: located(fp) ?: throw KeyfileMissing(fp)
    }

    /**
     * ตำแหน่งไฟล์กุญแจที่ผู้ใช้เลือกไว้ (แฟลชไดรฟ์/SD card/คลาวด์ผ่าน SAF) fp → secret
     * อ่านจากที่นั่นตรงๆ ทุกครั้ง ไม่คัดลอกมาไว้ข้างตู้
     */
    @Volatile var keyLocator: ((String) -> ByteArray?)? = null

    private fun located(fp: String): ByteArray? {
        val sec = try { keyLocator?.invoke(fp) } catch (e: Exception) { null } ?: return null
        return if (Keyfiles.fp(sec) == fp) sec else { sec.fill(0); null }
    }

    /** กุญแจของตู้นี้ (จากข้างตู้ หรือจากตำแหน่งที่เลือกไว้) – ไว้สำรอง; null = หาไม่เจอ */
    fun keySecret(): ByteArray? = keyfileFp()?.let { Keyfiles.find(keyDir, it) ?: located(it) }

    /** ใช้ bestspace.key ที่มีอยู่ หรือสุ่มกุญแจใหม่ → (secret, ไฟล์ถ้าสร้างใหม่) */
    private fun getOrMakeSecret(): Pair<ByteArray, File?> {
        val dir = keyDir ?: throw VaultException("ไม่ได้กำหนดที่เก็บไฟล์กุญแจ")
        val main = File(dir, "bestspace.key")
        if (main.exists()) Keyfiles.read(main)?.let { return it to null }
        val sec = Crypto.random(32)
        return try { sec to Keyfiles.install(dir, sec) }
        catch (e: Exception) { Crypto.wipe(sec); throw VaultException("สร้างไฟล์กุญแจไม่ได้ (${e.message})") }
    }

    /** ไฟล์กุญแจที่ตู้นี้ใช้อยู่ (ไว้สำรอง) */
    fun keyfilePath(): File? = keyfileFp()?.let { Keyfiles.pathOf(keyDir, it) }

    private fun mixKeyfile(kek: ByteArray, secret: ByteArray?): ByteArray {
        if (secret == null) return kek
        val ikm = kek + secret
        val out = Crypto.hkdf(ikm, "bestspace-keyfile-v1".toByteArray(), "kek".toByteArray(), 32)
        Crypto.wipe(kek); Crypto.wipe(ikm)
        return out
    }

    private fun writeMeta(password: CharArray, master: ByteArray, secret: ByteArray?, progress: ((Float) -> Unit)?,
                          secureReplace: Boolean = false) {
        val salt = Crypto.random(16)
        val kdf = kdfDefault()
        val kek = mixKeyfile(deriveKey(password, salt, kdf, progress), secret)
        val nonce = Crypto.random(12)
        val ct = try { Crypto.gcmEncrypt(kek, nonce, master, AAD_MASTER) } finally { Crypto.wipe(kek) }
        kdf["salt"] = Crypto.b64(salt)
        val meta = linkedMapOf<String, Any?>("app" to "BestSpace", "version" to 1L, "kdf" to kdf,
            "master" to linkedMapOf("nonce" to Crypto.b64(nonce), "ct" to Crypto.b64(ct)))
        if (secret != null) meta["keyfile"] = linkedMapOf("fp" to Keyfiles.fp(secret))
        val data = Json.write(meta).toByteArray(Charsets.UTF_8)
        if (secureReplace) replaceSecure(metaFile, data) else atomicWrite(metaFile, data)
    }

    // ------------------------------------------------------------------ สร้าง / ปลดล็อค / ล็อค
    /** สร้างตู้ใหม่ คืนไฟล์กุญแจถ้าเพิ่งสร้างใหม่ (ต้องให้ผู้ใช้สำรองทันที) */
    fun create(password: CharArray, useKeyfile: Boolean, progress: ((Float) -> Unit)? = null): File? {
        if (exists()) throw VaultException("มีตู้อยู่แล้ว")
        root.mkdirs(); filesDir.mkdirs()
        if (!filesDir.isDirectory) throw VaultException("สร้างโฟลเดอร์ตู้ไม่ได้: ${root.path}")
        try { File(root, ".nomedia").createNewFile() } catch (_: Exception) {}   // ไม่ให้แอปแกลเลอรีสแกน
        var secret: ByteArray? = null
        var newKey: File? = null
        val m = Crypto.random(32)
        try {
            if (useKeyfile) { val (s, f) = getOrMakeSecret(); secret = s; newKey = f }
            writeMeta(password, m, secret, progress)
        } catch (e: Exception) {
            Crypto.wipe(m)
            throw e
        } finally {
            Crypto.wipe(secret)
        }
        master = m
        index = linkedMapOf("version" to 1L, "files" to linkedMapOf<String, Any?>(), "folders" to linkedMapOf<String, Any?>())
        try { saveIndex() } catch (e: Exception) { lock(); throw e }
        return newKey
    }

    fun create(password: String, useKeyfile: Boolean, progress: ((Float) -> Unit)? = null): File? {
        val c = password.toCharArray()
        try { return create(c, useKeyfile, progress) } finally { Crypto.wipe(c) }
    }

    private fun unwrap(password: CharArray, progress: ((Float) -> Unit)?): ByteArray {
        val meta = meta()
        val kdf = meta["kdf"].asMap()
        val secret = secretFor(meta)                  // ไม่มีไฟล์กุญแจ = หยุดก่อนเสียเวลา scrypt
        val kek = try {
            mixKeyfile(deriveKey(password, Crypto.unb64(kdf["salt"].asStr() ?: ""), kdf, progress), secret)
        } finally {
            Crypto.wipe(secret)
        }
        try {
            val m = meta["master"].asMap()
            return Crypto.gcmDecrypt(kek, Crypto.unb64(m["nonce"].asStr() ?: ""), Crypto.unb64(m["ct"].asStr() ?: ""), AAD_MASTER)
        } catch (e: Crypto.BadTag) {
            throw WrongPassword()
        } finally {
            Crypto.wipe(kek)
        }
    }

    fun verifyPassword(password: CharArray, progress: ((Float) -> Unit)? = null) {
        val m = unwrap(password, progress)
        Crypto.wipe(m)
    }

    fun verifyPassword(password: String, progress: ((Float) -> Unit)? = null) {
        val c = password.toCharArray()
        try { verifyPassword(c, progress) } finally { Crypto.wipe(c) }
    }

    fun unlock(password: CharArray, progress: ((Float) -> Unit)? = null) {
        val m = unwrap(password, progress)
        synchronized(this) {
            master = m
            try { loadIndex() } catch (e: Exception) { lock(); throw e }
        }
    }

    fun unlock(password: String, progress: ((Float) -> Unit)? = null) {
        val c = password.toCharArray()
        try { unlock(c, progress) } finally { Crypto.wipe(c) }
    }

    @Synchronized fun lock() {
        Crypto.wipe(master)
        master = null
        index = null
        diskSig = null
        indexRevision++
    }

    /** เปลี่ยนรหัส + หมุน master key ใหม่ (สำเนา vault.json เก่า + รหัสเก่า เปิดตู้ปัจจุบันไม่ได้อีก) */
    fun changePassword(old: CharArray, new: CharArray, progress: ((Float) -> Unit)? = null) {
        val secret = secretFor(meta())
        try { rekey(old, new, secret, progress) } finally { Crypto.wipe(secret) }
    }

    fun changePassword(old: String, new: String, progress: ((Float) -> Unit)? = null) {
        val oc = old.toCharArray(); val nc = new.toCharArray()
        try { changePassword(oc, nc, progress) } finally { Crypto.wipe(oc); Crypto.wipe(nc) }
    }

    /** เพิ่มไฟล์กุญแจให้ตู้ที่มีอยู่ + หมุน master key ใหม่ คืนไฟล์กุญแจถ้าเพิ่งสร้างใหม่ */
    fun addKeyfile(password: CharArray, progress: ((Float) -> Unit)? = null): File? {
        if (needsKeyfile()) throw VaultException("ตู้นี้ใช้ไฟล์กุญแจอยู่แล้ว")
        val (secret, newKey) = getOrMakeSecret()
        try { rekey(password, password, secret, progress) } finally { Crypto.wipe(secret) }
        return newKey
    }

    fun addKeyfile(password: String, progress: ((Float) -> Unit)? = null): File? {
        val c = password.toCharArray()
        try { return addKeyfile(c, progress) } finally { Crypto.wipe(c) }
    }

    /** ลำดับเขียนกันไฟดับ: index.enc.next → vault.json → index.enc */
    private fun rekey(old: CharArray, new: CharArray, secret: ByteArray?, progress: ((Float) -> Unit)?) {
        val oldMaster = unwrap(old) { p -> progress?.invoke(p / 2) }
        var newMaster: ByteArray? = null
        try {
            synchronized(this) {
                if (index == null) {                      // เรียกตอนล็อคอยู่
                    master = oldMaster.copyOf()
                    try { loadIndex() } finally { Crypto.wipe(master); master = null }
                }
                if (master != null) syncFromDisk()
                val idx = Json.obj(Json.write(index!!))      // สำเนา (ห่อคีย์ใหม่ก่อน แล้วค่อยสลับ)
                newMaster = Crypto.random(32)
                for ((fid, ev) in idx["files"].asMap()) {
                    val e = ev.asMap()
                    val k = e["wkey"].asStr()?.let { unwrapKey(oldMaster, fid, it) }
                        ?: Crypto.unb64((e.remove("key") as? String) ?: throw CorruptData("ไม่มีคีย์ของไฟล์"))
                    e["wkey"] = wrapKey(newMaster!!, fid, k)
                    Crypto.wipe(k)
                }
                val nxt = File(root, "index.enc.next")
                val sealed = sealIndex(idx, newMaster!!)
                atomicWrite(nxt, sealed)
                // vault.json เดิม (master เก่าห่อด้วยรหัสเก่า) ถูกเขียนทับก่อนแทนที่ – กันกู้จากพื้นที่ว่างของดิสก์
                writeMeta(new, newMaster!!, secret, { p -> progress?.invoke(0.5f + p / 2) }, secureReplace = true)
                // index.enc เดิม (คีย์ไฟล์ห่อด้วย master เก่า) เขียนทับก่อน rename – ถ้าดับตรงนี้ เปิดใหม่จะกู้จาก .next ได้
                overwriteInPlace(indexFile)
                if (!nxt.renameTo(indexFile)) {
                    indexFile.delete()
                    if (!nxt.renameTo(indexFile)) throw VaultException("บันทึก index.enc ไม่สำเร็จ")
                }
                fsyncDir(root)
                diskSig = sig(sealed)
                if (master != null) {
                    Crypto.wipe(master)
                    master = newMaster
                    newMaster = null
                    index = idx
                } else {
                    index = null
                }
            }
        } finally {
            Crypto.wipe(oldMaster)
            Crypto.wipe(newMaster)
            Crypto.wipe(secret)
        }
    }

    // ------------------------------------------------------------------ คีย์ไฟล์ (ห่อด้วย master)
    private fun wrapKey(m: ByteArray, fid: String, fkey: ByteArray): String {
        val nonce = Crypto.random(12)
        return Crypto.b64(nonce + Crypto.gcmEncrypt(m, nonce, fkey, AAD_FKEY + Crypto.unhex(fid)))
    }

    private fun unwrapKey(m: ByteArray, fid: String, wkey: String): ByteArray {
        val raw = Crypto.unb64(wkey)
        return try { Crypto.gcmDecrypt(m, raw.copyOfRange(0, 12), raw.copyOfRange(12, raw.size), AAD_FKEY + Crypto.unhex(fid)) }
        catch (e: Crypto.BadTag) { throw CorruptData("คีย์ของไฟล์เสียหาย") }
    }

    /** คีย์ของไฟล์ – ผู้เรียกควร Crypto.wipe() หลังใช้ */
    @Synchronized fun fileKey(fid: String): ByteArray {
        val e = entry(fid)
        e["wkey"].asStr()?.let { return unwrapKey(need(), fid, it) }
        return Crypto.unb64(e["key"].asStr() ?: throw CorruptData("ไม่มีคีย์ของไฟล์"))   // ตู้รุ่นเก่า
    }

    private fun migrateKeys() {
        val m = need()
        var changed = false
        for ((fid, ev) in files()) {
            val e = ev.asMap()
            val k = e["key"].asStr()
            if (k != null && !e.containsKey("wkey")) {
                val raw = Crypto.unb64(k)
                e["wkey"] = wrapKey(m, fid, raw); Crypto.wipe(raw); e.remove("key"); changed = true
            }
        }
        if (changed) saveIndex()
    }

    // ------------------------------------------------------------------ ดัชนี
    private fun sealIndex(idx: Map<String, Any?>, m: ByteArray): ByteArray {
        val nonce = Crypto.random(12)
        val pt = Json.write(idx).toByteArray(Charsets.UTF_8)
        try { return INDEX_MAGIC + nonce + Crypto.gcmEncrypt(m, nonce, pt, AAD_INDEX) } finally { Crypto.wipe(pt) }
    }

    private fun openIndex(raw: ByteArray): MutableMap<String, Any?> {
        if (raw.size < 32 || !raw.copyOfRange(0, 4).contentEquals(INDEX_MAGIC)) throw CorruptData("index เสียหาย")
        val pt = try {
            Crypto.gcmDecrypt(need(), raw.copyOfRange(4, 16), raw.copyOfRange(16, raw.size), AAD_INDEX)
        } catch (e: Crypto.BadTag) { throw BadKeyTag("index ถูกแก้ไขหรือเสียหาย") }
        try { return Json.obj(String(pt, Charsets.UTF_8)) }
        catch (e: IllegalArgumentException) { throw CorruptData("index เสียหาย") }
        finally { Crypto.wipe(pt) }
    }

    private fun sig(b: ByteArray) = Crypto.hex(Crypto.sha256(b))

    private fun loadIndex() {
        val nxt = File(root, "index.enc.next")
        diskSig = null
        try {
            val raw = indexFile.readBytes()
            index = openIndex(raw); diskSig = sig(raw)
            if (nxt.exists()) nxt.delete()            // เปลี่ยนรหัสค้างก่อนเขียน vault.json → ทิ้ง
        } catch (e: CorruptData) {
            if (!nxt.exists()) throw e
            val raw = nxt.readBytes()
            index = openIndex(raw)                    // ไฟดับหลังเขียน vault.json → กู้ต่อ
            if (!nxt.renameTo(indexFile)) { indexFile.delete(); nxt.renameTo(indexFile) }
            diskSig = sig(raw)
        }
        if (index!!["folders"] == null) index!!["folders"] = linkedMapOf<String, Any?>()
        if (index!!["files"] == null) index!!["files"] = linkedMapOf<String, Any?>()
        indexRevision++
        noteGen(true)
        migrateKeys()
    }

    /** ถ้าที่อื่นบันทึก index.enc ไว้ (เครื่องอื่นผ่านคลาวด์/Syncthing) → รวมเข้ามา คืนจำนวนรายการที่ได้เพิ่ม */
    @Synchronized fun syncFromDisk(): Int {
        need()
        if (!indexFile.exists()) return 0
        var cur = indexFile.readBytes()
        if (diskSig == null || sig(cur) == diskSig) return 0
        val other = try {
            openIndex(cur)
        } catch (e: BadKeyTag) {
            throw RemoteRekey()
        } catch (e: CorruptData) {
            // คลาวด์/ซิงก์อาจกำลังเขียนไฟล์อยู่ → รอสักครู่แล้วอ่านซ้ำอีก 1 ครั้ง
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
            cur = indexFile.readBytes()
            try { openIndex(cur) } catch (_: BadKeyTag) { throw RemoteRekey() }
        }
        val (merged, gained) = mergeIndex(index!!, other)
        index = merged; diskSig = sig(cur); externalMerges++; indexRevision++
        return gained
    }

    @Synchronized fun saveIndex() {
        val m = need()
        syncFromDisk()                                // อย่าเขียนทับของที่เครื่องอื่นเพิ่งบันทึก – รวมก่อน
        val idx = index!!
        if (idx["vid"] !is String) idx["vid"] = Crypto.hex(Crypto.random(16))
        idx["gen"] = GenStore.genOf(idx) + 1
        val data = sealIndex(idx, m)
        atomicWrite(indexFile, data)
        diskSig = sig(data)
        indexRevision++
        noteGen(false)
    }

    /**
     * จำเลขรุ่นดัชนี • check=true (ตอนเปิด): รุ่นบนดิสก์เก่ากว่าที่เคยเห็น = ถูกย้อนเวอร์ชัน → ตั้ง rollback
     * ไม่ขัดขวางการเปิดตู้ และไม่ให้ความผิดพลาดของไฟล์บันทึกทำให้ตู้ใช้งานไม่ได้
     */
    private fun noteGen(check: Boolean) {
        if (check) rollback = null
        val store = genStore ?: return
        val idx = index ?: return
        val vid = idx["vid"] as? String ?: return
        val got = GenStore.genOf(idx)
        try {
            val seen = store.get(vid)
            if (check && seen != null && got < seen) rollback = got to seen
            store.put(vid, got)                       // เตือนครั้งเดียว แล้วยอมรับรุ่นนี้
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ ไฟล์ขัดแย้งจากโปรแกรมซิงค์คลาวด์
    @Synchronized fun mergeConflicts(): ConflictReport {
        need()
        val rep = ConflictReport()
        var changed = false
        val toDelete = ArrayList<File>()
        val prevIndex = index
        var working = index!!
        val names = root.list()?.sorted() ?: return rep
        for (name in names) {
            val kind = conflictKind(name) ?: continue
            val p = File(root, name)
            if (!p.isFile) continue
            when (kind) {
                "index" -> {
                    val other = try { openIndex(p.readBytes()) } catch (e: Exception) { rep.unmergeable.add(name); null }
                        ?: continue
                    val (merged, g) = mergeIndex(working, other)
                    working = merged
                    rep.merged++; rep.gained += g; changed = true
                    toDelete.add(p)
                }
                "vault" -> rep.vaultConflicts.add(name)        // ไม่ลบเอง – อาจเป็นรหัสผ่านใหม่จากอีกเครื่อง
                else -> { toDelete.add(p); rep.cleaned++ }
            }
        }
        // ไฟล์ซ้ำในโฟลเดอร์ files (เช่น 'abc… (1).bst') – ลบเฉพาะที่มีตัวจริงอยู่แล้ว
        filesDir.listFiles()?.forEach { p ->
            if (p.isFile && !CANON_BLOB.matches(p.name) && !p.name.endsWith(".tmp")) {
                val m = Regex("^([0-9a-f]{32})").find(p.name)
                val ext = "." + p.name.substringAfterLast('.', "").lowercase()
                if (m != null && (ext == ".bsf" || ext == ".bst") && File(filesDir, m.groupValues[1] + ext).exists()) {
                    toDelete.add(p); rep.cleaned++
                }
            }
        }
        if (changed) {
            index = working
            try { saveIndex() } catch (e: Exception) { index = prevIndex; throw e }
        }
        // ลบไฟล์ขัดแย้งหลังจาก saveIndex() สำเร็จแล้วเท่านั้น – กันข้อมูลสูญหายหาก saveIndex() ล้มเหลว
        for (p in toDelete) secureDelete(p)
        return rep
    }

    // ------------------------------------------------------------------ ป้าย "กำลังเปิดอยู่" (session.lock)
    fun sessionOther(): OtherSession? {
        val d = try { Json.obj(String(sessionFile.readBytes(), Charsets.UTF_8)) } catch (e: Exception) { return null }
        val age = now() - d["beat"].asDouble()
        if (age > SESSION_STALE || age < -SESSION_STALE) return null
        val sameDev = d["device"] == device
        // มือถือเปิดแอปได้ทีละหน้าต่าง: ป้ายของเครื่องนี้ = ของเราเอง หรือของ process เดิมที่ปิดไปแล้ว
        if (sameDev) return null
        return OtherSession(false, maxOf(0.0, age), d["since"].asDouble())
    }

    /** เขียน/อัปเดตป้าย (เรียกตอนเปิดตู้ และทุก ~1 นาที) */
    fun sessionTouch(first: Boolean = false) {
        val now = now()
        var since = now
        if (!first) {
            try {
                val d = Json.obj(String(sessionFile.readBytes(), Charsets.UTF_8))
                if (d["device"] == device && d["pid"].asLong() == PROCESS_TAG) since = d["since"].asDouble().takeIf { it > 0 } ?: now
            } catch (_: Exception) {}
        }
        try {
            atomicWrite(sessionFile, Json.write(linkedMapOf("device" to device, "pid" to PROCESS_TAG,
                "since" to since, "beat" to now)).toByteArray())
        } catch (_: Exception) {}
    }

    fun sessionRelease() {
        try {
            val d = Json.obj(String(sessionFile.readBytes(), Charsets.UTF_8))
            if (d["device"] == device && d["pid"].asLong() == PROCESS_TAG) sessionFile.delete()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ รายการไฟล์ / โฟลเดอร์
    @Synchronized fun files(): MutableMap<String, Any?> {
        need()
        val idx = index!!
        return (idx["files"] as? MutableMap<String, Any?>) ?: linkedMapOf<String, Any?>().also { idx["files"] = it }
    }

    @Synchronized fun folders(): MutableMap<String, Any?> {
        need()
        val idx = index!!
        return (idx["folders"] as? MutableMap<String, Any?>) ?: linkedMapOf<String, Any?>().also { idx["folders"] = it }
    }

    @Synchronized fun entry(fid: String): MutableMap<String, Any?> =
        files()[fid] as? MutableMap<String, Any?> ?: throw VaultException("ไม่พบไฟล์")

    /** โฟลเดอร์แม่ของไฟล์หรือโฟลเดอร์ (null = หน้าแรกของตู้) */
    @Synchronized fun parentOf(id: String): String? {
        val dirs = folders()
        val p = if (dirs.containsKey(id)) dirs[id].asMap()["parent"].asStr() else files()[id].asMap()["folder"].asStr()
        return if (p != null && dirs.containsKey(p)) p else null
    }

    /** รายการ id จากบนสุดลงมาถึง did */
    @Synchronized fun folderChain(did: String?): List<String> {
        val chain = ArrayList<String>()
        val seen = HashSet<String>()
        var d = did
        while (d != null && seen.add(d)) { chain.add(d); d = parentOf(d) }
        return chain.reversed()
    }

    fun isInside(did: String, ancestor: String) = ancestor in folderChain(did)

    private fun checkFolderName(name: String, parent: String?, skip: String? = null): String {
        val n = name.trim()
        if (n.isEmpty() || n.length > 200) throw VaultException("ชื่อโฟลเดอร์ไม่ถูกต้อง")
        for ((d, f) in folders()) {
            if (d != skip && parentOf(d) == parent && f.asMap()["name"].asStr()?.lowercase() == n.lowercase())
                throw VaultException("มีโฟลเดอร์ชื่อ \"$n\" อยู่แล้ว")
        }
        return n
    }

    @Synchronized fun createFolder(name: String, parent: String?, save: Boolean = true): String {
        need()
        if (parent != null && !folders().containsKey(parent)) throw VaultException("ไม่พบโฟลเดอร์ปลายทาง")
        val n = checkFolderName(name, parent)
        val did = "dir-" + Crypto.hex(newId()).substring(0, 16)
        folders()[did] = linkedMapOf("name" to n, "parent" to parent, "created" to now())
        if (save) saveIndex()
        return did
    }

    @Synchronized fun findOrCreateFolder(name: String, parent: String?, save: Boolean = true): String {
        for ((d, f) in folders()) {
            if (parentOf(d) == parent && f.asMap()["name"].asStr()?.lowercase() == name.trim().lowercase()) return d
        }
        return createFolder(name, parent, save)
    }

    @Synchronized fun renameFolder(did: String, name: String) {
        val f = folders()[did] as? MutableMap<String, Any?> ?: throw VaultException("ไม่พบโฟลเดอร์")
        f["name"] = checkFolderName(name, parentOf(did), skip = did)
        saveIndex()
    }

    /** ย้ายไฟล์/โฟลเดอร์ไปไว้ใน dest (null = หน้าแรก) */
    @Synchronized fun move(fileIds: Collection<String>, folderIds: Collection<String>, dest: String?) {
        need()
        val dirs = folders()
        if (dest != null && !dirs.containsKey(dest)) throw VaultException("ไม่พบโฟลเดอร์ปลายทาง")
        for (d in folderIds) {
            if (dest != null && isInside(dest, d)) throw VaultException("ย้ายโฟลเดอร์ \"${dirs[d].asMap()["name"]}\" เข้าไปในตัวเองไม่ได้")
            if (parentOf(d) != dest) checkFolderName(dirs[d].asMap()["name"].asStr() ?: "", dest, skip = d)
        }
        for (f in fileIds) {
            val e = entry(f)
            if (e["folder"] != dest) e.remove("pos")            // ลำดับที่จัดเองใช้กับโฟลเดอร์เดิมเท่านั้น
            e["folder"] = dest
        }
        for (d in folderIds) (dirs[d] as? MutableMap<String, Any?>)?.set("parent", dest)
        saveIndex()
    }

    /** ลำดับที่ผู้ใช้จัดเอง (ลากเรียง): ตั้ง "pos" = ลำดับใน order (เก็บในดัชนีที่เข้ารหัส – ซิงก์ข้ามเครื่องได้) */
    @Synchronized fun setOrder(order: List<String>) {
        need()
        val fs = files()
        for ((i, fid) in order.withIndex()) (fs[fid] as? MutableMap<String, Any?>)?.set("pos", i.toDouble())
        saveIndex()
    }

    /** บันทึกลำดับโฟลเดอร์แบบกำหนดเองในดัชนีที่เข้ารหัส */
    @Synchronized fun setFolderOrder(order: List<String>) {
        need()
        val ds = folders()
        for ((i, did) in order.withIndex()) (ds[did] as? MutableMap<String, Any?>)?.set("pos", i.toDouble())
        saveIndex()
    }

    /** ภาพปกโฟลเดอร์ที่เลือกเอง (null = อัตโนมัติ: รูป/วิดีโอล่าสุดข้างใน) */
    @Synchronized fun setFolderCover(did: String, fid: String?) {
        val f = folders()[did] as? MutableMap<String, Any?> ?: throw VaultException("ไม่พบโฟลเดอร์")
        if (fid == null) f.remove("cover")
        else {
            if (!files().containsKey(fid)) throw VaultException("ไม่พบไฟล์")
            f["cover"] = fid
        }
        saveIndex()
    }

    /** เวลาของเฟรมที่ผู้ใช้เลือกเป็นภาพปกวิดีโอ – เก็บในดัชนีที่เข้ารหัส เพื่อให้ทุกเครื่องสร้างภาพปกเดียวกันได้
     *  แม้ไฟล์ภาพย่อ (.bst) จะไม่ได้ถูกคัดลอก/ซิงก์ไปด้วย หรือถูกสร้างใหม่อัตโนมัติ */
    @Synchronized fun setCoverAt(fid: String, at: Double) {
        val e = files()[fid] as? MutableMap<String, Any?> ?: throw VaultException("ไม่พบไฟล์")
        e["cover_at"] = Math.round(at * 100) / 100.0
        saveIndex()
    }

    /** ปักหมุดโฟลเดอร์ไว้บนสุด (เก็บในดัชนีที่เข้ารหัส เหมือนภาพปก) */
    @Synchronized fun setFoldersPinned(dids: Collection<String>, pinned: Boolean) {
        val dirs = folders()
        for (did in dids) {
            val f = dirs[did] as? MutableMap<String, Any?> ?: continue
            if (pinned) f["pinned"] = true else f.remove("pinned")
        }
        saveIndex()
    }

    /** อ่านการตั้งค่ามุมมอง (sort, cols) ของโฟลเดอร์จากดัชนีที่เข้ารหัส (did == null = หน้าหลักของตู้) */
    @Synchronized fun folderView(did: String?): Pair<Int, Int> {
        if (master == null || index == null) return 0 to 3
        val s: Long
        val c: Long
        if (did == null) {
            val idx = index!!
            s = (idx["root_sort"] as? Number)?.toLong() ?: 0L
            c = (idx["root_cols"] as? Number)?.toLong() ?: 3L
        } else {
            val f = folders()[did]?.asMap() ?: return 0 to 3
            s = (f["sort"] as? Number)?.toLong() ?: 0L
            c = (f["cols"] as? Number)?.toLong() ?: 3L
        }
        return s.toInt() to c.toInt().coerceIn(2, 6)
    }

    /** บันทึกการตั้งค่ามุมมอง (sort / cols) ของแต่ละโฟลเดอร์ลงในดัชนีที่เข้ารหัส (ไม่ทิ้งร่องรอยใน prefs) */
    @Synchronized fun setFolderView(did: String?, sort: Int? = null, cols: Int? = null) {
        need()
        val target: MutableMap<String, Any?> = if (did == null) {
            index!!
        } else {
            folders()[did] as? MutableMap<String, Any?> ?: return
        }
        val sortKey = if (did == null) "root_sort" else "sort"
        val colsKey = if (did == null) "root_cols" else "cols"
        if (sort != null) {
            if (sort == 0) target.remove(sortKey) else target[sortKey] = sort.toLong()
        }
        if (cols != null) {
            val clamped = cols.coerceIn(2, 6)
            if (clamped == 3) target.remove(colsKey) else target[colsKey] = clamped.toLong()
        }
        saveIndex()
    }

    /** ไฟล์ทั้งหมดใต้โฟลเดอร์ (รวมโฟลเดอร์ย่อย) → (file_id, [ชื่อโฟลเดอร์จาก did ลงไป]) */
    @Synchronized fun walk(did: String): List<Pair<String, List<String>>> {
        val dirs = folders()
        val filesIn = HashMap<String?, MutableList<String>>()
        val kids = HashMap<String?, MutableList<String>>()
        for (fid in files().keys) filesIn.getOrPut(parentOf(fid)) { ArrayList() }.add(fid)
        for (d in dirs.keys) kids.getOrPut(parentOf(d)) { ArrayList() }.add(d)
        val out = ArrayList<Pair<String, List<String>>>()
        val stack = ArrayDeque<Pair<String, List<String>>>()
        stack.addLast(did to listOf(dirs[did].asMap()["name"].asStr() ?: "folder"))
        val seen = HashSet<String>()
        while (stack.isNotEmpty()) {
            val (d, path) = stack.removeLast()
            if (!seen.add(d)) continue
            filesIn[d]?.forEach { out.add(it to path) }
            kids[d]?.forEach { k -> stack.addLast(k to path + (dirs[k].asMap()["name"].asStr() ?: "folder")) }
        }
        return out
    }

    @Synchronized fun subfolders(did: String): List<String> {
        val out = ArrayList<String>()
        val stack = ArrayDeque<String>().apply { add(did) }
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            if (d in out) continue
            out.add(d)
            for (k in folders().keys) if (parentOf(k) == d && k !in out) stack.addLast(k)
        }
        return out
    }

    /** ลบไฟล์ + โฟลเดอร์ (พร้อมทุกอย่างข้างใน) บันทึกดัชนีครั้งเดียว แล้วลบเนื้อไฟล์แบบเขียนทับ */
    fun deleteMany(fileIds: Collection<String>, folderIds: Collection<String>, progress: Progress = null): List<String> {
        val gone: List<String>
        synchronized(this) {
            need()
            val files = LinkedHashSet(fileIds)
            val dirs = LinkedHashSet<String>()
            for (d in folderIds) if (folders().containsKey(d)) {
                walk(d).forEach { files.add(it.first) }
                dirs.addAll(subfolders(d))
            }
            files.retainAll(files().keys)
            val idx = index!!
            @Suppress("UNCHECKED_CAST")
            val tomb = (idx["tombstones"] as? MutableMap<String, Any?>) ?: linkedMapOf<String, Any?>().also { idx["tombstones"] = it }
            val t = System.currentTimeMillis() / 1000
            for (fid in files) { files().remove(fid); tomb[fid] = t }
            for (d in dirs) { folders().remove(d); tomb[d] = t }
            saveIndex()
            gone = files.toList()
        }
        for ((i, fid) in gone.withIndex()) {
            progress?.invoke("ลบ ${i + 1}/${gone.size}")
            secureDelete(blob(fid)); secureDelete(thumbFile(fid))
        }
        return gone
    }

    fun blob(fid: String) = File(filesDir, "$fid.bsf")
    fun thumbFile(fid: String) = File(filesDir, "$fid.bst")

    // ------------------------------------------------------------------ งานค้างจากครั้งก่อน (ถูกขัดจังหวะ)
    private fun jrAdd(fid: String): String? = try {
        journal?.add(linkedMapOf("k" to "vfid", "vault" to (try { root.canonicalPath } catch (_: Exception) { root.absolutePath }), "fid" to fid))
    } catch (_: Exception) { null }

    private fun jrDone(tok: String?) { try { journal?.remove(tok) } catch (_: Exception) {} }

    /** เรียกหลังปลดล็อค: ไฟล์ใหม่ที่เข้ารหัสค้างไว้แต่ยังไม่ได้ลงดัชนี (ถูกขัดจังหวะ) → ลบทิ้ง
     *  (ต้นฉบับยังอยู่ครบ เพราะลบต้นฉบับหลังบันทึกดัชนีเสร็จเท่านั้น) → จำนวนไฟล์ที่เก็บกวาด */
    fun recoverInterrupted(): Int {
        need()
        var n = 0
        val j = journal
        if (j != null) {
            val me = try { root.canonicalPath } catch (_: Exception) { root.absolutePath }
            for ((tok, e) in j.entries()) {
                if (e["k"] != "vfid" || e["vault"] != me || Journal.isLive(tok)) continue
                val fid = e["fid"]?.toString() ?: ""
                val known = synchronized(this) { files().containsKey(fid) }
                if (Regex("[0-9a-f]{32}").matches(fid) && !known) {
                    for (f in listOf(blob(fid), thumbFile(fid), File(filesDir, "$fid.tmp"), File(filesDir, "$fid.in.tmp")))
                        if (f.exists()) { secureDelete(f); n++ }
                }
                j.remove(tok)
            }
        }
        // .tmp ที่ค้างนานแล้ว (ไม่มีใครเขียนต่อ) – ข้อมูลเข้ารหัสครึ่งๆ กลางๆ
        val cutoff = System.currentTimeMillis() - 3600_000L
        filesDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".tmp") && f.lastModified() in 1 until cutoff) { secureDelete(f); n++ }
        }
        return n
    }

    /** เข้ารหัสจาก stream เป็นไฟล์ใหม่ในตู้ → (fid, entry) – ผู้เรียกใส่ entry ลงดัชนีเอง */
    private fun encryptNew(input: InputStream, name: String, folder: String?, total: Long, progress: Progress,
                           mimeHint: String? = null, fidB: ByteArray = newId()): Pair<String, MutableMap<String, Any?>> {
        need()
        val fid = Crypto.hex(fidB)
        val fkey = Crypto.random(32)
        filesDir.mkdirs()
        val tmp = File(filesDir, "$fid.tmp")
        try {
            val size = FileOutputStream(tmp).use { out ->
                val bo = java.io.BufferedOutputStream(out, 1 shl 16)
                val n = encryptStream(fkey, input, bo, fidB, total, progress)
                bo.flush(); out.fd.sync()
                n
            }
            if (!tmp.renameTo(blob(fid))) throw VaultException("บันทึกไฟล์ไม่สำเร็จ")
            fsyncDir(filesDir)
            val m: ByteArray
            synchronized(this) { m = need().copyOf() }
            val wk = try { wrapKey(m, fid, fkey) } finally { Crypto.wipe(m) }
            val entry = linkedMapOf<String, Any?>("name" to name, "size" to size,
                "mime" to Mime.guess(name, mimeHint), "added" to now(), "wkey" to wk, "folder" to folder)
            return fid to entry
        } catch (e: Exception) {
            tmp.delete(); throw e
        } finally {
            Crypto.wipe(fkey)
        }
    }

    private fun commitNew(fid: String, entry: MutableMap<String, Any?>) {
        synchronized(this) {
            val f = entry["folder"] as? String
            entry["folder"] = if (f != null && folders().containsKey(f)) f else null
            files()[fid] = entry
            try { saveIndex() } catch (e: Exception) { files().remove(fid); throw e }
        }
    }

    /** ไฟล์ที่เข้ารหัสลงตู้แล้วแต่ยังไม่ลงดัชนี – นำเข้าจำนวนมากลงดัชนีทีละชุด (บันทึกดัชนีครั้งเดียวต่อชุด
     *  แทนทุกไฟล์: ดัชนีใหญ่ขึ้นเรื่อยๆ การบันทึกทุกไฟล์ทำให้นำเข้าหลายพันไฟล์ช้าแบบกำลังสอง) */
    class Pending(val fid: String, val entry: MutableMap<String, Any?>, internal val tok: String?)

    /** เข้ารหัสเป็นไฟล์ใหม่ (ยังไม่ลงดัชนี) – ถูกขัดจังหวะก่อน commitPending ครั้งหน้าเก็บกวาดให้ (journal) */
    fun encryptPending(input: InputStream, name: String, mime: String?, folder: String?, total: Long = 0,
                       progress: Progress = null): Pending {
        val fidB = newId()
        val tok = jrAdd(Crypto.hex(fidB))     // บันทึกก่อนเริ่มเขียน → ถ้าดับกลางทาง ครั้งหน้าเก็บกวาดให้
        try {
            val (fid, entry) = encryptNew(input, cleanName(name), folder, total, progress, mime, fidB)
            return Pending(fid, entry, tok)
        } catch (e: Exception) { jrDone(tok); throw e }
    }

    /** ลงดัชนีทั้งชุด บันทึกครั้งเดียว (sync) → ผู้เรียกลบต้นฉบับได้
     *  ล้มเหลว = ไม่มีไฟล์ไหนของชุดนี้ลงดัชนี และไฟล์ที่เข้ารหัสไว้ถูกลบทิ้ง (ต้นฉบับยังอยู่ครบ) */
    fun commitPending(batch: List<Pending>) {
        if (batch.isEmpty()) return
        try {
            synchronized(this) {
                val dirs = folders(); val fs = files()
                for (p in batch) {
                    val f = p.entry["folder"] as? String
                    p.entry["folder"] = if (f != null && dirs.containsKey(f)) f else null
                    fs[p.fid] = p.entry
                }
                try { saveIndex() } catch (e: Exception) { for (p in batch) fs.remove(p.fid); throw e }
            }
        } catch (e: Exception) {
            for (p in batch) secureDelete(blob(p.fid))
            throw e
        } finally { for (p in batch) jrDone(p.tok) }
    }

    /** ทิ้งไฟล์ที่เข้ารหัสไว้แต่ยังไม่ลงดัชนี (เช่น ยกเลิกงาน) */
    fun discardPending(p: Pending) { secureDelete(blob(p.fid)); jrDone(p.tok) }

    /** เข้ารหัสจาก InputStream (ไม่รู้ขนาดล่วงหน้าก็ได้) → id ของไฟล์ใหม่ */
    fun addFile(input: InputStream, name: String, mime: String?, folder: String?, total: Long = 0,
                progress: Progress = null): String {
        val p = encryptPending(input, name, mime, folder, total, progress)
        commitPending(listOf(p))
        return p.fid                           // ลงดัชนีบนดิสก์แล้ว (sync) → ผู้เรียกลบต้นฉบับได้
    }

    /** เข้ารหัสข้อมูลจากหน่วยความจำเป็นไฟล์ใหม่ (ไม่มีไฟล์ธรรมดาลงดิสก์) */
    fun addBytes(data: ByteArray, name: String, folder: String?): String {
        val fidB = newId()
        val tok = jrAdd(Crypto.hex(fidB))
        try {
            val (fid, entry) = encryptNew(data.inputStream(), cleanName(name), folder, data.size.toLong(), null, null, fidB)
            if (entry["mime"] == "application/octet-stream" && name.lowercase().endsWith(".txt")) entry["mime"] = "text/plain"
            try { commitNew(fid, entry) } catch (e: Exception) { secureDelete(blob(fid)); throw e }
            return fid
        } finally { jrDone(tok) }
    }

    /** บันทึกเนื้อหาใหม่ (เช่น แก้ไฟล์ข้อความ) → id ใหม่: ไฟล์ใหม่ + คีย์ใหม่ แล้วลบของเก่าแบบมี tombstone */
    fun replaceBytes(fid: String, data: ByteArray): String {
        val old = synchronized(this) { LinkedHashMap(entry(fid)) }
        val nidB = newId()
        val tok = jrAdd(Crypto.hex(nidB))
        val (nid, entry) = try {
            encryptNew(data.inputStream(), old["name"].asStr() ?: "file", old["folder"].asStr(), data.size.toLong(), null, null, nidB)
        } catch (e: Exception) { jrDone(tok); throw e }
        old["mime"]?.let { entry["mime"] = it }
        old["added"]?.let { entry["added"] = it }
        entry["modified"] = now()
        old["origin"]?.let { entry["origin"] = it }
        old["pos"]?.let { entry["pos"] = it }                 // คงตำแหน่งที่จัดเรียงไว้
        synchronized(this) {
            val f = entry["folder"] as? String
            entry["folder"] = if (f != null && folders().containsKey(f)) f else null
            files()[nid] = entry
            files().remove(fid)
            val idx = index!!
            @Suppress("UNCHECKED_CAST")
            val tomb = (idx["tombstones"] as? MutableMap<String, Any?>) ?: linkedMapOf<String, Any?>().also { idx["tombstones"] = it }
            tomb[fid] = System.currentTimeMillis() / 1000
            try { saveIndex() } finally { jrDone(tok) }
        }
        secureDelete(blob(fid)); secureDelete(thumbFile(fid))
        return nid
    }

    /** ถอดรหัสเข้าหน่วยความจำเท่านั้น */
    fun readBytes(fid: String, limit: Long = 0): ByteArray {
        val size = entry(fid)["size"].asLong()
        if (limit > 0 && size > limit) throw VaultException("ไฟล์ใหญ่เกินกว่าจะแสดงในแอป")
        if (size > Int.MAX_VALUE - 16) throw VaultException("ไฟล์ใหญ่เกินไป")
        if (size == 0L) {
            decryptTo(fid, object : OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
            })
            return ByteArray(0)
        }
        val out = ByteArray(size.toInt())
        openReader(fid).use { r ->
            var got = 0
            while (got < out.size) {
                val n = r.readAt(got.toLong(), out, got, out.size - got)
                if (n <= 0) break
                got += n
            }
            if (got != out.size) { Crypto.wipe(out); throw CorruptData("ไฟล์สั้นกว่าที่ควร") }
        }
        return out
    }

    fun openReader(fid: String): DecryptedReader {
        val e = entry(fid)
        val k = fileKey(fid)
        try { return DecryptedReader(blob(fid), k, Crypto.unhex(fid), e["size"].asLong()) } finally { Crypto.wipe(k) }
    }

    /** ถอดรหัสลง OutputStream (ตรวจความถูกต้องทุกก้อน) */
    fun decryptTo(fid: String, out: OutputStream, progress: ((Long) -> Unit)? = null) {
        entry(fid)
        val k = fileKey(fid)
        var done = 0L
        try {
            decryptFile(k, blob(fid), 0, Crypto.unhex(fid)) { b -> out.write(b); done += b.size; progress?.invoke(done) }
        } finally { Crypto.wipe(k) }
    }

    @Synchronized fun rename(fid: String, newName: String) {
        entry(fid)["name"] = cleanName(newName)
        saveIndex()
    }

    // ------------------------------------------------------------------ ภาพย่อ (.bst)
    fun readThumb(fid: String): ByteArray? {
        entry(fid)
        val f = thumbFile(fid)
        if (!f.exists()) return null
        val raw = f.readBytes()
        if (raw.size < 32 || !raw.copyOfRange(0, 4).contentEquals(THUMB_MAGIC)) return null
        val k = fileKey(fid)
        return try { Crypto.gcmDecrypt(k, raw.copyOfRange(4, 16), raw.copyOfRange(16, raw.size), THUMB_MAGIC + Crypto.unhex(fid)) }
        catch (e: Crypto.BadTag) { null } finally { Crypto.wipe(k) }
    }

    fun saveThumb(fid: String, jpeg: ByteArray) {
        val k = fileKey(fid)
        val nonce = Crypto.random(12)
        val ct = try { Crypto.gcmEncrypt(k, nonce, jpeg, THUMB_MAGIC + Crypto.unhex(fid)) } finally { Crypto.wipe(k) }
        atomicWrite(thumbFile(fid), THUMB_MAGIC + nonce + ct)
        val still = synchronized(this) { index?.let { files().containsKey(fid) } ?: false }
        if (!still) secureDelete(thumbFile(fid))                   // ถูกลบระหว่างสร้าง
    }

    // ------------------------------------------------------------------ แชร์ (.bsx): ไฟล์เข้ารหัสเดี่ยว ใช้รหัสแชร์แยก
    private fun shareHeader(fidB: ByteArray, fkey: ByteArray, meta: Map<String, Any?>, password: CharArray): ByteArray {
        val salt = Crypto.random(16)
        val kdf = kdfDefault()
        val kek = deriveKey(password, salt, kdf)
        val kn = Crypto.random(12)
        val wk = try { Crypto.gcmEncrypt(kek, kn, fkey, SHARE_MAGIC + "key".toByteArray() + fidB) } finally { Crypto.wipe(kek) }
        val mn = Crypto.random(12)
        val em = Crypto.gcmEncrypt(fkey, mn, Json.write(meta).toByteArray(Charsets.UTF_8), SHARE_MAGIC + "meta".toByteArray() + fidB)
        kdf["salt"] = Crypto.b64(salt)
        val header = Json.write(linkedMapOf("v" to 1L, "id" to Crypto.hex(fidB), "kdf" to kdf,
            "key" to linkedMapOf("nonce" to Crypto.b64(kn), "ct" to Crypto.b64(wk)),
            "meta" to linkedMapOf("nonce" to Crypto.b64(mn), "ct" to Crypto.b64(em)))).toByteArray(Charsets.UTF_8)
        return SHARE_MAGIC + Crypto.be32(header.size) + header
    }

    fun exportShare(fid: String, out: OutputStream, password: CharArray) {
        val e = synchronized(this) { LinkedHashMap(entry(fid)) }
        val fkey = fileKey(fid)
        try {
            out.write(shareHeader(Crypto.unhex(fid), fkey,
                linkedMapOf("name" to e["name"], "size" to e["size"], "mime" to e["mime"]), password))
            FileInputStream(blob(fid)).use { it.copyTo(out, 64 * 1024) }
        } finally { Crypto.wipe(fkey) }
    }

    fun exportShare(fid: String, out: OutputStream, password: String) {
        val c = password.toCharArray()
        try { exportShare(fid, out, c) } finally { Crypto.wipe(c) }
    }

    /** .bsx จากข้อมูลในหน่วยความจำ (เช่น รูปที่ลบ GPS แล้ว) ด้วยคีย์/ID ใหม่ */
    fun exportShareData(data: ByteArray, name: String, mime: String, out: OutputStream, password: CharArray) {
        val fidB = newId()
        val fkey = Crypto.random(32)
        try {
            out.write(shareHeader(fidB, fkey, linkedMapOf("name" to name, "size" to data.size.toLong(), "mime" to mime), password))
            encryptStream(fkey, data.inputStream(), out, fidB)
        } finally { Crypto.wipe(fkey) }
    }

    fun exportShareData(data: ByteArray, name: String, mime: String, out: OutputStream, password: String) {
        val c = password.toCharArray()
        try { exportShareData(data, name, mime, out, c) } finally { Crypto.wipe(c) }
    }

    /** นำเข้า .bsx จาก stream → id ใหม่ในตู้ (ตรวจความสมบูรณ์ทั้งไฟล์ก่อนรับเข้า)
     *  เข้ารหัสใหม่ด้วยคีย์ + รหัสไฟล์ใหม่ของตู้นี้: ผู้ส่งไม่มีคีย์ของไฟล์ในตู้ และไม่ชนกับรหัสไฟล์ที่เคยลบไปแล้ว
     *  (เดิมใช้รหัสไฟล์ของผู้ส่ง → นำเข้าไฟล์เดิมซ้ำหลังลบ แล้วไฟล์หายเองตอนรวมดัชนีกับเครื่องอื่น เพราะติด tombstone) */
    fun importShare(input: InputStream, displayName: String, password: CharArray, folder: String?): String {
        need()
        val magic = readFully(input, 4)
        if (!magic.contentEquals(SHARE_MAGIC)) throw CorruptData("$displayName ไม่ใช่ไฟล์ .bsx")
        val hlb = readFully(input, 4)
        if (hlb.size != 4) throw CorruptData("header เสียหาย")
        val hl = java.nio.ByteBuffer.wrap(hlb).int
        if (hl < 2 || hl > 65536) throw CorruptData("header เสียหาย")
        val hraw = readFully(input, hl)
        if (hraw.size != hl) throw CorruptData("header เสียหาย")
        val h = try { Json.obj(String(hraw, Charsets.UTF_8)) } catch (e: Exception) { throw CorruptData("header เสียหาย") }
        val srcB = try { Crypto.unhex(h["id"].toString()) } catch (e: Exception) { throw CorruptData("header เสียหาย") }
        if (srcB.size != 16) throw CorruptData("header เสียหาย")
        val src = Crypto.hex(srcB)                      // ใช้รูปแบบมาตรฐานเท่านั้น
        if (hasShare(src)) throw VaultException("$displayName มีอยู่ในตู้แล้ว")
        val kdf = h["kdf"].asMap()
        val kek = deriveKey(password, Crypto.unb64(kdf["salt"].asStr() ?: ""), kdf)
        val fkey = try {
            val k = h["key"].asMap()
            Crypto.gcmDecrypt(kek, Crypto.unb64(k["nonce"].asStr() ?: ""), Crypto.unb64(k["ct"].asStr() ?: ""),
                SHARE_MAGIC + "key".toByteArray() + srcB)
        } catch (e: Crypto.BadTag) { throw WrongPassword("รหัสแชร์ของ $displayName ไม่ถูกต้อง") } finally { Crypto.wipe(kek) }
        try {
            val meta = try {
                val m = h["meta"].asMap()
                Json.obj(String(Crypto.gcmDecrypt(fkey, Crypto.unb64(m["nonce"].asStr() ?: ""), Crypto.unb64(m["ct"].asStr() ?: ""),
                    SHARE_MAGIC + "meta".toByteArray() + srcB), Charsets.UTF_8))
            } catch (e: Crypto.BadTag) { throw CorruptData("$displayName เสียหาย") }
            filesDir.mkdirs()
            val nidB = newId()
            val nid = Crypto.hex(nidB)
            val tok = jrAdd(nid)                        // ถูกขัดจังหวะ → ครั้งหน้าลบทั้งไฟล์คัดลอกและไฟล์ที่เข้ารหัสค้าง
            val tmp = File(filesDir, "$nid.in.tmp")
            try {
                var real = 0L
                FileOutputStream(tmp).use { o -> input.copyTo(o, CHUNK); o.fd.sync() }
                decryptFile(fkey, tmp, 0, srcB) { b -> real += b.size }      // ตรวจทั้งไฟล์ + นับขนาดจริง
                val name = cleanName(meta["name"].asStr() ?: "file")
                val (fid, entry) = DecryptedReader(tmp, fkey, srcB, real).use { r ->
                    encryptNew(r.asInputStream(), name, folder, real, null, null, nidB)
                }
                entry["origin"] = "bsx"                 // มาจากคนอื่น
                entry["bsx"] = src                      // กันนำเข้าไฟล์เดียวกันซ้ำ
                commitPending(listOf(Pending(fid, entry, tok)))
                return fid
            } catch (e: Exception) {
                secureDelete(blob(nid)); jrDone(tok); throw e
            } finally { tmp.delete() }
        } finally { Crypto.wipe(fkey) }
    }

    fun importShare(input: InputStream, displayName: String, password: String, folder: String?): String {
        val c = password.toCharArray()
        try { return importShare(input, displayName, c, folder) } finally { Crypto.wipe(c) }
    }

    /** มีไฟล์จาก .bsx รหัสนี้อยู่แล้ว (รุ่นเก่าใช้รหัสของผู้ส่งเป็นรหัสไฟล์ในตู้ตรงๆ) */
    @Synchronized private fun hasShare(src: String): Boolean =
        files().containsKey(src) || files().values.any { (it as? Map<*, *>)?.get("bsx") == src }

    // ------------------------------------------------------------------ สำรองทั้งตู้ (ยังเข้ารหัสอยู่ทั้งหมด)
    /** สำรองทั้งตู้ลงโฟลเดอร์ชั่วคราว (<ชื่อ>.part) → อ่านกลับเทียบทุกไฟล์ → ค่อยเปลี่ยนเป็นชื่อจริง
     *  ถูกขัดจังหวะ = ไม่มีโฟลเดอร์สำรองที่ดูเหมือนครบแต่จริงๆ ขาด */
    fun backup(destDir: File, progress: Progress = null): File {
        need()
        val target = File(destDir, "bestspace-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()))
        if (target.exists()) throw VaultException("มีโฟลเดอร์ ${target.name} อยู่แล้ว")
        val tmp = File(destDir, target.name + ".part")
        val fd = File(tmp, "files")
        if (!fd.mkdirs()) throw VaultException("สร้างโฟลเดอร์สำรองไม่ได้: ${target.path}")
        val tok = try { journal?.add(linkedMapOf("k" to "outdir", "p" to tmp.absolutePath)) } catch (_: Exception) { null }
        try {
            fun copyVerified(src: ByteArray?, from: File?, to: File) {
                val h = java.security.MessageDigest.getInstance("SHA-256")
                var n = 0L
                FileOutputStream(to).use { o ->
                    if (src != null) { o.write(src); h.update(src); n = src.size.toLong() }
                    else FileInputStream(from!!).use { i ->
                        val buf = ByteArray(1 shl 16)
                        while (true) { val r = i.read(buf); if (r < 0) break; o.write(buf, 0, r); h.update(buf, 0, r); n += r }
                    }
                    o.fd.sync()
                }
                val back = java.security.MessageDigest.getInstance("SHA-256")
                var m = 0L
                FileInputStream(to).use { i ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val r = i.read(buf); if (r < 0) break; back.update(buf, 0, r); m += r }
                }
                if (m != n || !java.security.MessageDigest.isEqual(h.digest(), back.digest()))
                    throw java.io.IOException("ไฟล์สำรอง ${to.name} ไม่ตรงกับต้นฉบับ")
            }
            copyVerified(null, metaFile, File(tmp, "vault.json"))
            copyVerified(synchronized(this) { indexFile.readBytes() }, null, File(tmp, "index.enc"))
            val list = filesDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".bsf") || it.name.endsWith(".bst")) } ?: emptyList()
            for ((i, f) in list.withIndex()) {
                progress?.invoke("สำรอง ${i + 1}/${list.size}")
                copyVerified(null, f, File(fd, f.name))
            }
            fsyncDir(fd)
            if (!tmp.renameTo(target)) throw VaultException("ตั้งชื่อโฟลเดอร์สำรองไม่สำเร็จ")
            fsyncDir(destDir)
        } catch (e: Exception) {
            tmp.deleteRecursively(); throw e
        } finally {
            if (!tmp.exists()) try { journal?.remove(tok) } catch (_: Exception) {}
        }
        return target
    }
}

/** อ่านไฟล์ในตู้แบบสุ่มตำแหน่งได้ ถอดรหัสทีละก้อนในหน่วยความจำ (ใช้กับรูป/วิดีโอ – ไม่ทิ้งไฟล์ถอดรหัส) */
class DecryptedReader(file: File, key: ByteArray, private val fid: ByteArray, val size: Long) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val key = key.copyOf()
    private val prefix: ByteArray
    private val nChunks: Long = maxOf(1L, (size + Vault.CHUNK - 1) / Vault.CHUNK)
    private var ci = -1L
    private var cd = ByteArray(0)
    @Volatile var pos = 0L
    @Volatile private var closed = false

    init {
        val hdr = ByteArray(12)
        try { raf.readFully(hdr) } catch (e: Exception) { raf.close(); throw CorruptData("ไฟล์ถูกตัดหรือเสียหาย") }
        if (!hdr.copyOfRange(0, 4).contentEquals(Vault.FILE_MAGIC)) { raf.close(); throw CorruptData("ไม่ใช่ไฟล์ BestSpace") }
        prefix = hdr.copyOfRange(4, 12)
    }

    @Synchronized
    private fun chunk(i: Long): ByteArray {
        if (closed) throw java.io.IOException("closed")
        if (i == ci) return cd
        val want = (minOf(Vault.CHUNK.toLong(), size - i * Vault.CHUNK) + Vault.TAG).toInt()
        raf.seek(12 + i * (4 + Vault.CHUNK + Vault.TAG))
        val len = raf.readInt()
        if (len != want) throw CorruptData("ไฟล์ถูกตัดหรือเสียหาย")
        val ct = ByteArray(want)
        raf.readFully(ct)
        val final = i == nChunks - 1
        val aad = Vault.FILE_MAGIC + fid + Crypto.be32(i.toInt()) + byteArrayOf(if (final) 1 else 0)
        val pt = try { Crypto.gcmDecrypt(key, prefix + Crypto.be32(i.toInt()), ct, aad) }
        catch (e: Crypto.BadTag) { throw CorruptData("ตรวจสอบความถูกต้องไม่ผ่าน (ไฟล์ถูกแก้ไขหรือเสียหาย)") }
        Crypto.wipe(cd)
        ci = i; cd = pt
        return pt
    }

    /** อ่านที่ตำแหน่ง position (ไม่ขยับ pos) – ใช้กับ MediaDataSource */
    @Synchronized
    fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        if (len == 0) return 0
        var got = 0
        var p = position
        while (got < len && p < size) {
            val i = p / Vault.CHUNK
            val o = (p % Vault.CHUNK).toInt()
            val d = chunk(i)
            val n = minOf(len - got, d.size - o)
            System.arraycopy(d, o, buf, off + got, n)
            got += n; p += n
        }
        return got
    }

    fun asInputStream(): InputStream = object : InputStream() {
        private var markPos = 0L
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = readAt(pos, b, off, len)
            if (n > 0) pos += n
            return n
        }
        override fun skip(n: Long): Long { val s = minOf(n, size - pos).coerceAtLeast(0); pos += s; return s }
        override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), size - pos).toInt()
        override fun markSupported() = true
        override fun mark(readlimit: Int) { markPos = pos }
        override fun reset() { pos = markPos }
        override fun close() {}
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        Crypto.wipe(cd); Crypto.wipe(key)
        raf.close()
    }
}
