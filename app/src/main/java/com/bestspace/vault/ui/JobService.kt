package com.bestspace.vault.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bestspace.vault.R

/**
 * บริการเบื้องหน้า (foreground service) ระหว่างนำเข้า/ส่งออกไฟล์จำนวนมาก
 * – ระบบไม่ฆ่าแอปทิ้งเมื่อสลับไปแอปอื่น/ปิดจอ  – ถือ wake lock ให้ CPU ทำงานต่อขณะจอดับ
 * การแจ้งเตือนไม่มีชื่อไฟล์ และซ่อนบนหน้าจอล็อค
 */
class JobService : Service() {
    companion object {
        private const val CHANNEL = "jobs"
        private const val ID = 7301
        @Volatile private var running = false
        @Volatile private var wanted = false

        fun start(ctx: Context) {
            wanted = true
            if (running) return
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, JobService::class.java)) } catch (_: Exception) {}
        }

        /** งานเสร็จ: ถ้าบริการยังไม่ทันเริ่ม ห้าม stopService ตอนนี้ (ระบบจะแครชแอปเพราะยังไม่ได้ startForeground)
         *  → ปล่อยให้บริการเริ่มก่อน แล้วปิดตัวเองใน onStartCommand */
        fun stop(ctx: Context) {
            wanted = false
            if (running) try { ctx.stopService(Intent(ctx, JobService::class.java)) } catch (_: Exception) {}
        }

        fun update(ctx: Context, title: String, text: String, pct: Int) {
            if (!running) return
            try {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build(ctx, title, text, pct))
            } catch (_: Exception) {}
        }

        private fun build(ctx: Context, title: String, text: String, pct: Int) =
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, pct.coerceIn(0, 100), pct < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(android.app.PendingIntent.getActivity(ctx, 0,
                    ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                .build()
    }

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "งานที่กำลังทำ", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            })
        }
        try {
            ServiceCompat.startForeground(this, ID, build(this, "กำลังทำงาน", "อย่าปิดแอปจนกว่าจะเสร็จ", -1),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            running = true
        } catch (_: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!wanted) { stopSelf(); return START_NOT_STICKY }      // งานจบไปก่อนบริการเริ่ม
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BestSpace:job").apply {
                    setReferenceCounted(false); acquire(6 * 3600 * 1000L)
                }
        }
        return START_NOT_STICKY                        // แอปถูกฆ่า = ไม่ต้องเริ่มใหม่ (งานค้างเก็บกวาดด้วย journal)
    }

    /** Android 15: งานประเภท dataSync จำกัดเวลา – หมดเวลาแล้วงานยังทำต่อได้ แค่ไม่มีบริการคุ้มกัน */
    override fun onTimeout(startId: Int, fgsType: Int) { wanted = false; stopSelf() }

    override fun onDestroy() {
        running = false
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
        super.onDestroy()
        if (wanted) start(applicationContext)                     // งานใหม่เริ่มระหว่างที่บริการกำลังปิด
    }
}
