package com.bestspace.vault.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * อัลกอริทึมเข้ารหัส – ตรงกับแอปคอม (Python cryptography) ทุกบิต
 *  • AES-256-GCM (javax.crypto ของระบบ)  • scrypt (RFC 7914)  • HKDF-SHA256 (RFC 5869)
 * scrypt/HKDF เขียนเองจากมาตรฐาน (ไม่พึ่งไลบรารีภายนอก) และทดสอบกับ test vector ของ RFC
 */
object Crypto {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun wipe(b: ByteArray?) { if (b != null) java.util.Arrays.fill(b, 0) }
    fun wipe(c: CharArray?) { if (c != null) java.util.Arrays.fill(c, '\u0000') }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex ไม่ถูกต้อง" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s.trim())

    // ------------------------------------------------------------------ AES-256-GCM
    /** คืน ciphertext||tag(16) – รูปแบบเดียวกับ AESGCM.encrypt ของ Python */
    fun gcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(plain)
    }

    /** ถอด ciphertext||tag – ข้อมูลถูกแก้/คีย์ผิด → BadTag */
    fun gcmDecrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) c.updateAAD(aad)
        try {
            return c.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw BadTag()
        }
    }

    class BadTag : Exception("ตรวจสอบความถูกต้องไม่ผ่าน")

    // ------------------------------------------------------------------ HMAC / HKDF / PBKDF2
    fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        for (d in data) m.update(d)
        return m.doFinal()
    }

    /** HKDF-SHA256 (RFC 5869) */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        try {
            while (pos < len) {
                val next = hmac(prk, t, info, byteArrayOf(i.toByte()))
                wipe(t)
                t = next
                val n = minOf(t.size, len - pos)
                System.arraycopy(t, 0, out, pos, n)
                pos += n; i++
            }
            return out
        } finally {
            wipe(t)
            wipe(prk)
        }
    }

    private fun pbkdf2(pass: ByteArray, salt: ByteArray, dkLen: Int): ByteArray {
        // c = 1 (ตามที่ scrypt ใช้)
        if (pass.isEmpty()) {          // HMAC กับคีย์ว่าง: javax ไม่รับ → ใช้ HMAC แบบเขียนเอง
            return pbkdf2EmptyKey(salt, dkLen)
        }
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(pass, "HmacSHA256"))
        val out = ByteArray(dkLen)
        var block = 1
        var pos = 0
        while (pos < dkLen) {
            m.update(salt)
            m.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val u = m.doFinal()
            val n = minOf(u.size, dkLen - pos)
            System.arraycopy(u, 0, out, pos, n)
            wipe(u)
            pos += n; block++
        }
        return out
    }

    private fun pbkdf2EmptyKey(salt: ByteArray, dkLen: Int): ByteArray {
        val k = ByteArray(64)
        val ipad = ByteArray(64) { (k[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(64) { (k[it].toInt() xor 0x5c).toByte() }
        val out = ByteArray(dkLen)
        var block = 1; var pos = 0
        try {
            while (pos < dkLen) {
                val inner = sha256(ipad, salt, byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                val u = sha256(opad, inner)
                wipe(inner)
                val n = minOf(u.size, dkLen - pos)
                System.arraycopy(u, 0, out, pos, n)
                wipe(u)
                pos += n; block++
            }
            return out
        } finally {
            wipe(k); wipe(ipad); wipe(opad)
        }
    }

    // ------------------------------------------------------------------ scrypt (RFC 7914)
    /**
     * @param progress เรียกเป็นระยะ (0.0–1.0) – ใช้ทำแถบความคืบหน้าบนมือถือ (scrypt ใช้เวลาหลายวินาที)
     * หน่วยความจำ = 128 × r × N ไบต์ (ตู้ปกติ 128 MB)
     */
    fun scrypt(pass: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int,
               progress: ((Float) -> Unit)? = null): ByteArray {
        require(n > 1 && n and (n - 1) == 0) { "N ต้องเป็นกำลังของ 2" }
        var b: ByteArray? = null
        var x: IntArray? = null
        var v: IntArray? = null
        var y: IntArray? = null
        val t = IntArray(16)
        val sx = IntArray(16)
        try {
            b = pbkdf2(pass, salt, p * 128 * r)
            x = IntArray(32 * r)
            y = IntArray(32 * r)
            v = IntArray(32 * r * n)
            for (i in 0 until p) {
                val off = i * 128 * r
                for (k in 0 until 32 * r) x[k] = le32(b, off + k * 4)
                for (j in 0 until n) {
                    System.arraycopy(x, 0, v, j * 32 * r, 32 * r)
                    blockMix(x, y, r, t, sx)
                    if (progress != null && j and 0x3FFF == 0) progress((i * 2f * n + j) / (2f * n * p))
                }
                for (j in 0 until n) {
                    val idx = x[(2 * r - 1) * 16] and (n - 1)
                    val base = idx * 32 * r
                    for (k in 0 until 32 * r) x[k] = x[k] xor v[base + k]
                    blockMix(x, y, r, t, sx)
                    if (progress != null && j and 0x3FFF == 0) progress((i * 2f * n + n + j) / (2f * n * p))
                }
                for (k in 0 until 32 * r) putLe32(b, off + k * 4, x[k])
            }
            java.util.Arrays.fill(v, 0)
            v = null
            val out = pbkdf2(pass, b, dkLen)
            progress?.invoke(1f)
            return out
        } finally {
            v?.let { java.util.Arrays.fill(it, 0) }
            x?.let { java.util.Arrays.fill(it, 0) }
            y?.let { java.util.Arrays.fill(it, 0) }
            java.util.Arrays.fill(t, 0)
            java.util.Arrays.fill(sx, 0)
            wipe(b)
        }
    }

    private fun blockMix(b: IntArray, y: IntArray, r: Int, t: IntArray, sx: IntArray) {
        System.arraycopy(b, (2 * r - 1) * 16, t, 0, 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) t[k] = t[k] xor b[i * 16 + k]
            salsa8(t, sx)
            System.arraycopy(t, 0, y, i * 16, 16)
        }
        for (i in 0 until r) {
            System.arraycopy(y, (2 * i) * 16, b, i * 16, 16)
            System.arraycopy(y, (2 * i + 1) * 16, b, (r + i) * 16, 16)
        }
    }

    private fun salsa8(b: IntArray, x: IntArray) {
        System.arraycopy(b, 0, x, 0, 16)
        var i = 0
        while (i < 8) {
            x[4] = x[4] xor Integer.rotateLeft(x[0] + x[12], 7);  x[8] = x[8] xor Integer.rotateLeft(x[4] + x[0], 9)
            x[12] = x[12] xor Integer.rotateLeft(x[8] + x[4], 13); x[0] = x[0] xor Integer.rotateLeft(x[12] + x[8], 18)
            x[9] = x[9] xor Integer.rotateLeft(x[5] + x[1], 7);   x[13] = x[13] xor Integer.rotateLeft(x[9] + x[5], 9)
            x[1] = x[1] xor Integer.rotateLeft(x[13] + x[9], 13); x[5] = x[5] xor Integer.rotateLeft(x[1] + x[13], 18)
            x[14] = x[14] xor Integer.rotateLeft(x[10] + x[6], 7); x[2] = x[2] xor Integer.rotateLeft(x[14] + x[10], 9)
            x[6] = x[6] xor Integer.rotateLeft(x[2] + x[14], 13); x[10] = x[10] xor Integer.rotateLeft(x[6] + x[2], 18)
            x[3] = x[3] xor Integer.rotateLeft(x[15] + x[11], 7); x[7] = x[7] xor Integer.rotateLeft(x[3] + x[15], 9)
            x[11] = x[11] xor Integer.rotateLeft(x[7] + x[3], 13); x[15] = x[15] xor Integer.rotateLeft(x[11] + x[7], 18)
            x[1] = x[1] xor Integer.rotateLeft(x[0] + x[3], 7);   x[2] = x[2] xor Integer.rotateLeft(x[1] + x[0], 9)
            x[3] = x[3] xor Integer.rotateLeft(x[2] + x[1], 13);  x[0] = x[0] xor Integer.rotateLeft(x[3] + x[2], 18)
            x[6] = x[6] xor Integer.rotateLeft(x[5] + x[4], 7);   x[7] = x[7] xor Integer.rotateLeft(x[6] + x[5], 9)
            x[4] = x[4] xor Integer.rotateLeft(x[7] + x[6], 13);  x[5] = x[5] xor Integer.rotateLeft(x[4] + x[7], 18)
            x[11] = x[11] xor Integer.rotateLeft(x[10] + x[9], 7); x[8] = x[8] xor Integer.rotateLeft(x[11] + x[10], 9)
            x[9] = x[9] xor Integer.rotateLeft(x[8] + x[11], 13); x[10] = x[10] xor Integer.rotateLeft(x[9] + x[8], 18)
            x[12] = x[12] xor Integer.rotateLeft(x[15] + x[14], 7); x[13] = x[13] xor Integer.rotateLeft(x[12] + x[15], 9)
            x[14] = x[14] xor Integer.rotateLeft(x[13] + x[12], 13); x[15] = x[15] xor Integer.rotateLeft(x[14] + x[13], 18)
            i += 2
        }
        for (k in 0 until 16) b[k] += x[k]
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    fun be32(v: Int): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
