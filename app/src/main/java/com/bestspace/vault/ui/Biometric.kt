package com.bestspace.vault.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import kotlin.coroutines.resume

/**
 * ลายนิ้วมือ – ใช้เฉพาะหน้า PIN ของโน้ต (ยืนยันตัวแทนการพิมพ์ PIN)
 * ไม่เกี่ยวกับตู้เลย: ไม่เก็บ/ไม่ห่อ/ไม่อ่านกุญแจของตู้ ตู้จริงเปิดได้ด้วยรหัสผ่าน (+ไฟล์กุญแจ) เท่านั้น
 */
object Bio {
    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** v1.0.71 เคยมีตัวเลือก "เปิดตู้ด้วยลายนิ้วมือ" – ลบสิ่งที่อาจค้างไว้ (กุญแจตู้ที่ห่อไว้ + กุญแจใน Keystore) */
    fun purgeVaultLeftovers(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences("bio", Context.MODE_PRIVATE)
            if (p.all.isNotEmpty()) p.edit().clear().commit()
            ctx.deleteSharedPreferences("bio")
        } catch (_: Exception) {}
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias("bestspace_bio_vault_v1")) ks.deleteEntry("bestspace_bio_vault_v1")
        } catch (_: Exception) {}
    }

    sealed class Result {
        object Ok : Result()
        object Cancelled : Result()
        class Error(val msg: String) : Result()
    }

    suspend fun prompt(act: FragmentActivity, title: String, subtitle: String?, negative: String): Result =
        suspendCancellableCoroutine { cont ->
            val p = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(Result.Ok)
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (!cont.isActive) return
                    cont.resume(when (code) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> Result.Cancelled
                        else -> Result.Error(msg.toString())
                    })
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setNegativeButtonText(negative)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            p.authenticate(info)
            cont.invokeOnCancellation { try { p.cancelAuthentication() } catch (_: Exception) {} }
        }
}
