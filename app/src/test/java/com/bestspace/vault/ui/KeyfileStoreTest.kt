package com.bestspace.vault.ui

import com.bestspace.vault.core.Crypto
import com.bestspace.vault.core.Keyfiles
import com.bestspace.vault.core.VaultException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KeyfileStoreTest {
    private class MemoryAesSeal(private val key: ByteArray = Crypto.random(32)) : KeySeal {
        override fun encrypt(aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
            val iv = Crypto.random(12)
            return iv to Crypto.gcmEncrypt(key, iv, plain, aad)
        }

        override fun decrypt(aad: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray? = try {
            Crypto.gcmDecrypt(key, iv, ct, aad)
        } catch (_: Exception) {
            null
        }
    }

    @Test fun saveLoadTamperAndForget() {
        val dir = Files.createTempDirectory("bs-kstore").toFile()
        val store = KeyfileStore(dir, MemoryAesSeal())
        val sec = Crypto.random(32)
        val fp = Keyfiles.fp(sec)

        assertFalse(store.has(fp))
        assertNull(store.load(fp))

        store.save(fp, sec)
        assertTrue(store.has(fp))
        val blobFile = File(dir, "$fp.k")
        assertTrue(blobFile.isFile)
        val raw = blobFile.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(Keyfiles.MAGIC))
        assertFalse(raw.toList().windowed(sec.size).any { it == sec.toList() })

        val loaded = store.load(fp)!!
        assertArrayEquals(sec, loaded)
        Crypto.wipe(loaded)

        // Tampering ciphertext byte must fail GCM tag verification
        val tampered = raw.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()
        blobFile.writeBytes(tampered)
        assertNull(store.load(fp))

        // Re-save cleanly, then swapping to another fp must fail AAD check
        store.save(fp, sec)
        val otherFp = Keyfiles.fp(Crypto.random(32))
        blobFile.copyTo(File(dir, "$otherFp.k"), overwrite = true)
        assertNull(store.load(otherFp))

        // Forget wipes and removes the stored keyfile
        assertTrue(store.forget(fp))
        assertFalse(store.has(fp))
        assertFalse(blobFile.exists())
        assertNull(store.load(fp))
        assertFalse(store.forget(fp))
    }

    @Test fun rejectsInvalidFingerprintAndMismatchedSecret() {
        val dir = Files.createTempDirectory("bs-kstore-bad").toFile()
        val store = KeyfileStore(dir, MemoryAesSeal())
        val sec = Crypto.random(32)
        val fp = Keyfiles.fp(sec)

        try {
            store.save("../escape0123456", sec)
            fail("expected VaultException for invalid fp")
        } catch (_: VaultException) {}

        try {
            store.save(fp, Crypto.random(32))
            fail("expected VaultException for mismatched secret")
        } catch (_: VaultException) {}

        try {
            store.save(fp, ByteArray(16))
            fail("expected VaultException for wrong secret size")
        } catch (_: VaultException) {}

        assertFalse(store.has("../escape0123456"))
        assertNull(store.load("../escape0123456"))
        assertFalse(store.forget("../escape0123456"))
    }
}
