package com.bestspace.vault.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bestspace.vault.core.Crypto
import com.bestspace.vault.core.PinLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * รหัสล็อคหน้าโน้ต – เก็บใน SharedPreferences ของแอป (ไม่ถูกสำรองขึ้นคลาวด์ – ดู data_extraction_rules)
 * เก็บเฉพาะ salt + ค่า scrypt ของรหัส ไม่มีรหัสจริง • ตัวนับรหัสผิดเก็บลงดิสก์ (ปิดแอปแล้วไม่รีเซ็ต)
 */
object NotesLockStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notes_lock", Context.MODE_PRIVATE)

    fun isSet(ctx: Context): Boolean = load(ctx) != null

    fun load(ctx: Context): PinLock.Stored? = try {
        val p = prefs(ctx)
        val s = p.getString("salt", null); val h = p.getString("hash", null)
        if (s == null || h == null) null else PinLock.Stored(Crypto.unb64(s), Crypto.unb64(h))
    } catch (_: Exception) { null }

    fun save(ctx: Context, s: PinLock.Stored) {
        val bio = bio(ctx)
        prefs(ctx).edit().clear().putString("salt", Crypto.b64(s.salt)).putString("hash", Crypto.b64(s.hash))
            .putBoolean("bio", bio).commit()
    }

    /** ใช้ลายนิ้วมือแทน PIN ได้ (ต้องตั้ง PIN ไว้ด้วยเสมอ – ใช้ตอนสแกนไม่ผ่าน/ลายนิ้วมือเปลี่ยน) */
    fun bio(ctx: Context): Boolean = prefs(ctx).getBoolean("bio", false)
    fun setBio(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("bio", on).commit() }

    fun clear(ctx: Context) { prefs(ctx).edit().clear().commit() }

    fun fails(ctx: Context): Int = prefs(ctx).getInt("fails", 0)
    fun waitUntil(ctx: Context): Long {
        val u = prefs(ctx).getLong("until", 0L)
        val max = System.currentTimeMillis() + PinLock.MAX_WAIT_MS
        if (u > max) {
            prefs(ctx).edit().putLong("until", max).commit()
            return max
        }
        return u
    }

    fun recordFail(ctx: Context) {
        val f = fails(ctx) + 1
        prefs(ctx).edit().putInt("fails", f).putLong("until", System.currentTimeMillis() + PinLock.waitMs(f)).commit()
    }

    fun recordOk(ctx: Context) { prefs(ctx).edit().remove("fails").remove("until").commit() }
}

/** ตรวจรหัสในเธรดพื้นหลัง (scrypt ใช้เวลาเสี้ยววินาที) + นับรหัสผิด */
private suspend fun checkPin(ctx: Context, pin: CharArray): Boolean = withContext(Dispatchers.Default) {
    try {
        val s = NotesLockStore.load(ctx) ?: return@withContext true
        val ok = PinLock.verify(pin, s)
        if (ok) NotesLockStore.recordOk(ctx) else NotesLockStore.recordFail(ctx)
        ok
    } finally { Crypto.wipe(pin) }
}

/** ตัวนับเวลารอหลังใส่ผิดหลายครั้ง – คืนค่าวินาทีที่เหลือ (0 = ใส่ได้) */
@Composable
private fun rememberWaitSec(ctx: Context, key: Any): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var until by remember { mutableLongStateOf(0L) }
    LaunchedEffect(key) {
        until = NotesLockStore.waitUntil(ctx)
        while (System.currentTimeMillis() < until) { now = System.currentTimeMillis(); delay(500) }
        now = System.currentTimeMillis()
    }
    return ((until - now + 999) / 1000).coerceAtLeast(0)
}

/** ปุ่มตัวเลขสไตล์แอปโน้ต – ไม่เปิดคีย์บอร์ดของเครื่อง */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotesPinPad(buf: PinBuffer, onInput: () -> Unit, onDone: () -> Unit, enabled: Boolean) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    val view = LocalView.current
    val tap = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // จุดแสดงจำนวนหลักที่กดแล้ว (4 หลักตายตัว)
        Row(Modifier.height(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            repeat(PinLock.MAX_LEN) { i ->
                val on = i < buf.length
                Box(Modifier.size(14.dp).clip(CircleShape)
                    .background(if (on) NoteColors.Accent else NoteColors.Bg)
                    .border(1.5.dp, if (on) NoteColors.Accent else NoteColors.Muted, CircleShape))
            }
        }
        Spacer(Modifier.height(20.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { k ->
                    val on = enabled && when (k) {
                        "⌫" -> buf.length > 0
                        "✓" -> buf.length >= PinLock.MIN_LEN
                        else -> buf.length < PinLock.MAX_LEN
                    }
                    Box(Modifier.weight(1f).height(60.dp).shadow(1.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                        .background(if (k == "✓" && on) NoteColors.Accent else NoteColors.Card)
                        .combinedClickable(enabled = on,
                            onLongClick = { if (k == "⌫") { tap(); buf.clear(); onInput() } },
                            onClick = {
                                tap()
                                when (k) {
                                    "⌫" -> { buf.backspace(); onInput() }
                                    "✓" -> onDone()
                                    else -> { buf.append(k[0]); onInput() }
                                }
                            }),
                        contentAlignment = Alignment.Center) {
                        Text(k, fontSize = 24.sp,
                            color = if (k == "✓" && on) NoteColors.OnAccent else if (on) NoteColors.Fg else NoteColors.Muted)
                    }
                }
            }
        }
    }
}

/** หัว + ปุ่มตัวเลข ใช้ร่วมกันทั้งหน้าปลดล็อคและหน้าตั้งรหัส */
@Composable
private fun PinPage(
    title: String, sub: String, msg: String?, buf: PinBuffer, onInput: () -> Unit,
    onDone: () -> Unit, enabled: Boolean, onBack: (() -> Unit)? = null, bottom: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(NoteColors.Bg).verticalScroll(rememberScrollState())
        .padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "กลับ", tint = NoteColors.Fg)
            }
        }
        Spacer(Modifier.height(12.dp))
        Icon(Icons.Filled.Lock, null, tint = NoteColors.Ink, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NoteColors.Fg, textAlign = TextAlign.Center)
        Text(sub, fontSize = 13.sp, color = NoteColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        Text(msg ?: " ", fontSize = 13.sp, color = NoteColors.Danger, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, bottom = 10.dp))
        NotesPinPad(buf, onInput, onDone, enabled)
        Spacer(Modifier.height(8.dp))
        bottom()
        Spacer(Modifier.height(24.dp))
    }
}

/** หน้าใส่รหัสก่อนเข้าหน้าโน้ต */
@Composable
fun NotesLockScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pin = remember { PinBuffer(PinLock.MAX_LEN) }
    var checking by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var forgot by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { pin.clear() } }
    val waitSec = rememberWaitSec(ctx, attempt)
    val act = ctx as? androidx.fragment.app.FragmentActivity
    val bio = act != null && NotesLockStore.bio(ctx) && Bio.available(ctx)
    val scan: () -> Unit = {
        if (bio && !checking) scope.launch {
            when (val r = Bio.prompt(act!!, "Unit Converter", "สแกนนิ้วเพื่อเปิดแอป", "ใช้รหัส")) {
                is Bio.Result.Ok -> { NotesLockStore.recordOk(ctx); msg = null; onUnlocked() }
                is Bio.Result.Error -> msg = r.msg
                else -> {}
            }
        }
    }
    var autoScanned by remember { mutableStateOf(false) }
    LaunchedEffect(bio) { if (bio && !autoScanned) { autoScanned = true; delay(250); scan() } }

    val go: () -> Unit = {
        if (!checking && waitSec == 0L && pin.length >= PinLock.MIN_LEN) {
            val c = pin.takeChars(); checking = true
            scope.launch {
                val ok = checkPin(ctx, c)
                checking = false
                if (ok) { msg = null; onUnlocked() }
                else {
                    val f = NotesLockStore.fails(ctx)
                    msg = if (f < PinLock.FREE_TRIES) "รหัสไม่ถูกต้อง (เหลืออีก ${PinLock.FREE_TRIES - f} ครั้งก่อนต้องรอ)"
                    else "รหัสไม่ถูกต้อง $f ครั้ง"
                    attempt++
                }
            }
        }
    }
    PinPage(
        title = "Unit Converter",
        sub = "ใส่รหัสเพื่อเปิดแอป",
        msg = if (waitSec > 0) "ใส่รหัสผิดหลายครั้ง – รออีก ${fmtWait(waitSec)}" else if (checking) "กำลังตรวจรหัส…" else msg,
        buf = pin, onInput = {}, onDone = go, enabled = !checking && waitSec == 0L,
        bottom = {
            if (bio) TextButton(onClick = scan) {
                Icon(Icons.Filled.Fingerprint, null, tint = NoteColors.Ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("ใช้ลายนิ้วมือ", color = NoteColors.Ink)
            }
            TextButton(onClick = { forgot = true }) { Text("ลืมรหัส?", color = NoteColors.Muted) }
        },
    )
    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            title = { Text("ลืมรหัส") },
            text = { Text("รีเซ็ตได้ด้วยการล้างประวัติการแปลงทั้งหมดในแอปนี้แล้วเอารหัสออก") },
            confirmButton = {
                TextButton(onClick = {
                    forgot = false
                    DecoyConverter.clearHistory(ctx)
                    NotesLockStore.clear(ctx)
                    onUnlocked()
                }) { Text("ล้างประวัติและรีเซ็ต", color = NoteColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { forgot = false }) { Text("ยกเลิก") } },
        )
    }
}

private enum class Step { MENU, VERIFY_CHANGE, VERIFY_REMOVE, NEW, CONFIRM }

/** หน้าตั้งค่าของแอปแปลงหน่วย: ตั้ง / เปลี่ยน / ปิด รหัสล็อค */
@Composable
fun NotesSettingsScreen(onClose: () -> Unit, onLockNow: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasPin by remember { mutableStateOf(NotesLockStore.isSet(ctx)) }
    var bioOn by remember { mutableStateOf(NotesLockStore.bio(ctx)) }
    var step by remember { mutableStateOf(Step.MENU) }
    val pin = remember { PinBuffer(PinLock.MAX_LEN) }
    val first = remember { PinBuffer(PinLock.MAX_LEN) }
    var msg by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { onDispose { pin.clear(); first.clear() } }
    val waitSec = rememberWaitSec(ctx, attempt)

    fun goto(s: Step) { step = s; pin.clear(); msg = null; if (s != Step.CONFIRM) first.clear() }
    val back: () -> Unit = { if (step == Step.MENU) onClose() else goto(Step.MENU) }
    BackHandler(onBack = back)

    when (step) {
        Step.MENU -> Column(Modifier.fillMaxSize().background(NoteColors.Bg)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "กลับ", tint = NoteColors.Fg) }
                Text("ตั้งค่า", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NoteColors.Fg)
            }
            Text("รหัสล็อคแอป", fontSize = 13.sp, color = NoteColors.Muted, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp))
            Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(NoteColors.Card)) {
                if (!hasPin) {
                    SettingRow(Icons.Filled.Lock, "ตั้งรหัสล็อคแอป", "ต้องใส่รหัสทุกครั้งที่เปิดแอป") { goto(Step.NEW) }
                } else {
                    SettingRow(Icons.Filled.Lock, "ล็อคตอนนี้", "ปิดหน้าแอปจนกว่าจะใส่รหัส") { onLockNow() }
                    SettingRow(Icons.Filled.Password, "เปลี่ยนรหัส", null) { goto(Step.VERIFY_CHANGE) }
                    SettingRow(Icons.Filled.LockOpen, "ปิดรหัสล็อค", "เปิดแอปแล้วใช้งานทันที") { goto(Step.VERIFY_REMOVE) }
                    val act = ctx as? androidx.fragment.app.FragmentActivity
                    if (act != null && (bioOn || Bio.available(ctx))) {
                        SettingRow(Icons.Filled.Fingerprint, "ปลดล็อคด้วยลายนิ้วมือ",
                            if (bioOn) "เปิดอยู่ – แตะเพื่อปิด" else "ใช้ลายนิ้วมือแทนการพิมพ์รหัส") {
                            if (bioOn) { NotesLockStore.setBio(ctx, false); bioOn = false; info = "ปิดลายนิ้วมือแล้ว" }
                            else scope.launch {
                                when (val r = Bio.prompt(act, "ลายนิ้วมือ", "สแกนนิ้วเพื่อยืนยัน", "ยกเลิก")) {
                                    is Bio.Result.Ok -> { NotesLockStore.setBio(ctx, true); bioOn = true; info = "✓ เปิดแอปด้วยลายนิ้วมือได้แล้ว" }
                                    is Bio.Result.Error -> info = r.msg
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
            Text(if (hasPin) "✓ เปิดใช้รหัสล็อคอยู่ • แอปล็อคเองเมื่อออกจากแอปหรือปิดจอ"
                else "ตั้งรหัสเป็นตัวเลข ${PinLock.MIN_LEN} หลัก",
                fontSize = 12.sp, color = NoteColors.Muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
            info?.let { Text(it, fontSize = 13.sp, color = NoteColors.Ink, modifier = Modifier.padding(start = 20.dp, top = 10.dp)) }
        }

        Step.VERIFY_CHANGE, Step.VERIFY_REMOVE -> {
            val go: () -> Unit = {
                if (!checking && waitSec == 0L && pin.length >= PinLock.MIN_LEN) {
                    val c = pin.takeChars(); checking = true
                    scope.launch {
                        val ok = checkPin(ctx, c)
                        checking = false
                        when {
                            !ok -> { msg = "รหัสไม่ถูกต้อง"; attempt++ }
                            step == Step.VERIFY_REMOVE -> {
                                NotesLockStore.clear(ctx); hasPin = false; bioOn = false
                                info = "ปิดรหัสล็อคแล้ว"; goto(Step.MENU)
                            }
                            else -> goto(Step.NEW)
                        }
                    }
                }
            }
            PinPage("ใส่รหัสปัจจุบัน", if (step == Step.VERIFY_REMOVE) "เพื่อปิดรหัสล็อค" else "เพื่อเปลี่ยนรหัส",
                if (waitSec > 0) "ใส่รหัสผิดหลายครั้ง – รออีก ${fmtWait(waitSec)}" else if (checking) "กำลังตรวจรหัส…" else msg,
                pin, {}, go, !checking && waitSec == 0L, onBack = back)
        }

        Step.NEW -> PinPage("ตั้งรหัสใหม่", "ตัวเลข ${PinLock.MIN_LEN} หลัก", msg, pin, { msg = null }, {
            if (pin.length >= PinLock.MIN_LEN) {
                val c = pin.takeChars()
                try { first.clear(); c.forEach { first.append(it) } } finally { Crypto.wipe(c) }
                msg = null; step = Step.CONFIRM
            }
        }, !checking, onBack = back)

        Step.CONFIRM -> PinPage("ใส่รหัสอีกครั้ง", "เพื่อยืนยัน", if (checking) "กำลังบันทึก…" else msg, pin, { msg = null }, {
            if (!pin.matches(first)) { msg = "รหัสไม่ตรงกัน – เริ่มตั้งใหม่"; first.clear(); pin.clear(); step = Step.NEW }
            else if (!checking) {
                val c = pin.takeChars(); first.clear(); checking = true
                scope.launch {
                    val s = withContext(Dispatchers.Default) {
                        try { PinLock.create(c) } finally { Crypto.wipe(c) }
                    }
                    NotesLockStore.save(ctx, s); NotesLockStore.recordOk(ctx)
                    checking = false
                    info = if (hasPin) "✓ เปลี่ยนรหัสแล้ว" else "✓ ตั้งรหัสล็อคแล้ว"
                    hasPin = true
                    goto(Step.MENU)
                }
            }
        }, !checking, onBack = back)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = NoteColors.Ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = NoteColors.Fg, fontSize = 16.sp)
            sub?.let { Text(it, color = NoteColors.Muted, fontSize = 12.sp) }
        }
    }
}
