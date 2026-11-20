package com.bestspace.vault.ui

import android.os.SystemClock
import com.bestspace.vault.core.Mime

/**
 * ความคืบหน้าของงานนำเข้า/ส่งออก – งาน (IO thread) เขียน, หน้าจออ่านทุก ~150 ms
 * หน่วยงาน (units) = ไบต์ที่ต้องประมวลผล เช่น เข้ารหัส + ตรวจสำเนา = 2 เท่าของขนาดไฟล์
 */
class JobInfo(val title: String, val count: Int, totalUnits: Long, totalBytes: Long = totalUnits) {
    val totalUnits = maxOf(1L, totalUnits)
    val totalBytes = maxOf(0L, totalBytes)
    @Volatile var i = 0
    @Volatile var name = ""
    @Volatile var phase = ""
    @Volatile private var before = 0L
    @Volatile private var curUnits = 0L
    @Volatile private var curDone = 0L
    private val t0 = SystemClock.elapsedRealtime()

    /** เริ่มไฟล์ที่ i (ไฟล์ก่อนหน้า – สำเร็จหรือไม่ก็ตาม – นับว่าผ่านไปแล้ว) */
    fun file(i: Int, name: String, units: Long, phase: String) {
        before += curUnits
        curUnits = maxOf(0L, units); curDone = 0
        this.i = i; this.name = name; this.phase = phase
    }

    fun advance(n: Long) { curDone = n.coerceIn(0L, curUnits) }

    fun absolute(i: Int, doneUnits: Long) { this.i = i; before = doneUnits; curUnits = 0; curDone = 0 }

    val done get() = minOf(totalUnits, before + curDone)
    fun frac() = done.toFloat() / totalUnits
    fun fileFrac() = if (curUnits > 0) curDone.toFloat() / curUnits else 0f

    /** สรุป (ไม่มีชื่อไฟล์ – ใช้บนหน้าปิดทับจอ/การแจ้งเตือนได้) */
    fun summary(): String {
        val f = frac()
        val parts = ArrayList<String>()
        if (count > 0) parts.add("${minOf(i, count)}/$count ไฟล์")
        if (totalBytes > 0) parts.add("${Mime.humanSize((totalBytes * f).toLong())} / ${Mime.humanSize(totalBytes)}")
        parts.add("${(f * 100).toInt()}%")
        eta()?.let { parts.add("เหลือ ~$it") }
        return parts.joinToString("  •  ")
    }

    fun eta(): String? {
        val el = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val f = frac()
        if (el < 3 || f < 0.01f) return null
        val left = el * (1 - f) / f
        return when {
            left < 90 -> "${left.toInt()} วินาที"
            left < 5400 -> "${(left / 60 + 0.5).toInt()} นาที"
            else -> "%.1f ชั่วโมง".format(left / 3600)
        }
    }

    fun view() = JobView(title, frac(), fileFrac(), summary(), if (phase.isEmpty()) name else "$phase  $name")
}

data class JobView(val title: String, val frac: Float, val fileFrac: Float, val summary: String, val file: String)

/** อ่านต่อพร้อมนับไบต์ (แถบความคืบหน้าระดับไบต์) */
class CountingInput(inp: java.io.InputStream, private val cb: (Long) -> Unit) : java.io.FilterInputStream(inp) {
    private var n = 0L
    override fun read(): Int { val r = super.read(); if (r >= 0) { n++; cb(n) }; return r }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = super.read(b, off, len)
        if (r > 0) { n += r; cb(n) }
        return r
    }
    override fun skip(k: Long): Long { val r = super.skip(k); if (r > 0) n += r; return r }
}

/** ผู้ใช้กดยกเลิกงานนำเข้า/ส่งออก */
class JobCancelled : java.io.IOException("ยกเลิกแล้ว")
