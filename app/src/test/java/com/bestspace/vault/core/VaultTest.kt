package com.bestspace.vault.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class VaultTest {
    private fun tmp(): File = Files.createTempDirectory("bs").toFile()

    @Test fun scryptRfc7914Vector() {
        val out = Crypto.scrypt("password".toByteArray(), "NaCl".toByteArray(), 1024, 8, 16, 64)
        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162" +
                "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            Crypto.hex(out),
        )
    }

    @Test fun roundTripWithKeyfile() {
        val dir = tmp()
        val v = Vault(File(dir, ".bestspace"), File(dir, "keys"))
        assertNotNull(v.create("pass-1234", useKeyfile = true))
        val data = ByteArray(Vault.CHUNK * 2 + 777) { (it * 31).toByte() }
        val fid = v.addFile(ByteArrayInputStream(data), "a/b\\..\\photo.jpg", "image/jpeg", null)
        assertEquals("photo.jpg", v.entry(fid)["name"])
        v.lock()

        try { v.unlock("wrong"); fail() } catch (e: WrongPassword) {}
        v.unlock("pass-1234")
        v.openReader(fid).use { r ->
            assertArrayEquals(data, r.asInputStream().readBytes())
            val b = ByteArray(10)
            r.readAt(Vault.CHUNK - 5L, b, 0, 10)
            assertArrayEquals(data.copyOfRange(Vault.CHUNK - 5, Vault.CHUNK + 5), b)
        }

        // ไม่มีไฟล์กุญแจ = เปิดไม่ได้แม้รหัสถูก
        v.lock()
        File(dir, "keys").deleteRecursively()
        try { v.unlock("pass-1234"); fail() } catch (e: KeyfileMissing) {}
    }

    @Test fun keyfileReadFromRememberedLocation() {
        val dir = tmp()
        val v = Vault(File(dir, ".bestspace"), File(dir, "keys"))
        val kf = v.create("pass-1234", useKeyfile = true)!!
        val fp = v.keyfileFp()!!
        val usb = File(dir, "usb").apply { mkdirs() }
        val moved = File(usb, "my.key"); assertTrue(kf.renameTo(moved))   // ไม่มีสำเนาข้างตู้แล้ว
        v.lock()
        try { v.unlock("pass-1234"); fail() } catch (e: KeyfileMissing) {}
        v.keyLocator = { f -> if (f == fp) Keyfiles.read(moved) else null }
        v.unlock("pass-1234")
        assertNotNull(v.keySecret()); assertEquals(fp, Keyfiles.fp(v.keySecret()!!))
        v.lock()
        moved.delete()                                                  // ถอดแฟลชไดรฟ์ / ไม่มีเน็ต
        try { v.unlock("pass-1234"); fail() } catch (e: KeyfileMissing) {}
        v.keyLocator = { ByteArray(32) { 7 } }                          // กุญแจผิด = ไม่รับ
        try { v.unlock("pass-1234"); fail() } catch (e: KeyfileMissing) {}
        v.keyLocator = { throw java.io.IOException("offline") }
        try { v.unlock("pass-1234"); fail() } catch (e: KeyfileMissing) {}
    }

    @Test fun folderPinSurvivesReopen() {
        val dir = tmp()
        val v = Vault(File(dir, ".bestspace"), File(dir, "keys"))
        v.create("pass-1234", useKeyfile = false)
        val a = v.createFolder("A", null); val b = v.createFolder("B", null)
        v.setFoldersPinned(listOf(b), true)
        v.lock(); v.unlock("pass-1234")
        assertEquals(true, (v.folders()[b] as Map<*, *>)["pinned"])
        assertEquals(null, (v.folders()[a] as Map<*, *>)["pinned"])
        v.setFoldersPinned(listOf(b), false)
        v.lock(); v.unlock("pass-1234")
        assertEquals(null, (v.folders()[b] as Map<*, *>)["pinned"])
        v.lock()
    }

    @Test fun detectsIndexRollback() {
        val dir = tmp()
        val store = GenStore(File(dir, "cfg/seen.json"))
        fun open() = Vault(File(dir, ".bestspace"), null).also { it.genStore = store }
        var v = open(); v.create("pass-1234", useKeyfile = false)
        val a = v.addFile(ByteArrayInputStream("A".toByteArray()), "a.txt", "text/plain", null)
        val idx = File(dir, ".bestspace/index.enc"); val old = idx.readBytes()      // คลาวด์เก็บฉบับเก่าไว้
        v.addFile(ByteArrayInputStream("B".toByteArray()), "b.txt", "text/plain", null)
        v.deleteMany(listOf(a), emptyList()); v.lock()
        v = open(); v.unlock("pass-1234"); assertEquals(null, v.rollback); v.lock()
        idx.writeBytes(old)                                                       // ย้อนเวอร์ชัน
        v = open(); v.unlock("pass-1234"); val rb = v.rollback!!; assertTrue(rb.first < rb.second); v.lock()
        v = open(); v.unlock("pass-1234"); assertEquals(null, v.rollback)            // เตือนครั้งเดียว
        v.addFile(ByteArrayInputStream("C".toByteArray()), "c.txt", "text/plain", null); v.lock()
        v = open(); v.unlock("pass-1234"); assertEquals(null, v.rollback); v.lock()
        val (m, _) = Vault.mergeIndex(linkedMapOf("gen" to 3L, "vid" to "b"), linkedMapOf("gen" to 9L, "vid" to "a"))
        assertEquals(9L, m["gen"]); assertEquals("a", m["vid"])
        val w = Vault(File(dir, ".bestspace"), null); w.unlock("pass-1234"); assertEquals(null, w.rollback)   // ไม่ตั้ง store = ไม่ตรวจ
    }

    @Test fun deleteLeavesTombstone() {
        val dir = tmp()
        val v = Vault(File(dir, ".bestspace"), null)
        v.create("pass-1234", useKeyfile = false)
        val fid = v.addFile(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "x.bin", null, null)
        v.deleteMany(listOf(fid), emptyList())
        assertTrue(!v.blob(fid).exists())
        assertTrue(v.index!!["tombstones"].asMap().containsKey(fid))
    }

    @Test fun shareAndPasswordAndText() {
        val dir = tmp()
        val v = Vault(File(dir, ".bestspace"), File(dir, "keys"))
        v.create("pass-1234", useKeyfile = false)
        val d = v.createFolder("ทริป", null)
        val fid = v.addFile(ByteArrayInputStream("hello".toByteArray()), "a.txt", null, d)
        val bsx = java.io.ByteArrayOutputStream()
        v.exportShare(fid, bsx, "share-pw-1")
        v.deleteMany(emptyList(), listOf(d))
        assertTrue(v.files().isEmpty() && v.folders().isEmpty())
        try { v.importShare(bsx.toByteArray().inputStream(), "x.bsx", "wrong", null); fail() } catch (e: WrongPassword) {}
        val nid = v.importShare(bsx.toByteArray().inputStream(), "x.bsx", "share-pw-1", null)
        assertArrayEquals("hello".toByteArray(), v.readBytes(nid))
        assertEquals("bsx", v.entry(nid)["origin"])
        v.changePassword("pass-1234", "new-pass-99")
        v.lock()
        try { v.unlock("pass-1234"); fail() } catch (e: WrongPassword) {}
        v.unlock("new-pass-99")
        assertArrayEquals("hello".toByteArray(), v.readBytes(nid))
        val r = v.replaceBytes(nid, "แก้ไข".toByteArray())
        assertEquals("แก้ไข", String(v.readBytes(r)))
        val t = TextCodec.decode("ไทย\r\nTIS".toByteArray(charset("x-windows-874")))
        assertEquals("cp874", t.enc); assertTrue(t.crlf && t.exact)
        val lo = Lockout(v, File(dir, "cfg"))
        repeat(3) { lo.failed() }
        assertTrue(lo.waitLeft() > 25)
        assertEquals(3L, lo.succeeded().sinceOk)
    }

    /** เปลี่ยนรหัสแล้วเครื่องดับตรงจังหวะที่เขียนทับไฟล์เดิมไปแล้ว → เปิดใหม่ต้องกู้ได้ */
    @Test fun passwordChangeCrashRecovery() {
        val dir = tmp()
        val root = File(dir, ".bestspace")
        val v = Vault(root, null)
        v.create("pass-1234", useKeyfile = false)
        val fid = v.addFile(ByteArrayInputStream("data".toByteArray()), "a.txt", null, null)
        v.changePassword("pass-1234", "new-pass-99")
        v.lock()
        // จำลอง: vault.json ใหม่อยู่ใน .tmp แต่ vault.json ถูกเขียนทับด้วยข้อมูลสุ่มแล้ว (ดับก่อน rename)
        File(root, "vault.json").copyTo(File(root, "vault.json.tmp"))
        Vault.overwriteInPlace(File(root, "vault.json"))
        // จำลอง: index.enc เดิมถูกเขียนทับแล้ว แต่ .next ยังไม่ถูก rename
        File(root, "index.enc").copyTo(File(root, "index.enc.next"))
        Vault.overwriteInPlace(File(root, "index.enc"))
        val v2 = Vault(root, null)
        v2.unlock("new-pass-99")
        assertArrayEquals("data".toByteArray(), v2.readBytes(fid))
        assertTrue(!File(root, "vault.json.tmp").exists() && !File(root, "index.enc.next").exists())
    }

    /** ถูกขัดจังหวะกลางการนำเข้า (เข้ารหัสค้าง / ยังไม่ลงดัชนี) → ปลดล็อคครั้งหน้าเก็บกวาด ไม่แตะไฟล์ที่ลงดัชนีแล้ว */
    @Test fun interruptedImportIsCleanedOnUnlock() {
        val dir = tmp()
        val root = File(dir, ".bestspace")
        val j = Journal(File(dir, "cfg/jobs.json"))
        val v = Vault(root, null).also { it.journal = j }
        v.create("pass-1234", useKeyfile = false)
        val keep = v.addFile(ByteArrayInputStream("keep".toByteArray()), "keep.txt", null, null)
        assertTrue(j.entries().isEmpty())                                  // สำเร็จ = ไม่มีงานค้าง
        // จำลอง: ดับหลังเขียนไฟล์เข้ารหัสเสร็จ แต่ก่อนบันทึกดัชนี + อีกไฟล์ดับกลางการเข้ารหัส (.tmp)
        val orphan = "0123456789abcdef0123456789abcdef"
        val half = "fedcba9876543210fedcba9876543210"
        File(root, "files/$orphan.bsf").writeBytes(ByteArray(5000) { 1 })
        File(root, "files/$half.tmp").writeBytes(ByteArray(3000) { 2 })
        val me = root.canonicalPath
        // งานของโปรเซสก่อน (ถูกฆ่าไปแล้ว) = เขียนลงไฟล์ตรงๆ ไม่ใช่งานที่ยังวิ่งอยู่
        j.file.writeText(Json.write(linkedMapOf(
            "a1" to linkedMapOf("k" to "vfid", "vault" to me, "fid" to orphan),
            "a2" to linkedMapOf("k" to "vfid", "vault" to me, "fid" to half),
            "a3" to linkedMapOf("k" to "vfid", "vault" to me, "fid" to keep),   // ดับหลังลงดัชนีแล้ว → ต้องเก็บไว้
            "a4" to linkedMapOf("k" to "out", "p" to "/x/.bestspace-aa.part"))))  // ของงานส่งออก – ไม่ใช่หน้าที่ของตู้
        // งานที่ยังวิ่งอยู่ในโปรเซสนี้ (เช่น หน้าจอถูกปิดแต่งานยังทำต่อ) → ห้ามแตะ
        val running = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        File(root, "files/$running.tmp").writeBytes(ByteArray(10) { 3 })
        j.add(linkedMapOf("k" to "vfid", "vault" to me, "fid" to running))
        v.lock()
        val v2 = Vault(root, null).also { it.journal = j }
        v2.unlock("pass-1234")
        assertEquals(2, v2.recoverInterrupted())
        assertTrue(!File(root, "files/$orphan.bsf").exists() && !File(root, "files/$half.tmp").exists())
        assertArrayEquals("keep".toByteArray(), v2.readBytes(keep))
        assertTrue(File(root, "files/$running.tmp").exists())
        assertEquals(listOf("out", "vfid"), j.entries().values.map { it["k"] }.sortedBy { it.toString() })
    }

    @Test fun backupIsAtomicAndVerified() {
        val dir = tmp()
        val j = Journal(File(dir, "jobs.json"))
        val v = Vault(File(dir, ".bestspace"), null).also { it.journal = j }
        v.create("pass-1234", useKeyfile = false)
        val data = ByteArray(Vault.CHUNK + 99) { (it * 7).toByte() }
        val fid = v.addFile(ByteArrayInputStream(data), "a.bin", null, null)
        val dest = File(dir, "bk").apply { mkdirs() }
        val t = v.backup(dest)
        assertEquals(listOf(t.name), dest.list()!!.toList())                // ไม่มี .part ค้าง
        assertTrue(!t.name.endsWith(".part") && j.entries().isEmpty())
        val v2 = Vault(t, null); v2.unlock("pass-1234")
        assertArrayEquals(data, v2.readBytes(fid))
    }

    /** ลำดับที่จัดเอง + ภาพปกโฟลเดอร์: เก็บในดัชนี, ย้ายโฟลเดอร์แล้วล้างลำดับ, แก้ข้อความแล้วตำแหน่งคงเดิม, เปิดใหม่ยังอยู่ */
    @Test fun customOrderAndFolderCover() {
        val dir = tmp()
        val v = Vault(File(dir, ".bestspace"), null)
        v.create("pass-1234", useKeyfile = false)
        val d = v.createFolder("ทริป", null)
        val a = v.addBytes("a".toByteArray(), "a.txt", d)
        val b = v.addBytes("b".toByteArray(), "b.txt", d)
        val c = v.addBytes("c".toByteArray(), "c.txt", d)
        v.setOrder(listOf(c, a, b))
        assertEquals(listOf(1.0, 2.0, 0.0), listOf(a, b, c).map { v.entry(it)["pos"] })
        val a2 = v.replaceBytes(a, "a2".toByteArray())
        assertEquals(1.0, v.entry(a2)["pos"])
        v.move(listOf(b), emptyList(), null)
        assertTrue(!v.entry(b).containsKey("pos"))
        v.setFolderCover(d, c)
        try { v.setFolderCover(d, "nope"); fail() } catch (e: VaultException) {}
        v.lock(); v.unlock("pass-1234")
        assertEquals(c, v.folders()[d].asMap()["cover"])
        assertEquals(0.0, v.entry(c)["pos"])
        v.setFolderCover(d, null)
        assertTrue(!v.folders()[d].asMap().containsKey("cover"))
    }

    @Test fun secureDeleteReportsHonestly() {
        val dir = tmp()
        val f = File(dir, "x.jpg").apply { writeBytes(ByteArray(3000) { 7 }) }
        assertTrue(Vault.secureDelete(f))
        assertTrue(!f.exists() && dir.listFiles()!!.isEmpty())          // ไม่มีไฟล์ชื่อสุ่มค้าง
        assertTrue(Vault.secureDelete(File(dir, "missing")))
    }

    /** ตู้ที่สร้างจากโปรแกรมบนคอม (Python) ต้องเปิดได้ในมือถือ */
    @Test fun opensDesktopVault() {
        val src = File(javaClass.classLoader!!.getResource("desktop_vault/vault.json")!!.toURI()).parentFile!!
        val dir = tmp()
        src.copyRecursively(File(dir, "v"))
        val v = Vault(File(dir, "v"), null)
        v.unlock("test-pass-123")
        val (fid, e) = v.files().entries.first()
        assertEquals("src.bin", e.asMap()["name"])
        assertEquals("ภาพ", v.folders()[v.parentOf(fid)].asMap()["name"])
        val expect = ByteArray(256 * 9000) { it.toByte() }
        v.openReader(fid).use { assertArrayEquals(expect, it.asInputStream().readBytes()) }
    }

    /** นำเข้า .bsx เดิมซ้ำหลังลบ: ต้องได้รหัสไฟล์ใหม่ (ไม่ติด tombstone → ไม่หายเองตอนรวมดัชนีกับเครื่องอื่น)
     *  และคีย์ของไฟล์ในตู้ต้องไม่ใช่คีย์ของผู้ส่ง */
    @Test fun reimportedShareSurvivesTombstoneMerge() {
        val dir = tmp()
        val v = Vault(File(dir, ".bestspace"), null)
        v.create("pass-1234", useKeyfile = false)
        val data = ByteArray(Vault.CHUNK + 4321) { (it * 7).toByte() }
        val src = v.addFile(ByteArrayInputStream(data), "clip.mp4", null, null)
        val bsx = java.io.ByteArrayOutputStream().also { v.exportShare(src, it, "share-pw-1") }.toByteArray()
        try { v.importShare(bsx.inputStream(), "x.bsx", "share-pw-1", null); fail() } catch (e: VaultException) {}  // ต้นฉบับยังอยู่
        v.deleteMany(listOf(src), emptyList())
        val a = v.importShare(bsx.inputStream(), "x.bsx", "share-pw-1", null)
        assertTrue(a != src)
        try { v.importShare(bsx.inputStream(), "x.bsx", "share-pw-1", null); fail() } catch (e: VaultException) {}  // ซ้ำ
        v.deleteMany(listOf(a), emptyList())
        val b = v.importShare(bsx.inputStream(), "x.bsx", "share-pw-1", null)
        assertTrue(b != src && b != a)
        assertArrayEquals(data, v.readBytes(b))
        // รวมกับดัชนีของ "อีกเครื่อง" ที่มี tombstone ของไฟล์เดิม → ไฟล์ที่นำเข้าใหม่ต้องยังอยู่
        @Suppress("UNCHECKED_CAST")
        val other = Json.obj(Json.write(v.index!!)).also { it["files"] = linkedMapOf<String, Any?>() }
        val (merged, _) = Vault.mergeIndex(Json.obj(Json.write(v.index!!)), other)
        assertTrue(merged["files"].asMap().containsKey(b))
        assertTrue(v.filesDir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    /** นำเข้าจำนวนมาก: ลงดัชนีทีละชุด – ชุดที่ยังไม่ลงดัชนีตอนดับ ถูกเก็บกวาด, ชุดที่ลงแล้วอยู่ครบ */
    @Test fun batchedCommitAndInterruptedBatch() {
        val dir = tmp()
        val root = File(dir, ".bestspace")
        val j = Journal(File(dir, "cfg/jobs.json"))
        val v = Vault(root, null).also { it.journal = j }
        v.create("pass-1234", useKeyfile = false)
        val batch = (0 until 40).map { v.encryptPending(ByteArrayInputStream("f$it".toByteArray()), "f$it.txt", null, null) }
        assertEquals(40, j.entries().size)
        val before = v.indexFile.lastModified()
        v.commitPending(batch)
        assertTrue(j.entries().isEmpty())
        assertEquals(40, v.files().size)
        assertArrayEquals("f7".toByteArray(), v.readBytes(batch[7].fid))
        // ชุดที่สองเข้ารหัสแล้วแต่ "ดับ" ก่อนลงดัชนี (จำลอง: งานของโปรเซสเก่า)
        val lost = (0 until 5).map { v.encryptPending(ByteArrayInputStream("g$it".toByteArray()), "g$it.txt", null, null) }
        val stale = j.entries()
        v.lock()
        val v2 = Vault(root, null).also { it.journal = j }
        // งานใน journal เป็นของโปรเซสนี้ (live) → จำลองว่าเป็นของโปรเซสเก่า: ลบออก (ล้างสถานะ live) แล้วเขียนกลับ
        stale.keys.forEach { j.remove(it) }
        j.file.writeText(Json.write(LinkedHashMap<String, Any?>(stale)))
        v2.unlock("pass-1234")
        assertEquals(5, v2.recoverInterrupted())
        assertEquals(40, v2.files().size)
        lost.forEach { assertTrue(!v2.blob(it.fid).exists()) }
        assertTrue(before <= v2.indexFile.lastModified())
    }

    @Test fun charArrayOverloadsAndVerifyPassword() {
        val dir = tmp()
        val v = Vault(File(dir, ".bestspace"), File(dir, "keys"))
        val p1 = "char-pass-1".toCharArray()
        assertNull(v.create(p1, useKeyfile = false))
        Crypto.wipe(p1)
        val fid = v.addBytes("secret".toByteArray(), "s.txt", null)
        v.lock()

        val checker = Vault(File(dir, ".bestspace"), File(dir, "keys"))
        val wrong = "wrong-pw".toCharArray()
        try { checker.verifyPassword(wrong); fail() } catch (_: WrongPassword) {} finally { Crypto.wipe(wrong) }
        val ok = "char-pass-1".toCharArray()
        checker.verifyPassword(ok)
        Crypto.wipe(ok)
        assertTrue(!checker.isUnlocked)

        val pAdd = "char-pass-1".toCharArray()
        v.unlock(pAdd.copyOf())
        assertArrayEquals("secret".toByteArray(), v.readBytes(fid))
        v.addKeyfile(pAdd)
        Crypto.wipe(pAdd)
        v.lock()

        File(dir, "keys").deleteRecursively()
        val missingCheck = "char-pass-1".toCharArray()
        try { checker.verifyPassword(missingCheck); fail() } catch (_: KeyfileMissing) {} finally { Crypto.wipe(missingCheck) }
    }

    @Test fun metaCrashRecoveryWhenMetaFileMissing() {
        val dir = tmp()
        val root = File(dir, ".bestspace")
        val v = Vault(root, null)
        v.create("pass-1234", useKeyfile = false)
        val fid = v.addBytes("hello".toByteArray(), "a.txt", null)
        v.lock()

        File(root, "vault.json").copyTo(File(root, "vault.json.tmp"))
        assertTrue(File(root, "vault.json").delete())

        val v2 = Vault(root, null)
        assertTrue(v2.exists())
        v2.unlock("pass-1234")
        assertArrayEquals("hello".toByteArray(), v2.readBytes(fid))
        assertTrue(File(root, "vault.json").isFile && !File(root, "vault.json.tmp").exists())
    }

    @Test fun mergeConflictsPreservesConflictOnSaveFailureAndSyncDistinguishesRekey() {
        val dir = tmp()
        val root = File(dir, ".bestspace")
        val v = Vault(root, null)
        v.create("pass-1234", useKeyfile = false)
        v.addBytes("one".toByteArray(), "1.txt", null)
        val snap = File(root, "index.enc").readBytes()
        v.addBytes("two".toByteArray(), "2.txt", null)

        val conflict = File(root, "index (conflicted copy).enc")
        conflict.writeBytes(snap)
        root.setWritable(false)
        try {
            try { v.mergeConflicts(); fail() } catch (_: Exception) {}
            assertTrue(conflict.exists())
        } finally {
            root.setWritable(true)
        }
        val rep = v.mergeConflicts()
        assertEquals(1, rep.merged)
        assertTrue(!conflict.exists())

        // Truncated index.enc (partial sync) must NOT masquerade as RemoteRekey
        val good = File(root, "index.enc").readBytes()
        File(root, "index.enc").writeBytes(good.copyOf(20))
        try { v.syncFromDisk(); fail() }
        catch (e: RemoteRekey) { fail("truncated index must not throw RemoteRekey") }
        catch (_: CorruptData) {}

        // Index sealed by another key must throw RemoteRekey
        val otherDir = tmp()
        val other = Vault(File(otherDir, ".bestspace"), null)
        other.create("other-pass", useKeyfile = false)
        File(root, "index.enc").writeBytes(File(otherDir, ".bestspace/index.enc").readBytes())
        try { v.syncFromDisk(); fail() } catch (_: RemoteRekey) {}
        File(root, "index.enc").writeBytes(good)
    }

    @Test fun zeroByteFileVerifiesGcmTag() {
        val dir = tmp()
        val v = Vault(File(dir, ".bestspace"), null)
        v.create("pass-1234", useKeyfile = false)
        val fid = v.addBytes(ByteArray(0), "empty.txt", null)
        assertArrayEquals(ByteArray(0), v.readBytes(fid))

        val blob = v.blob(fid)
        val bytes = blob.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        blob.writeBytes(bytes)
        try { v.readBytes(fid); fail() } catch (_: CorruptData) {}
    }
}

