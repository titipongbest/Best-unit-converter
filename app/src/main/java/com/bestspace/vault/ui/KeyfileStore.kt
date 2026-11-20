package com.bestspace.vault.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bestspace.vault.core.Crypto
import com.bestspace.vault.core.Keyfiles
import com.bestspace.vault.core.Vault
import com.bestspace.vault.core.VaultException
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface KeySeal {
    fun encrypt(aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray>   // (iv, ct)
    fun decrypt(aad: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray?
}

object AndroidKeyStoreSeal : KeySeal {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "bestspace_keyfile_store_v1"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    override fun encrypt(aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plain)
        return cipher.iv.copyOf() to ct
    }

    override fun decrypt(aad: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = ks.getKey(ALIAS, null) as? SecretKey ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        cipher.doFinal(ct)
    } catch (_: Exception) {
        null
    }
}

/**
 * เก็บไฟล์กุญแจที่ผู้ใช้เลือกเก็บไว้ในพื้นที่ส่วนตัวของแอป (filesDir/keys/<fp>.k)
 * เข้ารหัสด้วย Android Keystore (AES-256-GCM) เพื่อให้ผูกกับอุปกรณ์นี้ แอปอื่นเข้าไม่ถึง
 * และยังต้องใช้รหัสผ่านของตู้เสมอ (เก็บเฉพาะเมื่อผู้ใช้เลือกบันทึกไว้ และลบออกได้ทุกเมื่อ)
 */
class KeyfileStore(private val dir: File, private val seal: KeySeal = AndroidKeyStoreSeal) {
    constructor(ctx: Context) : this(File(ctx.filesDir, "keys"), AndroidKeyStoreSeal)

    companion object {
        private val MAGIC = "BSK1".toByteArray(Charsets.US_ASCII)
        private val VALID_FP = Regex("^[0-9a-f]{16}$")
        private fun aadFor(fp: String): ByteArray = "bestspace-app-keyfile-v1:$fp".toByteArray(Charsets.UTF_8)
    }

    private fun fileOf(fp: String): File? = if (VALID_FP.matches(fp)) File(dir, "$fp.k") else null

    fun has(fp: String): Boolean = fileOf(fp)?.isFile == true

    fun save(fp: String, secret: ByteArray) {
        val f = fileOf(fp) ?: throw VaultException("รหัสลายนิ้วมือกุญแจไม่ถูกต้อง")
        if (secret.size != 32 || Keyfiles.fp(secret) != fp) throw VaultException("ข้อมูลไฟล์กุญแจไม่ตรงกัน")
        val (iv, ct) = try {
            seal.encrypt(aadFor(fp), secret)
        } catch (e: Exception) {
            throw VaultException("เข้ารหัสเก็บไฟล์กุญแจในเครื่องไม่ได้: ${e.message}")
        }
        if (iv.isEmpty() || iv.size > 32) throw VaultException("IV จากระบบความปลอดภัยไม่ถูกต้อง")
        val blob = ByteArray(MAGIC.size + 1 + iv.size + ct.size)
        System.arraycopy(MAGIC, 0, blob, 0, MAGIC.size)
        blob[MAGIC.size] = iv.size.toByte()
        System.arraycopy(iv, 0, blob, MAGIC.size + 1, iv.size)
        System.arraycopy(ct, 0, blob, MAGIC.size + 1 + iv.size, ct.size)
        dir.mkdirs()
        Vault.replaceSecure(f, blob)
        Vault.fsyncDir(dir)
    }

    fun load(fp: String): ByteArray? {
        val f = fileOf(fp) ?: return null
        if (!f.isFile) return null
        val len = f.length()
        if (len < 32 || len > 4096) return null
        val raw = try { f.readBytes() } catch (_: Exception) { return null }
        if (raw.size < MAGIC.size + 2 || !raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val ivLen = raw[MAGIC.size].toInt() and 0xff
        val ctOff = MAGIC.size + 1 + ivLen
        if (ivLen !in 12..32 || ctOff >= raw.size) return null
        val iv = raw.copyOfRange(MAGIC.size + 1, ctOff)
        val ct = raw.copyOfRange(ctOff, raw.size)
        val plain = seal.decrypt(aadFor(fp), iv, ct) ?: return null
        if (plain.size != 32 || Keyfiles.fp(plain) != fp) {
            Crypto.wipe(plain)
            return null
        }
        return plain
    }

    fun forget(fp: String): Boolean {
        val f = fileOf(fp) ?: return false
        if (!f.exists()) return false
        return Vault.secureDelete(f)
    }
}
