package com.bestspace.vault.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockTest {
    @Test fun createAndVerify() {
        val s = PinLock.create("1234".toCharArray())
        assertEquals(16, s.salt.size)
        assertEquals(32, s.hash.size)
        assertTrue(PinLock.verify("1234".toCharArray(), s))
        assertFalse(PinLock.verify("1235".toCharArray(), s))
        assertFalse(PinLock.verify("".toCharArray(), s))
        assertFalse(PinLock.verify("123".toCharArray(), s))
        assertFalse(PinLock.verify("12345".toCharArray(), s))
        assertFalse(PinLock.verify("12345678".toCharArray(), s))
    }

    @Test fun saltDiffersEachTime() {
        val a = PinLock.create("0000".toCharArray())
        val b = PinLock.create("0000".toCharArray())
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.hash.contentEquals(b.hash))     // ไม่มีค่าเดียวกันข้ามเครื่อง/ข้ามการตั้งรหัส
    }

    @Test fun onlyDigits4() {
        assertTrue(PinLock.valid("1234".toCharArray()))
        assertTrue(PinLock.valid("0000".toCharArray()))
        assertFalse(PinLock.valid("".toCharArray()))
        assertFalse(PinLock.valid("123".toCharArray()))
        assertFalse(PinLock.valid("12345".toCharArray()))
        assertFalse(PinLock.valid("12345678".toCharArray()))
        assertFalse(PinLock.valid("12a4".toCharArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectMoreThan4DigitsOnCreate() {
        PinLock.create("12345".toCharArray())
    }

    @Test fun backoff() {
        assertEquals(0L, PinLock.waitMs(4))
        assertEquals(30_000L, PinLock.waitMs(5))
        assertEquals(60_000L, PinLock.waitMs(6))
        assertEquals(15 * 60_000L, PinLock.waitMs(50))
    }
}
