package com.bestspace.vault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.bestspace.vault.core.DecryptedReader
import com.bestspace.vault.core.Mime
import com.bestspace.vault.core.Vault
import com.bestspace.vault.core.asLong
import com.bestspace.vault.core.asStr
import java.io.ByteArrayOutputStream
import java.util.Locale

/** ให้ MediaMetadataRetriever อ่านไฟล์ในตู้โดยถอดรหัสในหน่วยความจำ – ไม่มีไฟล์ถอดรหัสบนดิสก์ */
class VaultDataSource(private val reader: DecryptedReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        return reader.readAt(position, buffer, offset, size)
    }
    override fun getSize(): Long = reader.size
    override fun close() = reader.close()
}

/** ByteArrayOutputStream ที่ทับข้อมูลในบัฟเฟอร์ทิ้งได้ (ภาพย่อ/รูปที่ถอดรหัสไม่ค้างในหน่วยความจำ) */
class WipingBytes(size: Int = 64 * 1024) : java.io.ByteArrayOutputStream(size) {
    fun wipe() { java.util.Arrays.fill(buf, 0.toByte()); count = 0 }
}

object Media {
    const val THUMB_SHORT = 320

    /** ทับพิกเซลด้วยสีดำแล้วคืนหน่วยความจำ (ภาพที่ถอดรหัสแล้ว) – ภาพแก้ไม่ได้ (immutable) จะคืนหน่วยความจำอย่างเดียว */
    fun wipeBitmap(b: Bitmap?) {
        if (b == null || b.isRecycled) return
        try { if (b.isMutable) b.eraseColor(0) } catch (_: Exception) {}
        try { b.recycle() } catch (_: Exception) {}
    }

    /** ถอดเป็นภาพที่แก้ได้ (mutable) เพื่อให้ทับพิกเซลทิ้งได้ตอนล็อค */
    private fun mutableOpts() = BitmapFactory.Options().apply { inMutable = true }
    const val MAX_PREVIEW = 200L * 1024 * 1024
    const val MAX_TEXT = 5L * 1024 * 1024

    /** รูปแบบรูปที่ยอมให้ถอด (ดูจากเนื้อไฟล์ ไม่ใช่นามสกุล) – เหมือน SAFE_IMAGE_FORMATS ของแอปคอม */
    fun sniffImage(head: ByteArray): String? {
        fun at(i: Int, s: String) = head.size >= i + s.length && String(head, i, s.length, Charsets.ISO_8859_1) == s
        return when {
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "jpeg"
            at(0, "\u0089PNG") -> "png"
            at(0, "GIF8") -> "gif"
            at(0, "BM") -> "bmp"
            at(0, "RIFF") && at(8, "WEBP") -> "webp"
            at(4, "ftyp") && listOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1", "avif", "avis").any { at(8, it) } -> "heif"
            head.size >= 4 && head[0] == 0.toByte() && head[1] == 0.toByte() && head[2] == 1.toByte() && head[3] == 0.toByte() -> "ico"
            else -> null
        }
    }

    private fun head(r: DecryptedReader): ByteArray {
        val b = ByteArray(32)
        val n = r.readAt(0, b, 0, 32)
        return if (n <= 0) ByteArray(0) else b.copyOf(n)
    }

    /** ถอดรูปจากตู้ (ด้านยาวไม่เกิน maxSide) + หมุนตาม EXIF – อ่านทีละก้อนจากตู้ ไม่มีไฟล์ถอดรหัส */
    fun decodeImage(vault: Vault, fid: String, maxSide: Int): Bitmap? {
        if (vault.entry(fid)["size"].asLong() > MAX_PREVIEW) return null
        vault.openReader(fid).use { r ->
            if (sniffImage(head(r)) == null) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(r.asInputStream(), null, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            r.pos = 0
            val bmp = BitmapFactory.decodeStream(r.asInputStream(), null,
                mutableOpts().apply { inSampleSize = sample }) ?: return null
            r.pos = 0
            val orient = try {
                ExifInterface(r.asInputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
            return rotate(fitLong(bmp, maxSide), orient)
        }
    }

    /** ขนาดจริงของรูป (หลังหมุน) – ใช้ตัดสินว่าต้องถอดละเอียดขึ้นตอนซูมไหม */
    fun imageSize(vault: Vault, fid: String): Pair<Int, Int>? = try {
        vault.openReader(fid).use { r ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(r.asInputStream(), null, o)
            if (o.outWidth > 0) o.outWidth to o.outHeight else null
        }
    } catch (e: Exception) { null }

    fun fitLong(b: Bitmap, maxSide: Int): Bitmap {
        val big = maxOf(b.width, b.height)
        if (big <= maxSide) return b
        val s = maxSide.toFloat() / big
        val out = Bitmap.createScaledBitmap(b, maxOf(1, (b.width * s).toInt()), maxOf(1, (b.height * s).toInt()), true)
        if (out !== b) wipeBitmap(b)
        return out
    }

    private fun fitShort(b: Bitmap, short: Int): Bitmap {
        val s = minOf(1f, short.toFloat() / maxOf(1, minOf(b.width, b.height)))
        if (s >= 1f) return b
        val out = Bitmap.createScaledBitmap(b, maxOf(1, Math.round(b.width * s)), maxOf(1, Math.round(b.height * s)), true)
        if (out !== b) wipeBitmap(b)
        return out
    }

    private fun rotate(b: Bitmap, orient: Int): Bitmap {
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (out !== b) wipeBitmap(b)
        return out
    }

    /** ภาพย่อ: อ่านจากตู้ถ้ามี ไม่งั้นสร้างในหน่วยความจำแล้วเก็บแบบเข้ารหัส (.bst) – เหมือนแอปคอม */
    /** คอมเมนต์ในภาพย่อ "dur=12.34;at=5.00" → แผนที่ key/value */
    fun commentFields(c: String?): Map<String, Double> =
        c?.split(';')?.mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].toDoubleOrNull() } }
            ?.filter { it.second != null }?.associate { it.first to it.second!! } ?: emptyMap()

    fun thumb(vault: Vault, fid: String): Pair<Bitmap, Double?>? {
        val coverAt = (vault.entry(fid)["cover_at"] as? Number)?.toDouble()
        vault.readThumb(fid)?.let { jpeg ->
            val f = commentFields(readComment(jpeg))
            // ภาพปกวิดีโอที่เลือกไว้ (จากเครื่องไหนก็ได้) ไม่ตรงกับภาพย่อที่มี → สร้างใหม่จากเฟรมนั้น (ไม่ได้ = ใช้ของเดิม)
            if (coverAt != null && (f["at"] == null || Math.abs(f["at"]!! - coverAt) > 0.05)) {
                val fr = try { videoFrame(vault, fid, coverAt) } catch (_: Exception) { null }
                if (fr != null) {
                    java.util.Arrays.fill(jpeg, 0)
                    val made = fitShort(fr.first, THUMB_SHORT) to fr.second
                    saveThumb(vault, fid, made.first, made.second, coverAt)
                    return made
                }
            }
            val b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, mutableOpts())
            java.util.Arrays.fill(jpeg, 0)
            if (b != null) return b to f["dur"]
        }
        val e = vault.entry(fid)
        val name = e["name"].asStr() ?: ""
        val mime = e["mime"].asStr() ?: ""
        val made: Pair<Bitmap, Double?> = when (Mime.kind(name, mime)) {
            "image" -> (decodeImage(vault, fid, THUMB_SHORT * 2) ?: return null).let { fitShort(it, THUMB_SHORT) to null }
            "video" -> videoFrame(vault, fid, coverAt)?.let { (b, d) -> fitShort(b, THUMB_SHORT) to d } ?: return null
            else -> return null
        }
        saveThumb(vault, fid, made.first, made.second, coverAt)
        return made
    }

    fun saveThumb(vault: Vault, fid: String, b: Bitmap, dur: Double?, at: Double? = null) {
        val small = fitShortCopy(b)
        val c = listOfNotNull(dur?.let { "dur=" + String.format(Locale.US, "%.2f", it) },
            at?.let { "at=" + String.format(Locale.US, "%.2f", it) }).joinToString(";").ifEmpty { null }
        val jpeg = encodeJpeg(small, c)
        if (small !== b) wipeBitmap(small)
        try { vault.saveThumb(fid, jpeg) } catch (_: Exception) {}
        java.util.Arrays.fill(jpeg, 0)
    }

    private fun fitShortCopy(b: Bitmap): Bitmap {
        val s = minOf(1f, THUMB_SHORT.toFloat() / maxOf(1, minOf(b.width, b.height)))
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, maxOf(1, Math.round(b.width * s)), maxOf(1, Math.round(b.height * s)), true)
    }

    /** เฟรมวิดีโอ ณ เวลา atSec (null = วินาทีที่ 1 หรือเฟรมแรก) + ความยาวคลิป */
    fun videoFrame(vault: Vault, fid: String, atSec: Double?): Pair<Bitmap, Double>? {
        val mmr = MediaMetadataRetriever()
        val src = VaultDataSource(vault.openReader(fid))
        try {
            mmr.setDataSource(src)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = when {
                atSec != null -> (atSec * 1_000_000).toLong()
                durMs > 3000 -> 1_000_000L
                else -> 0L
            }
            val frame = (if (atSec != null) mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)
                else mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                ?: mmr.getFrameAtTime(0) ?: return null
            return frame to durMs / 1000.0
        } catch (e: Exception) {
            return null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
            try { src.close() } catch (_: Exception) {}
        }
    }

    /** JPEG + COM segment (เช่น dur=12.34) ต่อหลัง SOI – แอปคอมอ่านได้ */
    fun encodeJpeg(b: Bitmap, comment: String?): ByteArray {
        val flat = if (b.hasAlpha()) {
            Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it); c.drawColor(Color.rgb(37, 41, 53)); c.drawBitmap(b, 0f, 0f, null)
            }
        } else b
        val bos = WipingBytes()
        flat.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        if (flat !== b) wipeBitmap(flat)
        val raw = bos.toByteArray()
        bos.wipe()
        if (comment == null || raw.size < 2) return raw
        val c = comment.toByteArray(Charsets.US_ASCII)
        val len = c.size + 2
        val seg = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + c
        val head = raw.copyOfRange(0, 2); val tail = raw.copyOfRange(2, raw.size)
        java.util.Arrays.fill(raw, 0)
        val out = head + seg + tail
        java.util.Arrays.fill(tail, 0)
        return out
    }

    fun readComment(jpeg: ByteArray): String? {
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker == 0xFE && i + 2 + len <= jpeg.size && len >= 2) return String(jpeg, i + 4, len - 2, Charsets.US_ASCII)
            if (marker == 0xDA || len < 2) return null
            i += 2 + len
        }
        return null
    }

    /** ลบ EXIF/GPS/ข้อมูลกล้องออกจากรูป (บันทึกใหม่ หมุนให้ถูกทิศ) → (ข้อมูล, นามสกุล) หรือ null ถ้าลบไม่ได้ */
    fun stripMetadata(vault: Vault, fid: String): Pair<ByteArray, String>? {
        val fmt = vault.openReader(fid).use { sniffImage(head(it)) } ?: return null
        if (fmt !in setOf("jpeg", "png", "webp")) return null       // HEIC/GIF ฯลฯ: ลบไม่ได้โดยไม่แปลงไฟล์
        val (w, h) = imageSize(vault, fid) ?: return null
        val longSide = maxOf(w, h)
        val px = w.toLong() * h
        val maxSide = if (px > 40_000_000L) longSide / 2 else longSide    // กันหน่วยความจำไม่พอ (รูป 40MP+)
        val bmp = decodeImage(vault, fid, maxOf(1, maxSide)) ?: return null
        val bos = WipingBytes(1 shl 20)
        try {
            when (fmt) {
                "jpeg" -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                "png" -> bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                else -> {
                    @Suppress("DEPRECATION")
                    val wf = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                    bmp.compress(wf, 95, bos)
                }
            }
        } finally { wipeBitmap(bmp) }
        val data = bos.toByteArray()
        bos.wipe()
        return data to when (fmt) { "jpeg" -> ".jpg"; "png" -> ".png"; else -> ".webp" }
    }

    fun fmtDuration(sec: Double): String {
        val s = sec.toLong().coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
