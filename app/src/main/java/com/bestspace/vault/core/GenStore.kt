package com.bestspace.vault.core

import java.io.File
import java.io.FileOutputStream

/**
 * เลขรุ่นดัชนีล่าสุดที่เครื่องนี้เคยเห็น (vid → gen) เก็บในเครื่อง นอกตู้ (เหมือน GenStore ของแอปคอม)
 * ใช้ตรวจว่าคลาวด์/คนอื่นย้อนตู้กลับเป็นเวอร์ชันเก่าหรือไม่ – ไม่มีข้อมูลลับ (มีแค่รหัสสุ่ม + ตัวเลข)
 */
class GenStore(val file: File) {
    @Synchronized private fun load(): MutableMap<String, Any?> =
        try { Json.obj(file.readText(Charsets.UTF_8)) } catch (_: Exception) { linkedMapOf() }

    @Synchronized fun get(vid: String): Long? = (load()[vid] as? Number)?.toLong()

    @Synchronized fun put(vid: String, gen: Long) {
        val d = load()
        if ((d[vid] as? Number)?.toLong() == gen) return
        d[vid] = gen
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(Json.write(d).toByteArray(Charsets.UTF_8)); it.fd.sync() }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        fun genOf(index: Map<String, Any?>): Long = ((index["gen"] as? Number)?.toLong() ?: 0L).coerceAtLeast(0L)

        /** เลขรุ่นหลังรวมดัชนี = ค่าที่สูงกว่า • รหัสตู้ (vid) เลือกแบบเดียวกันทุกเครื่อง – ตรงกับ _merge_gen ของแอปคอม */
        fun merge(out: MutableMap<String, Any?>, other: Map<String, Any?>) {
            out["gen"] = maxOf(genOf(out), genOf(other))
            val ov = other["vid"] as? String
            val mv = out["vid"] as? String
            if (ov != null && (mv == null || ov < mv)) out["vid"] = ov
        }
    }
}
