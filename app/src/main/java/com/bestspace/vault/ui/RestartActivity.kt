package com.bestspace.vault.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager

/**
 * ล็อคแล้ว "ปิดแอปแล้วเปิดใหม่": หน้าจอโปร่งใสนี้รันในโปรเซสแยก (:restart)
 * ปิดโปรเซสหลักเดิมให้แน่ใจ (หน่วยความจำทั้งหมดคืนระบบ) แล้วเปิดแอปใหม่ในโปรเซสใหม่ที่หน้าโน้ต
 */
class RestartActivity : Activity() {
    companion object {
        const val EXTRA_PID = "bs_old_pid"
        const val EXTRA_MSG = "bs_lock_msg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val old = intent.getIntExtra(EXTRA_PID, -1)
        if (old > 0 && old != Process.myPid()) {
            try { Process.killProcess(old) } catch (_: Exception) {}
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val end = SystemClock.uptimeMillis() + 3000
            while (SystemClock.uptimeMillis() < end && am.runningAppProcesses?.any { it.pid == old } == true) SystemClock.sleep(40)
        }
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_MSG, intent.getStringExtra(EXTRA_MSG)))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        Runtime.getRuntime().exit(0)
    }
}
