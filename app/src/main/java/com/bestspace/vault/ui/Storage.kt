package com.bestspace.vault.ui

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import com.bestspace.vault.core.Crypto
import com.bestspace.vault.core.Journal
import com.bestspace.vault.core.Vault
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** ที่เก็บข้อมูลหลักของเครื่อง + การแปลง Uri ของหน้าต่างเลือกไฟล์เป็นไฟล์จริง + ลบต้นฉบับแบบเขียนทับ */
object Storage {
    fun mainRoot(): File = Environment.getExternalStorageDirectory()
    fun defaultVault(): File = File(mainRoot(), ".bestspace")

    /** ที่วางไฟล์กุญแจ: ที่เก็บข้อมูลหลัก (ข้างตู้เริ่มต้น เหมือนแอปคอมที่วางข้างโปรแกรม) – ลบแอปแล้วยังอยู่ */
    fun keyDir(): File = mainRoot()

    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun accessIntents(ctx: Context): List<Intent> = if (Build.VERSION.SDK_INT >= 30) listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    ) else emptyList()

    /** โฟลเดอร์ตู้ (มี vault.json หรือ vault.json.tmp ที่สมบูรณ์) หรือโฟลเดอร์ที่มี .bestspace อยู่ข้างใน → null = ไม่พบ */
    fun resolveVaultDir(folder: File): File? {
        if (File(folder, "vault.json").exists() || Vault.hasValidTmpMeta(folder)) return folder
        val inner = File(folder, ".bestspace")
        if (File(inner, "vault.json").exists() || Vault.hasValidTmpMeta(inner)) return inner
        return null
    }

    // ------------------------------------------------------------------ Uri → File
    private fun volumePath(volume: String): File? =
        if (volume.equals("primary", true)) mainRoot()
        else File("/storage/$volume").takeIf { it.isDirectory }

    /** โฟลเดอร์ที่เลือกจาก OpenDocumentTree → ไฟล์จริง (ที่เก็บในเครื่อง / SD card) */
    fun treeToFile(uri: Uri): File? = try {
        val id = DocumentsContract.getTreeDocumentId(uri)
        idToFile(uri.authority, id)
    } catch (e: Exception) { null }

    private fun idToFile(authority: String?, id: String): File? {
        if (id.startsWith("raw:")) return File(id.removePrefix("raw:"))
        if (authority == "com.android.externalstorage.documents") {
            val vol = id.substringBefore(':')
            val rel = id.substringAfter(':', "")
            val base = volumePath(vol) ?: return null
            return if (rel.isEmpty()) base else File(base, rel)
        }
        return null
    }

    private fun queryData(cr: ContentResolver, uri: Uri, sel: String? = null, args: Array<String>? = null): String? = try {
        @Suppress("DEPRECATION")
        cr.query(uri, arrayOf(MediaStore.MediaColumns.DATA), sel, args, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    /** ไฟล์ที่เลือกจาก OpenDocument → ไฟล์จริงในเครื่อง (null = ไม่ใช่ไฟล์ในเครื่อง เช่น คลาวด์) */
    fun docToFile(ctx: Context, uri: Uri): File? {
        val cr = ctx.contentResolver
        try {
            if (DocumentsContract.isDocumentUri(ctx, uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                idToFile(uri.authority, id)?.let { return it }
                when (uri.authority) {
                    "com.android.providers.media.documents" -> {
                        val num = id.substringAfter(':')
                        return queryData(cr, MediaStore.Files.getContentUri("external"), "_id=?", arrayOf(num))?.let { File(it) }
                    }
                    "com.android.providers.downloads.documents" -> {
                        if (id.startsWith("msf:")) {
                            return queryData(cr, MediaStore.Files.getContentUri("external"), "_id=?", arrayOf(id.removePrefix("msf:")))?.let { File(it) }
                        }
                    }
                }
                return null
            }
            if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let { File(it) }
            if (uri.authority == MediaStore.AUTHORITY) return queryData(cr, uri)?.let { File(it) }
        } catch (_: Exception) {}
        return null
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun size(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (_: Exception) {}
        return 0
    }

    // ------------------------------------------------------------------ ลบต้นฉบับหลังเข้ารหัส
    private val STANDARD = setOf("", "dcim", "pictures", "download", "downloads", "documents", "movies", "music",
        "android", "dcim/camera", "pictures/screenshots", "dcim/screenshots", "recordings", "audiobooks", "podcasts",
        "ringtones", "alarms", "notifications")

    /** โฟลเดอร์มาตรฐานของเครื่อง – ไม่ลบทิ้งแม้ว่างแล้ว */
    fun isStandardDir(d: File): Boolean {
        val vol = volumeRootOf(d)?.absolutePath?.trimEnd('/') ?: mainRoot().absolutePath.trimEnd('/')
        val p = d.absolutePath.trimEnd('/')
        if (p != vol && !p.startsWith("$vol/")) return d.parentFile == null || d.parentFile?.parentFile == null
        return p.removePrefix(vol).trim('/').lowercase() in STANDARD
    }

    /** ผลการลบต้นฉบับ: เขียนทับ+ลบครบ / ลบได้แต่เขียนทับไม่ได้ / ลบไม่ได้ */
    enum class Wipe { OK, DELETED_ONLY, FAILED }

    /** รากของ volume ที่ไฟล์อยู่ (/storage/emulated/0 หรือ /storage/XXXX-XXXX) */
    private fun volumeRootOf(f: File): File? {
        val p = f.absolutePath
        val main = mainRoot().absolutePath.trimEnd('/')
        if (p.startsWith("$main/")) return mainRoot()
        val parts = p.split('/').filter { it.isNotEmpty() }
        return if (parts.size >= 2 && parts[0] == "storage") File("/storage/${parts[1]}") else null
    }

    @Suppress("DEPRECATION")
    private fun mediaIds(ctx: Context, path: String): List<Long> {
        val out = ArrayList<Long>()
        try {
            ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null)?.use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        } catch (_: Exception) {}
        return out
    }

    /** ภาพย่อที่ระบบ (MediaProvider) สร้างเก็บไว้ของไฟล์นี้ – ถูกสร้างทันทีที่เปิดดูรูปในหน้าต่างเลือกไฟล์/แกลเลอรี
     *  Android 10+: <volume>/Pictures|Movies|Music/.thumbnails/<id>.jpg  •  Android 9 ลงไป: ตาราง Thumbnails (DCIM/.thumbnails) */
    @Suppress("DEPRECATION")
    private fun systemThumbsOf(ctx: Context, f: File): List<File> {
        val out = ArrayList<File>()
        val ids = mediaIds(ctx, f.absolutePath)
        val vol = volumeRootOf(f) ?: mainRoot()
        for (id in ids) {
            for (dir in listOf("Pictures", "Movies", "Music", "DCIM")) {
                val t = File(vol, "$dir/.thumbnails/$id.jpg")
                if (t.isFile) out.add(t)
            }
            if (Build.VERSION.SDK_INT < 29) {
                for ((uri, col) in listOf(MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI to MediaStore.Images.Thumbnails.IMAGE_ID,
                    MediaStore.Video.Thumbnails.EXTERNAL_CONTENT_URI to MediaStore.Video.Thumbnails.VIDEO_ID)) {
                    try {
                        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), "$col=?", arrayOf(id.toString()), null)
                            ?.use { c -> while (c.moveToNext()) c.getString(0)?.let { p -> File(p).takeIf { it.isFile }?.let(out::add) } }
                    } catch (_: Exception) {}
                }
            }
        }
        return out
    }

    /** ลบต้นฉบับแบบทำลายทิ้ง (เท่าที่ระบบทำได้):
     *  1) เขียนทับ+ลบภาพย่อที่ระบบเก็บไว้ของไฟล์นี้ (ต้องทำก่อน – พอลบไฟล์แล้วระบบจะลบภาพย่อแบบธรรมดา ไม่เขียนทับ)
     *  2) เขียนทับเนื้อไฟล์ → ตัดขนาด → เปลี่ยนชื่อสุ่ม 3 รอบ → ลบ
     *  3) ลบรายการออกจากฐานข้อมูลสื่อของระบบ (ชื่อไฟล์/วันที่/ตำแหน่งที่ถ่ายในฐานข้อมูลของแกลเลอรี) */
    fun wipeFile(ctx: Context, f: File): Wipe {
        val path = f.absolutePath
        for (t in systemThumbsOf(ctx, f)) Vault.secureDelete(t)
        val overwritten = Vault.secureDelete(f)
        try {
            @Suppress("DEPRECATION")
            ctx.contentResolver.delete(MediaStore.Files.getContentUri("external"), "${MediaStore.MediaColumns.DATA}=?", arrayOf(path))
        } catch (_: Exception) {}
        try { MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null) } catch (_: Exception) {}
        return when {
            f.exists() -> Wipe.FAILED
            overwritten -> Wipe.OK
            else -> Wipe.DELETED_ONLY
        }
    }

    /** ต้นฉบับที่ไม่ใช่ไฟล์ที่เข้าถึงตรงได้ (เช่น SD card ผ่าน SAF): เขียนทับผ่าน Uri (ขนาดจริงจาก fd) แล้วลบ */
    fun wipeDocument(ctx: Context, uri: Uri): Wipe {
        var overwritten = false
        val buf = ByteArray(1 shl 20)
        try {
            ctx.contentResolver.openFileDescriptor(uri, "rw")?.let { pfd ->
                val size = pfd.statSize
                if (size >= 0) {
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { o ->
                        var left = size
                        val rnd = java.security.SecureRandom()
                        while (left > 0) {
                            val n = minOf(left, buf.size.toLong()).toInt()
                            rnd.nextBytes(buf)
                            o.write(buf, 0, n); left -= n
                        }
                        o.flush(); pfd.fileDescriptor.sync()
                    }
                    overwritten = true
                } else {
                    try { pfd.close() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        } finally {
            Crypto.wipe(buf)
        }
        var deleted = try {
            DocumentsContract.isDocumentUri(ctx, uri) && DocumentsContract.deleteDocument(ctx.contentResolver, uri)
        } catch (_: Exception) { false }
        if (!deleted) deleted = try { ctx.contentResolver.delete(uri, null, null) > 0 } catch (_: Exception) { false }
        return when {
            !deleted -> Wipe.FAILED          // (ถ้าเขียนทับได้แล้ว เนื้อหาเดิมถูกทำลาย แต่ยังมีไฟล์ค้างให้ลบเอง)
            overwritten -> Wipe.OK
            else -> Wipe.DELETED_ONLY
        }
    }

    /** ภาพย่อทั้งหมดที่ระบบเก็บไว้ (ทุก volume) – ระบบสร้างใหม่เองเมื่อจำเป็น */
    fun systemThumbDirs(): List<File> {
        val vols = (listOf(mainRoot()) + (File("/storage").listFiles()?.filter {
            it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead()
        } ?: emptyList()))
        return vols.flatMap { v -> listOf("Pictures", "Movies", "Music", "DCIM").map { File(v, "$it/.thumbnails") } }.filter { it.isDirectory }
    }

    /** ลบรายการของไฟล์/โฟลเดอร์ที่ลบไปแล้วออกจากฐานข้อมูลสื่อของระบบ
     *  (ไม่งั้นแอป Files ของระบบ เช่น เมนู Downloads ยังโชว์ชื่อโฟลเดอร์ว่างๆ ค้างอยู่) */
    @Suppress("DEPRECATION")
    fun forgetMedia(ctx: Context, path: String) {
        try {
            ctx.contentResolver.delete(MediaStore.Files.getContentUri("external"), "${MediaStore.MediaColumns.DATA}=?", arrayOf(path))
        } catch (_: Exception) {}
        try { MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null) } catch (_: Exception) {}
    }

    /** รายการค้างทั้งหมดในฐานข้อมูลสื่อ (ไฟล์/โฟลเดอร์จริงไม่อยู่แล้ว) → ลบออก คืนจำนวน
     *  ข้ามรายการบน volume ที่ไม่ได้เสียบอยู่ (เช่น SD card ที่ถอดออก) */
    @Suppress("DEPRECATION")
    fun forgetMissingMedia(ctx: Context): Int {
        val gone = ArrayList<String>()
        try {
            ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.DATA),
                null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val p = c.getString(0) ?: continue
                    val f = File(p)
                    if (!f.exists() && volumeRootOf(f)?.isDirectory == true) gone.add(p)
                }
            }
        } catch (_: Exception) {}
        var n = 0
        for (p in gone) n += try {
            ctx.contentResolver.delete(MediaStore.Files.getContentUri("external"), "${MediaStore.MediaColumns.DATA}=?", arrayOf(p))
        } catch (_: Exception) { 0 }
        return n
    }

    fun wipeAllSystemThumbs(): Int {
        var n = 0
        for (d in systemThumbDirs()) d.walkBottomUp().forEach { f -> if (f.isFile) { Vault.secureDelete(f); n++ } }
        return n
    }

    /** เก็บกวาดไฟล์ส่งออก/โฟลเดอร์สำรองที่เขียนไม่เสร็จ จากงานที่ถูกขัดจังหวะครั้งก่อน (ตอนเปิดแอป)
     *  → จำนวนที่ลบได้ (ลบไม่ได้ เช่น ยังไม่มีสิทธิ์ = เก็บรายการไว้ลองครั้งหน้า) */
    fun cleanupJournal(ctx: Context, j: Journal): Int {
        var n = 0
        for ((tok, e) in j.entries()) {
            val k = e["k"]
            val p = e["p"]?.toString() ?: continue
            if ((k != "out" && k != "outdir") || Journal.isLive(tok)) continue
            val done = try {
                if (p.startsWith("content://")) {
                    val u = Uri.parse(p)
                    val exists = ctx.contentResolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                        null, null, null)?.use { it.count > 0 } ?: false
                    if (!exists) true
                    else {
                        val ok = if (k == "out") wipeDocument(ctx, u) != Wipe.FAILED
                                 else DocumentsContract.deleteDocument(ctx.contentResolver, u)
                        if (ok) n++
                        ok
                    }
                } else {
                    val f = File(p)
                    if (!f.exists()) true
                    else {
                        val ok = if (k == "out") { Vault.secureDelete(f); !f.exists() } else f.deleteRecursively()
                        if (ok) n++
                        ok
                    }
                }
            } catch (_: java.io.FileNotFoundException) { true
            } catch (_: IllegalArgumentException) { true          // เอกสารไม่มีแล้ว
            } catch (_: Exception) { false }                       // เช่น ไม่มีสิทธิ์ – ลองครั้งหน้า
            if (done) {
                j.remove(tok)
                (e["tree"] as? String)?.let { t ->                 // คืนสิทธิ์โฟลเดอร์ที่ขอไว้ตอนส่งออก
                    try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(t),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                }
            }
        }
        return n
    }

    fun scan(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        try { MediaScannerConnection.scanFile(ctx, files.map { it.absolutePath }.toTypedArray(), null, null) } catch (_: Exception) {}
    }

    fun wipeNotes(): List<String> = listOf(
        "มือถือใช้หน่วยความจำแฟลช – การเขียนทับอาจไม่ลบข้อมูลเก่าครบทุกบิต (wear-leveling) แต่ Android เข้ารหัสพื้นที่เก็บข้อมูลทั้งเครื่องอยู่แล้ว",
        "รูป/วิดีโอที่สำรองขึ้น Google Photos หรือคลาวด์อื่นไว้แล้ว ยังอยู่บนคลาวด์ – ต้องลบในแอปนั้นด้วย",
        "แอปแกลเลอรีบางยี่ห้อมี \"ถังขยะ\" ของตัวเอง – ตรวจและล้างถังขยะด้วย",
    )

    /** ชื่อไฟล์ชั่วคราวแบบสุ่ม (คงนามสกุลไว้ให้แอปอื่นเปิดถูก) */
    fun randomTempName(name: String): String {
        val ext = Vault.cleanName(name).substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(10)
        return Crypto.hex(Crypto.random(6)) + if (ext.isNotEmpty()) ".$ext" else ""
    }
}

/** ปลายทางสำหรับส่งออก/สำรอง: โฟลเดอร์ในเครื่อง (File) หรือโฟลเดอร์ผ่านหน้าต่างของระบบ (SAF) */
sealed class Dest {
    abstract val label: String
    /** path (ไฟล์ในเครื่อง) หรือ uri ของโฟลเดอร์นี้ – ใช้จดใน journal */
    abstract val path: String
    abstract fun child(name: String): Dest
    abstract fun create(name: String, mime: String): Pair<OutputStream, String>
    /** สร้างไฟล์ชื่อชั่วคราว (.bestspace-xxxx.part) – เขียน+ตรวจเสร็จแล้วค่อย commit เป็นชื่อจริง */
    fun createTemp(): Pair<OutputStream, String> = create(Journal.partName(), "application/octet-stream")
    /** ตั้งชื่อจริงให้ไฟล์ชั่วคราว (ไม่ทับไฟล์ที่มีอยู่ – ชื่อซ้ำจะเติม (1), (2)…) → path/uri ของไฟล์จริง */
    abstract fun commit(tmp: String, finalName: String, mime: String): String
    /** เปลี่ยนชื่อโฟลเดอร์นี้ (ใช้ตอนสำรองตู้เสร็จ: <ชื่อ>.part → <ชื่อ>) */
    abstract fun renameSelf(newName: String): String
    /** ปลายทางนี้อยู่ภายในโฟลเดอร์ตู้หรือเป็นตัวโฟลเดอร์ตู้เอง (ห้ามส่งออก/สำรองทับตู้) */
    abstract fun isInsideOrSame(vaultRoot: File): Boolean
    /** ไฟล์ที่เขียนไม่เสร็จ (เช่น ถอดรหัสล้มเหลวกลางทาง) → เขียนทับ+ลบ ไม่ทิ้งข้อมูลครึ่งๆ กลางๆ ไว้ */
    abstract fun discard(path: String)
    /** อ่านไฟล์ที่เพิ่งเขียนกลับมา (ตรวจว่าส่งออกครบถูกต้อง) */
    abstract fun openRead(path: String): java.io.InputStream

    class Local(val dir: File) : Dest() {
        override val label get() = dir.absolutePath
        override val path get() = dir.absolutePath
        override fun commit(tmp: String, finalName: String, mime: String): String {
            val src = File(tmp).toPath()
            repeat(50) {
                val f = com.bestspace.vault.core.Mime.uniqueFile(dir, finalName)
                try {
                    java.nio.file.Files.move(src, f.toPath())          // ไม่ทับไฟล์เดิม (มีอยู่แล้ว = exception)
                    Vault.fsyncDir(dir)
                    return f.absolutePath
                } catch (_: java.nio.file.FileAlreadyExistsException) {}
            }
            throw java.io.IOException("ตั้งชื่อไฟล์ $finalName ไม่ได้")
        }
        override fun renameSelf(newName: String): String {
            val to = File(dir.parentFile, newName)
            java.nio.file.Files.move(dir.toPath(), to.toPath())
            Vault.fsyncDir(dir.parentFile)
            return to.absolutePath
        }
        override fun child(name: String): Dest = Local(File(dir, name).also { it.mkdirs() })
        override fun create(name: String, mime: String): Pair<OutputStream, String> {
            dir.mkdirs()
            val f = com.bestspace.vault.core.Mime.uniqueFile(dir, name)
            if (!f.createNewFile()) throw java.io.IOException("สร้างไฟล์ไม่ได้: ${f.name}")
            val fo = FileOutputStream(f)
            return SyncOnClose(fo, fo.fd) to f.absolutePath
        }
        override fun openRead(path: String): java.io.InputStream = File(path).inputStream()
        override fun isInsideOrSame(vaultRoot: File): Boolean = isPathInsideOrSame(dir, vaultRoot)
        override fun discard(path: String) { Vault.secureDelete(File(path)) }
    }

    class Saf(val ctx: Context, val doc: DocumentFile) : Dest() {
        override val label get() = doc.name ?: "โฟลเดอร์ที่เลือก"
        override val path get() = doc.uri.toString()
        override fun commit(tmp: String, finalName: String, mime: String): String {
            val u = Uri.parse(tmp)
            val renamed = try { DocumentsContract.renameDocument(ctx.contentResolver, u, finalName) } catch (_: Exception) { null }
            if (renamed != null) return renamed.toString()
            // ผู้ให้บริการนี้เปลี่ยนชื่อไม่ได้ → คัดลอกเป็นไฟล์ชื่อจริง + อ่านกลับเทียบ แล้วลบไฟล์ชั่วคราว
            val (os, out) = create(finalName, mime)
            try {
                val h = java.security.MessageDigest.getInstance("SHA-256")
                var n = 0L
                os.use { o ->
                    openRead(tmp).use { i ->
                        val buf = ByteArray(1 shl 16)
                        while (true) { val r = i.read(buf); if (r < 0) break; o.write(buf, 0, r); h.update(buf, 0, r); n += r }
                        java.util.Arrays.fill(buf, 0)
                    }
                }
                val b = java.security.MessageDigest.getInstance("SHA-256")
                var m = 0L
                openRead(out).use { i ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val r = i.read(buf); if (r < 0) break; b.update(buf, 0, r); m += r }
                    java.util.Arrays.fill(buf, 0)
                }
                if (m != n || !java.security.MessageDigest.isEqual(h.digest(), b.digest()))
                    throw java.io.IOException("คัดลอกเป็นชื่อจริงไม่ครบ")
            } catch (e: Exception) { try { discard(out) } catch (_: Exception) {}; throw e }
            discard(tmp)
            return out
        }
        override fun renameSelf(newName: String): String =
            (DocumentsContract.renameDocument(ctx.contentResolver, doc.uri, newName)
                ?: throw java.io.IOException("ตั้งชื่อโฟลเดอร์ $newName ไม่ได้")).toString()
        override fun child(name: String): Dest =
            Saf(ctx, doc.findFile(name)?.takeIf { it.isDirectory } ?: doc.createDirectory(name)
                ?: throw java.io.IOException("สร้างโฟลเดอร์ไม่ได้: $name"))
        override fun create(name: String, mime: String): Pair<OutputStream, String> {
            val f = doc.createFile(mime, name) ?: throw java.io.IOException("สร้างไฟล์ไม่ได้: $name")
            val pfd = try { ctx.contentResolver.openFileDescriptor(f.uri, "w") } catch (e: Exception) { null }
            if (pfd != null) return SyncOnClose(android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd), pfd.fileDescriptor) to f.uri.toString()
            val os = ctx.contentResolver.openOutputStream(f.uri, "w") ?: throw java.io.IOException("เขียนไฟล์ไม่ได้: $name")
            return os to f.uri.toString()
        }
        override fun openRead(path: String): java.io.InputStream =
            ctx.contentResolver.openInputStream(Uri.parse(path)) ?: throw java.io.IOException("อ่านไฟล์ที่ส่งออกไม่ได้")
        override fun isInsideOrSame(vaultRoot: File): Boolean {
            val local = Storage.treeToFile(doc.uri) ?: Storage.docToFile(ctx, doc.uri) ?: return false
            return isPathInsideOrSame(local, vaultRoot)
        }
        override fun discard(path: String) { Storage.wipeDocument(ctx, Uri.parse(path)) }
    }

    companion object {
        fun isPathInsideOrSame(candidate: File, ancestor: File): Boolean {
            val c = (try { candidate.canonicalPath } catch (_: Exception) { candidate.absolutePath }).trimEnd('/')
            val a = (try { ancestor.canonicalPath } catch (_: Exception) { ancestor.absolutePath }).trimEnd('/')
            return c == a || c.startsWith("$a/")
        }

        fun fromTree(ctx: Context, uri: Uri): Dest? {
            Storage.treeToFile(uri)?.let { f -> if (f.isDirectory && f.canWrite()) return Local(f) }
            return DocumentFile.fromTreeUri(ctx, uri)?.let { Saf(ctx, it) }
        }
    }
}

/** ปิดไฟล์แล้วสั่งเขียนลงดิสก์จริง (fsync) – ไฟดับหลังส่งออกจะไม่ได้ไฟล์ว่าง/ครึ่งๆ */
class SyncOnClose(out: OutputStream, private val fd: java.io.FileDescriptor,
                  private val owner: java.io.Closeable? = null) : java.io.FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
    override fun close() {
        try {
            out.flush()
            try { fd.sync() } catch (_: Exception) {}     // ผู้ให้บริการบางราย (คลาวด์) sync ไม่ได้ – ตรวจด้วยการอ่านกลับแทน
        } finally {
            try { out.close() } finally { owner?.close() }
        }
    }
}

/** แหล่งไฟล์ที่จะนำเข้า: ไฟล์ในเครื่อง (ลบต้นฉบับแบบเขียนทับได้) หรือ Uri */
class Source(val name: String, val size: Long, val mime: String?, val file: File?, val uri: Uri?, val folderPath: List<String>) {
    fun open(ctx: Context): InputStream =
        file?.inputStream() ?: ctx.contentResolver.openInputStream(uri!!) ?: throw java.io.IOException("เปิดไฟล์ไม่ได้: $name")
}
