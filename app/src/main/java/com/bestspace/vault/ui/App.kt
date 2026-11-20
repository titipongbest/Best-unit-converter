@file:OptIn(ExperimentalMaterial3Api::class)

package com.bestspace.vault.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
fun App(model: VaultModel, requestAccess: () -> Unit) {
    val snack = remember { SnackbarHostState() }
    val decoyShown = model.decoy && model.gate != Gate.MAIN
    LaunchedEffect(model.gate, decoyShown) { snack.currentSnackbarData?.dismiss() }   // ล็อคแล้วไม่ให้ข้อความเดิมค้างบนจอ
    LaunchedEffect(model.notice, decoyShown) {
        val n = model.notice ?: return@LaunchedEffect
        model.notice = null
        if (!decoyShown) snack.showSnackbar(n, withDismissAction = true, duration = SnackbarDuration.Long)
    }
    // เต็มจอ (วิดีโอ): ซ่อนแถบระบบ + หมุนจอตามสัดส่วนคลิป
    val act = LocalContext.current as? Activity
    LaunchedEffect(model.fullscreen, model.fsLandscape) {
        val a = act ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
        if (model.fullscreen) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            a.requestedOrientation = if (model.fsLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ปุ่มย้อนกลับในหน้ารหัสผ่าน/สร้างตู้/ขอสิทธิ์ = กลับไปหน้าโน้ต
    BackHandler(enabled = !model.decoy && model.gate != Gate.MAIN) { model.hideVault() }
    // หน้าโน้ตเป็นโทนสว่าง → ไอคอนแถบสถานะ/แถบนำทางต้องเป็นสีเข้ม
    LaunchedEffect(decoyShown) {
        val a = act ?: return@LaunchedEffect
        WindowCompat.getInsetsController(a.window, a.window.decorView).apply {
            isAppearanceLightStatusBars = decoyShown
            isAppearanceLightNavigationBars = decoyShown
        }
    }
    Box(Modifier.fillMaxSize().background(when {
        decoyShown -> NoteColors.Bg
        model.viewer != null -> Palette.ViewBg
        else -> Palette.Bg
    })) {
        Box(if (model.fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding()) {
            if (decoyShown) NotesTheme {
                // ตั้งรหัสล็อคโน้ตไว้ → ต้องใส่รหัสก่อนเห็นหน้าโน้ต (ล็อคใหม่ทุกครั้งที่ออกจากแอป/ปิดจอ)
                if (!model.notesOpen && NotesLockStore.isSet(LocalContext.current)) NotesLockScreen { model.notesOpen = true }
                else DecoyScreen(model::revealVault, onLockNow = { model.notesOpen = false })
            }
            else when (model.gate) {
                Gate.PERMISSION -> PermissionScreen(requestAccess)
                Gate.SETUP -> SetupScreen(model)
                Gate.UNLOCK -> UnlockScreen(model)
                Gate.MAIN -> {
                    GalleryScreen(model)
                    model.viewer?.let { ViewerScreen(model, it) }
                    (model.ask as? AskInAppPick)?.let { InAppPickerScreen(model, it) }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
        val jv = model.jobView
        if (model.busy != null && jv != null) JobOverlay(jv, model.cancelRequested, model::cancelJob)
        else model.busy?.let { BusyOverlay(it, model.progress) }
        if (model.blind) BlindOverlay(jv)
    }
    // ระหว่างนำเข้า/ส่งออก: จอไม่ดับเอง (จอดับ = แอปไปอยู่เบื้องหลัง) + ปุ่มย้อนกลับไม่ปิดแอป
    val keepOn = model.busy != null && model.jobView != null
    DisposableEffect(keepOn) {
        if (keepOn) act?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler(enabled = model.busy != null) { }
    if (!decoyShown) {
        model.error?.let { msg ->
            AlertDialog(
                onDismissRequest = { model.error = null },
                confirmButton = { TextButton(onClick = { model.error = null }) { Text("ตกลง") } },
                title = { Text("แจ้งเตือน") },
                text = { Text(msg) },
            )
        }
        model.ask?.takeIf { it !is AskInAppPick }?.let { AskDialog(model, it) }
    }
}

@Composable
private fun BusyOverlay(label: String, progress: Float) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            if (progress >= 0f) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.width(240.dp))
            else CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(label, color = Color.White, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** แถบความคืบหน้างานนำเข้า/ส่งออก: รวมทั้งงาน + ไฟล์ปัจจุบัน + เวลาที่เหลือ */
@Composable
private fun JobOverlay(j: JobView, cancelling: Boolean, onCancel: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth().padding(28.dp)) {
            Text(j.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(progress = { j.frac.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp), color = Palette.Accent, trackColor = Palette.Panel2)
            Spacer(Modifier.height(8.dp))
            Text(j.summary, color = Palette.Fg, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Text(j.file, color = Palette.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { j.fileFrac.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp), color = Palette.Muted, trackColor = Palette.Panel2)
            Spacer(Modifier.height(22.dp))
            Text("⚠ อย่าปิดแอปจนกว่าจะเสร็จ – จอจะไม่ดับเองระหว่างทำงาน\n" +
                "ถ้าถูกขัดจังหวะ (แบตหมด/แอปถูกปิด) ไฟล์ที่ทำเสร็จแล้วไม่เสียหาย และไฟล์ที่ค้างครึ่งทางจะถูกเก็บกวาดให้อัตโนมัติ",
                color = Palette.Warn, fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
            // ยกเลิก: หยุดที่ไฟล์ปัจจุบัน – ไฟล์ที่เสร็จแล้วคงไว้, ไฟล์ที่ค้างครึ่งทางถูกลบทิ้ง, ต้นฉบับของไฟล์ที่ยังไม่ได้ทำไม่ถูกแตะ
            OutlinedButton(onClick = onCancel, enabled = !cancelling, modifier = Modifier.align(Alignment.End)) {
                Text(if (cancelling) "กำลังยกเลิก…" else "ยกเลิก")
            }
        }
    }
}

@Composable
private fun BlindOverlay(job: JobView?) {
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, null, tint = Palette.Accent, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("ล็อคแล้ว – รอให้งานที่ค้างอยู่เสร็จก่อน…", color = Color.White)
            if (job != null) {                       // งานยังทำต่อ: แสดงความคืบหน้ารวม (ไม่มีชื่อไฟล์)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { job.frac.coerceIn(0f, 1f) },
                    modifier = Modifier.width(260.dp), color = Palette.Accent, trackColor = Palette.Panel2)
                Spacer(Modifier.height(8.dp))
                Text(job.summary, color = Palette.Muted, fontSize = 12.sp)
            }
        }
    }
}

// ====================================================================== หน้าต่างถาม-ตอบ
@Composable
private fun AskDialog(model: VaultModel, a: Ask<*>) {
    when (a) {
        is AskConfirm -> AlertDialog(
            onDismissRequest = { a.result.complete(false) },
            title = { Text(a.title) },
            text = { ScrollText(a.text) },
            confirmButton = {
                Button(onClick = { a.result.complete(true) },
                    colors = if (a.danger) ButtonDefaults.buttonColors(containerColor = Palette.Danger) else ButtonDefaults.buttonColors()
                ) { Text(a.yes) }
            },
            dismissButton = if (a.no != null) {
                { TextButton(onClick = { a.result.complete(false) }) { Text(a.no ?: "") } }
            } else null,
        )
        is AskChoice -> AlertDialog(
            onDismissRequest = { a.result.complete(null) },
            title = { Text(a.title) },
            text = {
                Column {
                    ScrollText(a.text, Modifier.weight(1f, fill = false))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { a.result.complete(true) }, modifier = Modifier.fillMaxWidth()) { Text(a.yes) }
                    OutlinedButton(onClick = { a.result.complete(false) }, modifier = Modifier.fillMaxWidth()) { Text(a.no) }
                    TextButton(onClick = { a.result.complete(null) }, modifier = Modifier.fillMaxWidth()) { Text(a.cancel) }
                }
            },
            confirmButton = {},
        )
        is AskInput -> if (a.password) {
            val sc = remember(a) { SecretController() }
            DisposableEffect(a) { onDispose { sc.clear() } }
            val submit = {
                val c = sc.takeChars()
                try { a.result.complete(String(c)) } finally { com.bestspace.vault.core.Crypto.wipe(c) }
                Unit
            }
            AlertDialog(
                onDismissRequest = { sc.clear(); a.result.complete(null) },
                title = { Text(a.title) },
                text = {
                    Column {
                        SecretField(sc, a.label, Modifier.fillMaxWidth(), autoFocus = true, onDone = submit)
                        if (a.hint.isNotEmpty()) Text(a.hint, fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                },
                confirmButton = { Button(onClick = submit) { Text("ตกลง") } },
                dismissButton = { TextButton(onClick = { sc.clear(); a.result.complete(null) }) { Text("ยกเลิก") } },
            )
        } else {
            var v by remember(a) { mutableStateOf(a.initial) }
            AlertDialog(
                onDismissRequest = { a.result.complete(null) },
                title = { Text(a.title) },
                text = {
                    Column {
                        IncognitoField(v, { v = it }, a.label, Modifier.fillMaxWidth(), password = false, autoFocus = true,
                            onDone = { a.result.complete(v) })
                        if (a.hint.isNotEmpty()) Text(a.hint, fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                },
                confirmButton = { Button(onClick = { a.result.complete(v) }) { Text("ตกลง") } },
                dismissButton = { TextButton(onClick = { a.result.complete(null) }) { Text("ยกเลิก") } },
            )
        }
        is AskInfo -> AlertDialog(
            onDismissRequest = { a.result.complete(Unit) },
            title = { Text(a.title) },
            text = { ScrollText(a.text) },
            confirmButton = { Button(onClick = { a.result.complete(Unit) }) { Text("ตกลง") } },
        )
        is AskMove -> MoveDialog(model, a)
        is AskClear -> ClearDialog(a)
        is AskCover -> CoverPickerDialog(model, a)
        is AskInAppPick -> {}
        is AskShareMode -> AlertDialog(
            onDismissRequest = { a.result.complete(null) },
            title = { Text("แชร์แบบเข้ารหัส (.bsx)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${a.count} ไฟล์ – ไฟล์ .bsx เข้ารหัส AES-256 ด้วยรหัสแชร์ ชื่อไฟล์เป็นเลขสุ่ม", color = Palette.Muted, fontSize = 14.sp)
                    Button(onClick = { a.result.complete("apps") }, modifier = Modifier.fillMaxWidth()) {
                        Text("ส่งผ่านแอปอื่น (LINE, Google Drive, อีเมล…)")
                    }
                    OutlinedButton(onClick = { a.result.complete("folder") }, modifier = Modifier.fillMaxWidth()) {
                        Text("บันทึกลงโฟลเดอร์ (เช่น โฟลเดอร์ที่ซิงก์คลาวด์)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { a.result.complete(null) }) { Text("ยกเลิก") } },
        )
    }
}

@Composable
private fun ScrollText(text: String, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(text, fontSize = 15.sp) }
}

@Composable
private fun MoveDialog(model: VaultModel, a: AskMove) {
    var target by remember(a) { mutableStateOf<String?>(null) }
    var epoch by remember(a) { mutableStateOf(0) }
    var newName by remember(a) { mutableStateOf("") }
    var expanded by remember(a) { mutableStateOf(emptySet<String>()) }
    val tree = remember(a, epoch) { model.folderTree(a.exclude) }
    val hasKids = remember(tree) {
        buildSet {
            for (i in 0 until tree.lastIndex) {
                if (tree[i + 1].third > tree[i].third) add(tree[i].first)
            }
        }
    }
    val visible = remember(tree, expanded, hasKids) {
        val out = ArrayList<Triple<String, String, Int>>()
        var hiddenBelow = Int.MAX_VALUE
        for (node in tree) {
            val (id, _, depth) = node
            if (depth > hiddenBelow) continue
            hiddenBelow = Int.MAX_VALUE
            out.add(node)
            if (id in hasKids && id !in expanded) hiddenBelow = depth
        }
        out
    }
    AlertDialog(
        onDismissRequest = { a.result.complete(false to null) },
        title = { Text("ย้ายไปที่…") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    item {
                        FolderRow("ตู้ของฉัน", 0, target == null, home = true) { target = null }
                    }
                    items(visible, key = { it.first }) { (id, name, depth) ->
                        val canExpand = id in hasKids
                        val isOpen = id in expanded
                        FolderRow(
                            name = name,
                            depth = depth,
                            selected = target == id,
                            hasKids = canExpand,
                            expanded = isOpen,
                            onToggle = {
                                expanded = if (isOpen) expanded - id else expanded + id
                            },
                            onClick = { target = id },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IncognitoField(newName, { newName = it }, "โฟลเดอร์ใหม่ในที่ที่เลือก", Modifier.weight(1f))
                    IconButton(onClick = {
                        if (newName.isNotBlank()) {
                            model.createFolderIn(newName.trim(), target)?.let { created ->
                                target?.let { parentId -> expanded = expanded + parentId }
                                target = created
                                newName = ""
                                epoch++
                            }
                        }
                    }) { Icon(Icons.Filled.CreateNewFolder, "สร้าง", tint = Palette.Accent) }
                }
            }
        },
        confirmButton = { Button(onClick = { a.result.complete(true to target) }) { Text("ย้ายมาที่นี่") } },
        dismissButton = { TextButton(onClick = { a.result.complete(false to null) }) { Text("ยกเลิก") } },
    )
}

@Composable
private fun FolderRow(
    name: String,
    depth: Int,
    selected: Boolean,
    home: Boolean = false,
    hasKids: Boolean = false,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (selected) Palette.Accent.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(
                start = if (home) 8.dp else (8 + depth * 18).dp,
                top = if (home) 10.dp else 6.dp,
                bottom = if (home) 10.dp else 6.dp,
                end = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!home) {
            if (hasKids && onToggle != null) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ArrowDropDown else Icons.Filled.ArrowRight,
                        contentDescription = if (expanded) "ย่อ" else "ขยาย",
                        tint = Palette.Muted,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(30.dp))
            }
        }
        Icon(
            if (home) Icons.Filled.Home else Icons.Filled.Folder,
            null,
            tint = if (home) Palette.Accent else Palette.Folder,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ClearDialog(a: AskClear) {
    var mem by remember(a) { mutableStateOf(true) }
    var tmp by remember(a) { mutableStateOf(true) }
    var quit by remember(a) { mutableStateOf(false) }
    var sys by remember(a) { mutableStateOf(false) }
    var ghosts by remember(a) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { a.result.complete(null) },
        title = { Text("🧹 ล้างแคชและร่องรอย") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("ไฟล์ในตู้ไม่ถูกแตะต้อง – ล้างเฉพาะสำเนาที่ถอดรหัสแล้วและร่องรอยรอบๆ", color = Palette.Muted, fontSize = 13.sp)
                Opt(mem, { mem = it }, "ภาพย่อ/รูป/ข้อความที่ถอดรหัสค้างในหน่วยความจำ")
                Opt(tmp, { tmp = it }, "ไฟล์ชั่วคราวที่ถอดรหัส (เปิดด้วยแอปอื่น) + ไฟล์ .bsx ที่รอส่ง + คลิปบอร์ด")
                Opt(sys, { sys = it }, "ภาพย่อทั้งหมดที่ระบบเก็บไว้ (Pictures/Movies/DCIM/.thumbnails)",
                    "รวมภาพย่อของรูป/วิดีโอต้นฉบับที่เคยเปิดดูก่อนนำเข้าตู้ – ระบบจะสร้างใหม่เองเมื่อจำเป็น " +
                        "(ภาพย่อในแอปแกลเลอรีของผู้ผลิต/Google Photos แอปนี้เข้าไม่ถึง)")
                Opt(ghosts, { ghosts = it }, "ชื่อไฟล์/โฟลเดอร์ที่ลบไปแล้วแต่ยังค้างในแอป Files ของระบบ",
                    "เช่น โฟลเดอร์ที่นำเข้าแล้วลบต้นฉบับ แต่ยังเห็นชื่อในเมนู Downloads – ลบแค่รายการในฐานข้อมูลสื่อของระบบ ไม่แตะไฟล์ที่ยังอยู่")
                Opt(quit, { quit = it }, "ล็อคและปิดแอปหลังล้าง",
                    "ล้างหน่วยความจำได้หมดจดที่สุด – ระบบคืนหน่วยความจำทั้งหมดของแอป")
            }
        },
        confirmButton = {
            Button(onClick = {
                a.result.complete(buildSet { if (mem) add("mem"); if (tmp) add("tmp"); if (sys) add("systhumbs"); if (ghosts) add("ghosts"); if (quit) add("quit") })
            }) { Text("ล้างเลย") }
        },
        dismissButton = { TextButton(onClick = { a.result.complete(null) }) { Text("ยกเลิก") } },
    )
}

@Composable
private fun Opt(v: Boolean, set: (Boolean) -> Unit, text: String, note: String = "") {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().clickable { set(!v) }.padding(vertical = 4.dp)) {
        Checkbox(v, set)
        Column(Modifier.padding(top = 12.dp)) {
            Text(text, fontSize = 15.sp)
            if (note.isNotEmpty()) Text(note, fontSize = 12.sp, color = Palette.Muted)
        }
    }
}
