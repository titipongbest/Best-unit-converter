package com.bestspace.vault.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bestspace.vault.core.Crypto
import com.bestspace.vault.core.Lockout
import kotlinx.coroutines.delay

/** เลขเวอร์ชันของแอป (versionName จาก build) */
fun appVersion(ctx: android.content.Context): String =
    try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

@Composable
private fun Header(title: String, sub: String) {
    Spacer(Modifier.height(40.dp))
    Icon(Icons.Filled.Lock, null, tint = Palette.Accent, modifier = Modifier.size(56.dp))
    Text("BestSpace", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Fg)
    Text("v${appVersion(LocalContext.current)}  •  by Northxbar", color = Palette.Muted, fontSize = 12.sp)
    Text(title, color = Palette.Fg, fontSize = 16.sp, textAlign = TextAlign.Center)
    if (sub.isNotEmpty()) Text(sub, color = Palette.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(20.dp))
}

@Composable
fun PermissionScreen(requestAccess: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Header("ต้องอนุญาตให้เข้าถึงไฟล์", "")
        Icon(Icons.Filled.SdStorage, null, tint = Palette.Muted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("BestSpace เก็บตู้ไว้ที่ที่เก็บข้อมูลหลักของเครื่อง (/${Storage.DEFAULT_VAULT_NAME}) เหมือนโปรแกรมบนคอม\n\n" +
            "• ลบแอปแล้ว ตู้และไฟล์กุญแจยังอยู่ – ติดตั้งใหม่แล้วเปิดตู้เดิมได้\n" +
            "• ย้ายตู้ไปคอม/แฟลชไดรฟ์ได้ด้วยการคัดลอกโฟลเดอร์\n" +
            "• ลบไฟล์ต้นฉบับแบบเขียนทับหลังเข้ารหัสได้\n\n" +
            "Android จึงต้องให้สิทธิ์ \"เข้าถึงไฟล์ทั้งหมด\" – แอปนี้ไม่มีสิทธิ์อินเทอร์เน็ต ไฟล์ไม่ถูกส่งไปไหน",
            color = Palette.Fg, fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        Button(onClick = requestAccess, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("อนุญาตการเข้าถึงไฟล์") }
        Text("ในหน้าตั้งค่าที่เปิดขึ้น ให้เปิดสวิตช์ \"อนุญาตการเข้าถึงเพื่อจัดการไฟล์ทั้งหมด\" แล้วกดย้อนกลับ",
            color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun LocationCard(model: VaultModel) {
    val isDefault = model.vaultDir.absolutePath == Storage.defaultVault().absolutePath
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Panel).padding(12.dp)) {
        Text("ตำแหน่งตู้", color = Palette.Muted, fontSize = 12.sp)
        Text(model.vaultDir.absolutePath, color = Palette.Fg, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { model.browseVault() }) {
                Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text("เลือกตู้อื่น / สร้างที่อื่น…")
            }
            if (!isDefault) TextButton(onClick = { model.useDefaultLocation() }) { Text("ใช้ตำแหน่งเริ่มต้น") }
        }
    }
}

@Composable
fun SetupScreen(model: VaultModel) {
    val p1 = remember { SecretController() }
    val p2 = remember { SecretController() }
    var useKey by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { p1.clear(); p2.clear() } }
    val go: () -> Unit = {
        val c1 = p1.copyChars()
        val c2 = p2.copyChars()
        try {
            when {
                c1.size < VaultModel.MIN_PASSWORD -> { err = "รหัสต้องยาวอย่างน้อย ${VaultModel.MIN_PASSWORD} ตัวอักษร" }
                !charsEqual(c1, c2) -> { err = "รหัสทั้งสองช่องไม่ตรงกัน" }
                model.busy == null -> {
                    err = null
                    val pw = c1.copyOf()
                    p1.clear(); p2.clear()
                    model.create(pw, useKey)
                }
            }
        } finally {
            Crypto.wipe(c1); Crypto.wipe(c2)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Header("สร้างตู้เซฟใหม่ – ตั้งรหัสผ่านหลัก", "")
        LocationCard(model)
        Spacer(Modifier.height(16.dp))
        SecretField(p1, "รหัสผ่าน", Modifier.fillMaxWidth(), onChange = { err = null })
        Spacer(Modifier.height(8.dp))
        SecretField(p2, "ยืนยันรหัสผ่าน", Modifier.fillMaxWidth(), onChange = { err = null }, onDone = { go() })
        err?.let { Text(it, color = Palette.Danger, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth().clickable { useKey = !useKey }.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
            Checkbox(useKey, { useKey = it })
            Column(Modifier.padding(top = 12.dp)) {
                Text("🔑 ใช้ไฟล์กุญแจด้วย (แนะนำ)", color = Palette.Fg)
                Text("สร้าง bestspace.key ไว้ที่ ${Storage.keyDir().absolutePath} – คนที่ได้ไปแค่โฟลเดอร์ ${Storage.DEFAULT_VAULT_NAME} " +
                    "จะเดารหัสผ่านไม่ได้เลย (ต้องมีทั้งรหัสผ่าน + ไฟล์กุญแจ)", color = Palette.Muted, fontSize = 13.sp)
            }
        }
        Text("⚠ ลืมรหัส หรือไฟล์กุญแจหาย = เปิดไฟล์ไม่ได้อีกเลย ไม่มีทางกู้คืน", color = Palette.Warn, fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = { go() }, enabled = model.busy == null, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("สร้างตู้เซฟ") }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun UnlockScreen(model: VaultModel) {
    val pinBuf = remember { PinBuffer() }
    val secret = remember { SecretController() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var fails by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) { onDispose { pinBuf.clear(); secret.clear() } }
    LaunchedEffect(model.waitUntil) {
        fails = try { model.lockout().load().fails } catch (_: Exception) { 0L }
        while (System.currentTimeMillis() < model.waitUntil) { now = System.currentTimeMillis(); delay(500) }
        now = System.currentTimeMillis()
    }
    val needsKey = model.needsKey
    val waitSec = ((model.waitUntil - now + 999) / 1000).coerceAtLeast(0)
    val pwLen = if (model.numpad) pinBuf.length else secret.length
    val ver = appVersion(LocalContext.current)
    val go: () -> Unit = {
        if (pwLen > 0 && waitSec == 0L && model.busy == null) {
            val c = if (model.numpad) pinBuf.takeChars() else secret.takeChars()
            model.unlock(c)
        }
    }
    val msg = if (waitSec > 0) "ใส่รหัสผิด $fails ครั้ง – รออีก ${fmtWait(waitSec)}" else model.unlockMsg

    @Composable
    fun HeaderBlock(compactRow: Boolean) {
        if (compactRow) {
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = Palette.Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("BestSpace", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.Fg)
                Spacer(Modifier.width(6.dp))
                Text("v$ver", color = Palette.Muted, fontSize = 11.sp)
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Icon(Icons.Filled.Lock, null, tint = Palette.Accent, modifier = Modifier.size(36.dp))
            Text("BestSpace", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Fg)
            Text("v$ver  •  by Northxbar", color = Palette.Muted, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
        }
    }

    @Composable
    fun VaultInfoBlock() {
        Text("ใส่รหัสผ่านเพื่อเปิดตู้" + if (needsKey) "  •  🔑 ใช้ไฟล์กุญแจ" else "",
            color = Palette.Fg, fontSize = 14.sp, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(model.vaultDir.absolutePath, color = Palette.Muted, fontSize = 11.sp, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        VaultShortcuts(model)
    }

    @Composable
    fun SwitchModeButton() {
        TextButton(
            onClick = { pinBuf.clear(); secret.clear(); model.setNumpadMode(!model.numpad) },
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text(if (model.numpad) "รหัสมีตัวอักษร? พิมพ์ด้วยคีย์บอร์ด" else "ใช้ปุ่มตัวเลขในแอป", fontSize = 12.sp)
        }
    }

    @Composable
    fun ActionButtons(compactBtn: Boolean) {
        msg?.let {
            Text(it, color = if (it.startsWith("✓")) Palette.Ok else if (it.startsWith("ล็อค")) Palette.Muted else Palette.Danger,
                textAlign = TextAlign.Center, fontSize = 12.sp, lineHeight = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
        }
        Button(
            onClick = { go() },
            enabled = pwLen > 0 && waitSec == 0L && model.busy == null,
            modifier = Modifier.fillMaxWidth().height(if (compactBtn) 40.dp else 46.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text(if (waitSec > 0) "รออีก ${fmtWait(waitSec)}" else "ปลดล็อค")
        }
        Spacer(Modifier.height(if (compactBtn) 4.dp else 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { model.browseVault() },
                modifier = Modifier.weight(1f).height(if (compactBtn) 36.dp else 40.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp))
                Text("เลือกตู้อื่น…", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            }
            if (needsKey || model.keyMissing) OutlinedButton(
                onClick = { model.importKeyfile() },
                modifier = Modifier.weight(1f).height(if (compactBtn) 36.dp else 40.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Filled.Key, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp))
                Text("เลือกไฟล์กุญแจ…", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            }
        }
        model.rememberedKeyMode?.let { mode ->
            Row(Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(if (mode == "app") "🔑 จำกุญแจในแอปแล้ว (เข้ารหัส)" else "🔑 จำตำแหน่งกุญแจไว้แล้ว",
                    color = Palette.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(
                    onClick = { model.forgetRememberedKeyfile() },
                    modifier = Modifier.height(26.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text("ลืมกุญแจที่จำไว้", color = Palette.Danger, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Text("ใส่ผิดครบ ${Lockout.FREE_TRIES} ครั้ง จะเริ่มต้องรอนานขึ้นเรื่อยๆ (สูงสุด 1 ชั่วโมง)",
            color = Palette.Muted, fontSize = 11.sp, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 6.dp)) {
        val screenW = maxWidth
        val screenH = maxHeight
        val landscape = screenW > screenH && screenH < 480.dp
        val compact = screenH < 640.dp
        if (landscape) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    HeaderBlock(compactRow = true)
                    VaultInfoBlock()
                    Spacer(Modifier.height(4.dp))
                    ActionButtons(compactBtn = true)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    if (model.numpad) PinPad(pinBuf, onDone = { go() }, maxPadHeight = (screenH - 36.dp).coerceAtLeast(180.dp))
                    else SecretField(secret, "รหัสผ่าน", Modifier.fillMaxWidth(), autoFocus = true, onDone = { go() })
                    SwitchModeButton()
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                HeaderBlock(compactRow = compact)
                VaultInfoBlock()
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (model.numpad) {
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val padH = maxHeight
                            PinPad(pinBuf, onDone = { go() }, maxPadHeight = padH)
                        }
                    } else {
                        SecretField(secret, "รหัสผ่าน", Modifier.fillMaxWidth(), autoFocus = true, onDone = { go() })
                    }
                }
                SwitchModeButton()
                ActionButtons(compactBtn = compact)
            }
        }
    }
}

/** ปุ่มลัดตู้ที่บันทึกไว้: แตะ = สลับไปตู้นั้น (พร้อมไฟล์กุญแจที่เลือกไว้ของตู้นั้น) • กดค้าง = ลบปุ่ม */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultShortcuts(model: VaultModel) {
    val cur = model.vaultDir.absolutePath
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        model.shortcuts.forEach { (name, path) ->
            val on = path == cur
            Box(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp))
                .background(if (on) Palette.Accent else Palette.Panel)
                .combinedClickable(onClick = { model.useShortcut(path) }, onLongClick = { model.removeShortcut(path) })
                .padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                Text((if (on) "✓ " else "🗄 ") + name, fontSize = 12.sp, color = Palette.Fg, maxLines = 1)
            }
        }
        if (model.shortcuts.none { it.second == cur }) {
            Box(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).border(1.dp, Palette.Muted, RoundedCornerShape(15.dp))
                .clickable { model.addShortcut() }.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                Text("＋ บันทึกตู้นี้เป็นปุ่มลัด", fontSize = 12.sp, color = Palette.Muted, maxLines = 1)
            }
        }
    }
}

/** ปุ่มตัวเลขในแอป – ไม่เปิดคีย์บอร์ดของเครื่อง (คีย์บอร์ดภายนอกจึงไม่เห็นรหัสเลย) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinPad(buf: PinBuffer, onDone: () -> Unit, maxPadHeight: Dp = 310.dp) {
    var show by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }     // สลับมาจากโหมดคีย์บอร์ด → ปิดคีย์บอร์ดที่ค้างอยู่
    val view = LocalView.current
    val tap = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }   // สั่นเบาๆ ตอนกดแป้น (ไม่ต้องขอสิทธิ์เพิ่ม)
    val tiny = maxPadHeight < 225.dp
    val showLabel = maxPadHeight >= 195.dp
    val labelH = if (showLabel) 16.dp else 0.dp
    val dotsH = if (tiny) 32.dp else 40.dp
    val rowPad = if (tiny) 2.dp else 3.dp
    val keyH = ((maxPadHeight - labelH - dotsH - 4.dp - (rowPad * 8)) / 4).coerceIn(30.dp, 54.dp)
    val numSp = if (keyH < 38.dp) 18.sp else if (keyH < 46.dp) 21.sp else 24.sp
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showLabel) Text("รหัสผ่าน", fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(dotsH), verticalAlignment = Alignment.CenterVertically) {
            Text(if (show) buf.revealString() else "●".repeat(buf.length), color = Palette.Fg,
                fontSize = if (tiny) 18.sp else 22.sp,
                letterSpacing = if (show) 2.sp else 4.sp, maxLines = 1, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { show = !show }, modifier = Modifier.size(if (tiny) 32.dp else 40.dp)) {
                Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "แสดงรหัส", tint = Palette.Muted)
            }
        }
        Spacer(Modifier.height(if (tiny) 2.dp else 4.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = rowPad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    val enabled = when (k) { "⌫", "✓" -> buf.length > 0; else -> true }
                    Box(Modifier.weight(1f).height(keyH).clip(RoundedCornerShape(12.dp))
                        .background(if (k == "✓") Palette.Accent else Palette.Panel)
                        .combinedClickable(enabled = enabled,
                            onLongClick = { if (k == "⌫") { tap(); buf.clear() } },
                            onClick = {
                                tap()
                                when (k) {
                                    "⌫" -> buf.backspace()
                                    "✓" -> onDone()
                                    else -> buf.append(k[0])
                                }
                            }),
                        contentAlignment = Alignment.Center) {
                        Text(k, fontSize = numSp, color = if (enabled) Palette.Fg else Palette.Muted)
                    }
                }
            }
        }
    }
}

fun fmtWait(sec: Long): String = if (sec >= 60) "${sec / 60} นาที ${sec % 60} วินาที" else "$sec วินาที"
