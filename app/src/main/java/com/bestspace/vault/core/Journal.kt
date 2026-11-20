package com.bestspace.vault.core

import java.io.File
import java.io.FileOutputStream

/**
 * บันทึกงานที่กำลังเขียนไฟล์ (นำเข้า/ส่งออก/สำรอง) ลงดิสก์ก่อนเริ่มเขียน (เหมือน Journal ของแอปคอม)
 * ถ้าถูกขัดจังหวะ (แอปถูกปิด/แบตหมด/แครช) ครั้งหน้าจะเก็บกวาดไฟล์ที่เขียนไม่ครบให้:
 *   vfid   ไฟล์ใหม่ในตู้ที่ยังไม่ได้ลงดัชนี → ลบ (ตอนปลดล็อค – Vault.recoverInterrupted)
 *   out    ไฟล์ส่งออกที่ยังเขียน/ตรวจไม่เสร็จ (ชื่อชั่วคราว) → เขียนทับ+ลบ (ตอนเปิดแอป)
 *   outdir โฟลเดอร์สำรองตู้ที่ยังไม่เสร็จ → ลบทั้งโฟลเดอร์
 * เก็บเฉพาะ path ชั่วคราว / รหัสไฟล์ ไม่มีชื่อไฟล์จริง
 */
class Journal(val file: File) {
    @Synchronized private fun load(): MutableMap<String, Any?> =
        try { Json.obj(file.readText(Charsets.UTF_8)) } catch (_: Exception) { linkedMapOf() }

    @Synchronized private fun save(d: Map<String, Any?>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(Json.write(d).toByteArray(Charsets.UTF_8)); it.fd.sync() }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    @Synchronized fun add(entry: Map<String, Any?>): String {
        val tok = Crypto.hex(Crypto.random(8))
        val d = load()
        d[tok] = LinkedHashMap(entry)
        save(d)
        live.add(tok)
        return tok
    }

    @Synchronized fun remove(tok: String?) {
        if (tok == null) return
        live.remove(tok)
        val d = load()
        if (d.remove(tok) != null) save(d)
    }

    @Synchronized fun entries(): Map<String, Map<String, Any?>> =
        load().mapNotNull { (k, v) -> (v as? Map<*, *>)?.let { m -> k to m.entries.associate { it.key.toString() to it.value } } }.toMap()

    companion object {
        /** งานที่ยังทำอยู่ในโปรเซสนี้ (เช่น หน้าจอถูกปิดแต่งานยังวิ่งต่อ) – ห้ามเก็บกวาด */
        private val live: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        fun isLive(tok: String) = tok in live

        /** ชื่อไฟล์ชั่วคราวระหว่างเขียน: ไม่บอกชื่อจริง ขึ้นต้นด้วยจุด (แกลเลอรี/ตัวสแกนสื่อไม่สแกน) */
        fun partName(): String = ".bestspace-" + Crypto.hex(Crypto.random(6)) + ".part"
    }
}
