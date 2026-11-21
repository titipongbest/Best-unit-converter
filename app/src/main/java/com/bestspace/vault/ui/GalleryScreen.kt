@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.bestspace.vault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bestspace.vault.core.Mime
import com.bestspace.vault.core.Vault
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

@Composable
fun GalleryScreen(model: VaultModel) {
    val l = model.listing
    val sel = model.selected
    val selSet by remember { derivedStateOf { sel.toHashSet() } }
    // จำ LazyGridState แยกตามโฟลเดอร์ (ในหน่วยความจำเท่านั้น) → กดย้อนกลับแล้วอยู่ตำแหน่งเดิมทันทีตั้งแต่เฟรมแรก ไม่กระพริบ
    val gridStates = remember { HashMap<String?, LazyGridState>() }
    val listFolder = l.crumbs.lastOrNull()?.first
    val stateKey = if (l.searching) "__search__" else listFolder
    val grid = remember(stateKey) {
        if (stateKey == "__search__") LazyGridState()
        else gridStates.getOrPut(stateKey) { LazyGridState() }
    }
    LaunchedEffect(listFolder, l.searching) {
        if (!l.searching) {
            val keep = l.crumbs.map { it.first }.toSet()
            gridStates.keys.retainAll(keep)
        }
    }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // ---------------- กดค้างที่ไฟล์แล้วลาก = จัดลำดับ (กดค้างแล้วปล่อยโดยไม่ลาก = เลือก เหมือนเดิม)
    val shown = remember { mutableStateListOf<Item>() }          // ลำดับระหว่างลาก
    var frozen by remember { mutableStateOf<Listing?>(null) }     // ปล่อยแล้ว: แสดงลำดับใหม่จนกว่ารายการจะโหลดใหม่
    var pressKey by remember { mutableStateOf<String?>(null) }   // รายการที่ถูกกดค้าง
    var pressSelected by remember { mutableStateOf(false) }      // กดค้างครั้งนี้เพิ่มรายการเข้า "เลือก" ไปแล้ว
    var dragKey by remember { mutableStateOf<String?>(null) }    // ไฟล์ที่กำลังลาก
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    val curL by rememberUpdatedState(l)
    val curGrid by rememberUpdatedState(grid)
    val canReorder by rememberUpdatedState(!l.searching && model.busy == null)
    val items: List<Item> = if (dragKey != null || frozen === l) shown else l.items
    fun itemAt(p: Offset) = curGrid.layoutInfo.visibleItemsInfo.firstOrNull { inf ->
        p.x >= inf.offset.x && p.x < inf.offset.x + inf.size.width && p.y >= inf.offset.y && p.y < inf.offset.y + inf.size.height
    }
    fun moveIfNeeded() {
        val g = curGrid
        val k = dragKey ?: return
        val inf = g.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k } ?: return
        val center = dragStart + dragDelta + Offset(inf.size.width / 2f, inf.size.height / 2f)
        val target = itemAt(center)?.takeIf { it.key != k } ?: return
        val from = shown.indexOfFirst { it.id == k }
        val to = shown.indexOfFirst { it.id == target.key }
        if (from < 0 || to < 0) return
        val src = shown[from]; val dst = shown[to]
        if (src.isDir != dst.isDir) return                            // โฟลเดอร์อยู่ด้านบนเสมอ – ไม่สลับข้ามกลุ่มกับไฟล์
        if (src.isDir && src.pinned != dst.pinned) return             // โฟลเดอร์ปักหมุดสลับกันเอง / ไม่ปักหมุดสลับกันเอง
        val first = g.firstVisibleItemIndex
        val firstOff = g.firstVisibleItemScrollOffset
        shown.add(to, shown.removeAt(from))
        if (from == first || to == first) scope.launch { g.scrollToItem(first, firstOff) }
    }
    fun finish(commit: Boolean) {
        val k = pressKey
        val dk = dragKey
        if (dk != null) {
            if (commit) {
                frozen = curL
                if (Vault.isDir(dk)) model.reorderFolders(shown.filter { it.isDir }.map { it.id })
                else model.reorder(shown.filter { !it.isDir }.map { it.id })
            }
        } else if (commit && k != null && k !in sel) sel.add(k)        // กดค้างแล้วปล่อย = เลือก (เหมือนเดิม)
        pressSelected = false
        pressKey = null; dragKey = null; dragDelta = Offset.Zero; autoScroll = 0f
    }
    LaunchedEffect(dragKey, grid) {                                     // ลากไปชิดขอบบน/ล่าง = เลื่อนรายการตาม
        while (dragKey != null) {
            if (autoScroll != 0f) { grid.scrollBy(autoScroll); moveIfNeeded() }
            delay(16)
        }
    }
    var lastQuery by remember { mutableStateOf(model.query) }
    LaunchedEffect(model.query) {
        if (model.query == lastQuery) return@LaunchedEffect
        delay(250); lastQuery = model.query; model.refresh()
    }
    BackHandler(enabled = model.busy == null && model.viewer == null && (sel.isNotEmpty() || model.folder != null || model.searchOpen)) {
        when {
            sel.isNotEmpty() -> sel.clear()
            model.searchOpen -> { model.searchOpen = false; model.query = ""; model.refresh() }
            else -> model.up()
        }
    }

    Scaffold(
        containerColor = Palette.Bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { if (sel.isNotEmpty()) SelectionBar(model) else MainBar(model) },
        floatingActionButton = { if (sel.isEmpty()) AddFab(model) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (model.searchOpen) {
                IncognitoField(model.query, { model.query = it }, "ค้นหาชื่อไฟล์/โฟลเดอร์ทั้งตู้",
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp), autoFocus = true)
            }
            if (l.searching) {
                Text("🔍 ผลการค้นหาทั้งตู้: “${model.query.trim()}”", color = Palette.Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            } else if (model.folder != null) {
                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    itemsIndexed(l.crumbs) { i, c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (i > 0) Text("›", color = Palette.Muted)
                            TextButton(onClick = { model.openFolder(c.first) }) {
                                Text(if (c.first == null) "🏠 ${c.second}" else c.second,
                                    fontWeight = if (i == l.crumbs.size - 1) FontWeight.Bold else FontWeight.Normal,
                                    color = if (i == l.crumbs.size - 1) Palette.Fg else Palette.Accent, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, label) in VaultModel.KINDS) {
                    FilterChip(
                        selected = model.filter == k,
                        onClick = { model.filter = k; model.refresh() },
                        label = { Text("$label  ${l.counts[k] ?: 0}") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Accent,
                            selectedLabelColor = Color.White),
                    )
                }
            }
            Text("${l.files} ไฟล์ • ${l.dirs} โฟลเดอร์ • ${Mime.humanSize(l.bytes)}", color = Palette.Muted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
            if (l.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (l.searching) "ไม่พบรายการที่ตรงกับคำค้นหา"
                    else "ยังไม่มีไฟล์ในที่นี่\nกด + เพื่อเพิ่มรูป วิดีโอ ไฟล์ หรือสร้างข้อความ/โฟลเดอร์",
                        color = Palette.Muted, textAlign = TextAlign.Center)
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(model.columns),
                        state = grid,
                        userScrollEnabled = pressKey == null,
                        modifier = Modifier.fillMaxSize().pointerInput(grid) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { off ->
                                    val inf = itemAt(off)
                                    if (inf != null) {
                                        pressKey = inf.key as? String
                                        dragStart = Offset(inf.offset.x.toFloat(), inf.offset.y.toFloat())
                                        dragDelta = Offset.Zero
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        // เลือกทันทีที่เครื่องสั่น ไม่ต้องรอปล่อยนิ้ว
                                        val k = pressKey
                                        pressSelected = k != null && k !in sel
                                        if (k != null && pressSelected) sel.add(k)
                                    }
                                },
                                onDrag = { change, amount ->
                                    val k = pressKey
                                    if (k != null) {
                                        change.consume()
                                        dragDelta += amount
                                        if (dragKey == null && dragDelta.getDistance() > viewConfiguration.touchSlop && canReorder) {
                                            val now = if (frozen === curL) shown.toList() else curL.items
                                            if (now.any { it.id == k }) {
                                                shown.clear(); shown.addAll(now); dragKey = k
                                                // กลายเป็นการลากจัดลำดับ → ยกเลิกการเลือกที่เพิ่งเพิ่มตอนกดค้าง (เหมือนเดิม)
                                                if (pressSelected) { sel.remove(k); pressSelected = false }
                                            }
                                        }
                                        if (dragKey != null) {
                                            moveIfNeeded()
                                            val y = change.position.y
                                            val edge = 72.dp.toPx()
                                            autoScroll = when {
                                                y < edge -> -((edge - y) / edge).coerceIn(0f, 1f) * 28f
                                                y > size.height - edge -> ((y - (size.height - edge)) / edge).coerceIn(0f, 1f) * 28f
                                                else -> 0f
                                            }
                                        }
                                    }
                                },
                                onDragEnd = { finish(true) },
                                onDragCancel = { finish(false) },
                            )
                        },
                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = items,
                            key = { it.id },
                            contentType = { if (it.isDir) 0 else if (it.media) 1 else if (it.text) 2 else 3 },
                        ) { item ->
                            val isSel = item.id in selSet
                            val dragging = item.id == dragKey
                            Box(
                                if (dragging) Modifier.zIndex(1f).graphicsLayer {
                                    val inf = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.id }
                                    if (inf != null) {
                                        translationX = dragStart.x + dragDelta.x - inf.offset.x
                                        translationY = dragStart.y + dragDelta.y - inf.offset.y
                                    }
                                    scaleX = 1.08f; scaleY = 1.08f; alpha = 0.92f
                                } else if (dragKey != null) Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                                else Modifier
                            ) {
                                Tile(model, item, isSel,
                                    isScrolling = { grid.isScrollInProgress },
                                    onClick = {
                                        when {
                                            pressKey != null -> {}               // ปล่อยนิ้วหลังกดค้าง – จัดการใน finish()
                                            sel.isNotEmpty() -> if (isSel) sel.remove(item.id) else sel.add(item.id)
                                            item.isDir -> model.openFolder(item.id)
                                            else -> model.openViewer(item.id)
                                        }
                                    })
                            }
                        }
                    }
                    GridFastScrollbar(
                        state = grid,
                        totalItems = items.size,
                        columns = model.columns,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        enabled = dragKey == null && pressKey == null,
                        spacingDp = 8.dp,
                        bottomInset = if (sel.isEmpty()) 88.dp else 12.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainBar(model: VaultModel) {
    var sortMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val l = model.listing
    TopAppBar(
        title = {
            Text(if (model.folder == null) "BestSpace" else l.crumbs.last().second, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            if (model.folder != null) IconButton(onClick = { model.up() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "ขึ้น") }
        },
        actions = {
            if (model.folder != null && !l.searching)
                IconButton(onClick = { model.pickFolderCover() }) { Icon(Icons.Filled.Wallpaper, "ตั้งภาพปกโฟลเดอร์") }
            IconButton(onClick = {
                model.searchOpen = !model.searchOpen
                if (!model.searchOpen && model.query.isNotEmpty()) { model.query = ""; model.refresh() }
            }) { Icon(Icons.Filled.Search, "ค้นหา") }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "เรียง") }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    VaultModel.SORTS.forEachIndexed { i, s ->
                        DropdownMenuItem(text = { Text((if (i == model.sort) "✓  " else "     ") + s) },
                            onClick = { sortMenu = false; model.setSortMode(i) })
                    }
                }
            }
            IconButton(onClick = { model.requestLock("ล็อคแล้ว") }) { Icon(Icons.Filled.Lock, "ล็อค") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "เมนู") }
                DropdownMenu(menu, { menu = false }) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("ขนาดภาพย่อ", Modifier.weight(1f))
                        TextButton(onClick = { model.setCols(model.columns + 1) }, enabled = model.columns < 6) { Text("−") }
                        Text("${model.columns}")
                        TextButton(onClick = { model.setCols(model.columns - 1) }, enabled = model.columns > 2) { Text("+") }
                    }
                    HorizontalDivider()
                    MenuItem("💾 สำรองทั้งตู้ (ยังเข้ารหัส) ไปยังโฟลเดอร์…") { menu = false; model.backupVault() }
                    MenuItem("🔑 เปลี่ยนรหัสผ่านหลัก…") { menu = false; model.changePassword() }
                    if (!model.fakeSession) {
                        if (model.needsKey) {
                            MenuItem("🔑 สำรองไฟล์กุญแจ…") { menu = false; model.backupKeyfile() }
                            if (model.rememberedKeyMode != null) {
                                MenuItem("🗑 ลืมไฟล์กุญแจที่จำไว้…") { menu = false; model.forgetRememberedKeyfile() }
                            }
                        }
                        else MenuItem("🔑 เพิ่มไฟล์กุญแจให้ตู้นี้ (กันเดารหัสออฟไลน์)…") { menu = false; model.addKeyfile() }
                        MenuItem("🎭 ตู้หลอก…") { menu = false; model.fakeVaultMenu() }
                    }
                    MenuItem("🧹 ล้างแคชและร่องรอย…") { menu = false; model.clearCache() }
                    MenuItem("📂 เลือกตู้อื่น…") { menu = false; model.browseVault() }
                    HorizontalDivider()
                    MenuItem("🛡 ความปลอดภัย / เกี่ยวกับ") { menu = false; model.securityInfo() }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg),
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}

@Composable
private fun SelectionBar(model: VaultModel) {
    val sel = model.selected
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("เลือก ${sel.size}") },
        navigationIcon = { IconButton(onClick = { sel.clear() }) { Icon(Icons.Filled.Close, "ยกเลิก") } },
        actions = {
            IconButton(onClick = { sel.clear(); sel.addAll(model.listing.items.map { it.id }) }) { Icon(Icons.Filled.SelectAll, "เลือกทั้งหมด") }
            IconButton(onClick = { model.move(sel.toList()) }) { Icon(Icons.Filled.DriveFileMove, "ย้าย") }
            IconButton(onClick = { model.shareItems(sel.toList()) }) { Icon(Icons.Filled.Share, "แชร์ .bsx") }
            IconButton(onClick = { model.delete(sel.toList()) }) { Icon(Icons.Filled.Delete, "ลบ", tint = Palette.Danger) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "เมนู") }
                DropdownMenu(menu, { menu = false }) {
                    val one = sel.singleOrNull()
                    DropdownMenuItem(text = { Text("⇡ ส่งออก (ถอดรหัส)…") }, onClick = { menu = false; model.exportItems(sel.toList()) })
                    DropdownMenuItem(text = { Text("☁ แชร์แบบเข้ารหัส (.bsx)…") }, onClick = { menu = false; model.shareItems(sel.toList()) })
                    DropdownMenuItem(text = { Text("📁 ย้ายไปโฟลเดอร์…") }, onClick = { menu = false; model.move(sel.toList()) })
                    if (one != null) DropdownMenuItem(text = { Text("✏ เปลี่ยนชื่อ…") }, onClick = { menu = false; model.rename(one) })
                    val oneItem = one?.let { id -> model.listing.items.firstOrNull { it.id == id } }
                    if (oneItem != null && oneItem.media && model.folder != null && !model.listing.searching)
                        DropdownMenuItem(text = { Text("🖼 ตั้งเป็นภาพปกโฟลเดอร์นี้") }, onClick = { menu = false; model.setFolderCoverTo(oneItem.id) })
                    val dirSel = sel.isNotEmpty() && sel.all { Vault.isDir(it) }
                    if (dirSel) {
                        val allPinned = sel.all { id -> model.listing.items.firstOrNull { it.id == id }?.pinned == true }
                        DropdownMenuItem(text = { Text(if (allPinned) "📌 เลิกปักหมุด" else "📌 ปักหมุดไว้บนสุด") },
                            onClick = { menu = false; model.pinFolders(sel.toList(), !allPinned) })
                    }
                    if (one != null && !Vault.isDir(one)) {
                        DropdownMenuItem(text = { Text("↗ เปิดด้วยแอปอื่น (ชั่วคราว)…") }, onClick = { menu = false; model.openExternal(one) })
                        DropdownMenuItem(text = { Text("ℹ ข้อมูลไฟล์") }, onClick = { menu = false; model.fileInfo(one) })
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF2E3A5C)),
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
private fun AddFab(model: VaultModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(onClick = { open = true }, containerColor = Palette.Accent, contentColor = Color.White) {
            Icon(Icons.Filled.Add, "เพิ่ม")
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("🖼 รูปภาพ / วิดีโอ") }, onClick = { open = false; model.addMedia() })
            DropdownMenuItem(text = { Text("📄 ไฟล์ใดๆ") }, onClick = { open = false; model.addFiles() })
            DropdownMenuItem(text = { Text("📂 นำเข้าทั้งโฟลเดอร์") }, onClick = { open = false; model.addFolder() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("📝 ข้อความใหม่") }, onClick = { open = false; model.newText() })
            DropdownMenuItem(text = { Text("＋ โฟลเดอร์ใหม่") }, onClick = { open = false; model.newFolder() })
            DropdownMenuItem(text = { Text("⇣ นำเข้า .bsx") }, onClick = { open = false; model.importBsx() })
        }
    }
}

fun iconFor(item: Item): ImageVector = when {
    item.isDir -> Icons.Filled.Folder
    item.kind == "image" -> Icons.Filled.Image
    item.kind == "video" -> Icons.Filled.Movie
    item.audio -> Icons.Filled.Audiotrack
    Mime.ext(item.name) == "pdf" -> Icons.Filled.PictureAsPdf
    Mime.ext(item.name) in Mime.ARCHIVE_EXT -> Icons.Filled.Archive
    else -> Icons.Filled.Description
}

@Composable
private fun Tile(
    model: VaultModel,
    item: Item,
    selected: Boolean,
    isScrolling: () -> Boolean = { false },
    onClick: () -> Unit,
) {
    val thumbId = if (item.isDir) item.cover else if (item.media) item.id else null
    val thumb by produceState(initialValue = thumbId?.let { model.thumbCached(it) }, thumbId, model.thumbEpoch) {
        if (thumbId == null) { value = null; return@produceState }
        val cached = model.thumbCached(thumbId)
        if (cached != null) { value = cached; return@produceState }
        if (isScrolling()) delay(45)
        value = model.thumb(thumbId)
    }
    Column(Modifier.combinedClickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Palette.Panel2)
                .then(if (selected) Modifier.border(3.dp, Palette.Accent, RoundedCornerShape(8.dp)) else Modifier),
        ) {
            val t = thumb
            if (t != null) Image(t.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (item.isDir) {
                if (t != null) Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                Icon(Icons.Filled.Folder, null, tint = Palette.Folder, modifier = Modifier.align(Alignment.Center).size(44.dp))
                if (item.pinned) Text("📌", fontSize = 14.sp, modifier = Modifier.align(Alignment.TopStart).padding(5.dp))
                Text("${item.count}", color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            } else if (t == null && item.text) {
                // ไฟล์ข้อความ: แสดงข้อความต้นไฟล์เท่าที่ช่องพอใส่ (ถอดรหัสในหน่วยความจำเท่านั้น)
                val snip by produceState(initialValue = model.snippetCached(item.id), item.id) {
                    if (value != null) return@produceState
                    if (isScrolling()) delay(45)
                    value = model.snippet(item.id)
                }
                val s = snip
                if (s.isNullOrBlank()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(iconFor(item), null, tint = Palette.Muted, modifier = Modifier.size(36.dp))
                    val ext = Mime.ext(item.name).uppercase().take(5)
                    if (ext.isNotEmpty()) Text(ext, color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text(s, color = Palette.Fg.copy(alpha = 0.85f), fontSize = 9.sp, lineHeight = 11.sp, overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxSize().padding(6.dp))
                    val ext = Mime.ext(item.name).uppercase().take(5)
                    if (ext.isNotEmpty()) Text(ext, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .background(Color(0xAA000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
                }
            } else if (t == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(iconFor(item), null, tint = Palette.Muted, modifier = Modifier.size(36.dp))
                    val ext = Mime.ext(item.name).uppercase().take(5)
                    if (ext.isNotEmpty()) Text(ext, color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (item.kind == "video" && t != null) {
                Row(Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    t.duration?.let { Text(Media.fmtDuration(it), color = Color.White, fontSize = 11.sp) }
                }
            }
            if (selected) Icon(Icons.Filled.CheckCircle, null, tint = Palette.Accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(Color.White, CircleShape))
        }
        Spacer(Modifier.height(3.dp))
        Text(item.name, color = Palette.Fg, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp))
    }
}


/** เลือกภาพปกโฟลเดอร์จากรูป/วิดีโอ/ปกโฟลเดอร์ย่อยชั้นเดียว (ภาพย่อถอดรหัสในหน่วยความจำเหมือนแกลเลอรี) */
@Composable
fun CoverPickerDialog(model: VaultModel, a: AskCover) {
    AlertDialog(
        onDismissRequest = { a.result.complete(null) },
        title = { Text("ภาพปกโฟลเดอร์ “${a.folderName}”", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text("แตะรูป ปกวิดีโอ หรือปกโฟลเดอร์ย่อยในหน้านี้เพื่อใช้เป็นภาพปก", color = Palette.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(a.items, key = { it.id }) { item ->
                        val thumb by produceState(initialValue = model.thumbCached(item.id), item.id, model.thumbEpoch) {
                            value = model.thumb(item.id)
                        }
                        val cur = item.id == a.current
                        Box(
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Palette.Panel2)
                                .then(if (cur) Modifier.border(3.dp, Palette.Accent, RoundedCornerShape(6.dp)) else Modifier)
                                .combinedClickable(onClick = { a.result.complete(item.id) }),
                        ) {
                            val t = thumb
                            if (t != null) Image(t.image, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Icon(iconFor(item), null, tint = Palette.Muted, modifier = Modifier.align(Alignment.Center).size(28.dp))
                            if (item.isDir) Icon(Icons.Filled.Folder, null, tint = Palette.Folder,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp)
                                    .background(Color(0xAA000000), RoundedCornerShape(3.dp)).padding(1.dp))
                            else if (item.kind == "video") Icon(Icons.Filled.PlayArrow, null, tint = Color.White,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).size(16.dp)
                                    .background(Color(0xAA000000), RoundedCornerShape(3.dp)))
                            if (cur) Icon(Icons.Filled.CheckCircle, null, tint = Palette.Accent,
                                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).background(Color.White, CircleShape))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { a.result.complete("") }) { Text("อัตโนมัติ (ล่าสุด)") } },
        dismissButton = { TextButton(onClick = { a.result.complete(null) }) { Text("ยกเลิก") } },
    )
}

@Composable
fun GridFastScrollbar(
    state: LazyGridState,
    totalItems: Int,
    columns: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    spacingDp: Dp = 8.dp,
    bottomInset: Dp = 12.dp,
) {
    if (!enabled || totalItems <= 0) return
    val canScroll by remember(state) { derivedStateOf { state.canScrollForward || state.canScrollBackward } }
    if (!canScroll) return
    val density = LocalDensity.current
    val spacingPx = with(density) { spacingDp.toPx() }
    val scrollFrac by remember(state, totalItems, columns, spacingPx) {
        derivedStateOf {
            val info = state.layoutInfo
            val vis = info.visibleItemsInfo
            if (vis.isEmpty() || totalItems <= 0) return@derivedStateOf 0f
            if (!state.canScrollBackward) return@derivedStateOf 0f
            if (!state.canScrollForward) return@derivedStateOf 1f
            val first = vis.first()
            val cols = columns.coerceAtLeast(1)
            val totalRows = (totalItems + cols - 1) / cols
            val firstRow = first.index / cols
            val rowPx = (vis.maxOfOrNull { it.size.height }?.toFloat() ?: 1f) + spacingPx
            val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat().coerceAtLeast(1f)
            val totalPx = (totalRows * rowPx + info.afterContentPadding).coerceAtLeast(viewportPx + 1f)
            val curPx = (firstRow * rowPx - first.offset.y + info.beforeContentPadding).coerceAtLeast(0f)
            (curPx / (totalPx - viewportPx)).coerceIn(0f, 1f)
        }
    }
    val curState by rememberUpdatedState(state)
    val curTotal by rememberUpdatedState(totalItems)
    val curCols by rememberUpdatedState(columns)
    FastScrollbarTrack(
        scrollFraction = scrollFrac,
        isScrolling = state.isScrollInProgress,
        modifier = modifier,
        bottomInset = bottomInset,
        onScrub = { frac ->
            val st = curState
            val count = curTotal
            val cols = curCols.coerceAtLeast(1)
            if (count <= 0) return@FastScrollbarTrack
            val info = st.layoutInfo
            val vis = info.visibleItemsInfo
            val totalRows = (count + cols - 1) / cols
            val rowPx = (vis.maxOfOrNull { it.size.height }?.toFloat() ?: 120f) + spacingPx
            val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat().coerceAtLeast(1f)
            val maxScroll = (totalRows * rowPx + info.afterContentPadding - viewportPx).coerceAtLeast(0f)
            val targetPx = frac * maxScroll
            val targetRow = (targetPx / rowPx).toInt().coerceIn(0, (totalRows - 1).coerceAtLeast(0))
            val targetIdx = (targetRow * cols).coerceIn(0, (count - 1).coerceAtLeast(0))
            val rowOff = (targetPx - targetRow * rowPx).toInt().coerceAtLeast(0)
            st.scrollToItem(targetIdx, rowOff)
        },
    )
}

@Composable
fun ListFastScrollbar(
    state: LazyListState,
    totalItems: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bottomInset: Dp = 12.dp,
) {
    if (!enabled || totalItems <= 0) return
    val canScroll by remember(state) { derivedStateOf { state.canScrollForward || state.canScrollBackward } }
    if (!canScroll) return
    val scrollFrac by remember(state, totalItems) {
        derivedStateOf {
            val info = state.layoutInfo
            val vis = info.visibleItemsInfo
            if (vis.isEmpty() || totalItems <= 0) return@derivedStateOf 0f
            if (!state.canScrollBackward) return@derivedStateOf 0f
            if (!state.canScrollForward) return@derivedStateOf 1f
            val first = vis.first()
            val avgPx = vis.map { it.size }.average().toFloat().coerceAtLeast(1f)
            val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat().coerceAtLeast(1f)
            val totalPx = (totalItems * avgPx + info.afterContentPadding).coerceAtLeast(viewportPx + 1f)
            val curPx = (first.index * avgPx - first.offset + info.beforeContentPadding).coerceAtLeast(0f)
            (curPx / (totalPx - viewportPx)).coerceIn(0f, 1f)
        }
    }
    val curState by rememberUpdatedState(state)
    val curTotal by rememberUpdatedState(totalItems)
    FastScrollbarTrack(
        scrollFraction = scrollFrac,
        isScrolling = state.isScrollInProgress,
        modifier = modifier,
        bottomInset = bottomInset,
        onScrub = { frac ->
            val st = curState
            val count = curTotal
            if (count <= 0) return@FastScrollbarTrack
            val info = st.layoutInfo
            val vis = info.visibleItemsInfo
            val avgPx = if (vis.isNotEmpty()) vis.map { it.size }.average().toFloat().coerceAtLeast(1f) else 120f
            val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat().coerceAtLeast(1f)
            val maxScroll = (count * avgPx + info.afterContentPadding - viewportPx).coerceAtLeast(0f)
            val targetPx = frac * maxScroll
            val targetIdx = (targetPx / avgPx).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
            val off = (targetPx - targetIdx * avgPx).toInt().coerceAtLeast(0)
            st.scrollToItem(targetIdx, off)
        },
    )
}

@Composable
private fun FastScrollbarTrack(
    scrollFraction: Float,
    isScrolling: Boolean,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 12.dp,
    onScrub: suspend (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val thumbHeightDp = 42.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
    val curOnScrub by rememberUpdatedState(onScrub)
    val scrubChannel = remember { Channel<Float>(Channel.CONFLATED) }

    LaunchedEffect(Unit) {
        for (f in scrubChannel) curOnScrub(f)
    }

    LaunchedEffect(isScrolling, dragging) {
        if (isScrolling || dragging) {
            active = true
        } else {
            delay(1000)
            active = false
        }
    }

    val thumbAlpha by animateFloatAsState(
        targetValue = when {
            dragging -> 1f
            active -> 0.72f
            else -> 0.28f
        },
        animationSpec = tween(durationMillis = if (active || dragging) 120 else 300),
        label = "sbAlpha",
    )
    val thumbWidth by animateDpAsState(
        targetValue = when {
            dragging -> 7.dp
            active -> 5.dp
            else -> 3.5.dp
        },
        animationSpec = tween(120),
        label = "sbWidth",
    )

    val frac = if (dragging) dragFrac else scrollFraction

    fun scrubAt(yPx: Float) {
        if (trackHeightPx <= 0) return
        val usable = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
        val f = ((yPx - thumbHeightPx / 2f) / usable).coerceIn(0f, 1f)
        dragFrac = f
        scrubChannel.trySend(f)
    }

    Box(
        modifier
            .fillMaxHeight()
            .padding(top = 6.dp, bottom = bottomInset, end = 2.dp)
            .width(20.dp)
            .onSizeChanged { trackHeightPx = it.height }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { off ->
                        dragging = true
                        scrubAt(off.y)
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        scrubAt(change.position.y)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                )
            },
    ) {
        val usable = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
        val thumbY = (frac * usable).toInt()
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbY) }
                .width(thumbWidth)
                .height(thumbHeightDp)
                .alpha(thumbAlpha)
                .clip(RoundedCornerShape(4.dp))
                .background(if (dragging) Palette.Accent else Color.White)
        )
    }
}
