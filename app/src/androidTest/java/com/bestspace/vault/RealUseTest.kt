package com.bestspace.vault

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bestspace.vault.core.Crypto
import com.bestspace.vault.core.Keyfiles
import com.bestspace.vault.core.Vault
import com.bestspace.vault.ui.Ask
import com.bestspace.vault.ui.AskChoice
import com.bestspace.vault.ui.AskConfirm
import com.bestspace.vault.ui.AskCover
import com.bestspace.vault.ui.AskInAppPick
import com.bestspace.vault.ui.AskInfo
import com.bestspace.vault.ui.AskInput
import com.bestspace.vault.ui.AskShareMode
import com.bestspace.vault.ui.Gate
import com.bestspace.vault.ui.InAppPickResult
import com.bestspace.vault.ui.MainActivity
import com.bestspace.vault.ui.PickMode
import com.bestspace.vault.ui.VaultModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * ใช้แอปจริงบน emulator แบบผู้ใช้: หน้าพราง → กด + ค้าง 3 วิ → สร้างตู้ → นำเข้า (ลบต้นฉบับ) → ดูรูป/วิดีโอ →
 * แก้ข้อความ + คัดลอก → ข้อความใหม่ → ส่งออก → แชร์ .bsx → เปิดด้วยแอปอื่น → ล็อค
 * แล้วตรวจสิ่งที่หลงเหลือ + เก็บหน่วยความจำของโปรเซสตอนล็อคไว้ให้ CI ไล่หา (ตัวทดสอบไม่เคยอ่านเนื้อหาไฟล์ลับเอง)
 *
 * ไฟล์ลับต้นฉบับถูกสร้างบนเครื่อง CI แล้ว adb push ไว้ที่ /sdcard/Download ก่อนรัน – ชื่อ/แฮชส่งมาทาง argument
 */
@RunWith(AndroidJUnit4::class)
class RealUseTest {
    companion object {
        private val args get() = InstrumentationRegistry.getArguments()
        private val ins get() = InstrumentationRegistry.getInstrumentation()
        val out = ArrayList<String>()
        private val thumbsBefore = HashSet<String>()

        fun sh(cmd: String): String =
            ins.uiAutomation.executeShellCommand(cmd).let { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
            }

        val sd: File get() = Environment.getExternalStorageDirectory()
        fun thumbFiles(): Set<String> = listOf("Pictures", "Movies", "DCIM", "Music", "Download")
            .flatMap { File(sd, "$it/.thumbnails").listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && it.name != ".database_uuid" }.map { it.absolutePath }.toSet()

        @BeforeClass @JvmStatic fun prepare() {
            VaultModel.restartOnLock = false      // ตรวจต่อในโปรเซสเดิมหลังล็อค (ปกติแอปจะปิดแล้วเปิดใหม่)
            val pkg = ins.targetContext.packageName
            sh("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
            sh("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            File(sd, "BSExport").mkdirs(); File(sd, "BSShare").mkdirs()
            // ให้ระบบรู้จักไฟล์ต้นฉบับ + สร้างภาพย่อของระบบไว้ (เหมือนรูปที่เคยเปิดดูในแกลเลอรี)
            val src = listOf(args.getString("img")!!, args.getString("vid")!!, args.getString("txt")!!)
                .map { File(sd, "Download/$it").absolutePath }
            val missing = src.filter { !File(it).isFile }
            if (missing.isNotEmpty()) out.add("FAIL ไม่พบไฟล์ต้นฉบับก่อนทดสอบ: " + missing.joinToString { File(it).name })
            val done = java.util.concurrent.CountDownLatch(src.size)
            val uris = java.util.concurrent.ConcurrentHashMap<String, Uri>()
            MediaScannerConnection.scanFile(ins.targetContext, src.toTypedArray(), null) { p, u -> if (u != null) uris[p] = u; done.countDown() }
            done.await(30, java.util.concurrent.TimeUnit.SECONDS)
            for ((p, u) in uris) if (!p.endsWith(".txt")) try {
                ins.targetContext.contentResolver.loadThumbnail(u, Size(256, 256), null).recycle()
            } catch (e: Exception) { out.add("NOTE ภาพย่อระบบของ ${File(p).name} สร้างไม่ได้: ${e.message}") }
            thumbsBefore += thumbFiles()
            out.add("NOTE ภาพย่อของระบบก่อนนำเข้า: ${thumbsBefore.size} ไฟล์")
        }

        @AfterClass @JvmStatic fun report() {
            try { File(sd, "BSResults").mkdirs(); File(sd, "BSResults/results.txt").writeText(out.joinToString("\n")) } catch (_: Exception) {}
        }
    }

    // เปิดแอปแบบปกติ (นาฬิกาจริง) แล้วแตะหน้าจอจริงผ่าน UiAutomator
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var act: MainActivity
    private val dev get() = UiDevice.getInstance(ins)
    private lateinit var model: VaultModel
    private val asked = ArrayList<String>()
    @Volatile private var answering = true
    @Volatile private var holdPicker = false
    private val M get() = args.getString("M")!!

    private fun check(name: String, ok: Boolean, detail: String = "") {
        out.add((if (ok) "PASS " else "FAIL ") + name + if (!ok && detail.isNotEmpty()) " – $detail" else "")
        report()                                  // บันทึกทีละข้อ – พังกลางทางก็ยังเห็นผลที่ผ่านมา
    }

    /** รันบน main thread – ข้อผิดพลาดโยนกลับมาที่ตัวทดสอบ (ไม่ทำให้โปรเซสล่ม) */
    private fun <T> main(block: () -> T): T {
        var r: T? = null; var err: Throwable? = null
        ins.runOnMainSync { try { r = block() } catch (t: Throwable) { err = t } }
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST") return r as T
    }

    private fun pause(ms: Long) = Thread.sleep(ms)

    /** แตะค้างที่จุด (x,y) นาน ms มิลลิวินาทีพอดี (ส่งสัญญาณนิ้วกด/ยกเหมือนนิ้วจริง) */
    private fun hold(x: Int, y: Int, ms: Long) {
        val t0 = android.os.SystemClock.uptimeMillis()
        fun ev(a: Int, t: Long) = android.view.MotionEvent.obtain(t0, t, a, x.toFloat(), y.toFloat(), 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }
        ins.uiAutomation.injectInputEvent(ev(android.view.MotionEvent.ACTION_DOWN, t0), true)
        Thread.sleep(ms)
        ins.uiAutomation.injectInputEvent(ev(android.view.MotionEvent.ACTION_UP, android.os.SystemClock.uptimeMillis()), true)
    }

    /** ข้อความบนหน้าจอตอนนี้ (ไว้ดูว่าหน้าไหนเปิดอยู่) */
    private fun screen(): String = try {
        val o = java.io.ByteArrayOutputStream(); dev.dumpWindowHierarchy(o)
        Regex("content-desc=\"([^\"]+)\"").findAll(o.toString()).map { it.groupValues[1] }.distinct().take(25).joinToString(" | ")
    } catch (e: Exception) { "?" }

    private fun waitFor(label: String, ms: Long = 60_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (main(cond)) return true
            pause(100)
        }
        out.add("FAIL รอไม่สำเร็จ: $label (busy=${main { model.busy }} ask=${main { model.ask?.javaClass?.simpleName }} error=${main { model.error }})")
        return false
    }
    private fun idle(ms: Long = 120_000): Boolean {
        var stableSince = 0L
        return waitFor("งานเสร็จ", ms) {
            val free = model.busy == null && model.ask == null
            val now = System.currentTimeMillis()
            if (!free) stableSince = 0L else if (stableSince == 0L) stableSince = now
            free && now - stableSince > 800
        }
    }

    /** ตอบหน้าต่างถาม-ตอบแบบผู้ใช้ (ปุ่มเดียวกับที่ผู้ใช้กด) */
    @Suppress("UNCHECKED_CAST")
    private fun answer(a: Ask<*>) {
        val r = a.result as CompletableDeferred<Any?>
        when (a) {
            is AskConfirm -> { asked.add("ยืนยัน: ${a.title}"); r.complete(a.title != "สำรองไฟล์กุญแจ") }
            is AskChoice -> { asked.add("เลือก: ${a.title}"); r.complete(a.title == "ลบต้นฉบับ?") }
            is AskInput -> { asked.add("กรอก: ${a.title}"); r.complete(if (a.password) "share-pass-777" else "note_${M}N.txt") }
            is AskShareMode -> r.complete("folder")
            is AskInfo -> { asked.add("แจ้ง: ${a.title}: ${a.text.take(160).replace('\n', ' ')}"); r.complete(Unit) }
            is AskCover -> r.complete("")
            is AskInAppPick -> if (!holdPicker) r.complete(InAppPickResult.UseSystemPicker)
            else -> r.complete(null)
        }
    }

    private fun docUri(rel: String): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
    private fun treeUri(rel: String): Uri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$rel")
    private fun pickResult(vararg uris: Uri) = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply {
        data = uris.first()
        if (uris.size > 1) clipData = ClipData.newRawUri("", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })

    private fun findEdit(): EditText? = main {
        fun walk(v: View): EditText? {
            if (v is EditText && v.isShown) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            return null
        }
        walk(act.window.decorView)
    }

    private fun fidOf(pred: (String) -> Boolean): String? = main {
        synchronized(model.vault) { model.vault.files().entries.firstOrNull { pred((it.value as Map<*, *>)["name"].toString()) }?.key }
    }

    private fun sha(f: File) = Crypto.hex(MessageDigest.getInstance("SHA-256").digest(f.readBytes()))

    @Test fun realUseAndLeaks() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { act = it; model = ViewModelProvider(it)[VaultModel::class.java] }
        val responder = Thread {
            while (answering) {
                try { ins.runOnMainSync { model.ask?.let { if (!it.result.isCompleted) answer(it) } } } catch (_: Throwable) {}
                Thread.sleep(120)
            }
        }.also { it.isDaemon = true; it.start() }
        Intents.init()
        try { run() } catch (t: Throwable) {
            val at = t.stackTrace.firstOrNull { it.className.startsWith("com.bestspace") }
            out.add("FAIL ทดสอบหยุดกลางทาง: ${t.javaClass.simpleName}: ${t.message?.take(300)} @ ${at?.fileName}:${at?.lineNumber}")
        } finally {
            Intents.release(); answering = false
            runCatching { scenario.close() }
            out.add("NOTE หน้าต่างที่ตอบ: " + asked.joinToString(" | "))
        }
        assertTrue(out.filter { it.startsWith("FAIL") }.joinToString("\n"), out.none { it.startsWith("FAIL") })
    }

    private fun run() {
        val img = args.getString("img")!!; val vid = args.getString("vid")!!; val txt = args.getString("txt")!!
        // ---------------- 1) หน้าพราง (แอปแปลงหน่วย Unit Converter) – แตะจอจริง
        val title = dev.wait(Until.hasObject(By.text("Unit Converter")), 10_000) == true
        check("เปิดแอปเจอหน้า Unit Converter (หน้าพราง) ก่อน", main { model.decoy } && title)
        val swapBtn = dev.wait(Until.findObject(By.desc("สลับหน่วย")), 5_000)
        check("มีปุ่มสลับหน่วย (⇄) ในหน้า Unit Converter", swapBtn != null)
        if (swapBtn == null) return
        val c = swapBtn.visibleCenter
        swapBtn.click(); pause(400)
        check("แตะ ⇄ สั้นๆ = สลับหน่วยปกติ (ไม่เข้าตู้)", main { model.decoy })
        hold(c.x, c.y, 1200)                                 // กดค้าง 1.2 วิ แล้วปล่อย
        pause(600)
        check("กด ⇄ ค้างไม่ถึง 2.5 วิ ยังไม่เข้าตู้", main { model.decoy })
        hold(c.x, c.y, 3200)                                 // กดค้าง 3.2 วิ
        waitFor("เข้าหน้าตู้", 8_000) { !model.decoy }
        check("กด ⇄ ค้าง 2.5–3 วิ = เข้าหน้าตู้", !main { model.decoy }, "gate=${main { model.gate }}")
        check("ยังไม่มีตู้ → หน้าสร้างตู้", main { model.gate } == Gate.SETUP, "gate=${main { model.gate }}")

        // ---------------- 2) สร้างตู้ (มีไฟล์กุญแจ = ค่าเริ่มต้น)
        val pw = "Pw-" + Crypto.hex(Crypto.random(6))
        main { model.create(pw.toCharArray(), true) }
        waitFor("สร้างตู้", 90_000) { model.gate == Gate.MAIN && model.busy == null && model.ask == null }
        val root = main { model.vault.root }
        check("สร้างตู้ที่ /sdcard/.bestspace + .nomedia", root.absolutePath.endsWith("/.bestspace"))

        // ---------------- 2.5) ตัวเลือกสื่อในแอป (ไม่ออกจากแอป → ย่อแอป/ปิดจอยังล็อคปกติ)
        holdPicker = true
        main { model.addMedia() }
        val pickerShown = waitFor("ตัวเลือกสื่อในแอป", 5_000) { model.ask is AskInAppPick }
        val pickerUi = dev.wait(Until.hasObject(By.textContains("เลือกรูป")), 5_000) == true
        check("เปิด In-App Picker โดยไม่ปิดระบบล็อค (!suppressLock)", pickerShown && pickerUi && !main { model.suppressLock },
            "ask=${main { model.ask?.javaClass?.simpleName }} suppress=${main { model.suppressLock }}")
        dev.findObject(By.desc("กลับ"))?.click()
        pause(400)
        main { (model.ask as? AskInAppPick)?.result?.complete(null) }
        holdPicker = false
        idle()

        // ---------------- 3) นำเข้าไฟล์ (ลบต้นฉบับ) ผ่านหน้าต่างเลือกไฟล์ของระบบ (fallback จาก In-App Picker)
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            .respondWith(pickResult(docUri("Download/$img"), docUri("Download/$vid"), docUri("Download/$txt")))
        main { model.addFiles() }
        pause(500); idle()
        val n = main { synchronized(model.vault) { model.vault.files().size } }
        check("นำเข้าครบ 3 ไฟล์", n == 3, "ได้ $n")
        val gone = listOf(img, vid, txt).filter { File(sd, "Download/$it").exists() }
        check("ต้นฉบับถูกลบ", gone.isEmpty(), gone.joinToString())
        val leftThumbs = thumbsBefore intersect thumbFiles()
        check("ภาพย่อของระบบของต้นฉบับถูกลบ", leftThumbs.isEmpty(), leftThumbs.joinToString())
        val ms = ins.targetContext.contentResolver.query(MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("%${M}N%"), null)
            ?.use { it.count } ?: -1
        check("ไม่มีชื่อไฟล์ลับค้างในฐานข้อมูลสื่อของระบบ (MediaStore)", ms == 0, "พบ $ms แถว")

        // ---------------- 4) ดูรูป / เล่นวิดีโอ
        if (n < 3) return
        pause(2500)          // แกลเลอรีสร้างภาพย่อ
        val fImg = fidOf { it == img }!!; val fVid = fidOf { it == vid }!!; val fTxt = fidOf { it == txt }!!
        main { model.openViewer(fImg) }; pause(2000); main { model.closeViewer() }; idle(); pause(500)
        main { model.openViewer(fVid) }
        dev.wait(Until.findObject(By.desc("เล่น")), 5_000)?.let { pause(200); it.click() }
        pause(3500)
        check("เล่นวิดีโอในแอปได้", main { model.player != null }, "error=${main { model.error }}")
        main { model.closeViewer() }; idle(); pause(500)

        // ---------------- 5) แก้ข้อความ + คัดลอก
        main { model.openViewer(fTxt) }
        waitFor("เปิดข้อความ", 20_000) { model.textSession != null }
        pause(800)
        val ed = findEdit()
        check("เปิดไฟล์ข้อความในแอปได้", ed != null)
        if (ed != null) {
            main { ed.append("\nEDIT${M}E") }
            pause(300)
            check("บันทึกข้อความที่แก้", main { model.saveText(model.textSession!!, true) })
            idle()
            main { ed.setSelection(0, ed.text.indexOf('\n').coerceAtLeast(1)); ed.onTextContextMenuItem(android.R.id.copy) }
            pause(300)
        }
        main { model.closeViewer() }; idle(); pause(500)

        // ---------------- 6) ข้อความใหม่
        main { model.newText() }
        waitFor("ข้อความใหม่", 20_000) { model.textSession != null }
        pause(800)
        findEdit()?.let { e -> main { e.append("NEW${M}W") }; pause(300); main { model.saveText(model.textSession!!, true) } }
        idle(); main { model.closeViewer() }; idle(); pause(500)

        // ---------------- 7) ส่งออกรูป (ตั้งใจ) / 8) แชร์ .bsx / 9) เปิดด้วยแอปอื่น
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE)).respondWith(pickResult(treeUri("BSExport")))
        main { model.exportItems(listOf(fImg)) }; pause(500); idle()
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE)).respondWith(pickResult(treeUri("BSShare")))
        main { model.shareItems(listOf(fVid)) }; pause(500); idle()
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        val fTxtNow = fidOf { it == txt }!!                   // บันทึกข้อความแล้วได้รหัสไฟล์ใหม่
        main { model.openExternal(fTxtNow) }; pause(500); idle()
        val openDir = main { model.openDir }
        check("เปิดด้วยแอปอื่น: ถอดรหัสชั่วคราวในแคชของแอป", openDir.walkTopDown().any { it.isFile })

        // ---------------- 10) ล็อค
        val cm = ins.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // ดูแค่ว่ามีข้อความในคลิปบอร์ด (ไม่อ่านเนื้อหา – ตัวทดสอบต้องไม่ถือข้อมูลลับเอง)
        val clipBefore = main { cm.hasPrimaryClip() && cm.primaryClipDescription?.hasMimeType("text/*") == true }
        check("คัดลอกข้อความได้ระหว่างปลดล็อค", clipBefore)
        main { model.requestLock("ล็อคแล้ว") }
        waitFor("ล็อค", 30_000) { model.gate == Gate.UNLOCK && model.busy == null }
        pause(1500)
        check("ล็อคแล้วกลับไปหน้าโน้ต (หน้าพราง)", main { model.decoy })
        check("ล็อคแล้วสั่งปิดแอป + เปิดใหม่ (ล้างหน่วยความจำ)", main { model.restarts } > 0)
        val clipAfter = main { cm.hasPrimaryClip() && (cm.primaryClip?.getItemAt(0)?.text?.length ?: 0) > 0 }
        check("ล็อคแล้วคลิปบอร์ดถูกล้าง", !clipAfter)
        val tmpLeft = listOf(main { model.openDir }, main { model.shareDir }).flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() }
        check("ล็อคแล้วไฟล์ชั่วคราวในแคชถูกลบ", tmpLeft.isEmpty(), tmpLeft.joinToString { it.name })
        val cache = ins.targetContext.cacheDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(ins.targetContext.cacheDir).path }.toList()
        out.add("NOTE ไฟล์ในแคชของแอปหลังล็อค: ${cache.size} " + cache.take(10).joinToString())
        check("ล็อคแล้ว master key ไม่อยู่ในตู้ที่แอปถือ", !main { model.vault.isUnlocked })

        // ---------------- 11) เก็บหน่วยความจำของโปรเซสตอนล็อค (CI ไล่หาข้อมูลลับ/กุญแจ)
        repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); pause(300) }
        // ไฟล์ส่วนตัวของแอป (prefs, โน้ตหน้าพราง, journal, แคช …) ต้องไม่มีชื่อ/เนื้อหาลับ
        val data = ins.targetContext.dataDir
        val mk = M.toByteArray(); val mk16 = M.toByteArray(Charsets.UTF_16LE)
        fun has(b: ByteArray, n: ByteArray): Boolean { outer@ for (i in 0..b.size - n.size) { for (j in n.indices) if (b[i + j] != n[j]) continue@outer; return true }; return false }
        val priv = data.walkTopDown().filter { it.isFile && it.length() < (64L shl 20) }
            .filter { f -> runCatching { f.readBytes().let { has(it, mk) || has(it, mk16) } }.getOrDefault(false) }
            .map { it.relativeTo(data).path }.toList()
        check("พื้นที่ส่วนตัวของแอปไม่มีชื่อ/เนื้อหาลับ", priv.isEmpty(), priv.joinToString())
        val dir = File(sd, "BSResults").also { it.mkdirs() }
        val bytes = dumpMemory(File(dir, "mem.bin"))
        out.add("NOTE เก็บหน่วยความจำ ${bytes / (1 shl 20)} MiB")

        // ---------------- 12) ตรวจความถูกต้อง (หลังเก็บหน่วยความจำแล้ว – ตอนนี้ตัวทดสอบถึงจะถอดรหัสอ่านเอง)
        val v = Vault(root, sd).also { it.unlock(pw) }
        val master = Vault::class.java.getDeclaredField("master").also { it.isAccessible = true }.get(v) as ByteArray
        File(dir, "keys.txt").writeText(Crypto.hex(master))
        fun h(fid: String) = Crypto.hex(MessageDigest.getInstance("SHA-256").digest(v.readBytes(fid)))
        check("รูปในตู้ตรงต้นฉบับทุกไบต์", h(fImg) == args.getString("hImg"))
        check("วิดีโอในตู้ตรงต้นฉบับทุกไบต์", h(fVid) == args.getString("hVid"))
        val t = String(v.readBytes(v.files().keys.first { (v.files()[it] as Map<*, *>)["name"] == txt }))
        check("ข้อความที่แก้ถูกบันทึก", t.contains("CONTENT${M}T") && t.contains("EDIT${M}E"))
        val nn = v.files().values.map { (it as Map<*, *>)["name"].toString() }
        check("ข้อความใหม่ถูกบันทึก", nn.any { it.startsWith("note_") } &&
            String(v.readBytes(v.files().keys.first { (v.files()[it] as Map<*, *>)["name"].toString().startsWith("note_") })).contains("NEW${M}W"))
        val ex = File(sd, "BSExport").listFiles()!!.toList()
        check("ส่งออก: ไฟล์ตรงต้นฉบับ ไม่มี .part ค้าง", ex.size == 1 && ex[0].name == img && sha(ex[0]) == args.getString("hImg"),
            ex.joinToString { it.name })
        val bsx = File(sd, "BSShare").listFiles()!!.toList()
        check("แชร์: .bsx 1 ไฟล์ ชื่อสุ่ม", bsx.size == 1 && !bsx[0].name.contains(M) && bsx[0].name.endsWith(".bsx"), bsx.joinToString { it.name })
        if (bsx.size == 1) {
            val v2 = Vault(File(ins.targetContext.cacheDir, "chk/.bestspace"), null).also { it.create("x-pass-1234", false) }
            val id = bsx[0].inputStream().use { v2.importShare(it, bsx[0].name, "share-pass-777", null) }
            check("เปิด .bsx ด้วยรหัสแชร์ได้ตรงต้นฉบับ", Crypto.hex(MessageDigest.getInstance("SHA-256").digest(v2.readBytes(id))) == args.getString("hVid"))
            v2.lock(); File(ins.targetContext.cacheDir, "chk").deleteRecursively()
        }
        val idx = File(root, "index.enc").readBytes()
        check("ดัชนีในตู้ไม่มีชื่อไฟล์แบบอ่านออก", !String(idx, Charsets.ISO_8859_1).contains("${M}N"))
        check("มี .nomedia ในตู้ (แกลเลอรีไม่สแกน)", File(root, ".nomedia").exists())
        v.lock()

        // ---------------- 13) กุญแจจากคลาวด์: เก็บในแอป (เข้ารหัส Android Keystore) + ปุ่มลืมกุญแจที่จำไว้
        val extKey = File(sd, "bestspace.key")
        val cloudDir = File(ins.targetContext.cacheDir, "share").apply { mkdirs() }
        val cloudKey = File(cloudDir, "cloud.key")
        try {
            val sec = Keyfiles.read(extKey)!!
            val fp = Keyfiles.fp(sec)
            extKey.copyTo(cloudKey, overwrite = true)
            check("ลบไฟล์กุญแจภายนอกชั่วคราว", extKey.delete())
            main { model.revealVault() }
            waitFor("หน้าใส่รหัส", 10_000) { !model.decoy && model.gate == Gate.UNLOCK }
            val cloudUri = FileProvider.getUriForFile(ins.targetContext, "${ins.targetContext.packageName}.files", cloudKey)
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(pickResult(cloudUri))
            main { model.importKeyfile() }
            pause(400); idle()
            val storedBlob = File(ins.targetContext.filesDir, "keys/$fp.k")
            val blobClean = storedBlob.isFile && storedBlob.readBytes().let { raw ->
                !String(raw, Charsets.ISO_8859_1).contains(Keyfiles.MAGIC) &&
                    !raw.toList().windowed(sec.size).any { it == sec.toList() }
            }
            Crypto.wipe(sec)
            check("เก็บไฟล์กุญแจในแอป (เข้ารหัส Keystore)", main { model.appKeys.has(fp) && model.rememberedKeyMode == "app" } && blobClean)
            main { model.unlock(pw.toCharArray()) }
            waitFor("ปลดล็อคด้วยกุญแจในแอป", 60_000) { model.gate == Gate.MAIN && model.busy == null && model.ask == null }
            check("ปลดล็อคได้โดยไม่มี bestspace.key นอกแอป", main { model.gate } == Gate.MAIN && main { model.vault.isUnlocked })
            main { model.requestLock("ล็อคแล้ว") }
            waitFor("ล็อคหลังเปิดด้วยกุญแจในแอป", 30_000) { model.gate == Gate.UNLOCK && model.busy == null }
            main { model.revealVault() }
            waitFor("แสดงปุ่มลืมกุญแจ", 10_000) { !model.decoy && model.rememberedKeyMode == "app" }
            val forgetBtn = dev.wait(Until.findObject(By.text("ลืมกุญแจที่จำไว้")), 5_000)
            check("มีปุ่มลืมกุญแจที่จำไว้ในหน้าปลดล็อค (พอดีจอไม่ต้องเลื่อน)", forgetBtn != null)
            forgetBtn?.click()
            pause(400); idle()
            check("ลืมกุญแจที่จำไว้: ลบไฟล์ในแอปออกเรียบร้อย", main { !model.appKeys.has(fp) && model.rememberedKeyMode == null && model.keyMissing })
        } finally {
            cloudKey.delete()
        }
    }

    /** เขียนหน้าหน่วยความจำ (ไม่ใช่ไฟล์โปรแกรม/ไลบรารี) ที่ไม่ว่างเปล่าทั้งหมดของโปรเซสนี้ลงไฟล์ */
    private fun dumpMemory(dst: File): Long {
        var total = 0L
        val page = ByteArray(4096)
        RandomAccessFile("/proc/self/mem", "r").use { mem ->
            FileOutputStream(dst).buffered(1 shl 20).use { o ->
                for (line in File("/proc/self/maps").readLines()) {
                    val p = line.trim().split(Regex("\\s+"), 6)
                    if (p.size < 5 || p[1][0] != 'r') continue
                    val path = p.getOrNull(5) ?: ""
                    val anon = path.isEmpty() || path.startsWith("[anon") || path == "[heap]" || path.startsWith("[stack") ||
                        path.startsWith("/dev/ashmem") || path.startsWith("/memfd") || path.contains("dalvik")
                    if (!anon) continue
                    val (lo, hi) = p[0].split("-").map { java.lang.Long.parseUnsignedLong(it, 16) }
                    if (hi - lo > (3L shl 30)) continue
                    var a = lo
                    while (a < hi) {
                        try {
                            mem.seek(a); mem.readFully(page)
                            if (page.any { it != 0.toByte() }) { o.write(page); total += 4096 }
                        } catch (_: Exception) {}
                        a += 4096
                    }
                }
            }
        }
        return total
    }
}
