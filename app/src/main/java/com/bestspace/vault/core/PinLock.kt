package com.bestspace.vault.core

/**
 * รหัสล็อคหน้าโน้ต (ตัวเลข 4 หลัก) – ไม่เก็บรหัสจริง เก็บแค่ salt + ค่า scrypt
 * • scrypt N=2^14, r=8 (16 MB) – ช้าพอให้เดาแบบออฟไลน์ไม่ได้ทันที แต่ปลดล็อคบนมือถือไม่ถึงวินาที
 * • เทียบผลแบบเวลาคงที่ (MessageDigest.isEqual)
 * • ผิดเกิน FREE_TRIES ครั้ง ต้องรอนานขึ้นเรื่อยๆ (30 วิ → 1 → 2 → ... สูงสุด 15 นาที)
 * ไม่เกี่ยวกับรหัสตู้/การเข้ารหัสตู้เลย
 */
object PinLock {
    const val MIN_LEN = 4
    const val MAX_LEN = 4
    const val FREE_TRIES = 5
    private const val N = 1 shl 14
    private const val R = 8
    const val MAX_WAIT_MS = 15 * 60_000L

    data class Stored(val salt: ByteArray, val hash: ByteArray)

    fun valid(pin: CharArray): Boolean = pin.size in MIN_LEN..MAX_LEN && pin.all { it in '0'..'9' }

    fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val b = ByteArray(pin.size) { pin[it].code.toByte() }        // ตัวเลขล้วน = ASCII
        try { return Crypto.scrypt(b, salt, N, R, 1, 32) } finally { Crypto.wipe(b) }
    }

    fun create(pin: CharArray): Stored {
        require(valid(pin)) { "รหัสต้องเป็นตัวเลข $MIN_LEN หลัก" }
        val salt = Crypto.random(16)
        return Stored(salt, derive(pin, salt))
    }

    fun verify(pin: CharArray, s: Stored): Boolean {
        if (!valid(pin)) return false
        val h = derive(pin, s.salt)
        try { return java.security.MessageDigest.isEqual(h, s.hash) } finally { Crypto.wipe(h) }
    }

    /** เวลาที่ต้องรอหลังผิดครั้งที่ [fails] (0 = ยังลองได้ทันที) */
    fun waitMs(fails: Int): Long {
        if (fails < FREE_TRIES) return 0
        val step = (fails - FREE_TRIES).coerceAtMost(10)
        return (30_000L shl step).coerceAtMost(MAX_WAIT_MS)
    }
}
