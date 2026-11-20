package com.bestspace.vault.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity(), Bridge {
    private val model: VaultModel by viewModels()

    private val openMulti = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.pickResult(it) }
    private val openOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { model.pickResult(listOfNotNull(it)) }
    private val openTreeL = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { model.pickResult(listOfNotNull(it)) }
    private val createL = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        model.pickResult(listOfNotNull(it))
    }
    private val legacyPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.suppressLock = false
        model.recheckAccess()
    }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { model.clipOurs = true }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) model.onScreenOff()
        }
    }

    private fun checkIdleLock() {
        if (model.player?.isPlaying == true || model.busy != null) { model.touch(); return }
        if (model.vault.isUnlocked && System.currentTimeMillis() - model.lastTouch > VaultModel.IDLE_LOCK_MS)
            model.requestLock("ล็อคอัตโนมัติ – ไม่มีการใช้งาน ${VaultModel.IDLE_LOCK_MS / 60000} นาที")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // FLAG_SECURE: ห้ามแคปหน้าจอ/อัดหน้าจอ และภาพในหน้า "แอปล่าสุด" เป็นสีดำ
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Android 12+: ซ่อนหน้าต่างลอยของแอปอื่นขณะแอปนี้อยู่บนจอ (กันแอปอื่นวางหน้าต่างใสทับเพื่อดักการแตะ/รหัสผ่าน)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        // Android 13+: ไม่เก็บภาพหน้าจอของแอปไว้ใช้ในหน้า "แอปล่าสุด" เลย (เสริม FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        model.bridge = this
        Bio.purgeVaultLeftovers(this)       // ตู้จริงห้ามใช้ลายนิ้วมือ – ลบของที่ v1.0.71 อาจเก็บไว้
        // เพิ่งเปิดใหม่หลังล็อค: แสดงเหตุผลที่ล็อค (เช่น ไม่มีการใช้งาน) ที่หน้าใส่รหัส
        intent?.getStringExtra(RestartActivity.EXTRA_MSG)?.let { if (model.unlockMsg == null) model.unlockMsg = it }
        setContent { BestSpaceTheme { App(model, ::requestAccess) } }

        // ล็อคอัตโนมัติเมื่อไม่มีการใช้งาน (ระหว่างเล่นวิดีโอไม่นับ – เหมือนแอปคอม)
        lifecycleScope.launch {
            while (true) {
                delay(5_000)
                checkIdleLock()
            }
        }
    }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            model.suppressLock = true              // ไปหน้าตั้งค่าระบบแล้วกลับมา ไม่ต้องเด้งกลับหน้าโน้ต
            for (i in Storage.accessIntents(this)) {
                try { startActivity(i); return } catch (_: Exception) {}
            }
            model.suppressLock = false
        } else {
            model.suppressLock = true
            try {
                legacyPerm.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            } catch (_: Exception) {
                model.suppressLock = false
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        model.touch()
    }

    override fun onStart() {
        super.onStart()
        model.visible = true
        checkIdleLock()
        model.suppressLock = false
    }

    override fun onResume() {
        super.onResume()
        // ยกเลิกตัวเลือกส่งไฟล์/เปิดแอปอื่น (createChooser แบบ bottom sheet) ไม่ผ่าน onStop/onStart → เคลียร์ที่ onResume ด้วย
        model.suppressLock = false
        checkIdleLock()
        model.recheckAccess()
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onPause() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.removePrimaryClipChangedListener(clipListener)
        super.onPause()
    }

    /** ออกจากแอป / ปิดจอ / สลับแอป → ล็อคทันที (ยกเว้นตอนเปิดหน้าต่างเลือกไฟล์ของระบบ หรือส่งไฟล์ให้แอปอื่น) */
    override fun onStop() {
        super.onStop()
        model.visible = false
        if (!model.suppressLock && !isChangingConfigurations) model.requestLock(null)
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        if (model.bridge === this) model.bridge = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Bridge
    override fun openDocs(mimes: Array<String>, multiple: Boolean) {
        if (multiple) openMulti.launch(mimes) else openOne.launch(mimes)
    }
    override fun openTree() = openTreeL.launch(null)
    override fun createDoc(name: String) = createL.launch(name)
    override fun start(intent: Intent) = startActivity(intent)
}
