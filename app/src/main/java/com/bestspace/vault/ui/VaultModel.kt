package com.bestspace.vault.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.bestspace.vault.core.ConflictReport
import com.bestspace.vault.core.Crypto
import com.bestspace.vault.core.GenStore
import com.bestspace.vault.core.Journal
import com.bestspace.vault.core.KeyfileMissing
import com.bestspace.vault.core.Keyfiles
import com.bestspace.vault.core.Lockout
import com.bestspace.vault.core.Mime
import com.bestspace.vault.core.RemoteRekey
import com.bestspace.vault.core.TextCodec
import com.bestspace.vault.core.Vault
import com.bestspace.vault.core.VaultException
import com.bestspace.vault.core.WrongPassword
import com.bestspace.vault.core.asDouble
import com.bestspace.vault.core.asLong
import com.bestspace.vault.core.asMap
import com.bestspace.vault.core.asStr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class Gate { PERMISSION, SETUP, UNLOCK, MAIN }

data class Item(
    val id: String, val name: String, val isDir: Boolean,
    val kind: String = "other", val mime: String = "", val size: Long = 0, val added: Double = 0.0,
    val count: Int = 0, val cover: String? = null, val origin: String? = null,
    val pos: Double? = null, val pinned: Boolean = false,
) {
    val audio get() = !isDir && Mime.isAudio(name, mime)
    val text get() = !isDir && Mime.isText(name, mime)
    val media get() = kind == "image" || kind == "video"
}

data class Listing(
    val items: List<Item> = emptyList(), val counts: Map<String, Int> = emptyMap(),
    val files: Int = 0, val dirs: Int = 0, val bytes: Long = 0,
    val crumbs: List<Pair<String?, String>> = listOf(null to "ตู้ของฉัน"), val searching: Boolean = false,
)

class Thumb(val image: ImageBitmap, val bitmap: Bitmap, val duration: Double?)

/** keys = id ตอนเปิด (คงที่แม้บันทึกไฟล์ข้อความแล้วได้ id ใหม่ → หน้าไม่ถูกสร้างใหม่ เคอร์เซอร์ไม่หาย) */
data class ViewerState(val ids: List<String>, val start: Int, val focusText: Boolean = false, val keys: List<String> = ids)

/** ไฟล์ข้อความที่เปิดแก้อยู่ (ไว้บันทึกอัตโนมัติก่อนล็อค) */
class TextSession(var fid: String, var enc: String, val crlf: Boolean, val exact: Boolean, val name: String) {
    var dirty by mutableStateOf(false)
    var getText: (() -> String)? = null
}

// ------------------------------------------------------------------ คำถาม/หน้าต่างที่รอคำตอบได้ (suspend)
sealed class Ask<T> { val result = CompletableDeferred<T>() }
class AskConfirm(val title: String, val text: String, val yes: String = "ตกลง", val no: String? = "ยกเลิก",
                 val danger: Boolean = false) : Ask<Boolean>()
class AskChoice(val title: String, val text: String, val yes: String, val no: String, val cancel: String = "ยกเลิก") : Ask<Boolean?>()
class AskInput(val title: String, val label: String, val initial: String = "", val password: Boolean = false,
               val hint: String = "") : Ask<String?>()
class AskInfo(val title: String, val text: String) : Ask<Unit>()
class AskMove(val exclude: Set<String>) : Ask<Pair<Boolean, String?>>()       // (ตกลงไหม, โฟลเดอร์ปลายทาง)
class AskClear : Ask<Set<String>?>()
class AskShareMode(val count: Int) : Ask<String?>()                           // "folder" / "apps"
/** เลือกภาพปกโฟลเดอร์ → fid, "" = อัตโนมัติ, null = ยกเลิก */
class AskCover(val folderName: String, val items: List<Item>, val current: String?) : Ask<String?>()

/** ช่องทางเรียกหน้าต่างของระบบจาก MainActivity */
interface Bridge {
    fun openDocs(mimes: Array<String>, multiple: Boolean)
    fun openTree()
    fun createDoc(name: String)
    fun start(intent: Intent)
}

class Reporter(private val m: VaultModel) {
    fun label(s: String) { m.busy = s }
    fun frac(f: Float) { m.progress = f.coerceIn(0f, 1f) }
    /** งานนำเข้า/ส่งออก: เริ่มแถบความคืบหน้าแบบละเอียด (จำนวนไฟล์ + ไบต์ + เวลาที่เหลือ) */
    fun start(title: String, count: Int, units: Long, bytes: Long = units): JobInfo =
        JobInfo(title, count, units, bytes).also { m.jobInfo = it }
}

class VaultModel(private val app: Application) : AndroidViewModel(app) {
    companion object {
        const val MIN_PASSWORD = 6
        const val IDLE_LOCK_MS = 180_000L       // ไม่มีการใช้งานกี่วินาทีแล้วล็อคเอง (เท่าแอปคอม)
        val KINDS = listOf("all" to "ทั้งหมด", "image" to "รูปภาพ", "video" to "วิดีโอ", "doc" to "เอกสาร", "other" to "อื่นๆ")
        val SORTS = listOf("ใหม่สุด", "เก่าสุด", "ชื่อ A–Z", "ขนาดใหญ่สุด", "กำหนดเอง (กดค้างแล้วลาก)")
        const val SORT_CUSTOM = 4
        /** ล็อค = ปิดโปรเซสแล้วเปิดแอปใหม่ (ล้างหน่วยความจำทั้งหมด) – ชุดทดสอบปิดไว้เพื่อตรวจต่อในโปรเซสเดิม */
        @Volatile var restartOnLock = true

        /** เรียงไฟล์ตามโหมด – กำหนดเอง: ไฟล์ที่ยังไม่เคยจัด (เช่น เพิ่งเพิ่ม) อยู่บนสุดแบบใหม่สุดก่อน แล้วตามลำดับที่จัดไว้ */
        fun sortFiles(files: MutableList<Item>, mode: Int) {
            when (mode) {
                1 -> files.sortBy { it.added }
                0 -> files.sortByDescending { it.added }
                2 -> files.sortBy { it.name.lowercase() }
                SORT_CUSTOM -> files.sortWith(compareBy<Item>({ it.pos != null }, { it.pos ?: 0.0 }, { -it.added }))
                else -> files.sortByDescending { it.size }
            }
        }
    }

    private val prefs = app.getSharedPreferences("bs", Context.MODE_PRIVATE)
    /** งานที่กำลังเขียนไฟล์ – ถูกขัดจังหวะ (แอปถูกปิด/แบตหมด) ครั้งหน้าเก็บกวาดให้ */
    val journal = Journal(File(app.filesDir, "jobs.json"))
    /** เลขรุ่นดัชนีที่เครื่องนี้เคยเห็น – ตรวจว่าตู้ถูกย้อนกลับเป็นเวอร์ชันเก่า (เช่น จากคลาวด์) */
    private val gens = GenStore(File(app.filesDir, "seen.json"))
    private var cleanedOnStart = 0
    val deviceId: String = loadDeviceId()
    var vaultDir by mutableStateOf(File(prefs.getString("vault", null) ?: Storage.defaultVault().path))
        private set
    var vault: Vault = newVault(vaultDir)
        private set

    var gate by mutableStateOf(Gate.PERMISSION)
    /** หน้าพราง (แอปโน้ต) อยู่หน้าสุดเสมอจนกว่าจะกดปุ่ม + ค้าง 2 วิ; ล็อค/ออกจากแอป = กลับมาหน้าพราง */
    var decoy by mutableStateOf(true)
    /** ใส่รหัสล็อคหน้าโน้ตแล้ว (เฉพาะในโปรเซสนี้ – ออกจากแอป/ปิดจอ/ล็อค = ต้องใส่ใหม่) */
    var notesOpen by mutableStateOf(false)
    var busy by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(-1f)
    /** ความคืบหน้าของงานนำเข้า/ส่งออก (null = ไม่มีงานแบบนี้) */
    var jobView by mutableStateOf<JobView?>(null)
    @Volatile var jobInfo: JobInfo? = null
    /** ผู้ใช้กดยกเลิกงานนำเข้า/ส่งออก – งานหยุดที่ไฟล์ถัดไป (ไฟล์ที่ทำเสร็จแล้วคงไว้ ไฟล์ที่ค้างครึ่งทางถูกลบทิ้ง) */
    var cancelRequested by mutableStateOf(false)
        private set
    var blind by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var ask by mutableStateOf<Ask<*>?>(null)
    var unlockMsg by mutableStateOf<String?>(null)
    var waitUntil by mutableLongStateOf(0L)
    var keyMissing by mutableStateOf(false)
    var needsKey by mutableStateOf(false)
        private set
    /** รูปแบบการจำไฟล์กุญแจของตู้ปัจจุบัน: "app" = เก็บในแอป (เข้ารหัส Keystore), "ref" = จำตำแหน่ง/URI, null = ไม่ได้จำไว้ */
    var rememberedKeyMode by mutableStateOf<String?>(null)
        private set
    val appKeys = KeyfileStore(app)
    /** กำลังเปิด "ตู้หลอก" อยู่ (เปิดด้วยรหัสตู้หลอก) – หน้าตาเหมือนตู้จริงทุกอย่าง */
    var fakeSession by mutableStateOf(false)
        private set

    // แกลเลอรี
    var folder by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var searchOpen by mutableStateOf(false)
    var filter by mutableStateOf("all")
    var sort by mutableIntStateOf(0)
    var columns by mutableIntStateOf(3)
    var numpad by mutableStateOf(prefs.getBoolean("numpad", true))   // หน้าปลดล็อค: ปุ่มตัวเลขในแอป แทนคีย์บอร์ดของเครื่อง
    var version by mutableIntStateOf(0)
    var thumbEpoch by mutableIntStateOf(0)       // เพิ่มเมื่อภาพย่อเปลี่ยน (ตั้งภาพปก / ล้างแคช)
    var fsLandscape by mutableStateOf(true)
    var listing by mutableStateOf(Listing())
    val selected = mutableStateListOf<String>()
    var viewer by mutableStateOf<ViewerState?>(null)
    var fullscreen by mutableStateOf(false)

    var textSession by mutableStateOf<TextSession?>(null)
    var player: ExoPlayer? = null
    var bridge: Bridge? = null
    @Volatile var suppressLock = false
    /** หน้าจอแอปแสดงอยู่ (onStart..onStop) – ล็อคตอนอยู่หน้าจอ = เปิดใหม่ทันที, อยู่เบื้องหลัง = ปิดเฉยๆ */
    @Volatile var visible = false
    /** จำนวนครั้งที่สั่งเปิดโปรเซสใหม่ (ใช้ตรวจในชุดทดสอบ) */
    @Volatile var restarts = 0
    @Volatile var lastTouch = System.currentTimeMillis()
    var clipOurs = false

    private var pendingLock = false
    private var pendingReason: String? = null
    private var pickWait: CompletableDeferred<List<Uri>>? = null
    private var flowJob: Job = SupervisorJob()
    private var flowScope = CoroutineScope(flowJob + Dispatchers.Main.immediate)
    private var activeFlow: Job? = null
    private var lockGen = 0
    private val bgWriteLock = Any()
    private val thumbGate = Semaphore(3)
    private class ThumbOutcome(val value: Thumb?)
    private val thumbInFlight = ConcurrentHashMap<String, CompletableDeferred<ThumbOutcome?>>()
    private val liveBitmaps = java.util.Collections.synchronizedList(ArrayList<java.lang.ref.WeakReference<Bitmap>>())

    /** รูปเต็มที่ถอดไว้ในหน้าดูรูป – ทับพิกเซลทิ้งตอนล็อค */
    fun track(b: Bitmap) { liveBitmaps.add(java.lang.ref.WeakReference(b)) }
    /** ภาพย่อที่ถอดไว้ (LRU) – จำกัดตามขนาดหน่วยความจำจริง (เดิมจำกัด 500 ภาพ ≈ 270 MB → ตู้ใหญ่เลื่อนดูแล้วแอปแครช) */
    private val thumbs = LinkedHashMap<String, Thumb>(256, 0.75f, true)
    private val thumbRead = ConcurrentHashMap<String, Thumb>()
    private var thumbBytes = 0L
    private val thumbBudget = minOf(Runtime.getRuntime().maxMemory() / 6, 96L shl 20)
    private fun thumbSize(t: Thumb) = try { t.bitmap.allocationByteCount.toLong() } catch (_: Exception) { 0L }
    private fun putThumb(fid: String, t: Thumb) = synchronized(thumbs) {
        thumbs.put(fid, t)?.let { thumbBytes -= thumbSize(it) }
        thumbRead[fid] = t
        thumbBytes += thumbSize(t)
        val iter = thumbs.entries.iterator()
        while (thumbBytes > thumbBudget && thumbs.size > 1 && iter.hasNext()) {
            val e = iter.next()
            if (e.key == fid) continue
            thumbBytes -= thumbSize(e.value); thumbRead.remove(e.key); iter.remove()      // ไม่ทับพิกเซล – อาจยังแสดงอยู่บนจอ (ทับให้ตอนล็อคไม่ได้แล้ว แต่ GC เก็บ)
        }
    }
    private fun clearThumbs(): List<Bitmap> = synchronized(thumbs) {
        synchronized(snippets) { snippets.clear(); snippetRead.clear() }
        val old = thumbs.values.map { it.bitmap }; thumbs.clear(); thumbRead.clear(); thumbBytes = 0; old
    }
    private fun dropThumbsForScrypt() {
        val had = clearThumbs().isNotEmpty()
        if (had) System.gc()
    }
    private fun rememberedModeFor(v: Vault): String? {
        val fp = try { v.keyfileFp() } catch (_: Exception) { null } ?: return null
        return when {
            appKeys.has(fp) -> "app"
            keyUriOf(fp) != null || prefs.getString("keypath_$fp", null) != null -> "ref"
            else -> null
        }
    }

    private fun refreshNeedsKey() {
        val v = vault
        viewModelScope.launch(Dispatchers.IO) {
            val nk = try { v.needsKeyfile() } catch (_: Exception) { false }
            val rm = if (nk && !fakeSession) rememberedModeFor(v) else null
            withContext(Dispatchers.Main.immediate) {
                if (vault === v) { needsKey = nk; rememberedKeyMode = rm }
            }
        }
    }
    /** ข้อความต้นไฟล์ .txt ที่แสดงในช่องแกลเลอรี – ถอดรหัสแค่ส่วนต้นในหน่วยความจำ ไม่เก็บลงดิสก์ ล้างตอนล็อค */
    private val snippetRead = ConcurrentHashMap<String, String>()
    private val snippets = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            val drop = size > 300
            if (drop && eldest != null) snippetRead.remove(eldest.key)
            return drop
        }
    }

    val openDir get() = File(app.cacheDir, "open")
    val shareDir get() = File(app.cacheDir, "share")

    init {
        if (prefs.contains("sort") || prefs.contains("columns")) {
            prefs.edit().remove("sort").remove("columns").apply()
        }
        gate = initialGate()
        refreshWait()
        refreshNeedsKey()
        Thread { wipeTemp(); cleanupInterrupted() }.start()   // ไฟล์ชั่วคราว/ส่งออกที่ค้างจากครั้งก่อน (แอปถูกปิดกลางคัน)
        viewModelScope.launch {
            while (true) {                            // ป้าย "กำลังเปิดอยู่" ทุก 1 นาที (ใช้หลายเครื่องผ่านคลาวด์)
                delay(60_000)
                if (vault.isUnlocked) withContext(Dispatchers.IO) { try { vault.sessionTouch() } catch (_: Exception) {} }
            }
        }
    }

    private fun loadDeviceId(): String {
        val f = File(app.filesDir, "device.id")
        try { f.readText().trim().takeIf { Regex("[0-9a-f]{16}").matches(it) }?.let { return it } } catch (_: Exception) {}
        val v = Crypto.hex(Crypto.random(8))
        try { f.writeText(v) } catch (_: Exception) {}
        return v
    }

    private fun newVault(dir: File) = Vault(dir, Storage.keyDir()).also {
        it.device = deviceId; it.journal = journal; it.keyLocator = ::locateKey; it.genStore = gens
    }

    // ตำแหน่งไฟล์กุญแจที่ผู้ใช้เลือก (ลายนิ้วมือ → URI / path / หรือเก็บแบบเข้ารหัสในแอป)
    private fun keyUriOf(fp: String): Uri? = prefs.getString("key_$fp", null)?.let { Uri.parse(it) }

    private fun clearKeyRef(fp: String) {
        keyUriOf(fp)?.let { old ->
            try { app.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
        prefs.edit().remove("key_$fp").remove("keypath_$fp").commit()
    }

    /** อ่านไฟล์กุญแจที่จำไว้ทุกครั้งที่ปลดล็อค (ในตัวแอปที่เข้ารหัส Keystore / URI คลาวด์ / ตำแหน่งไฟล์บน SD card) */
    private fun locateKey(fp: String): ByteArray? =
        appKeys.load(fp)
            ?: keyUriOf(fp)?.let { readKeyUri(it) }
            ?: prefs.getString("keypath_$fp", null)?.let { p -> readKeyFile(File(p))?.takeIf { Keyfiles.fp(it) == fp } }

    private fun readKeyUri(uri: Uri): ByteArray? = try {
        app.contentResolver.openInputStream(uri)?.use { readKeyStream(it) }
    } catch (_: Exception) { null }

    private fun readKeyFile(f: File): ByteArray? = try {
        if (f.isFile) java.io.FileInputStream(f).use { readKeyStream(it) } else null
    } catch (_: Exception) { null }

    private fun readKeyStream(s: java.io.InputStream): ByteArray? {
        val b = ByteArray(65536)
        return try {
            var n = 0
            while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }
            Keyfiles.parse(String(b, 0, n, Charsets.UTF_8))
        } finally {
            Crypto.wipe(b)
        }
    }

    /** ไฟล์ส่งออก/สำรองที่เขียนไม่เสร็จจากงานที่ถูกขัดจังหวะ → เขียนทับ+ลบ (ต้องมีสิทธิ์เข้าถึงไฟล์) */
    private fun cleanupInterrupted() {
        if (!Storage.hasAccess(app)) return
        try { cleanedOnStart += Storage.cleanupJournal(app, journal) } catch (_: Exception) {}
    }
    private fun initialGate() = when {
        !Storage.hasAccess(app) -> Gate.PERMISSION
        vault.exists() -> Gate.UNLOCK
        else -> Gate.SETUP
    }
    fun lockout() = Lockout(vault, app.filesDir)

    /** กดปุ่ม C ติดต่อกัน 3 ครั้ง → เข้าหน้าตู้ (ขอสิทธิ์/สร้างตู้/ใส่รหัส) */
    fun revealVault() {
        if (gate != Gate.MAIN) { gate = initialGate(); refreshWait(); refreshNeedsKey() }
        decoy = false
    }
    fun hideVault() { if (gate != Gate.MAIN && busy == null) { ask?.let { return }; decoy = true } }

    /** เรียกตอนกลับเข้าแอป: ตรวจสิทธิ์เข้าถึงไฟล์ */
    fun recheckAccess() {
        val ok = Storage.hasAccess(app)
        if (gate == Gate.PERMISSION && ok) { gate = initialGate(); refreshWait(); refreshNeedsKey(); Thread { cleanupInterrupted() }.start() }
        else if (!ok && gate != Gate.PERMISSION) { if (vault.isUnlocked) lockNow(null); gate = Gate.PERMISSION }
    }

    // ================================================================== งานเบื้องหลัง / คำถาม / ตัวเลือกไฟล์
    fun flow(block: suspend CoroutineScope.() -> Unit) {
        if (activeFlow?.isActive == true || busy != null || ask != null) return
        val gen = lockGen
        var job: Job? = null
        job = flowScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { if (gen == lockGen && (!decoy || vault.isUnlocked)) showError(e) }
            finally { if (activeFlow === job) activeFlow = null }
        }
        activeFlow = job
    }

    fun showError(e: Throwable) {
        if (decoy && !vault.isUnlocked) return
        error = when (e) {
            is RemoteRekey -> { val m = e.message; requestLock(null); m }
            is VaultException -> e.message
            is OutOfMemoryError -> "หน่วยความจำไม่พอ – ลองปิดแอปอื่นแล้วลองใหม่"
            is JobCancelled -> "ยกเลิกแล้ว (ไฟล์ที่ค้างครึ่งทางถูกลบทิ้ง)"
            is java.io.IOException -> "อ่าน/เขียนไฟล์ไม่สำเร็จ: ${e.message}"
            else -> "ผิดพลาด: ${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    /** keepAlive = งานยาว (นำเข้า/ส่งออก): บริการเบื้องหน้า + wake lock ไม่ให้ระบบปิดแอปกลางคัน
     *  และแสดงแถบความคืบหน้าแบบละเอียด (ถ้างานเรียก Reporter.start) */
    suspend fun <T> task(label: String, showProgress: Boolean = false, keepAlive: Boolean = false,
                         block: suspend (Reporter) -> T): T {
        val gen = lockGen
        busy = label; progress = if (showProgress) 0f else -1f
        jobInfo = null; jobView = null; cancelRequested = false
        var running = true
        if (keepAlive) {
            JobService.start(app)
            viewModelScope.launch {
                var last = 0L
                while (running) {
                    jobInfo?.let { j ->
                        jobView = j.view()
                        val now = System.currentTimeMillis()
                        if (now - last > 1000) {
                            last = now
                            JobService.update(app, "กำลังทำงาน – อย่าปิดแอป", j.summary(), (j.frac() * 100).toInt())
                        }
                    }
                    delay(150)
                }
            }
        }
        val res = try {
            withContext(Dispatchers.IO) { block(Reporter(this@VaultModel)) }
        } finally {
            running = false
            jobInfo = null; jobView = null; cancelRequested = false
            if (keepAlive) JobService.stop(app)
            busy = null; progress = -1f
            lastTouch = System.currentTimeMillis()
            if (pendingLock) { val r = pendingReason; pendingLock = false; pendingReason = null; lockNow(r) }
        }
        if (gen != lockGen) throw CancellationException("locked")
        return res
    }

    suspend fun <T> show(a: Ask<T>): T {
        if (decoy && !vault.isUnlocked) throw CancellationException("locked")
        val gen = lockGen
        ask = a
        try { return a.result.await() }
        finally {
            if (ask === a) ask = null
            if (gen != lockGen) throw CancellationException("locked")
        }
    }

    private suspend fun pickVia(launch: (Bridge) -> Unit): List<Uri> {
        val b = bridge ?: return emptyList()
        val d = CompletableDeferred<List<Uri>>()
        pickWait = d
        suppressLock = true
        try { launch(b) } catch (e: Exception) {
            suppressLock = false; pickWait = null
            throw VaultException("เปิดหน้าต่างเลือกไฟล์ไม่ได้: ${e.message}")
        }
        try { return d.await() } finally { if (pickWait === d) pickWait = null }
    }
    suspend fun pickDocs(mimes: Array<String>, multiple: Boolean) = pickVia { it.openDocs(mimes, multiple) }
    suspend fun pickTree(): Uri? = pickVia { it.openTree() }.firstOrNull()
    suspend fun pickCreate(name: String): Uri? = pickVia { it.createDoc(name) }.firstOrNull()
    fun pickResult(uris: List<Uri>) { suppressLock = false; pickWait?.complete(uris) }

    private fun startExternal(i: Intent) {
        val b = bridge ?: throw VaultException("เปิดแอปอื่นไม่ได้")
        suppressLock = true
        try { b.start(i) } catch (e: Exception) { suppressLock = false; throw VaultException("ไม่พบแอปที่เปิดไฟล์นี้ได้") }
    }

    // ================================================================== ล็อค
    fun touch() { lastTouch = System.currentTimeMillis() }

    fun cancelJob() { if (jobInfo != null) cancelRequested = true }

    /** เรียกระหว่างอ่าน/เขียนไฟล์ใหญ่: กดยกเลิกแล้ว = หยุดกลางไฟล์ได้เลย (ไฟล์นั้นถูกเก็บกวาดตามปกติ) */
    fun checkCancel() { if (cancelRequested) throw JobCancelled() }

    fun onScreenOff() {
        suppressLock = false
        requestLock(null)
    }

    /** ขอให้ล็อค – ถ้ามีงานค้าง (เช่นกำลังเข้ารหัส) ปิดทับจอทันที แล้วล็อคเมื่องานเสร็จ */
    fun requestLock(reason: String?) {
        notesOpen = false
        if (!vault.isUnlocked && gate != Gate.MAIN) { decoy = true; return }
        if (busy != null) {
            pendingLock = true; pendingReason = reason; blind = true
            return
        }
        lockNow(reason)
    }

    fun lockNow(reason: String?) {
        val wasUnlocked = vault.isUnlocked
        val ts = textSession
        val dirtySnap = if (ts != null && ts.dirty && wasUnlocked) {
            val txt = try { ts.getText?.invoke() } catch (_: Exception) { null }
            if (txt != null) Triple(ts.fid, ts.enc, ts.crlf to txt) else null
        } else null
        textSession = null
        releasePlayer()
        activeFlow = null
        flowJob.cancel()
        flowJob = SupervisorJob()
        flowScope = CoroutineScope(flowJob + Dispatchers.Main.immediate)
        ask = null
        pickWait?.cancel(); pickWait = null
        thumbInFlight.clear()
        val myGen = ++lockGen
        val oldVault = vault
        if (dirtySnap != null || fakeSession) {
            fakeSession = false
            vault = newVault(vaultDir)
        } else {
            oldVault.lock()
        }
        clearClipboard(false)
        refreshJob?.cancel(); refreshJob = null
        statsCache = null
        viewer = null; fullscreen = false
        selected.clear(); folder = null; query = ""; searchOpen = false
        sort = 0; columns = 3
        listing = Listing()
        thumbEpoch++
        val old = ArrayList<Bitmap>()
        old.addAll(clearThumbs())
        synchronized(liveBitmaps) { liveBitmaps.mapNotNullTo(old) { it.get() }; liveBitmaps.clear() }
        gate = if (!Storage.hasAccess(app)) Gate.PERMISSION else if (vault.exists()) Gate.UNLOCK else Gate.SETUP
        decoy = true
        notesOpen = false
        error = null; notice = null                 // ข้อความค้างอาจมีชื่อไฟล์ – ไม่ให้โผล่บนหน้าล็อค
        blind = false; pendingLock = false
        unlockMsg = reason
        refreshWait()
        refreshNeedsKey()
        val started = SystemClock.elapsedRealtime()
        Thread {
            synchronized(bgWriteLock) {
                try {
                    if (dirtySnap != null) {
                        val (fid, enc, crlfText) = dirtySnap
                        val (crlf, txt) = crlfText
                        try {
                            if (synchronized(oldVault) { oldVault.isUnlocked && oldVault.files().containsKey(fid) }) {
                                val (data, _) = TextCodec.encode(txt, enc, crlf)
                                try { oldVault.replaceBytes(fid, data) } finally { Crypto.wipe(data) }
                            }
                        } catch (_: Exception) {}
                    }
                    try { oldVault.sessionRelease() } catch (_: Exception) {}
                } finally {
                    oldVault.lock()
                }
                wipeTemp()
            }
            val left = 300L - (SystemClock.elapsedRealtime() - started)
            if (left > 0) try { Thread.sleep(left) } catch (_: InterruptedException) {}
            Handler(Looper.getMainLooper()).post {
                old.forEach { Media.wipeBitmap(it) }
                // ล็อคแล้วปิดโปรเซสนี้ + เปิดใหม่: เศษข้อมูลที่ระบบ/ไลบรารีปล่อยไว้ในหน่วยความจำหายไปทั้งหมด
                if (wasUnlocked) restartProcess(reason, myGen)
            }
        }.start()
        System.gc()
    }

    private fun restartProcess(reason: String?, expectedGen: Int) {
        if (expectedGen != lockGen || busy != null || vault.isUnlocked) return // ปลดล็อคใหม่ไปแล้ว/มีงาน → ไม่ปิด
        restarts++
        if (!restartOnLock) return
        val act = bridge as? android.app.Activity
        if (visible && act != null && !act.isFinishing) {
            try {
                act.startActivity(Intent(act, RestartActivity::class.java)
                    .putExtra(RestartActivity.EXTRA_PID, android.os.Process.myPid())
                    .putExtra(RestartActivity.EXTRA_MSG, reason)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (_: Exception) {}
        }
        // อยู่เบื้องหลัง: ปิดเฉยๆ – กลับเข้าแอปเมื่อไรก็เริ่มใหม่ที่หน้าโน้ต
        Runtime.getRuntime().exit(0)
    }

    private fun textAutosave() {
        val ts = textSession ?: return
        try { if (ts.dirty) saveText(ts, quiet = true) } catch (_: Exception) {}
        textSession = null
    }

    fun releasePlayer() {
        val p = player
        player = null
        try { p?.release() } catch (_: Exception) {}
    }

    fun wipeTemp() {
        for (d in listOf(openDir, shareDir)) {
            d.walkBottomUp().forEach { f -> if (f.isFile) Vault.secureDelete(f) else if (f != d) f.delete() }
        }
    }

    fun clearClipboard(force: Boolean) {
        if (!force && !clipOurs) return
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {}
        clipOurs = false
    }

    // ================================================================== ตู้หลอก
    /** ตู้หลอก: ตู้จริงอีกใบในพื้นที่ส่วนตัวของแอป (แอปอื่นเข้าไม่ถึง • หายไปเมื่อถอนการติดตั้งแอป)
     *  เปิดได้จากหน้าใส่รหัสของทุกตู้ ด้วยรหัสตู้หลอก */
    private val fakeDir get() = File(app.filesDir, "fake/${Storage.DEFAULT_VAULT_NAME}")
    fun fakeExists() = File(fakeDir, "vault.json").exists() || Vault.hasValidTmpMeta(fakeDir)

    private fun openFake(pw: CharArray): Vault? {
        if (!fakeExists()) return null
        val f = newVault(fakeDir)
        return try { f.unlock(pw); f } catch (_: Exception) { null }
    }

    private fun wipeFake() {
        val d = File(app.filesDir, "fake")
        d.walkBottomUp().forEach { f -> if (f.isFile) Vault.secureDelete(f) else f.delete() }
    }

    fun fakeVaultMenu() = flow {
        if (fakeSession || !vault.isUnlocked) return@flow
        val intro = "ตู้หลอกเป็นพื้นที่แยกต่างหาก เปิดได้จากหน้าใส่รหัสของทุกตู้ ด้วย \"รหัสตู้หลอก\"\n\n" +
            "• หน้าตาและการใช้งานเหมือนตู้จริง เพิ่ม/เปิด/ลบไฟล์ได้จริง (เข้ารหัสเหมือนกัน)\n" +
            "• เก็บในพื้นที่ส่วนตัวของแอป แอปอื่นเข้าไม่ถึง และจะหายไปเมื่อถอนการติดตั้งแอป\n" +
            "• ใช้ตอนถูกบังคับให้เปิดตู้ – ใส่รหัสตู้หลอกแทนรหัสจริง"
        if (fakeExists()) {
            val c = show(AskChoice("🎭 ตู้หลอก", "$intro\n\nมีตู้หลอกอยู่แล้ว", "ตั้งรหัสใหม่ (ล้างข้อมูลตู้หลอกเดิม)", "ลบตู้หลอก"))
                ?: return@flow
            if (!c) {
                if (!show(AskConfirm("ลบตู้หลอก", "ลบตู้หลอกและไฟล์ทั้งหมดในตู้หลอก (เขียนทับก่อนลบ)?", "ลบ", danger = true))) return@flow
                task("กำลังลบตู้หลอก…") { wipeFake() }
                notice = "ลบตู้หลอกแล้ว"
                return@flow
            }
        } else if (!show(AskConfirm("🎭 ตู้หลอก", intro, "ตั้งรหัสตู้หลอก"))) return@flow
        val pwStr = askNewPassword("รหัสตู้หลอก", "รหัสตู้หลอก (ต้องไม่ใช่รหัสจริง)") ?: return@flow
        val pw = pwStr.toCharArray()
        try {
            dropThumbsForScrypt()
            val check = task("กำลังตรวจรหัส…", true) { rep ->
                val v = newVault(vaultDir)
                try { v.verifyPassword(pw) { rep.frac(it) }; "same" }
                catch (_: WrongPassword) { "ok" }
                catch (_: KeyfileMissing) { "keyfile_missing" }
                catch (_: Exception) { "ok" }
            }
            if (check == "keyfile_missing") {
                show(AskInfo("ตู้หลอก", "ไม่พบไฟล์กุญแจของตู้นี้ – ต้องเข้าถึงไฟล์กุญแจของตู้จริงก่อน จึงจะตรวจได้ว่ารหัสตู้หลอกซ้ำกับรหัสจริงหรือไม่"))
                return@flow
            }
            if (check == "same") {
                show(AskInfo("รหัสตู้หลอก", "รหัสนี้เป็นรหัสของตู้นี้ – ตั้งรหัสตู้หลอกให้ต่างจากรหัสจริง"))
                return@flow
            }
            task("กำลังสร้างตู้หลอก…", true) { r ->
                wipeFake()
                newVault(fakeDir).also { it.create(pw, false) { p -> r.frac(p) } }.lock()
            }
        } finally {
            Crypto.wipe(pw)
        }
        show(AskInfo("🎭 ตู้หลอก", "ตั้งรหัสตู้หลอกแล้ว\n\nล็อคแล้วใส่รหัสตู้หลอกที่หน้าใส่รหัส (ตู้ไหนก็ได้) จะเข้าตู้หลอก\n" +
            "ถ้าตู้ที่เลือกอยู่ใช้รหัสเดียวกัน จะเปิดตู้จริงก่อน"))
    }

    // ================================================================== ตำแหน่งตู้
    private fun setVaultDir(dir: File, save: Boolean) {
        vaultDir = dir
        vault = newVault(dir)
        if (save) prefs.edit().putString("vault", dir.absolutePath).commit()
        keyMissing = false
        rememberedKeyMode = null
        unlockMsg = null
        refreshWait()
        refreshNeedsKey()
    }

    fun useDefaultLocation() {
        if (vault.isUnlocked) return
        setVaultDir(Storage.defaultVault(), true)
        gate = if (vault.exists()) Gate.UNLOCK else Gate.SETUP
    }

    // ปุ่มลัดตู้ (หน้าปลดล็อค): จำชื่อ → ตำแหน่งตู้ ในที่เก็บส่วนตัวของแอปเหมือนตำแหน่งตู้ปัจจุบัน
    // ไฟล์กุญแจของแต่ละตู้จำตำแหน่งตามลายนิ้วมือกุญแจอยู่แล้ว (key_<fp>) จึงสลับตามตู้ไปเอง
    var shortcuts by mutableStateOf(loadShortcuts())
        private set

    private fun loadShortcuts(): List<Pair<String, String>> = try {
        val a = org.json.JSONArray(prefs.getString("shortcuts", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getString("name") to it.getString("path") } }
    } catch (_: Exception) { emptyList() }

    private fun saveShortcuts(l: List<Pair<String, String>>) {
        shortcuts = l
        val a = org.json.JSONArray()
        l.forEach { (n, p) -> a.put(org.json.JSONObject().put("name", n).put("path", p)) }
        prefs.edit().putString("shortcuts", a.toString()).commit()
    }

    fun addShortcut() = flow {
        val path = vaultDir.absolutePath
        if (shortcuts.any { it.second == path }) return@flow
        val def = if (path == Storage.defaultVault().absolutePath) "ตู้หลัก"
            else (if (vaultDir.name == Storage.DEFAULT_VAULT_NAME || vaultDir.name == ".bestspace") vaultDir.parentFile?.name else vaultDir.name) ?: "ตู้"
        val name = show(AskInput("บันทึกปุ่มลัด", "ชื่อปุ่ม", def,
            hint = "กดปุ่มนี้ที่หน้าใส่รหัสเพื่อสลับมาตู้นี้ (ไฟล์กุญแจที่เลือกไว้ของตู้นี้ใช้ตามไปด้วย)"))?.trim()?.take(24)
        if (name.isNullOrEmpty()) return@flow
        saveShortcuts(shortcuts + (name to path))
    }

    fun removeShortcut(path: String) = flow {
        val s = shortcuts.firstOrNull { it.second == path } ?: return@flow
        if (show(AskConfirm("ลบปุ่มลัด", "ลบปุ่มลัด \"${s.first}\"?\n${s.second}\n\n(ลบแค่ปุ่ม ตู้และไฟล์ไม่ถูกลบ)", "ลบปุ่ม")))
            saveShortcuts(shortcuts.filter { it.second != path })
    }

    fun useShortcut(path: String) {
        if (vault.isUnlocked || busy != null || path == vaultDir.absolutePath) return
        val dir = File(path)
        if (!File(dir, "vault.json").exists() && !Vault.hasValidTmpMeta(dir)) {
            unlockMsg = "ไม่พบตู้ที่ $path\n(ถอดการ์ด / ย้ายหรือลบโฟลเดอร์?) กดค้างที่ปุ่มเพื่อลบปุ่มลัด"
            return
        }
        setVaultDir(dir, true)
        gate = Gate.UNLOCK
    }

    /** เลือกโฟลเดอร์ .Bestunit (หรือโฟลเดอร์ที่มี .Bestunit / .bestspace อยู่ข้างใน) – ไม่พบ = ถามสร้างใหม่ที่นั่น */
    fun browseVault() {
        if (vault.isUnlocked) { if (busy != null) return; lockNow("ล็อคแล้ว – เลือกตู้ที่จะเปิด") }
        flow { browseFlow() }
    }

    private suspend fun browseFlow() {
        val uri = pickTree() ?: return
        val folder = Storage.treeToFile(uri)
        if (folder == null || !folder.isDirectory) {
            show(AskInfo("เลือกที่นี่ไม่ได้", "ตู้ต้องอยู่ในที่เก็บข้อมูลของเครื่องหรือ SD card (ไม่ใช่คลาวด์ เช่น Google Drive)\n" +
                "ถ้าจะใช้ตู้ร่วมกับคอมผ่านคลาวด์ ให้ใช้แอปซิงก์ที่ซิงก์ลงโฟลเดอร์ในเครื่อง เช่น Syncthing / FolderSync"))
            return
        }
        var found = Storage.resolveVaultDir(folder)
        if (found == null) {
            val target = if (folder.name == Storage.DEFAULT_VAULT_NAME || folder.name == ".bestspace") folder else File(folder, Storage.DEFAULT_VAULT_NAME)
            if (target.exists() && (target.list()?.any { it != ".nomedia" } == true)) {
                show(AskInfo("ไม่ใช่ตู้ BestSpace", "${target.path}\nมีไฟล์อื่นอยู่และไม่ใช่ตู้ BestSpace")); return
            }
            if (!show(AskConfirm("ไม่พบตู้", "ไม่พบตู้ BestSpace ในโฟลเดอร์นี้\n\nสร้างตู้ใหม่ที่\n${target.path}\nไหม?", "สร้างที่นี่"))) return
            found = target
        }
        setVaultDir(found, false)
        if (vault.exists()) { prefs.edit().putString("vault", found.absolutePath).commit(); gate = Gate.UNLOCK } else gate = Gate.SETUP
    }

    // ================================================================== สร้าง / ปลดล็อค
    fun create(pw: CharArray, useKey: Boolean) = flow {
        val gen = lockGen
        val newKey = try {
            task("กำลังสร้างตู้…", true) { r ->
                synchronized(bgWriteLock) {}
                vault.create(pw, useKey) { r.frac(it) }
            }
        } finally { Crypto.wipe(pw) }
        prefs.edit().putString("vault", vaultDir.absolutePath).commit()
        lockout().succeeded()
        vault.sessionTouch(true)
        enterMain()
        if (gen == lockGen && vault.isUnlocked && newKey != null) promptKeyBackup(newKey, first = true)
    }

    fun refreshWait() {
        waitUntil = try { if (vault.exists()) System.currentTimeMillis() + (lockout().waitLeft() * 1000).toLong() else 0L }
        catch (_: Exception) { 0L }
    }

    fun unlock(pw: CharArray) = flow {
        val gen = lockGen
        val lo = lockout()
        if (lo.waitLeft() > 0 || busy != null) { Crypto.wipe(pw); refreshWait(); return@flow }
        unlockMsg = null
        var fake: Vault? = null
        val res = try {
            task("กำลังปลดล็อค…", true) { r ->
                synchronized(bgWriteLock) {}
                // รหัสไม่ใช่ของตู้ที่เลือก (หรือไม่มีไฟล์กุญแจ) → ลองเป็นรหัสตู้หลอก
                try { vault.unlock(pw) { r.frac(it) }; "ok" }
                catch (e: WrongPassword) { fake = openFake(pw); if (fake != null) "fake" else "wrong" }
                catch (e: KeyfileMissing) { fake = openFake(pw); if (fake != null) "fake" else throw e }
            }
        } catch (e: KeyfileMissing) {
            keyMissing = true
            unlockMsg = when {
                appKeys.has(e.fp) ->
                    "🔑 ถอดรหัสไฟล์กุญแจที่จำไว้ในแอปไม่ได้ (ระบบรีเซ็ตคีย์ความปลอดภัย?) – กด \"เลือกไฟล์กุญแจ…\" เพื่อเลือกใหม่"
                keyUriOf(e.fp) != null ->
                    "🔑 อ่านไฟล์กุญแจจากตำแหน่งที่เลือกไว้ไม่ได้\n(ไม่มีเน็ต / ไฟล์ถูกย้ายหรือลบ / ถอดการ์ด?) ลองใหม่ หรือกด \"เลือกไฟล์กุญแจ…\" เพื่อเลือกใหม่"
                else -> "🔑 ${e.message}\nกด \"เลือกไฟล์กุญแจ…\" เพื่อเลือกไฟล์กุญแจ"
            }
            return@flow
        } finally { Crypto.wipe(pw) }
        if (res == "wrong") {
            val st = lo.failed()
            refreshWait()
            val leftFree = Lockout.FREE_TRIES - st.fails
            var hint = ""
            if (vault.root.list()?.any { Vault.conflictKind(it) == "vault" } == true)
                hint = "\n(พบ vault.json อีกฉบับจากโปรแกรมซิงก์ – ถ้าเพิ่งเปลี่ยนรหัสจากเครื่องอื่น รอให้ซิงก์เสร็จ หรือใช้รหัสเดิม)"
            unlockMsg = if (lo.waitLeft() > 0) null
            else "รหัสผ่านไม่ถูกต้อง" + (if (leftFree in 1 until Lockout.FREE_TRIES) " (เหลืออีก $leftFree ครั้งก่อนต้องรอ)" else "") + hint
            return@flow
        }
        fake?.let { f ->
            // ตู้หลอก: ไม่ล้างตัวนับรหัสผิดของตู้จริง (เจ้าของยังเห็นว่ามีคนพยายามเปิด)
            vault = f; fakeSession = true
            f.sessionTouch(true)
            enterMain()
            return@flow
        }
        val other = task("กำลังตรวจสถานะตู้…") { vault.sessionOther() }
        if (other != null) {
            val mins = (other.ageSec / 60).toInt()
            val go = show(AskConfirm("ตู้กำลังเปิดอยู่ที่อื่น",
                "ตู้นี้กำลังเปิดอยู่บนเครื่องอื่น (ผ่านคลาวด์/แฟลชไดรฟ์)\n(ยืนยันว่ายังเปิดอยู่เมื่อ " +
                    (if (mins < 1) "ไม่ถึง 1 นาที" else "$mins นาที") + "ที่แล้ว)\n\n" +
                    "เปิดพร้อมกันได้ – แอปจะรวมรายการไฟล์ให้อัตโนมัติ แต่ถ้าคลาวด์ยังซิงก์ไม่ทัน อาจเกิดไฟล์ขัดแย้ง\n" +
                    "แนะนำ: ล็อคตู้ที่นั่นก่อน แล้วรอให้ซิงก์เสร็จ (ถ้าเครื่องนั้นปิดไปแล้ว ป้ายนี้จะหมดอายุเองใน 5 นาที)\n\nเปิดต่อเลยไหม?",
                "เปิดต่อ", "ยกเลิก", danger = true))
            if (!go) { vault.lock(); unlockMsg = "ยังไม่ได้เปิดตู้ – ล็อคตู้ที่เครื่องอื่นก่อน แล้วลองใหม่"; return@flow }
        }
        val report = task("ตรวจไฟล์ขัดแย้งจากคลาวด์…") { try { vault.mergeConflicts() } catch (e: Exception) { null } }
        // ไฟล์ที่เข้ารหัสค้างจากงานนำเข้าที่ถูกขัดจังหวะครั้งก่อน (ยังไม่ได้ลงดัชนี) → เก็บกวาด
        val recovered = task("ตรวจงานที่ค้างจากครั้งก่อน…") { try { vault.recoverInterrupted() } catch (e: Exception) { 0 } }
        val st = lo.succeeded()
        refreshWait()
        try { File(vault.root, ".nomedia").createNewFile() } catch (_: Exception) {}   // ตู้จากคอม: กันแอปแกลเลอรีสแกน
        vault.sessionTouch(true)
        enterMain()
        if (gen != lockGen || !vault.isUnlocked) return@flow
        reportConflicts(report)
        if (vault.rollback != null) show(AskInfo("ตู้อาจถูกย้อนเวอร์ชัน",
            "ตู้นี้เก่ากว่าที่เครื่องนี้เคยเปิดครั้งล่าสุด – อาจถูกย้อนกลับเป็นเวอร์ชันเก่า (จากคลาวด์ / ซิงก์ทับ / กู้จากไฟล์สำรอง)\n\n" +
                "ไฟล์ที่ลบไปแล้วอาจกลับมา หรือไฟล์ที่เพิ่มล่าสุดอาจหายไป\n\n" +
                "ถ้าเพิ่งกู้จากไฟล์สำรองเอง ไม่ต้องกังวล • ถ้าไม่ใช่ ตรวจรายการไฟล์ และดูประวัติเวอร์ชันในคลาวด์"))
        val cleaned = cleanedOnStart.also { cleanedOnStart = 0 }
        if (recovered > 0 || cleaned > 0) notice = listOfNotNull(
            if (recovered > 0) "เก็บกวาดไฟล์ที่นำเข้าค้างจากครั้งก่อน $recovered ไฟล์ (ต้นฉบับยังอยู่ – นำเข้าใหม่ได้)" else null,
            if (cleaned > 0) "ลบไฟล์ส่งออกที่เขียนไม่ครบจากครั้งก่อน $cleaned รายการ" else null).joinToString(" • ")
        if (st.sinceOk > 0) {
            val whenS = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date((st.lastFail * 1000).toLong()))
            show(AskInfo("มีคนพยายามเปิดตู้", "ตั้งแต่เปิดตู้ครั้งก่อน มีการใส่รหัสผ่านผิด ${st.sinceOk} ครั้ง\nครั้งล่าสุด: $whenS\n\n" +
                "ถ้าไม่ใช่คุณ ควรเปลี่ยนรหัสผ่าน (เมนู ⋮ → เปลี่ยนรหัสผ่าน)"))
        }
    }

    private fun enterMain() {
        touch()
        folder = null; query = ""; filter = "all"; selected.clear(); viewer = null
        gate = Gate.MAIN
        unlockMsg = null; keyMissing = false
        refreshNeedsKey()
        syncFolderView()
        refresh()
    }

    private suspend fun reportConflicts(r: ConflictReport?) {
        if (r == null) return
        val lines = ArrayList<String>()
        if (r.gained > 0) lines.add("✓ รวมไฟล์ขัดแย้งจากคลาวด์ ${r.merged} ชุด – ได้รายการคืน ${r.gained} รายการ")
        else if (r.merged > 0) lines.add("✓ ลบไฟล์ขัดแย้งจากคลาวด์ ${r.merged} ชุด (ไม่มีรายการตกหล่น)")
        if (r.unmergeable.isNotEmpty()) lines.add("⚠ รวมไม่ได้ (เข้ารหัสด้วยรหัสผ่านอื่น – อาจเปลี่ยนรหัสจากอีกเครื่อง):\n   " + r.unmergeable.take(5).joinToString("\n   "))
        if (r.vaultConflicts.isNotEmpty()) lines.add("⚠ พบ vault.json หลายฉบับ (เปลี่ยนรหัสพร้อมกันจาก 2 เครื่อง?):\n   " +
            r.vaultConflicts.take(5).joinToString("\n   ") + "\n   ไม่ได้ลบให้ – ถ้าทุกอย่างปกติ ลบไฟล์เหล่านี้เองได้")
        if (lines.any { it.startsWith("⚠") }) show(AskInfo("ไฟล์ขัดแย้งจากคลาวด์", lines.joinToString("\n\n")))
        else if (lines.isNotEmpty()) notice = lines[0]
    }

    // ================================================================== ไฟล์กุญแจ
    private suspend fun promptKeyBackup(key: File?, first: Boolean) {
        val msg = (if (first) "สร้างไฟล์กุญแจแล้ว:\n" else "") + "${key?.path ?: ""}\n\n" +
            "ถ้าไฟล์นี้หาย (ลบผิด / ล้างเครื่อง / มือถือเสีย) จะเปิดตู้ไม่ได้อีกเลย แม้รู้รหัสผ่าน\n\n" +
            "สำรองไฟล์กุญแจไว้ที่อื่นตอนนี้เลยไหม? (แนะนำ: คอม/แฟลชไดรฟ์ ที่ไม่ใช่ที่เดียวกับโฟลเดอร์ ${Storage.DEFAULT_VAULT_NAME} หรือไฟล์สำรองของตู้)"
        if (show(AskConfirm("สำรองไฟล์กุญแจ", msg, "สำรองเลย", "ไว้ทีหลัง"))) backupKeyfileFlow()
    }

    fun backupKeyfile() = flow { backupKeyfileFlow() }

    private suspend fun backupKeyfileFlow() {
        if (!vault.isUnlocked) return
        val gen = lockGen
        val sec = task("กำลังอ่านไฟล์กุญแจ…") { vault.keySecret() } ?: throw VaultException("ไม่พบไฟล์กุญแจของตู้นี้")
        try {
            val uri = pickCreate("bestspace-${Keyfiles.fp(sec)}.key") ?: return
            Storage.docToFile(app, uri)?.let { f ->
                if (Dest.isPathInsideOrSame(f, vault.root)) {
                    try { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) } catch (_: Exception) {}
                    throw VaultException("ห้ามเก็บไฟล์กุญแจไว้ในโฟลเดอร์ ${Storage.DEFAULT_VAULT_NAME} – เลือกที่อื่น")
                }
            }
            task("กำลังบันทึกไฟล์กุญแจ…") {
                val txtBytes = Keyfiles.text(sec).toByteArray(Charsets.UTF_8)
                try {
                    (app.contentResolver.openOutputStream(uri, "wt") ?: throw VaultException("เขียนไฟล์ไม่ได้")).use {
                        it.write(txtBytes)
                    }
                } finally { Crypto.wipe(txtBytes) }
            }
            if (gen != lockGen || !vault.isUnlocked) return
            show(AskInfo("สำรองไฟล์กุญแจ", "บันทึกแล้ว\n\nเก็บไว้คนละที่กับไฟล์สำรองของตู้ – ถ้าตู้กับกุญแจอยู่บนคลาวด์เดียวกัน " +
                "คนที่เข้าบัญชีคลาวด์ได้จะมีทั้งสองอย่าง"))
        } finally {
            Crypto.wipe(sec)
        }
    }

    /**
     * เลือกไฟล์กุญแจ:
     *  • ไฟล์ในเครื่อง/SD card/แฟลชไดรฟ์ → จำตำแหน่งไว้ (อ่านจากที่นั่นทุกครั้ง)
     *  • ไฟล์จากคลาวด์ / แอปที่ไม่ให้จำสิทธิ์ถาวร → เสนอเก็บในตัวแอป (เข้ารหัสด้วย Android Keystore)
     */
    fun importKeyfile() = flow {
        val uri = pickDocs(arrayOf("*/*"), false).firstOrNull() ?: return@flow
        val sec = task("กำลังอ่านไฟล์กุญแจ…") { readKeyUri(uri) }
            ?: throw VaultException("อ่านไฟล์ไม่ได้ หรือไฟล์นี้ไม่ใช่ไฟล์กุญแจของ BestSpace")
        try {
            val fp = Keyfiles.fp(sec)
            val want = vault.keyfileFp()
            if (want != null && fp != want) {
                throw VaultException("ไฟล์กุญแจนี้ไม่ใช่ของตู้นี้\n(ของตู้นี้: $want • ไฟล์นี้: $fp)")
            }
            val localFile = try { Storage.docToFile(app, uri)?.takeIf { it.isFile } } catch (_: Exception) { null }
            val held = try {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true
            } catch (_: Exception) { false }

            val saveInApp: Boolean = when {
                !held && localFile == null -> {
                    val ok = show(AskConfirm(
                        "ไฟล์กุญแจ",
                        "แอปที่เก็บไฟล์นี้ไม่อนุญาตให้จำตำแหน่งถาวร\n\n" +
                            "เก็บไฟล์กุญแจไว้ในตัวแอป (เข้ารหัสด้วยระบบความปลอดภัยของเครื่อง) " +
                            "เพื่อเปิดตู้นี้ได้โดยไม่ต้องเลือกใหม่ไหม?\n\n" +
                            "• แอปอื่นเข้าถึงไม่ได้ และนำออกไปใช้เครื่องอื่นไม่ได้\n" +
                            "• ยังต้องใส่รหัสผ่านของตู้ทุกครั้ง และสั่ง \"ลืมกุญแจที่จำไว้\" ได้ตลอด",
                        "เก็บในแอป (เข้ารหัส)", "ยกเลิก",
                    ))
                    if (!ok) return@flow
                    true
                }
                !held && localFile != null -> false
                localFile == null -> {
                    val c = show(AskChoice(
                        "ไฟล์กุญแจจากคลาวด์",
                        "ต้องการจำไฟล์กุญแจนี้แบบไหน?\n\n" +
                            "• เก็บในแอป (เข้ารหัส): เปิดตู้ได้ทันทีไม่ต้องเลือกใหม่แม้ไม่มีเน็ต แอปอื่นเข้าไม่ถึง (ยังใช้รหัสผ่านทุกครั้ง)\n" +
                            "• จำเฉพาะตำแหน่งคลาวด์: ไม่เก็บกุญแจไว้ในเครื่อง ต้องเข้าถึงคลาวด์ได้ทุกครั้ง (บางแอปอาจต้องเลือกใหม่หลังล็อค)",
                        "เก็บในแอป (เข้ารหัส)",
                        "จำเฉพาะตำแหน่งคลาวด์",
                    )) ?: return@flow
                    c
                }
                else -> false
            }

            if (saveInApp) {
                task("กำลังเข้ารหัสเก็บไฟล์กุญแจในแอป…") { appKeys.save(fp, sec) }
                clearKeyRef(fp)
                val localCopy = Keyfiles.find(Storage.keyDir(), fp)
                keyMissing = false
                rememberedKeyMode = "app"
                unlockMsg = "✓ เก็บไฟล์กุญแจไว้ในแอปแล้ว (เข้ารหัส) – เปิดตู้นี้ได้เลยโดยไม่ต้องเลือกใหม่" +
                    (if (localCopy != null) { Crypto.wipe(localCopy); "\n⚠ ยังมีสำเนากุญแจในที่เก็บข้อมูลหลัก (ข้างตู้) – ลบเองถ้าต้องการให้เก็บเฉพาะในแอป" } else "")
                return@flow
            }

            appKeys.forget(fp)
            if (held) {
                keyUriOf(fp)?.takeIf { it != uri }?.let { old ->       // ปล่อยสิทธิ์ตำแหน่งเดิม
                    try { app.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                }
            }
            val path = localFile?.absolutePath
            prefs.edit().apply {
                if (held) putString("key_$fp", uri.toString()) else remove("key_$fp")
                if (path != null) putString("keypath_$fp", path) else remove("keypath_$fp")
            }.commit()
            val localCopy = Keyfiles.find(Storage.keyDir(), fp)
            keyMissing = false
            rememberedKeyMode = "ref"
            unlockMsg = "✓ จำตำแหน่งไฟล์กุญแจแล้ว – จะอ่านจากที่นั่นทุกครั้ง (ไม่คัดลอกมาไว้ในเครื่อง) ใส่รหัสผ่านเพื่อเปิดตู้" +
                (if (localCopy != null) { Crypto.wipe(localCopy); "\n⚠ ยังมีสำเนากุญแจในที่เก็บข้อมูลหลัก (ข้างตู้) – ลบเองถ้าต้องการให้กุญแจอยู่ที่เดียว" } else "")
        } finally {
            Crypto.wipe(sec)
        }
    }

    fun forgetRememberedKeyfile() = flow {
        val fp = try { vault.keyfileFp() } catch (_: Exception) { null } ?: return@flow
        val mode = rememberedModeFor(vault) ?: return@flow
        val desc = if (mode == "app")
            "ลบไฟล์กุญแจที่เก็บแบบเข้ารหัสไว้ในตัวแอป (ลายนิ้วมือ $fp)?\n\n" +
                "• หลังลบแล้ว จะต้องเลือกไฟล์กุญแจใหม่ในครั้งถัดไป (หรือมีไฟล์กุญแจอยู่ที่เดิม)\n" +
                "• ไฟล์กุญแจต้นฉบับบนคลาวด์/แฟลชไดรฟ์ไม่ถูกลบ"
        else
            "ลืมตำแหน่งไฟล์กุญแจที่จำไว้สำหรับตู้นี้ (ลายนิ้วมือ $fp)?\n\n" +
                "• ไฟล์กุญแจจริงไม่ถูกลบ เพียงแต่แอปจะไม่จำตำแหน่งไว้อีก"
        if (!show(AskConfirm("ลืมไฟล์กุญแจที่จำไว้", desc, "ลืมเลย", danger = true))) return@flow
        val hadLocal = task("กำลังลบข้อมูลกุญแจที่จำไว้…") {
            appKeys.forget(fp)
            clearKeyRef(fp)
            val local = Keyfiles.find(Storage.keyDir(), fp)
            if (local != null) { Crypto.wipe(local); true } else false
        }
        rememberedKeyMode = null
        if (vault.isUnlocked) {
            notice = "✓ ลืมไฟล์กุญแจที่จำไว้แล้ว" +
                if (hadLocal) " (ยังพบ bestspace.key ในที่เก็บข้อมูลหลัก)" else ""
        } else {
            keyMissing = !hadLocal
            unlockMsg = "✓ ลืมไฟล์กุญแจที่จำไว้แล้ว" +
                if (hadLocal) "\n(ยังพบไฟล์กุญแจในที่เก็บข้อมูลหลัก)" else " – ครั้งหน้ากด \"เลือกไฟล์กุญแจ…\" เพื่อเลือกใหม่"
        }
    }

    fun addKeyfile() = flow {
        val gen = lockGen
        if (vault.needsKeyfile()) return@flow
        if (!show(AskConfirm("เพิ่มไฟล์กุญแจ", "เพิ่มไฟล์กุญแจ (bestspace.key ในที่เก็บข้อมูลหลัก) ให้ตู้นี้?\n\n" +
                "• หลังจากนี้ต้องมีทั้งรหัสผ่าน + ไฟล์กุญแจ จึงจะเปิดตู้ได้\n" +
                "• คนที่ได้ไปแค่โฟลเดอร์ ${Storage.DEFAULT_VAULT_NAME} จะเดารหัสผ่านไม่ได้\n" +
                "• master key จะถูกเปลี่ยนใหม่ด้วย: สำเนาตู้ที่อาจหลุดไปก่อนหน้านี้ เปิดข้อมูลใหม่ไม่ได้", "เพิ่ม"))) return@flow
        checkLockout()
        val pwStr = show(AskInput("ยืนยันรหัสผ่าน", "รหัสผ่านปัจจุบัน", password = true)) ?: return@flow
        val pw = pwStr.toCharArray()
        val r = try {
            dropThumbsForScrypt()
            task("กำลังเพิ่มไฟล์กุญแจ + เปลี่ยน master key…", true) { rep ->
                try { "ok" to vault.addKeyfile(pw) { rep.frac(it) } } catch (e: WrongPassword) { "wrong" to null }
            }
        } finally { Crypto.wipe(pw) }
        if (gen != lockGen || !vault.isUnlocked) return@flow
        if (r.first == "wrong") wrongInSession()
        refreshNeedsKey()
        notice = "🔑 ตู้นี้ใช้ไฟล์กุญแจแล้ว"
        promptKeyBackup(r.second ?: vault.keyfilePath(), first = r.second != null)
    }

    /** ใส่รหัสผิดถี่เกินไป (รวมการเดาผ่านหน้าเปลี่ยนรหัส/เพิ่มไฟล์กุญแจ) → ห้ามลองจนกว่าจะครบเวลา */
    private fun checkLockout() {
        val left = lockout().waitLeft()
        if (left > 0) throw VaultException("ใส่รหัสผ่านผิดหลายครั้ง – รออีก ${fmtWait(left.toLong() + 1)} แล้วลองใหม่")
    }

    /** รหัสผิดระหว่างเปิดตู้อยู่ = นับรวมกับหน้าปลดล็อค (กันคนหยิบมือถือที่เปิดตู้ค้างไว้มาเดารหัส) */
    private fun wrongInSession(): Nothing {
        val st = lockout().failed()
        refreshWait()
        val left = lockout().waitLeft()
        throw VaultException("รหัสผ่านปัจจุบันไม่ถูกต้อง" +
            if (left > 0) " – ใส่ผิด ${st.fails} ครั้ง ต้องรอ ${fmtWait(left.toLong() + 1)}" else " (นับรวมกับการใส่รหัสผิดที่หน้าปลดล็อค)")
    }

    fun changePassword() = flow {
        val gen = lockGen
        checkLockout()
        val oldStr = show(AskInput("เปลี่ยนรหัสผ่าน", "รหัสผ่านปัจจุบัน", password = true)) ?: return@flow
        val newStr = askNewPassword("เปลี่ยนรหัสผ่าน", "รหัสผ่านใหม่") ?: return@flow
        checkLockout()
        val old = oldStr.toCharArray()
        val new = newStr.toCharArray()
        val ok = try {
            dropThumbsForScrypt()
            task("กำลังเปลี่ยนรหัส + หมุน master key…", true) { rep ->
                try { vault.changePassword(old, new) { rep.frac(it) }; true } catch (e: WrongPassword) { false }
            }
        } finally { Crypto.wipe(old); Crypto.wipe(new) }
        if (gen != lockGen || !vault.isUnlocked) return@flow
        if (!ok) wrongInSession()
        show(AskInfo("เปลี่ยนรหัสผ่าน", "เปลี่ยนรหัสผ่านแล้ว\n\n• master key ถูกเปลี่ยนใหม่ และ vault.json / index.enc เดิมถูกเขียนทับก่อนแทนที่\n" +
            "• สำเนาตู้ที่เคยสำรอง/ซิงก์ไว้ก่อนหน้านี้ ยังเปิดได้ด้วยรหัสเดิม – ถ้ารหัสเดิมรั่ว ให้ลบสำเนาเก่าเหล่านั้นด้วย\n" +
            "• ถ้าใช้ตู้นี้บนเครื่องอื่นผ่านคลาวด์ เครื่องนั้นต้องล็อคแล้วเปิดใหม่ด้วยรหัสใหม่"))
    }

    private suspend fun askNewPassword(title: String, label: String): String? {
        val a = show(AskInput(title, label, password = true, hint = "อย่างน้อย $MIN_PASSWORD ตัวอักษร")) ?: return null
        if (a.length < MIN_PASSWORD) { show(AskInfo(title, "รหัสต้องยาวอย่างน้อย $MIN_PASSWORD ตัวอักษร")); return null }
        val b = show(AskInput(title, "ยืนยันรหัสอีกครั้ง", password = true)) ?: return null
        if (a != b) { show(AskInfo(title, "รหัสทั้งสองช่องไม่ตรงกัน")); return null }
        return a
    }

    // ================================================================== รายการในแกลเลอรี
    private class VaultStats(
        val vault: Vault,
        val rev: Long,
        val count: Map<String, Int>,
        val cover: Map<String, String>,
        val totalBytes: Long,
        val filesByParent: Map<String?, List<String>>,
        val dirsByParent: Map<String?, List<String>>,
    )
    @Volatile private var statsCache: VaultStats? = null
    private var refreshJob: Job? = null

    private fun getStatsLocked(v: Vault): VaultStats {
        val rev = v.indexRevision
        statsCache?.let { if (it.vault === v && it.rev == rev) return it }
        val ents = v.files(); val dirs = v.folders()
        val count = HashMap<String, Int>()
        val coverBest = HashMap<String, Pair<String, Double>>()
        val filesByParent = HashMap<String?, ArrayList<String>>()
        val dirsByParent = HashMap<String?, ArrayList<String>>()
        var total = 0L
        for (d in dirs.keys) {
            dirsByParent.getOrPut(v.parentOf(d)) { ArrayList() }.add(d)
        }
        for ((fid, ev) in ents) {
            val e = ev.asMap()
            total += e["size"].asLong()
            val parent = v.parentOf(fid)
            filesByParent.getOrPut(parent) { ArrayList() }.add(fid)
            val media = Mime.kind(e["name"].asStr() ?: "", e["mime"].asStr() ?: "") in setOf("image", "video")
            val added = e["added"].asDouble()
            for (d in v.folderChain(parent)) {
                count[d] = (count[d] ?: 0) + 1
                if (media && added > (coverBest[d]?.second ?: -1.0)) coverBest[d] = fid to added
            }
        }
        val cover = HashMap<String, String>(coverBest.size)
        for ((d, p) in coverBest) cover[d] = p.first
        return VaultStats(v, rev, count, cover, total, filesByParent, dirsByParent).also { statsCache = it }
    }

    fun refresh() {
        if (!vault.isUnlocked) return
        val gen = lockGen
        val f = folder; val q = query.trim().lowercase(); val flt = filter; val s = sort
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val l = withContext(Dispatchers.Default) { try { computeListing(f, q, flt, s) } catch (e: Exception) { null } }
            if (gen == lockGen && l != null && vault.isUnlocked) {
                listing = l
                selected.retainAll(l.items.map { it.id }.toSet())
                version++
            }
        }
    }

    private fun navigateTo(target: String?, clearSearch: Boolean) {
        if (!vault.isUnlocked) return
        val gen = lockGen
        val flt = filter
        val q = if (clearSearch) "" else query.trim().lowercase()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val (s, c) = synchronized(vault) { vault.folderView(target) }
                    val sc = s.coerceIn(0, SORTS.lastIndex)
                    val cc = c.coerceIn(2, 6)
                    val l = computeListing(target, q, flt, sc)
                    Triple(sc, cc, l)
                } catch (_: Exception) { null }
            }
            if (gen == lockGen && result != null && vault.isUnlocked) {
                val (sc, cc, l) = result
                folder = target
                sort = sc
                columns = cc
                selected.clear()
                if (clearSearch) { query = ""; searchOpen = false }
                listing = l
                version++
            }
        }
    }

    private fun computeListing(cur0: String?, q: String, flt: String, mode: Int): Listing = synchronized(vault) {
        if (!vault.isUnlocked) return Listing()
        val ents = vault.files(); val dirs = vault.folders()
        val cur = if (cur0 != null && dirs.containsKey(cur0)) cur0 else null
        val st = getStatsLocked(vault)
        fun chosenCover(d: String): String? {                 // ภาพปกที่ผู้ใช้เลือกไว้ – ยังอยู่ในโฟลเดอร์นี้และเป็นรูป/วิดีโอ
            val c = dirs[d].asMap()["cover"].asStr() ?: return null
            val e = ents[c]?.asMap() ?: return null
            if (Mime.kind(e["name"].asStr() ?: "", e["mime"].asStr() ?: "") !in setOf("image", "video")) return null
            return if (d in vault.folderChain(vault.parentOf(c))) c else null
        }
        fun dirItem(d: String) = Item(d, dirs[d].asMap()["name"].asStr() ?: "?", true, count = st.count[d] ?: 0,
            cover = chosenCover(d) ?: st.cover[d], added = dirs[d].asMap()["created"].asDouble(),
            pinned = dirs[d].asMap()["pinned"] == true)
        val dirIds = if (q.isNotEmpty()) dirs.keys.filter { (dirs[it].asMap()["name"].asStr() ?: "").lowercase().contains(q) }
        else st.dirsByParent[cur] ?: emptyList()
        val pool = if (q.isNotEmpty()) ents.keys.filter { (ents[it].asMap()["name"].asStr() ?: "").lowercase().contains(q) }
        else st.filesByParent[cur] ?: emptyList()
        val counts = HashMap<String, Int>().apply { KINDS.forEach { put(it.first, 0) } }
        val files = ArrayList<Item>(pool.size)
        for (fid in pool) {
            val e = ents[fid].asMap()
            val name = e["name"].asStr() ?: "file"
            val mime = e["mime"].asStr() ?: "application/octet-stream"
            val k = Mime.kind(name, mime)
            counts["all"] = (counts["all"] ?: 0) + 1
            counts[k] = (counts[k] ?: 0) + 1
            if (flt == "all" || flt == k) files.add(Item(fid, name, false, k, mime, e["size"].asLong(), e["added"].asDouble(),
                origin = e["origin"].asStr(), pos = (e["pos"] as? Number)?.toDouble()))
        }
        var dItems = dirIds.map { dirItem(it) }
        sortFiles(files, mode)
        dItems = if (mode == 1) dItems.sortedBy { it.added } else dItems.sortedBy { it.name.lowercase() }
        dItems = dItems.sortedByDescending { it.pinned }      // โฟลเดอร์ที่ปักหมุดอยู่บนสุด (ลำดับที่เหลือคงเดิม)
        val crumbs = listOf<Pair<String?, String>>(null to "ตู้ของฉัน") +
            vault.folderChain(cur).map { it to (dirs[it].asMap()["name"].asStr() ?: "?") }
        return Listing(dItems + files, counts, ents.size, dirs.size, st.totalBytes, crumbs, q.isNotEmpty())
    }

    private val viewSaveMutex = Mutex()

    private fun syncFolderView() {
        val (s, c) = try { synchronized(vault) { vault.folderView(folder) } } catch (_: Exception) { 0 to 3 }
        sort = s.coerceIn(0, SORTS.lastIndex)
        columns = c.coerceIn(2, 6)
    }

    private fun saveFolderViewAsync(did: String?, sortVal: Int? = null, colsVal: Int? = null) {
        val targetVault = vault
        if (!targetVault.isUnlocked) return
        viewModelScope.launch(Dispatchers.IO) {
            viewSaveMutex.withLock {
                try {
                    synchronized(targetVault) {
                        if (vault === targetVault && targetVault.isUnlocked) {
                            targetVault.setFolderView(did, sort = sortVal, cols = colsVal)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun openFolder(d: String?) { navigateTo(d, clearSearch = true) }

    fun up(): Boolean {
        val f = folder ?: return false
        val parent = synchronized(vault) { if (vault.isUnlocked) vault.parentOf(f) else null }
        navigateTo(parent, clearSearch = false)
        return true
    }

    fun setSortMode(i: Int) {
        val s = i.coerceIn(0, SORTS.lastIndex)
        sort = s
        saveFolderViewAsync(folder, sortVal = s)
        refresh()
    }
    fun setNumpadMode(on: Boolean) { numpad = on; prefs.edit().putBoolean("numpad", on).commit() }
    fun setCols(n: Int) {
        val c = n.coerceIn(2, 6)
        columns = c
        saveFolderViewAsync(folder, colsVal = c)
    }

    /** ลากเรียงไฟล์เสร็จ: visible = ไฟล์ที่เห็นอยู่ (ตามแท็บกรอง) ในลำดับใหม่
     *  ไฟล์ที่ซ่อนอยู่ (แท็บกรองอื่น) คงตำแหน่งเดิม → บันทึกลำดับของทั้งโฟลเดอร์ แล้วสลับไปโหมด "กำหนดเอง" */
    fun reorder(visible: List<String>) = flow {
        val gen = lockGen
        val cur = folder
        val mode = sort
        try {
            task("กำลังบันทึกลำดับ…") {
                synchronized(vault) {
                    val ents = vault.files()
                    val all = ArrayList<Item>()
                    for (fid in ents.keys) {
                        if (vault.parentOf(fid) != cur) continue
                        val e = ents[fid].asMap()
                        all.add(Item(fid, e["name"].asStr() ?: "file", false, size = e["size"].asLong(),
                            added = e["added"].asDouble(), pos = (e["pos"] as? Number)?.toDouble()))
                    }
                    sortFiles(all, mode)
                    val order = all.map { it.id }.toMutableList()
                    val vis = visible.filter { id -> order.contains(id) }
                    val visSet = vis.toSet()
                    val slots = order.indices.filter { order[it] in visSet }
                    if (slots.size == vis.size) for ((k, slot) in slots.withIndex()) order[slot] = vis[k]
                    vault.setOrder(order)
                }
            }
        } finally {
            if (gen == lockGen && vault.isUnlocked) {
                if (sort != SORT_CUSTOM) { setSortMode(SORT_CUSTOM); notice = "เปลี่ยนการเรียงเป็น \"กำหนดเอง\" แล้ว" }
                else refresh()
            }
        }
    }

    /** ปุ่มภาพปกในโฟลเดอร์: เลือกจากรูป/วิดีโอในโฟลเดอร์นี้ (รวมโฟลเดอร์ย่อย) */
    fun pickFolderCover() = flow {
        val d = folder ?: return@flow
        val (name, cands, cur) = synchronized(vault) {
            val dirs = vault.folders(); val ents = vault.files()
            val out = ArrayList<Item>()
            for ((fid, ev) in ents) {
                val e = ev.asMap()
                val n = e["name"].asStr() ?: "file"; val mime = e["mime"].asStr() ?: ""
                val k = Mime.kind(n, mime)
                if (k != "image" && k != "video") continue
                if (d !in vault.folderChain(vault.parentOf(fid))) continue
                out.add(Item(fid, n, false, k, mime, e["size"].asLong(), e["added"].asDouble(), pos = (e["pos"] as? Number)?.toDouble()))
            }
            sortFiles(out, sort)
            Triple(dirs[d].asMap()["name"].asStr() ?: "โฟลเดอร์", out.take(600), dirs[d].asMap()["cover"].asStr())
        }
        if (cands.isEmpty()) { show(AskInfo("ภาพปกโฟลเดอร์", "โฟลเดอร์นี้ยังไม่มีรูปหรือวิดีโอ")); return@flow }
        val r = show(AskCover(name, cands, cur)) ?: return@flow
        task("กำลังบันทึกภาพปก…") { vault.setFolderCover(d, r.ifEmpty { null }) }
        refresh()
        notice = if (r.isEmpty()) "ภาพปกโฟลเดอร์: อัตโนมัติ (รูป/วิดีโอล่าสุด)" else "ตั้งภาพปกโฟลเดอร์ \"$name\" แล้ว"
    }

    /** ปักหมุด / เลิกปักหมุดโฟลเดอร์ที่เลือก */
    fun pinFolders(dids: List<String>, pinned: Boolean) = flow {
        task(if (pinned) "กำลังปักหมุด…" else "กำลังเลิกปักหมุด…") { vault.setFoldersPinned(dids, pinned) }
        selected.clear()
        refresh()
        notice = if (pinned) "ปักหมุดโฟลเดอร์ไว้บนสุดแล้ว" else "เลิกปักหมุดแล้ว"
    }

    /** ตั้งไฟล์ที่เลือกเป็นภาพปกของโฟลเดอร์ที่เปิดอยู่ */
    fun setFolderCoverTo(fid: String) = flow {
        val d = folder ?: return@flow
        task("กำลังบันทึกภาพปก…") { vault.setFolderCover(d, fid) }
        selected.clear()
        refresh()
        notice = "ตั้งเป็นภาพปกโฟลเดอร์แล้ว"
    }

    suspend fun thumb(fid: String): Thumb? {
        synchronized(thumbs) { thumbs[fid] }?.let { return it }
        val gen = lockGen
        while (true) {
            if (gen != lockGen || !vault.isUnlocked) return null
            synchronized(thumbs) { thumbs[fid] }?.let { return it }
            val existing = thumbInFlight[fid]
            if (existing != null) {
                val res = try { existing.await() } catch (_: CancellationException) { null }
                if (res != null) return res.value
                continue
            }
            val ours = CompletableDeferred<ThumbOutcome?>()
            if (thumbInFlight.putIfAbsent(fid, ours) != null) continue
            try {
                val outcome = thumbGate.withPermit {
                    withContext(Dispatchers.IO) {
                        if (gen != lockGen || !vault.isUnlocked) return@withContext ThumbOutcome(null)
                        synchronized(thumbs) { thumbs[fid] }?.let { return@withContext ThumbOutcome(it) }
                        val r = try { Media.thumb(vault, fid) } catch (e: Throwable) { null }
                            ?: return@withContext ThumbOutcome(null)
                        if (gen != lockGen) { Media.wipeBitmap(r.first); return@withContext ThumbOutcome(null) }
                        val t = Thumb(r.first.asImageBitmap(), r.first, r.second)
                        putThumb(fid, t)
                        ThumbOutcome(t)
                    }
                }
                ours.complete(outcome)
                return outcome.value
            } finally {
                thumbInFlight.remove(fid, ours)
                if (!ours.isCompleted) ours.complete(null)
            }
        }
    }

    fun thumbCached(fid: String): Thumb? = thumbRead[fid]

    fun snippetCached(fid: String): String? = snippetRead[fid]

    /** อ่านแค่ 1 KB แรกของไฟล์ข้อความ แล้วแปลงเป็นตัวอักษร (เข้ารหัสแบบเดียวกับหน้าแก้ไขข้อความ) */
    private fun readSnippet(fid: String): String? {
        val buf = ByteArray(1024)
        var part: ByteArray? = null
        try {
            var got = 0
            vault.openReader(fid).use { r ->
                while (got < buf.size) { val n = r.readAt(got.toLong(), buf, got, buf.size - got); if (n <= 0) break; got += n }
            }
            var end = got
            if (got == buf.size) {                         // ตัดกลางตัวอักษร UTF-8 → ถอยไปต้นตัวอักษรนั้น
                var i = end - 1
                while (i > 0 && (buf[i].toInt() and 0xC0) == 0x80) i--
                if ((buf[i].toInt() and 0xC0) == 0xC0) end = i
            }
            part = buf.copyOf(end)
            return TextCodec.decode(part).text.replace("\r\n", "\n").replace('\t', ' ').trimStart().take(600)
        } catch (_: Throwable) {
            return null
        } finally {
            java.util.Arrays.fill(buf, 0); part?.let { java.util.Arrays.fill(it, 0) }
        }
    }

    suspend fun snippet(fid: String): String? {
        snippetCached(fid)?.let { return it }
        val gen = lockGen
        return thumbGate.withPermit {
            withContext(Dispatchers.IO) {
                if (gen != lockGen || !vault.isUnlocked) return@withContext null
                val text = readSnippet(fid) ?: return@withContext null
                if (gen != lockGen) return@withContext null
                synchronized(snippets) { snippets[fid] = text; snippetRead[fid] = text }
                text
            }
        }
    }
    private fun dropThumb(fid: String) {
        synchronized(thumbs) {
            thumbRead.remove(fid)
            thumbs.remove(fid)?.let { thumbBytes -= thumbSize(it) }
        }
    }

    fun entryOf(fid: String): Item? = synchronized(vault) {
        if (!vault.isUnlocked) return null
        val e = vault.files()[fid]?.asMap() ?: return null
        val name = e["name"].asStr() ?: "file"; val mime = e["mime"].asStr() ?: ""
        Item(fid, name, false, Mime.kind(name, mime), mime, e["size"].asLong(), e["added"].asDouble(), origin = e["origin"].asStr())
    }

    fun location(fid: String): String = synchronized(vault) {
        if (!vault.isUnlocked) return ""
        (listOf("ตู้ของฉัน") + vault.folderChain(vault.parentOf(fid)).map { vault.folders()[it].asMap()["name"].asStr() ?: "?" })
            .joinToString(" / ")
    }

    /** ขยายรายการที่เลือก: โฟลเดอร์ → ไฟล์ทั้งหมดข้างใน พร้อมเส้นทางโฟลเดอร์ */
    private fun expand(ids: Collection<String>): List<Pair<String, List<String>>> = synchronized(vault) {
        val out = ArrayList<Pair<String, List<String>>>()
        val seen = HashSet<String>()
        for (x in ids) {
            if (Vault.isDir(x)) { for (p in vault.walk(x)) if (seen.add(p.first)) out.add(p) }
            else if (vault.files().containsKey(x) && seen.add(x)) out.add(x to emptyList())
        }
        out
    }

    fun openViewer(fid: String, focusText: Boolean = false) {
        val ids = listing.items.filter { !it.isDir }.map { it.id }.toMutableList()
        if (fid !in ids) ids.add(0, fid)
        viewer = ViewerState(ids, ids.indexOf(fid).coerceAtLeast(0), focusText)
    }

    // ================================================================== เพิ่มไฟล์ / นำเข้า
    private suspend fun pickSourcesInAppOrSystem(mode: PickMode, fallbackMimes: Array<String>): List<Source> =
        when (val res = show(AskInAppPick(mode))) {
            is InAppPickResult.Sources -> res.items
            is InAppPickResult.UseSystemPicker -> pickDocs(fallbackMimes, true).map { uriSource(it, emptyList()) }
            null -> emptyList()
        }

    fun addMedia() = flow {
        val sources = pickSourcesInAppOrSystem(PickMode.MEDIA, arrayOf("image/*", "video/*"))
        if (sources.isNotEmpty()) importSources(sources, emptyList())
    }

    fun addFiles() = flow {
        val sources = pickSourcesInAppOrSystem(PickMode.FILES, arrayOf("*/*"))
        if (sources.isNotEmpty()) importSources(sources, emptyList())
    }

    private fun uriSource(u: Uri, path: List<String>): Source {
        val f = Storage.docToFile(app, u)?.takeIf { it.isFile && it.canRead() }
        return Source(f?.name ?: Storage.displayName(app, u), f?.length() ?: Storage.size(app, u),
            app.contentResolver.getType(u), f, u, path)
    }

    /** นำเข้าโฟลเดอร์ (เลือกได้หลายโฟลเดอร์พร้อมกัน): สร้างโฟลเดอร์ในตู้ชื่อเดียวกัน พร้อมโครงสร้างโฟลเดอร์ย่อย */
    fun addFolder() = flow {
        val (bases, uri) = when (val res = show(AskInAppPick(PickMode.FOLDER))) {
            is InAppPickResult.Sources -> pruneNestedFolders(res.folderBases.filter { it.isDirectory && it.canRead() }) to null
            is InAppPickResult.UseSystemPicker -> {
                val u = pickTree() ?: return@flow
                val f = Storage.treeToFile(u)?.takeIf { it.isDirectory && it.canRead() }
                (if (f != null) listOf(f) else emptyList()) to u
            }
            null -> return@flow
        }
        val sources = task("กำลังอ่านรายชื่อไฟล์…") {
            val out = ArrayList<Source>()
            if (bases.isNotEmpty()) {
                for (base in bases) {
                    base.walkTopDown().onEnter { !isVaultOwn(it) }.filter { it.isFile }.forEach { f ->
                        val rel = f.parentFile!!.relativeTo(base).path.split('/').filter { it.isNotEmpty() }
                        out.add(Source(f.name, f.length(), null, f, null, listOf(base.name) + rel))
                    }
                }
            } else if (uri != null) {
                val root = DocumentFile.fromTreeUri(app, uri) ?: throw VaultException("เปิดโฟลเดอร์ไม่ได้")
                fun walk(d: DocumentFile, path: List<String>) {
                    for (c in d.listFiles()) {
                        if (c.isDirectory) walk(c, path + (c.name ?: "folder"))
                        else out.add(Source(c.name ?: "file", c.length(), c.type, null, c.uri, path))
                    }
                }
                walk(root, listOf(root.name ?: "folder"))
            }
            out
        }
        if (sources.isEmpty()) { show(AskInfo("นำเข้าโฟลเดอร์", "โฟลเดอร์ที่เลือกไม่มีไฟล์")); return@flow }
        importSources(sources, bases)
    }

    /** ไฟล์ของตู้เอง (ในโฟลเดอร์ตู้) หรือไฟล์กุญแจ – ห้ามนำเข้า/ลบ (ลบแล้วตู้เปิดไม่ได้อีก) */
    fun isVaultOwnFile(f: File?): Boolean = isVaultOwn(f)

    fun vaultRootPaths(): List<String> = listOfNotNull(
        try { vault.root.canonicalPath } catch (_: Exception) { null },
        vault.root.absolutePath,
    ).distinct()

    fun isVaultOwnPath(path: String?, name: String?, vaultRoots: List<String>): Boolean {
        val n = (name ?: path?.substringAfterLast('/') ?: "").lowercase()
        if (n.startsWith("bestspace") && n.endsWith(".key")) return true
        if (path.isNullOrEmpty()) return false
        return vaultRoots.any { r -> path == r || path.startsWith("$r/") }
    }

    private fun isVaultOwn(f: File?): Boolean {
        if (f == null) return false
        val p = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }
        val root = try { vault.root.canonicalPath } catch (_: Exception) { vault.root.absolutePath }
        if (p == root || p.startsWith("$root/")) return true
        val n = f.name.lowercase()
        return n.startsWith("bestspace") && n.endsWith(".key")
    }

    private suspend fun importSources(all: List<Source>, bases: List<File>) {
        val gen = lockGen
        val sources = all.filter { !isVaultOwn(it.file) }
        val skipped = all.size - sources.size
        if (sources.isEmpty()) {
            show(AskInfo("นำเข้าไม่ได้", "ไฟล์ที่เลือกเป็นไฟล์ของตู้เองหรือไฟล์กุญแจ – นำเข้าไม่ได้ (ถ้าลบ ตู้จะเปิดไม่ได้อีก)"))
            return
        }
        val dest = if (query.isNotBlank()) null else folder
        val notes = Storage.wipeNotes().joinToString("\n") { "• $it" }
        val wipe = show(AskChoice("ลบต้นฉบับ?",
            "เข้ารหัส ${sources.size} ไฟล์\n\nลบไฟล์ต้นฉบับ (ไม่เข้ารหัส) แบบเขียนทับหลังเข้ารหัสเสร็จไหม?\n" +
                "แนะนำ: ลบ – เขียนทับเนื้อไฟล์ ทับชื่อไฟล์ แล้วลบ + ล้างภาพย่อ/รายการในแกลเลอรีของระบบ\n\nข้อจำกัด:\n$notes",
            "เข้ารหัส + ลบต้นฉบับ", "เข้ารหัส เก็บต้นฉบับไว้")) ?: return
        val errors = ArrayList<String>()
        if (skipped > 0) errors.add("⚠ ข้าม $skipped ไฟล์ที่เป็นไฟล์ของตู้เอง/ไฟล์กุญแจ (ห้ามนำเข้า)")
        var wiped = 0
        var added = 0
        var cancelled = false
        task("กำลังเข้ารหัส…", true, keepAlive = true) { rep ->
            val made = HashMap<List<String>, String?>()
            var foldersUnsaved = false
            fun folderFor(path: List<String>): String? {
                if (path.isEmpty()) return dest
                made[path]?.let { return it }
                val parent = folderFor(path.dropLast(1))
                val d = synchronized(vault) {
                    val before = vault.folders().size
                    vault.findOrCreateFolder(path.last(), parent, save = false).also {
                        if (vault.folders().size != before) foldersUnsaved = true
                    }
                }
                made[path] = d
                return d
            }
            val factor = if (wipe) 2L else 1L                 // ลบต้นฉบับ = เข้ารหัส + ถอดกลับมาตรวจ
            val totalBytes = sources.sumOf { maxOf(0L, it.size) }
            val job = rep.start("กำลังเข้ารหัสเข้าตู้", sources.size, totalBytes * factor, totalBytes)

            // ไฟล์ที่เข้ารหัสแล้วรอลงดัชนี: ลงดัชนีทีละชุด (บันทึกดัชนีครั้งเดียวต่อชุด) แล้วค่อยตรวจสำเนา+ลบต้นฉบับ
            // ถูกขัดจังหวะก่อนลงดัชนี = ไฟล์ของชุดนั้นถูกเก็บกวาดตอนปลดล็อคครั้งหน้า ต้นฉบับยังอยู่ครบ
            class Done(val idx: Int, val s: Source, val p: Vault.Pending, val digest: ByteArray)
            val batch = ArrayList<Done>()
            var lastFlush = android.os.SystemClock.elapsedRealtime()
            fun flush() {
                if (batch.isEmpty()) return
                val cur = ArrayList(batch); batch.clear()
                lastFlush = android.os.SystemClock.elapsedRealtime()
                try { vault.commitPending(cur.map { it.p }); foldersUnsaved = false } catch (e: Exception) {
                    cur.forEach { errors.add("${it.s.name}: บันทึกลงตู้ไม่สำเร็จ – ${e.message ?: e.javaClass.simpleName}") }
                    return
                }
                added += cur.size
                if (!wipe) return
                for (d in cur) {
                    val s = d.s
                    val sz = maxOf(0L, s.size)
                    // ตรวจว่าสำเนาที่เข้ารหัสถอดกลับได้ตรงกับต้นฉบับทุกไบต์ ก่อนทำลายต้นฉบับ
                    // (ถึงตรงนี้ไฟล์ลงดัชนีบนดิสก์แล้ว – ถูกขัดจังหวะหลังจากนี้ ทั้งสำเนาในตู้และต้นฉบับยังอยู่)
                    job.file(d.idx + 1, s.name, sz, "ตรวจสำเนาที่เข้ารหัส")
                    rep.label("ตรวจสำเนาที่เข้ารหัส ${d.idx + 1}/${sources.size}  ${s.name}")
                    val encHash = java.security.MessageDigest.getInstance("SHA-256")
                    val verified = try {
                        vault.decryptTo(d.p.fid, object : java.io.OutputStream() {
                            override fun write(b: Int) { encHash.update(b.toByte()) }
                            override fun write(b: ByteArray, off: Int, len: Int) { encHash.update(b, off, len) }
                        }) { n -> job.advance(n) }
                        java.security.MessageDigest.isEqual(d.digest, encHash.digest())
                    } catch (e: Exception) { false }
                    if (!verified) {
                        errors.add("⚠ ${s.name}: ตรวจสำเนาที่เข้ารหัสไม่ผ่าน – ไม่ได้ลบต้นฉบับ (ลองนำเข้าใหม่)")
                        continue
                    }
                    job.phase = "ลบต้นฉบับแบบเขียนทับ"
                    rep.label("ลบต้นฉบับแบบเขียนทับ ${d.idx + 1}/${sources.size}  ${s.name}")
                    val r = when {
                        s.file != null -> Storage.wipeFile(app, s.file)
                        s.uri != null -> Storage.wipeDocument(app, s.uri)
                        else -> Storage.Wipe.FAILED
                    }
                    when (r) {
                        Storage.Wipe.OK -> wiped++
                        Storage.Wipe.DELETED_ONLY ->
                            errors.add("⚠ ${s.name}: ลบต้นฉบับแล้ว แต่เขียนทับเนื้อไฟล์ไม่ได้ (ข้อมูลเดิมอาจกู้คืนได้ด้วยเครื่องมือพิเศษ)")
                        Storage.Wipe.FAILED -> errors.add("⚠ ${s.name}: เข้ารหัสแล้ว แต่ลบต้นฉบับไม่ได้ (เช่น ไฟล์บนคลาวด์) – ต้นฉบับยังอยู่")
                    }
                }
            }

            for ((i, s) in sources.withIndex()) {
                if (cancelRequested) { cancelled = true; break }
                val sz = maxOf(0L, s.size)
                job.file(i + 1, s.name, sz, "เข้ารหัส")
                val srcHash = java.security.MessageDigest.getInstance("SHA-256")
                try {
                    val fd = folderFor(s.folderPath)
                    val p = s.open(app).use { input ->
                        val counted = CountingInput(input) { n -> checkCancel(); job.advance(n) }
                        vault.encryptPending(java.security.DigestInputStream(counted, srcHash), s.name, s.mime, fd, s.size) { pct ->
                            rep.label("เข้ารหัส ${i + 1}/${sources.size}  ${s.name}  $pct")
                        }
                    }
                    batch.add(Done(i, s, p, srcHash.digest()))
                } catch (e: Throwable) {
                    if (e is CancellationException) { flush(); throw e }
                    if (e is JobCancelled) { cancelled = true; break }
                    errors.add("${s.name}: เข้ารหัสไม่สำเร็จ – ${e.message ?: e.javaClass.simpleName}")
                    continue
                }
                if (batch.size >= 100 || android.os.SystemClock.elapsedRealtime() - lastFlush > 3000) flush()
            }
            flush()                                            // ชุดสุดท้าย (รวมตอนกดยกเลิก: ไฟล์ที่เข้ารหัสเสร็จแล้วเก็บไว้)
            if (foldersUnsaved) synchronized(vault) { try { vault.saveIndex() } catch (_: Exception) {} }
            if (wipe && bases.isNotEmpty()) {
                // ลบโฟลเดอร์ต้นทางที่ว่างแล้ว (ไม่ทิ้งชื่อโฟลเดอร์ไว้) – ไม่ลบโฟลเดอร์มาตรฐาน เช่น DCIM, Pictures, Download
                val basePaths = bases.map { it.absolutePath.trimEnd('/') }
                val dirs = sources.mapNotNull { it.file?.parentFile }.toSet() + bases
                for (d in dirs.sortedByDescending { it.absolutePath.length }) {
                    var cur: File? = d
                    while (cur != null) {
                        val cp = cur.absolutePath.trimEnd('/')
                        if (basePaths.none { bp -> cp == bp || cp.startsWith("$bp/") }) break
                        if (Storage.isStandardDir(cur) || (cur.list()?.isNotEmpty() != false)) break
                        if (!cur.delete()) break
                        Storage.forgetMedia(app, cur.absolutePath)     // ไม่ให้ชื่อโฟลเดอร์ค้างในแอป Files ของระบบ
                        cur = cur.parentFile
                    }
                }
            }
        }
        if (gen != lockGen || !vault.isUnlocked) return
        refresh()
        val head = if (cancelled) "ยกเลิกแล้ว – เข้ารหัสเสร็จ $added จาก ${sources.size} ไฟล์ (ไฟล์ที่เหลือไม่ได้แตะ ต้นฉบับยังอยู่)" else null
        if (errors.isNotEmpty() || cancelled) show(AskInfo(if (cancelled) "ยกเลิกการนำเข้า" else "บางรายการมีปัญหา",
            listOfNotNull(head).plus(errors.take(15)).joinToString("\n")))
        else notice = "เข้ารหัสแล้ว $added ไฟล์" + if (wipe) " • ลบต้นฉบับแบบเขียนทับ $wiped ไฟล์" else ""
    }

    // ================================================================== โฟลเดอร์ / ข้อความ / เปลี่ยนชื่อ / ย้าย / ลบ
    fun newFolder() = flow {
        val name = show(AskInput("โฟลเดอร์ใหม่", "ชื่อโฟลเดอร์", hint = "ชื่อถูกเข้ารหัสในตู้ ไม่มีใครเห็นจากภายนอก")) ?: return@flow
        if (name.isBlank()) return@flow
        val parent = if (query.isNotBlank()) null else folder
        task("กำลังสร้างโฟลเดอร์…") { vault.createFolder(name, parent) }
        query = ""; searchOpen = false
        refresh()
    }

    /** สร้างไฟล์ข้อความใหม่ในตู้ (เข้ารหัสตั้งแต่แรก ไม่มีไฟล์ .txt ธรรมดาลงดิสก์) แล้วเปิดแก้ไขทันที */
    fun newText() = flow {
        val def = "บันทึก " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date()) + ".txt"
        var name = show(AskInput("ไฟล์ข้อความใหม่", "ชื่อไฟล์", def, hint = "ชื่อถูกเข้ารหัสในตู้ ไม่มีใครเห็นจากภายนอก")) ?: return@flow
        name = Vault.cleanName(name.trim())
        if (Mime.ext(name) !in Mime.TEXT_EXT) name += ".txt"
        val parent = if (query.isNotBlank()) null else folder
        val fid = task("กำลังสร้างไฟล์…") { vault.addBytes(ByteArray(0), name, parent) }
        query = ""; searchOpen = false
        refresh()
        val ids = listOf(fid)
        viewer = ViewerState(ids, 0, focusText = true)
    }

    fun rename(id: String) = flow {
        val old = synchronized(vault) {
            if (Vault.isDir(id)) vault.folders()[id].asMap()["name"].asStr() else vault.files()[id].asMap()["name"].asStr()
        } ?: return@flow
        val new = show(AskInput("เปลี่ยนชื่อ", "ชื่อใหม่", old)) ?: return@flow
        if (new.isBlank() || new.trim() == old) return@flow
        task("กำลังเปลี่ยนชื่อ…") { if (Vault.isDir(id)) vault.renameFolder(id, new.trim()) else vault.rename(id, new.trim()) }
        refresh()
    }

    fun move(ids: List<String>) = flow {
        if (ids.isEmpty()) return@flow
        val (ok, dest) = show(AskMove(ids.filter { Vault.isDir(it) }.toSet()))
        if (!ok) return@flow
        task("กำลังย้าย…") { vault.move(ids.filter { !Vault.isDir(it) }, ids.filter { Vault.isDir(it) }, dest) }
        selected.clear()
        refresh()
        notice = "ย้ายแล้ว ${ids.size} รายการ"
    }

    /** โฟลเดอร์ทั้งหมด (สำหรับหน้าต่างย้าย) → (id, ชื่อ, ระดับความลึก) เรียงแบบต้นไม้ */
    fun folderTree(exclude: Set<String>): List<Triple<String, String, Int>> = synchronized(vault) {
        if (!vault.isUnlocked) return emptyList()
        val dirs = vault.folders()
        val kids = HashMap<String?, MutableList<String>>()
        for (d in dirs.keys) kids.getOrPut(vault.parentOf(d)) { ArrayList() }.add(d)
        val out = ArrayList<Triple<String, String, Int>>()
        fun add(p: String?, depth: Int) {
            for (d in (kids[p] ?: emptyList()).sortedBy { (dirs[it].asMap()["name"].asStr() ?: "").lowercase() }) {
                if (d in exclude) continue
                out.add(Triple(d, dirs[d].asMap()["name"].asStr() ?: "?", depth))
                add(d, depth + 1)
            }
        }
        add(null, 0)
        out
    }

    fun createFolderIn(name: String, parent: String?): String? = try {
        synchronized(vault) { vault.createFolder(name, parent) }.also { refresh() }
    } catch (e: Exception) { showError(e); null }

    fun delete(ids: List<String>, after: (() -> Unit)? = null) = flow {
        if (ids.isEmpty()) return@flow
        val dsel = ids.filter { Vault.isDir(it) }
        val fsel = ids.filter { !Vault.isDir(it) }
        val msg = if (dsel.isNotEmpty()) {
            val total = expand(ids).size
            "ลบ ${dsel.size} โฟลเดอร์" + (if (fsel.isNotEmpty()) " และ ${fsel.size} ไฟล์" else "") +
                "\nรวมไฟล์ทั้งหมด $total ไฟล์ (รวมทุกอย่างในโฟลเดอร์ย่อย)\n\nลบออกจากตู้ถาวร? กู้คืนไม่ได้"
        } else "ลบ ${fsel.size} ไฟล์ออกจากตู้ถาวร?\nกู้คืนไม่ได้ (เขียนทับแล้วลบ)"
        if (!show(AskConfirm("ลบ", msg, "ลบ", danger = true))) return@flow
        if (viewer != null) releasePlayer()
        val gone = task("กำลังลบ…") { rep -> vault.deleteMany(fsel, dsel) { rep.label(it) } }
        val all = gone.toSet() + dsel
        all.forEach { dropThumb(it) }
        textSession?.let { if (it.fid in all) textSession = null }
        selected.clear()
        viewer?.let { v ->
            val rest = v.ids.filter { it !in all }
            val i = v.ids.indexOfFirst { it in all }.coerceAtLeast(0)
            viewer = if (rest.isEmpty()) null else ViewerState(rest, i.coerceAtMost(rest.size - 1))
        }
        refresh()
        after?.invoke()
    }

    // ================================================================== ส่งออก / แชร์ .bsx / นำเข้า .bsx / เปิดด้วยแอปอื่น
    private suspend fun askStrip(fids: List<String>): Boolean? {
        val kinds = fids.mapNotNull { entryOf(it)?.kind }
        val photos = kinds.count { it == "image" }
        val vids = kinds.count { it == "video" }
        if (photos == 0) return false
        val note = if (vids > 0) "\n\n(วิดีโอ $vids ไฟล์ยังคงข้อมูลเดิม – ลบไม่ได้โดยไม่แปลงไฟล์)" else ""
        return show(AskChoice("ข้อมูลแฝงในรูป",
            "รูป $photos ไฟล์อาจมี ตำแหน่ง GPS ที่ถ่าย, วันเวลา, รุ่นกล้อง/มือถือ แฝงอยู่\n\nลบข้อมูลเหล่านี้ออกก่อนไหม? (แนะนำ: ลบ)\n" +
                "• ลบ = บันทึกรูปใหม่คุณภาพสูง หมุนภาพให้ถูกทิศ\n• ไม่ลบ = ส่งไฟล์ต้นฉบับตามเดิม$note",
            "ลบข้อมูลแฝง", "ไม่ลบ"))
    }

    fun exportItems(ids: List<String>) = flow {
        val gen = lockGen
        val items = expand(ids)
        if (items.isEmpty()) return@flow
        if (!show(AskConfirm("ส่งออกแบบถอดรหัส", "ไฟล์ ${items.size} ไฟล์จะถูกบันทึกเป็นไฟล์ปกติที่ไม่มีการเข้ารหัส\n" +
                "(โฟลเดอร์ที่เลือกจะถูกสร้างตามโครงสร้างเดิม)\nแอปอื่น/ใครที่เข้าถึงมือถือจะเปิดดูได้\n\nยืนยันส่งออก?", "ส่งออก", danger = true))) return@flow
        val strip = askStrip(items.map { it.first }) ?: return@flow
        val uri = pickTree() ?: return@flow
        val dest = Dest.fromTree(app, uri) ?: throw VaultException("เขียนลงโฟลเดอร์นี้ไม่ได้")
        if (dest.isInsideOrSame(vault.root)) throw VaultException("ห้ามส่งออกไปในโฟลเดอร์ตู้ – เลือกที่อื่น")
        var kept = 0
        val written = ArrayList<File>()
        val failed = ArrayList<String>()
        var okCount = 0
        var cancelled = false
        val tree = holdTree(uri, dest)
        try { task("กำลังถอดรหัส…", true, keepAlive = true) { rep ->
            val sizes = items.map { maxOf(0L, entryOf(it.first)?.size ?: 0L) }
            val job = rep.start("กำลังส่งออก (ถอดรหัส + ตรวจอ่านกลับ)", items.size, sizes.sum() * 2, sizes.sum())
            // โฟลเดอร์ปลายทางที่สร้าง/หาไว้แล้ว – SAF หาโฟลเดอร์ย่อยด้วยการไล่รายชื่อทั้งโฟลเดอร์ (ช้ามากถ้าทำทุกไฟล์)
            val dirs = HashMap<List<String>, Dest>()
            fun dirFor(path: List<String>): Dest {
                if (path.isEmpty()) return dest
                dirs[path]?.let { return it }
                val parent = dirFor(path.dropLast(1))
                val seg = Mime.safeName(path.last())
                if (parent is Dest.Local && Dest.isPathInsideOrSame(File(parent.dir, seg), vault.root)) {
                    throw VaultException("ห้ามส่งออกไปในโฟลเดอร์ตู้ – เลือกที่อื่น")
                }
                val sub = parent.child(seg)
                if (sub.isInsideOrSame(vault.root)) {
                    throw VaultException("ห้ามส่งออกไปในโฟลเดอร์ตู้ – เลือกที่อื่น")
                }
                return sub.also { dirs[path] = it }
            }
            for ((i, p) in items.withIndex()) {
                if (cancelRequested) { cancelled = true; break }
                val (fid, path) = p
                val e = entryOf(fid)
                if (e == null) { failed.add("(ไฟล์ถูกลบออกจากตู้ระหว่างส่งออก)"); continue }
                val sz = sizes[i]
                job.file(i + 1, e.name, sz * 2, "ถอดรหัส")
                rep.label("ถอดรหัส ${i + 1}/${items.size}  ${e.name}")
                try {
                    val d = dirFor(path)
                    val res = if (strip && e.kind == "image") Media.stripMetadata(vault, fid) else null
                    if (strip && e.kind == "image" && res == null) kept++
                    val out = if (res != null) {
                        val stem = Mime.safeName(e.name).substringBeforeLast('.', Mime.safeName(e.name))
                        try { writeVerified(d, stem + res.second, Mime.guess(stem + res.second, null), tree) { it.write(res.first) } }
                        finally { java.util.Arrays.fill(res.first, 0) }
                    } else {
                        writeVerified(d, Mime.safeName(e.name), e.mime, tree, { ph, n ->
                            if (ph == 1) job.phase = "ตรวจอ่านกลับ"
                            job.advance(if (ph == 0) n else sz + n)
                        }) { o -> vault.decryptTo(fid, o) }
                    }
                    if (d is Dest.Local) written.add(File(out))
                    okCount++
                } catch (ex: Exception) {
                    if (ex is CancellationException) throw ex
                    if (ex is JobCancelled) { cancelled = true; break }
                    failed.add("${e.name}: ${ex.message ?: ex.javaClass.simpleName}")
                }
            }
            Storage.scan(app, written)
        } } finally { releaseTree(tree) }
        if (gen != lockGen || !vault.isUnlocked) return@flow
        val extra = if (kept > 0) "\n($kept รูปลบข้อมูลแฝงไม่ได้ เช่น HEIC – ส่งออกแบบเดิม)" else ""
        if (cancelled) show(AskInfo("ยกเลิกการส่งออก", "ส่งออกเสร็จ $okCount จาก ${items.size} ไฟล์ (ไฟล์ที่เสร็จตรวจอ่านกลับแล้ว • ไฟล์ที่ค้างครึ่งทางถูกลบทิ้ง)" +
            (if (failed.isNotEmpty()) "\n\nไม่สำเร็จ ${failed.size} ไฟล์:\n" + failed.take(10).joinToString("\n") else "") + extra))
        else if (failed.isEmpty()) notice = "ส่งออกสำเร็จ $okCount ไฟล์ (ตรวจอ่านกลับแล้วตรงทุกไบต์)" + extra.replace("\n", " ")
        else show(AskInfo("ส่งออกไม่ครบ", "สำเร็จ $okCount จาก ${items.size} ไฟล์ (ไฟล์ที่สำเร็จตรวจอ่านกลับแล้วตรงทุกไบต์)\n\n" +
            "ไม่สำเร็จ ${failed.size} ไฟล์ (ลบไฟล์ที่เขียนไม่ครบทิ้งแล้ว):\n" + failed.take(15).joinToString("\n") + extra))
    }

    /** SAF (เช่น ไดรฟ์คลาวด์): ขอสิทธิ์โฟลเดอร์ไว้ชั่วคราว – ถ้าแอปถูกปิดกลางคัน ครั้งหน้ายังลบไฟล์ที่ค้างได้ */
    private fun holdTree(uri: Uri, d: Dest): String? {
        if (d !is Dest.Saf) return null
        return try {
            app.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            uri.toString()
        } catch (_: Exception) { null }
    }

    private fun releaseTree(t: String?) {
        if (t == null || journal.entries().values.any { it["tree"] == t }) return     // ยังมีของค้างให้ลบ
        try {
            app.contentResolver.releasePersistableUriPermission(Uri.parse(t),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
    }

    private fun journalOut(kind: String, p: String, tree: String?): String? = try {
        journal.add(linkedMapOf<String, Any?>("k" to kind, "p" to p).also { if (tree != null) it["tree"] = tree })
    } catch (_: Exception) { null }

    /** เขียนไฟล์ส่งออกลงชื่อชั่วคราว (.bestspace-xxxx.part) → sync → อ่านกลับมาเทียบ SHA-256 → ค่อยตั้งชื่อจริง
     *  ไม่ตรง/ผิดพลาด = เขียนทับ+ลบไฟล์นั้นทิ้ง แล้วแจ้งว่าไม่สำเร็จ → คืน path/uri ของไฟล์ที่สำเร็จ
     *  ถูกขัดจังหวะ (แอปถูกปิด/แบตหมด) = ไม่มีไฟล์ชื่อจริงที่เขียนไม่ครบ และไฟล์ชั่วคราวถูกเก็บกวาดตอนเปิดแอปครั้งหน้า
     *  onBytes(phase, n): phase 0 = เขียน, 1 = อ่านกลับตรวจ */
    private fun writeVerified(d: Dest, name: String, mime: String, tree: String? = null,
                              onBytes: ((Int, Long) -> Unit)? = null, body: (java.io.OutputStream) -> Unit): String {
        val (os, path) = d.createTemp()
        val tok = journalOut("out", path, tree)
        try {
            val wrote = java.security.MessageDigest.getInstance("SHA-256")
            var n = 0L
            java.security.DigestOutputStream(os, wrote).use { o ->
                body(object : java.io.FilterOutputStream(o) {
                    override fun write(b: ByteArray, off: Int, len: Int) { checkCancel(); o.write(b, off, len); n += len; onBytes?.invoke(0, n) }
                    override fun write(b: Int) { o.write(b); n++ }
                    override fun close() {}
                })
            }
            val back = java.security.MessageDigest.getInstance("SHA-256")
            var m = 0L
            d.openRead(path).use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) { checkCancel(); val r = ins.read(buf); if (r < 0) break; back.update(buf, 0, r); m += r; onBytes?.invoke(1, m) }
                java.util.Arrays.fill(buf, 0)
            }
            if (m != n || !java.security.MessageDigest.isEqual(wrote.digest(), back.digest()))
                throw java.io.IOException("ไฟล์ที่เขียนไม่ตรงกับต้นฉบับ (เขียน $n ไบต์ อ่านกลับได้ $m ไบต์)")
            val out = d.commit(path, name, mime)            // ตรวจผ่านแล้ว → ตั้งชื่อจริง
            journal.remove(tok)
            return out
        } catch (ex: Exception) {
            val gone = try { d.discard(path); true } catch (_: Exception) { false }
            if (gone) journal.remove(tok)                   // ลบไม่ได้ = เก็บรายการไว้ ลบให้ครั้งหน้า
            throw ex
        }
    }

    /** คัดลอกลงไฟล์ชื่อจริงในโฟลเดอร์ชั่วคราว (.part) + อ่านกลับเทียบ (ใช้กับสำรองตู้ – ทั้งโฟลเดอร์เป็นของชั่วคราวอยู่แล้ว) */
    private fun copyVerified(d: Dest, name: String, src: () -> java.io.InputStream, onBytes: (Int, Long) -> Unit) {
        val (os, path) = d.create(name, "application/octet-stream")
        val h = java.security.MessageDigest.getInstance("SHA-256")
        var n = 0L
        os.use { o ->
            src().use { i ->
                val buf = ByteArray(1 shl 16)
                while (true) { checkCancel(); val r = i.read(buf); if (r < 0) break; o.write(buf, 0, r); h.update(buf, 0, r); n += r; onBytes(0, n) }
            }
        }
        val b = java.security.MessageDigest.getInstance("SHA-256")
        var m = 0L
        d.openRead(path).use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) { val r = i.read(buf); if (r < 0) break; b.update(buf, 0, r); m += r; onBytes(1, m) }
        }
        if (m != n || !java.security.MessageDigest.isEqual(h.digest(), b.digest()))
            throw java.io.IOException("ไฟล์สำรอง $name ไม่ตรงกับต้นฉบับ (เขียน $n ไบต์ อ่านกลับได้ $m ไบต์)")
    }

    fun shareItems(ids: List<String>) = flow {
        val gen = lockGen
        val sel = expand(ids).map { it.first }
        if (sel.isEmpty()) { show(AskInfo("แชร์", "เลือกไฟล์ที่จะแชร์ก่อน")); return@flow }
        val strip = askStrip(sel) ?: return@flow
        val pwStr = askNewPassword("รหัสแชร์", "ตั้งรหัสสำหรับไฟล์ .bsx ที่จะแชร์/อัปคลาวด์ (ใช้รหัสเดียวกับตู้ก็ได้)") ?: return@flow
        val mode = show(AskShareMode(sel.size)) ?: return@flow
        var lastTree: Uri? = null
        val dest: Dest = if (mode == "folder") {
            val uri = pickTree() ?: return@flow
            lastTree = uri
            val d = Dest.fromTree(app, uri) ?: throw VaultException("เขียนลงโฟลเดอร์นี้ไม่ได้")
            if (d.isInsideOrSame(vault.root)) throw VaultException("ห้ามสร้างไฟล์แชร์ในโฟลเดอร์ตู้ – เลือกที่อื่น")
            d
        } else {
            shareDir.mkdirs(); Dest.Local(shareDir)
        }
        val made = ArrayList<File>()
        var okCount = 0
        var cancelled = false
        val failed = ArrayList<String>()
        val tree = if (mode == "folder") lastTree?.let { holdTree(it, dest) } else null
        val pw = pwStr.toCharArray()
        try {
            dropThumbsForScrypt()
            task("กำลังสร้างไฟล์แชร์…", true, keepAlive = true) { rep ->
                val sizes = sel.map { maxOf(0L, entryOf(it)?.size ?: 0L) }
                val job = rep.start("กำลังสร้างไฟล์แชร์ .bsx", sel.size, sizes.sum() * 2, sizes.sum())
                for ((i, fid) in sel.withIndex()) {
                    if (cancelRequested) { cancelled = true; break }
                    rep.label("สร้างไฟล์แชร์ ${i + 1}/${sel.size}")
                    val e = entryOf(fid)
                    if (e == null) { failed.add("(ไฟล์ถูกลบออกจากตู้ระหว่างสร้างไฟล์แชร์)"); continue }
                    job.file(i + 1, e.name, sizes[i] * 2, "เข้ารหัสด้วยรหัสแชร์")
                    // ไฟล์หนึ่งล้มเหลว = ข้ามไปทำไฟล์ที่เหลือต่อ แล้วรายงาน (เดิมทั้งงานหยุด ไฟล์ที่ทำไปแล้วค้างโดยไม่บอก)
                    val res = try { if (strip && e.kind == "image") Media.stripMetadata(vault, fid) else null }
                    catch (ex: Exception) { failed.add("${e.name}: ${ex.message ?: ex.javaClass.simpleName}"); continue }
                    // ชื่อไฟล์ .bsx เป็นเลขสุ่ม ไม่เปิดเผยชื่อจริงบนคลาวด์ – เขียนชื่อชั่วคราว + อ่านกลับตรวจ แล้วค่อยตั้งชื่อ
                    val path = try {
                        writeVerified(dest, Crypto.hex(Crypto.random(6)) + ".bsx", "application/octet-stream", tree, { ph, n ->
                            if (ph == 1) job.phase = "ตรวจอ่านกลับ"
                            job.advance(if (ph == 0) minOf(n, sizes[i]) else sizes[i] + n)
                        }) { o ->
                            if (res != null) {
                                val name = e.name.substringBeforeLast('.', e.name) + res.second
                                vault.exportShareData(res.first, name, Mime.guess(name, "image/jpeg"), o, pw)
                            } else vault.exportShare(fid, o, pw)
                        }
                    } catch (ex: Exception) {
                        if (ex is CancellationException) throw ex
                        if (ex is JobCancelled) { cancelled = true; break }
                        failed.add("${e.name}: ${ex.message ?: ex.javaClass.simpleName}")
                        continue
                    } finally { res?.let { java.util.Arrays.fill(it.first, 0) } }
                    okCount++
                    if (dest is Dest.Local) made.add(File(path))
                }
            }
        } finally {
            Crypto.wipe(pw)
            releaseTree(tree)
        }
        if (gen != lockGen || !vault.isUnlocked) return@flow
        if (cancelled || failed.isNotEmpty()) {
            show(AskInfo(if (cancelled) "ยกเลิกการแชร์" else "แชร์ไม่ครบ", "สร้างไฟล์ .bsx สำเร็จ $okCount จาก ${sel.size} ไฟล์" +
                (if (failed.isNotEmpty()) "\n\nไม่สำเร็จ ${failed.size} ไฟล์:\n" + failed.take(10).joinToString("\n") else "") +
                (if (mode == "apps" && okCount > 0) "\n\nจะส่งเฉพาะไฟล์ที่สำเร็จ" else "")))
        }
        if (okCount == 0) return@flow
        if (mode == "apps") {
            val auth = app.packageName + ".files"
            val uris = ArrayList(made.map { FileProvider.getUriForFile(app, auth, it) })
            val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newUri(app.contentResolver, "bsx", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startExternal(Intent.createChooser(send, "ส่งไฟล์ .bsx (เข้ารหัสอยู่)").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } else {
            show(AskInfo("แชร์แบบเข้ารหัส", "สร้างไฟล์ .bsx $okCount ไฟล์แล้ว (${dest.label})\n\n" +
                "อัปขึ้นคลาวด์หรือส่งต่อได้เลย ไฟล์ยังเข้ารหัส AES-256 อยู่\nเปิดได้ด้วย BestSpace → 'นำเข้า .bsx' + รหัสแชร์เท่านั้น"))
        }
    }

    fun importBsx() = flow {
        val gen = lockGen
        val sources = pickSourcesInAppOrSystem(PickMode.BSX, arrayOf("*/*"))
        if (sources.isEmpty()) return@flow
        val pwStr = show(AskInput("รหัสแชร์", "ใส่รหัสแชร์ของไฟล์ .bsx", password = true)) ?: return@flow
        val target = if (query.isNotBlank()) null else folder
        val errors = ArrayList<String>()
        var okCount = 0
        var cancelled = false
        val pw = pwStr.toCharArray()
        try {
            dropThumbsForScrypt()
            task("กำลังนำเข้า…", true, keepAlive = true) { rep ->
                val sizes = sources.map { maxOf(0L, it.size) }
                val job = rep.start("กำลังนำเข้า .bsx", sources.size, sizes.sum())
                for ((i, s) in sources.withIndex()) {
                    if (cancelRequested) { cancelled = true; break }
                    rep.label("นำเข้า ${i + 1}/${sources.size}")
                    val name = s.name
                    job.file(i + 1, name, sizes[i], "คัดลอก + ตรวจความสมบูรณ์")
                    try {
                        s.open(app).use {
                            vault.importShare(CountingInput(it) { n -> checkCancel(); job.advance(n) }.buffered(1 shl 16), name, pw, target)
                        }
                        okCount++
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (e is JobCancelled) { cancelled = true; break }
                        errors.add(e.message ?: "$name: ผิดพลาด")
                    }
                }
            }
        } finally { Crypto.wipe(pw) }
        if (gen != lockGen || !vault.isUnlocked) return@flow
        refresh()
        if (errors.isNotEmpty() || cancelled) show(AskInfo("นำเข้า .bsx", "นำเข้าสำเร็จ $okCount จาก ${sources.size} ไฟล์" +
            (if (cancelled) " (ยกเลิกแล้ว)" else "") + (if (errors.isNotEmpty()) "\n\n" + errors.take(15).joinToString("\n") else "")))
        else notice = "นำเข้าแล้ว $okCount ไฟล์"
    }

    fun openExternal(fid: String) = flow {
        val gen = lockGen
        val e = entryOf(fid) ?: return@flow
        if (Mime.isDangerous(e.name)) {
            show(AskInfo("เปิดไม่ได้", "“${e.name}” เป็นไฟล์โปรแกรม/แพ็กเกจแอป ซึ่งการ 'เปิด' คือการติดตั้ง/รันทันที\n" +
                "BestSpace ไม่เปิดไฟล์ประเภทนี้จากตู้ เพื่อกันโปรแกรมอันตรายที่แฝงมากับไฟล์ .bsx\n\n" +
                "ถ้ามั่นใจว่าปลอดภัย ใช้ \"ส่งออก\" แล้วสแกนไวรัสก่อนเปิดเอง"))
            return@flow
        }
        val mediaNote = if (e.media || e.audio) "⚠ ไฟล์ประเภทนี้เปิดดูในแอปได้โดยไม่ทิ้งร่องรอย – แอปอื่นมักสร้างภาพย่อ/ประวัติไฟล์ล่าสุดของตัวเองไว้\n\n" else ""
        if (!show(AskConfirm("เปิดด้วยแอปอื่น", mediaNote +
                "ไฟล์จะถูกถอดรหัสชั่วคราวในพื้นที่ส่วนตัวของแอป เพื่อให้แอปอื่นเปิดได้\n" +
                "และจะถูกเขียนทับ+ลบเมื่อล็อคหรือปิด BestSpace\n(แก้ไขในแอปนั้นจะไม่ถูกบันทึกกลับเข้าตู้)\n\nดำเนินการต่อ?", "เปิด"))) return@flow
        val out = task("กำลังถอดรหัสชั่วคราว…", true) { rep ->
            openDir.walkBottomUp().forEach { x -> if (x.isFile) Vault.secureDelete(x) else if (x != openDir) x.delete() }
            val dir = File(openDir, Crypto.hex(Crypto.random(6))).also { it.mkdirs() }
            val f = File(dir, Storage.randomTempName(e.name))
            try { f.outputStream().use { o -> vault.decryptTo(fid, o) { n -> rep.frac(n.toFloat() / maxOf(1L, e.size)) } } }
            catch (ex: Exception) { Vault.secureDelete(f); throw ex }
            f
        }
        if (gen != lockGen || !vault.isUnlocked) return@flow
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", out)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startExternal(Intent.createChooser(view, "เปิดด้วย").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    // ================================================================== สำรอง / ล้างแคช
    fun backupVault() = flow {
        val gen = lockGen
        val uri = pickTree() ?: return@flow
        val dest = Dest.fromTree(app, uri) ?: throw VaultException("เขียนลงโฟลเดอร์นี้ไม่ได้")
        if (dest.isInsideOrSame(vault.root)) throw VaultException("ห้ามสำรองไว้ในโฟลเดอร์ตู้เอง – เลือกที่อื่น")
        val tree = holdTree(uri, dest)
        val name = try { task("กำลังสำรองตู้…", true, keepAlive = true) { rep ->
            val baseName = "bestspace-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val folderName = when (dest) {
                is Dest.Local -> {
                    var n = baseName; var idx = 2
                    while (File(dest.dir, n).exists() || File(dest.dir, "$n.part").exists()) n = "$baseName-${idx++}"
                    n
                }
                is Dest.Saf -> {
                    var n = baseName; var idx = 2
                    while (dest.doc.findFile(n) != null || dest.doc.findFile("$n.part") != null) n = "$baseName-${idx++}"
                    n
                }
            }
            // เขียนลงโฟลเดอร์ <ชื่อ>.part ก่อน → อ่านกลับเทียบทุกไฟล์ → ค่อยเปลี่ยนเป็นชื่อจริง
            // ถูกขัดจังหวะ = ไม่มีโฟลเดอร์สำรองที่ดูเหมือนครบแต่จริงๆ ขาด (โฟลเดอร์ .part ถูกลบตอนเปิดแอปครั้งหน้า)
            val t = dest.child("$folderName.part")
            val tok = journalOut("outdir", t.path, tree)
            try {
                val fd = t.child("files")
                val idx = synchronized(vault) { vault.indexFile.readBytes() }
                val list = vault.filesDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".bsf") || it.name.endsWith(".bst")) } ?: emptyList()
                val total = vault.metaFile.length() + idx.size + list.sumOf { it.length() }
                val job = rep.start("กำลังสำรองตู้ (+ ตรวจอ่านกลับ)", list.size + 2, total * 2, total)
                var i = 0
                fun put(d: Dest, n: String, size: Long, src: () -> java.io.InputStream) {
                    i++
                    job.file(i, "", size * 2, "")
                    copyVerified(d, n, src) { ph, b -> job.advance(if (ph == 0) b else size + b) }
                }
                put(t, "vault.json", vault.metaFile.length()) { vault.metaFile.inputStream() }
                put(t, "index.enc", idx.size.toLong()) { idx.inputStream() }
                for (f in list) put(fd, f.name, f.length()) { f.inputStream() }
                t.renameSelf(folderName)
                journal.remove(tok)
            } catch (e: Exception) {
                val gone = try { if (t is Dest.Local) t.dir.deleteRecursively() else
                    android.provider.DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(t.path)) } catch (_: Exception) { false }
                if (gone) journal.remove(tok)
                throw e
            }
            folderName
        } } finally { releaseTree(tree) }
        if (gen != lockGen || !vault.isUnlocked) return@flow
        show(AskInfo("สำรองข้อมูล", "สำรองเสร็จ:\n${dest.label}/$name\n\nกู้คืน: เปลี่ยนชื่อโฟลเดอร์นี้เป็น ${Storage.DEFAULT_VAULT_NAME} " +
            "แล้วกด \"เลือกตู้อื่น\" ชี้ไปที่โฟลเดอร์นั้น แล้วเปิดด้วยรหัสผ่านหลักตอนสำรอง" +
            if (vault.needsKeyfile()) "\n\n🔑 ตู้นี้ใช้ไฟล์กุญแจ: ไฟล์สำรองนี้ไม่มีกุญแจอยู่ข้างใน (ตั้งใจ) – เก็บไฟล์กุญแจสำรองไว้คนละที่" else ""))
    }

    fun clearCache() = flow {
        val opts = show(AskClear()) ?: return@flow
        val report = ArrayList<String>()
        if ("mem" in opts) {
            viewer = null; releasePlayer()
            val old = clearThumbs()
            Handler(Looper.getMainLooper()).postDelayed({ old.forEach { Media.wipeBitmap(it) } }, 800)
            thumbEpoch++
        }
        if ("tmp" in opts) clearClipboard(true)
        task("กำลังล้าง…") {
            if ("tmp" in opts) { wipeTemp(); report.add("ไฟล์ชั่วคราว (เปิดด้วยแอปอื่น / .bsx ที่แชร์) + คลิปบอร์ด: ล้างแล้ว") }
            if ("mem" in opts) { System.gc(); report.add("หน่วยความจำ: ล้างภาพย่อ/รูปที่ถอดรหัสแล้ว") }
            if ("ghosts" in opts) report.add("รายการไฟล์/โฟลเดอร์ที่ลบแล้วแต่ค้างในแอป Files ของระบบ: ลบ ${Storage.forgetMissingMedia(app)} รายการ")
            if ("systhumbs" in opts) report.add("ภาพย่อของระบบ (.thumbnails): เขียนทับ+ลบ ${Storage.wipeAllSystemThumbs()} ไฟล์")
        }
        if ("quit" in opts) { lockNow(null); bridge?.let { b -> (b as? android.app.Activity)?.finishAndRemoveTask() }; android.os.Process.killProcess(android.os.Process.myPid()); return@flow }
        refresh()
        show(AskInfo("ล้างแคชและร่องรอย", report.joinToString("\n") { "✓ $it" }.ifEmpty { "ไม่ได้เลือกอะไร" }))
    }

    fun fileInfo(fid: String) = flow {
        val e = entryOf(fid) ?: return@flow
        val raw = synchronized(vault) { vault.files()[fid].asMap() }
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        val mod = raw["modified"]?.let { "\nแก้ไขล่าสุด: " + fmt.format(Date((it.asDouble() * 1000).toLong())) } ?: ""
        val kindTh = mapOf("image" to "รูปภาพ", "video" to "วิดีโอ", "doc" to "เอกสาร", "other" to "อื่นๆ")[e.kind] ?: e.kind
        show(AskInfo("ข้อมูลไฟล์", "${e.name}\n\nขนาด: ${Mime.humanSize(e.size)} (${e.size} ไบต์)\nชนิด: $kindTh (${e.mime})\n" +
            "อยู่ที่: ${location(fid)}\nเพิ่มเข้าตู้: ${fmt.format(Date((e.added * 1000).toLong()))}$mod" +
            (if (e.origin == "bsx") "\n\nที่มา: นำเข้าจากไฟล์ .bsx (มาจากคนอื่น – ระวังไฟล์อันตราย)" else "") +
            "\n\nเข้ารหัส AES-256-GCM ด้วยคีย์เฉพาะไฟล์นี้"))
    }

    fun securityInfo() = flow {
        val vault = if (fakeSession) newVault(vaultDir) else this@VaultModel.vault  // ตู้หลอก: แสดงข้อมูลตู้ที่เลือกไว้
        val kfStatus = if (!vault.needsKeyfile()) "ไม่ได้ใช้"
        else vault.keyfilePath()?.path
            ?: vault.keyfileFp()?.let { fp ->
                when {
                    appKeys.has(fp) -> "เก็บในตัวแอป (เข้ารหัส Android Keystore)"
                    keyUriOf(fp) != null -> "ตำแหน่งที่เลือกไว้: ${Uri.decode(keyUriOf(fp)?.lastPathSegment ?: "")}"
                    prefs.getString("keypath_$fp", null) != null -> "ตำแหน่งที่เลือกไว้: ${prefs.getString("keypath_$fp", null)}"
                    else -> null
                }
            } ?: "ไม่พบ!"
        show(AskInfo("ความปลอดภัย",
            "BestSpace v${appVersion(app)}  •  by Northxbar\n\n" +
            "• ตู้: ${vault.root.path}\n• ไฟล์กุญแจ: $kfStatus\n\n" +
                "• เข้ารหัส AES-256-GCM ทุกไฟล์ (คีย์แยกต่อไฟล์) • รหัสผ่าน: scrypt (N=2^17)\n" +
                "• ดู/เล่น/แก้ไขในแอป: ถอดรหัสในหน่วยความจำเท่านั้น ไม่มีไฟล์ถอดรหัสลงเครื่อง\n" +
                "• ห้ามแคปหน้าจอ/อัดหน้าจอ • ภาพในหน้าแอปล่าสุดเป็นสีดำ\n" +
                "• ล็อคทันทีเมื่อออกจากแอป/ปิดจอ • ล็อคเองเมื่อไม่ใช้ ${IDLE_LOCK_MS / 60000} นาที\n" +
                "• คีย์บอร์ดไม่จำสิ่งที่พิมพ์ในแอป • ล้างคลิปบอร์ดตอนล็อค\n" +
                "• ใส่รหัสผิดเกิน ${Lockout.FREE_TRIES} ครั้ง ต้องรอนานขึ้นเรื่อยๆ (จำไว้ทั้งในตู้และในแอป)\n" +
                "• แอปไม่มีสิทธิ์อินเทอร์เน็ต\n\nข้อจำกัด:\n" + Storage.wipeNotes().joinToString("\n") { "• $it" } +
                "\n• ระหว่างเปิดหน้าต่างเลือกไฟล์/แอปอื่น ตู้ยังไม่ล็อคจนกว่าจะไม่ได้ใช้ ${IDLE_LOCK_MS / 60000} นาที"))
    }

    // ================================================================== ภาพปกวิดีโอ / ไฟล์ข้อความ
    fun setCover(fid: String, posSec: Double) = flow {
        val r = task("กำลังบันทึกภาพปก…") {
            val fr = Media.videoFrame(vault, fid, posSec) ?: throw VaultException("ดึงเฟรมจากวิดีโอนี้ไม่ได้")
            val at = Math.round(posSec * 100) / 100.0
            Media.saveThumb(vault, fid, fr.first, fr.second, at)
            Media.wipeBitmap(fr.first)
            synchronized(vault) { vault.setCoverAt(fid, at) }     // ให้เครื่องอื่นสร้างภาพปกเดียวกันได้
            true
        }
        if (r) { dropThumb(fid); thumbEpoch++; refresh(); notice = "✓ ตั้งภาพปกเป็นเฟรมที่ ${Media.fmtDuration(posSec)} แล้ว" }
    }

    suspend fun loadText(fid: String): Pair<TextCodec.Decoded, Boolean>? = withContext(Dispatchers.IO) {
        val e = entryOf(fid) ?: return@withContext null
        if (e.size > Media.MAX_TEXT) return@withContext null
        val raw = vault.readBytes(fid, Media.MAX_TEXT)
        try { TextCodec.decode(raw) to true } finally { java.util.Arrays.fill(raw, 0) }
    }

    /** เข้ารหัสเนื้อหาใหม่ลงตู้จากหน่วยความจำ (ไฟล์ใหม่ + คีย์ใหม่ แล้วเขียนทับ/ลบของเดิม) */
    fun saveText(ts: TextSession, quiet: Boolean): Boolean {
        if (!ts.dirty) return true
        if (!vault.isUnlocked) return false
        if (busy != null) { if (!quiet) notice = "รอให้งานที่ทำอยู่เสร็จก่อน แล้วกดบันทึกอีกครั้ง"; return false }
        val text = ts.getText?.invoke() ?: return false
        if (synchronized(vault) { !vault.files().containsKey(ts.fid) }) return true
        val (data, used) = TextCodec.encode(text, ts.enc, ts.crlf)
        val note = if (used != ts.enc && ts.enc == "cp874") " (มีตัวอักษรที่ TIS-620 ไม่รองรับ – บันทึกเป็น UTF-8 แทน)" else ""
        val nid = try { vault.replaceBytes(ts.fid, data) }
        catch (e: Exception) { if (!quiet) showError(e); return false }
        finally { java.util.Arrays.fill(data, 0) }
        val old = ts.fid
        ts.fid = nid; ts.enc = used; ts.dirty = false
        viewer?.let { v -> viewer = v.copy(ids = v.ids.map { if (it == old) nid else it }, focusText = false) }
        if (!quiet) { notice = "✓ บันทึกแล้ว (เข้ารหัสในตู้) " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + note; refresh() }
        return true
    }

    /** ออกจากไฟล์ข้อความที่ยังไม่บันทึก → ถาม บันทึก / ไม่บันทึก / ยกเลิก → true = ออกได้ */
    suspend fun confirmLeaveText(): Boolean {
        val ts = textSession ?: return true
        if (!ts.dirty || !vault.isUnlocked) return true
        if (synchronized(vault) { !vault.files().containsKey(ts.fid) }) return true
        val r = show(AskChoice("ยังไม่ได้บันทึก", "\"${ts.name}\" มีการแก้ไขที่ยังไม่ได้บันทึก\n\nบันทึกก่อนออกไหม?", "บันทึก", "ไม่บันทึก"))
            ?: return false
        if (r) return saveText(ts, quiet = false)
        ts.dirty = false
        return true
    }

    fun closeViewer() = flow {
        if (!confirmLeaveText()) return@flow
        textSession = null
        releasePlayer()
        viewer = null
        fullscreen = false
        refresh()
    }

    override fun onCleared() {
        if (busy != null) {
            // หน้าจอถูกปิด (เช่น ปัดแอปทิ้ง) ระหว่างนำเข้า/ส่งออก: ให้งานทำต่อจนเสร็จ (บริการเบื้องหน้า) แล้วค่อยล็อค
            pendingLock = true; blind = true
            return
        }
        textAutosave(); releasePlayer()
        try { vault.sessionRelease() } catch (_: Exception) {}
        vault.lock()
        wipeTemp()
    }
}

internal fun pruneNestedFolders(folders: List<File>): List<File> {
    val resolved = folders.map { f ->
        val p = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }
        p.trimEnd('/') to f
    }.distinctBy { it.first }.sortedBy { it.first.length }
    val kept = ArrayList<Pair<String, File>>()
    for (item in resolved) {
        if (kept.none { (kp, _) -> item.first == kp || item.first.startsWith("$kp/") }) {
            kept.add(item)
        }
    }
    return kept.map { it.second }
}
