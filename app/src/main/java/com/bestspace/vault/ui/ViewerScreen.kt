@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, UnstableApi::class)

package com.bestspace.vault.ui

import android.graphics.Bitmap
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.SeekParameters
import com.bestspace.vault.core.Mime
import com.bestspace.vault.core.TextCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun ViewerScreen(model: VaultModel, v: ViewerState) {
    if (v.ids.isEmpty()) return
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = v.start.coerceIn(0, v.ids.size - 1)) { v.ids.size }
    // เปิดไฟล์อื่นขณะหน้าดูยังเปิดอยู่ (start เปลี่ยน) → เลื่อนไปไฟล์นั้นเสมอ
    LaunchedEffect(v.keys, v.start) { if (v.start in v.ids.indices && pager.currentPage != v.start) pager.scrollToPage(v.start) }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val page = pager.currentPage.coerceIn(0, v.ids.size - 1)
    val curId = v.ids[page]
    val cur = remember(curId, model.version) { model.entryOf(curId) }
    LaunchedEffect(page) { zoomed = false; chrome = true }
    BackHandler(enabled = model.busy == null) { if (model.fullscreen) model.fullscreen = false else model.closeViewer() }

    fun go(d: Int) {
        val n = page + d
        if (n !in v.ids.indices) return
        scope.launch {
            if (model.confirmLeaveText()) { model.textSession = null; pager.animateScrollToPage(n) }
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.ViewBg)
        // กันการแตะทะลุไปโดนแกลเลอรีที่อยู่ข้างใต้
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        HorizontalPager(
            state = pager,
            key = { v.keys.getOrElse(it) { v.ids[it] } },
            userScrollEnabled = !zoomed && cur?.text != true && !model.fullscreen,
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize(),
        ) { p ->
            val id = v.ids.getOrNull(p)
            val e = remember(id, model.version) { id?.let { model.entryOf(it) } }
            val active = p == pager.currentPage
            when {
                id == null || e == null -> Box(Modifier.fillMaxSize())
                e.kind == "image" -> ImagePage(model, id, onTap = { chrome = !chrome }, onZoom = { if (active) zoomed = it })
                e.kind == "video" || e.audio -> MediaPage(model, e, active, chrome,
                    onTap = { chrome = !chrome }, onZoom = { if (active) zoomed = it })
                e.text -> TextPage(model, v.keys.getOrElse(p) { id }, id, active, v.focusText && p == v.start)
                else -> OtherPage(model, e)
            }
        }
        if (chrome && !model.fullscreen && cur != null) {
            ViewerBar(model, cur, page, v.ids.size, onPrev = { go(-1) }, onNext = { go(1) },
                modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun ViewerBar(model: VaultModel, cur: Item, page: Int, total: Int, onPrev: () -> Unit, onNext: () -> Unit,
                      modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    val ts = model.textSession
    val dirty = cur.text && ts != null && ts.dirty
    val isVideo = cur.kind == "video"
    Row(modifier.fillMaxWidth().background(if (isVideo) Color.Transparent else Color(0x66000000))
        .padding(start = if (isVideo) 12.dp else 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (!isVideo) {
            IconButton(onClick = { model.closeViewer() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "กลับ", tint = Color.White) }
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text((if (dirty) "● " else "") + cur.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
            Text("  •  ${Mime.humanSize(cur.size)}", color = Color.LightGray, fontSize = 12.sp, maxLines = 1)
        }
        if (!isVideo) {
            if (cur.text) {
                IconButton(onClick = onPrev, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "ก่อนหน้า", tint = Color.White) }
                IconButton(onClick = onNext, enabled = page < total - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "ถัดไป", tint = Color.White) }
                IconButton(onClick = { model.textSession?.let { model.saveText(it, quiet = false) } }, enabled = dirty) {
                    Icon(Icons.Filled.Save, "บันทึก", tint = if (dirty) Palette.Accent else Color.Gray)
                }
            }
            IconButton(onClick = { model.shareItems(listOf(cur.id)) }) { Icon(Icons.Filled.Share, "แชร์ .bsx", tint = Color.White) }
            IconButton(onClick = { model.delete(listOf(cur.id)) }) { Icon(Icons.Filled.Delete, "ลบ", tint = Palette.Danger) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "เมนู", tint = Color.White) }
            DropdownMenu(menu, { menu = false }) {
                MenuItem("⇡ ส่งออก (ถอดรหัส)…") { menu = false; model.exportItems(listOf(cur.id)) }
                MenuItem("↗ เปิดด้วยแอปอื่น (ชั่วคราว)…") { menu = false; model.openExternal(cur.id) }
                MenuItem("✏ เปลี่ยนชื่อ…") { menu = false; model.rename(cur.id) }
                MenuItem("📁 ย้ายไปโฟลเดอร์…") { menu = false; model.move(listOf(cur.id)) }
                MenuItem("ℹ ข้อมูลไฟล์") { menu = false; model.fileInfo(cur.id) }
                MenuItem("🗑 ลบ") { menu = false; model.delete(listOf(cur.id)) }
            }
        }
    }
}

// ====================================================================== ซูม (ใช้ร่วมกันทั้งรูปและวิดีโอ)
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

private fun clampOffset(o: Offset, size: IntSize, s: Float): Offset {
    val mx = size.width * (s - 1) / 2f
    val my = size.height * (s - 1) / 2f
    return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
}

/** แตะ = ซ่อน/แสดงแถบควบคุม • แตะสองครั้ง = ซูม 2.5 เท่าตรงจุดที่แตะ / กลับพอดีจอ • สองนิ้ว = ซูม • ลาก (ตอนซูม) = เลื่อน */
/** onDoubleTap: ถ้าระบุ แตะสองครั้งจะเรียกอันนี้แทนการซูม – ยังซูมด้วยสองนิ้วได้ */
fun Modifier.zoomGestures(
    z: ZoomState,
    onTap: () -> Unit,
    onZoom: (Boolean) -> Unit,
    onDoubleTap: ((Offset, IntSize) -> Unit)? = null,
    onHorizontalScrubStart: (() -> Unit)? = null,
    onHorizontalScrub: ((deltaX: Float, width: Int) -> Unit)? = null,
    onHorizontalScrubEnd: (() -> Unit)? = null,
): Modifier = this
    .pointerInput(z) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { p ->
                if (onDoubleTap != null) onDoubleTap(p, size)
                else if (z.scale > 1f) { z.scale = 1f; z.offset = Offset.Zero; onZoom(false) }
                else {
                    val s = 2.5f
                    z.scale = s
                    z.offset = clampOffset((Offset(size.width / 2f, size.height / 2f) - p) * (s - 1f), size, s)
                    onZoom(true)
                }
            },
        )
    }
    .pointerInput(z) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var draggingH = false
            var accX = 0f
            var accY = 0f
            val slop = viewConfiguration.touchSlop
            try {
                do {
                    val ev = awaitPointerEvent()
                    if (ev.changes.size > 1 || z.scale > 1f) {
                        if (draggingH) {
                            draggingH = false
                            onHorizontalScrubEnd?.invoke()
                        }
                        val ns = (z.scale * ev.calculateZoom()).coerceIn(1f, 8f)
                        z.scale = ns
                        z.offset = if (ns <= 1.001f) Offset.Zero else clampOffset(z.offset + ev.calculatePan(), size, ns)
                        onZoom(ns > 1.001f)
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    } else if (onHorizontalScrub != null && ev.changes.size == 1) {
                        val c = ev.changes[0]
                        if (c.pressed) {
                            val pan = ev.calculatePan()
                            accX += pan.x
                            accY += pan.y
                            if (!draggingH && abs(accX) > slop && abs(accX) > abs(accY) * 1.2f) {
                                draggingH = true
                                onHorizontalScrubStart?.invoke()
                            }
                            if (draggingH) {
                                onHorizontalScrub(accX, size.width)
                                if (c.positionChanged()) c.consume()
                            }
                        }
                    }
                } while (ev.changes.any { it.pressed })
            } finally {
                if (draggingH) onHorizontalScrubEnd?.invoke()
            }
        }
    }

fun Modifier.zoomLayer(z: ZoomState): Modifier = this.graphicsLayer {
    scaleX = z.scale; scaleY = z.scale; translationX = z.offset.x; translationY = z.offset.y
}

// ====================================================================== รูป
@Composable
private fun ImagePage(model: VaultModel, fid: String, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    val z = remember(fid) { ZoomState() }
    var bmp by remember(fid) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(fid) { mutableStateOf(false) }
    var hiRes by remember(fid) { mutableStateOf(false) }
    val cfg = LocalConfiguration.current
    val screenMax = with(LocalDensity.current) { maxOf(cfg.screenWidthDp, cfg.screenHeightDp).dp.roundToPx() }.coerceAtLeast(1080)
    LaunchedEffect(fid) {
        val b = withContext(Dispatchers.IO) { try { Media.decodeImage(model.vault, fid, screenMax) } catch (e: Throwable) { null } }
        b?.let { model.track(it) }
        bmp = b; failed = b == null
    }
    // ซูมลึกแล้วรูปถูกถอดแบบย่อไว้ → ถอดละเอียดขึ้น (ในหน่วยความจำเท่านั้น)
    val deep = z.scale > 1.6f
    LaunchedEffect(fid, deep) {
        if (!deep || hiRes) return@LaunchedEffect
        hiRes = true
        val curB = bmp ?: return@LaunchedEffect
        val real = withContext(Dispatchers.IO) { Media.imageSize(model.vault, fid) } ?: return@LaunchedEffect
        val realMax = maxOf(real.first, real.second)
        if (realMax > maxOf(curB.width, curB.height) * 1.2f) {
            val b = withContext(Dispatchers.IO) {
                try { Media.decodeImage(model.vault, fid, minOf(4096, realMax)) } catch (e: Throwable) { null }
            }
            if (b != null) { model.track(b); bmp = b }
        }
    }
    Box(Modifier.fillMaxSize().zoomGestures(z, onTap, onZoom), contentAlignment = Alignment.Center) {
        val b = bmp
        when {
            b != null -> Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().zoomLayer(z))
            failed -> Text("แสดงรูปนี้ในแอปไม่ได้ (ไฟล์ใหญ่เกินไปหรือรูปแบบไม่รองรับ)\nใช้เมนู ⋮ → เปิดด้วยแอปอื่น",
                color = Color.LightGray, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            else -> CircularProgressIndicator()
        }
    }
}

// ====================================================================== วิดีโอ / เสียง
@Composable
private fun MediaPage(model: VaultModel, e: Item, active: Boolean, chrome: Boolean, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    if (!active) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(iconFor(e), null, tint = Palette.Muted, modifier = Modifier.size(64.dp))
        }
        return
    }
    // วิดีโอ: แสดงภาพปกเต็มจอก่อน – ยังไม่สร้างตัวเล่น/ไม่ถอดรหัสวิดีโอจนกว่าจะกดปุ่มเล่น
    var started by remember(e.id) { mutableStateOf(e.kind != "video") }
    if (!started) { VideoPoster(model, e, onPlay = { started = true }, onTap = onTap); return }
    val ctx = LocalContext.current
    val view = LocalView.current
    val player = remember(e.id) { PlayerFactory.create(ctx, e.id) { model.vault.openReader(e.id) } }
    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var vw by remember { mutableIntStateOf(0) }
    var vh by remember { mutableIntStateOf(0) }
    var par by remember { mutableFloatStateOf(1f) }
    var err by remember { mutableStateOf<String?>(null) }
    var muted by remember { mutableStateOf(false) }
    var scrubbing by remember(player) { mutableStateOf(false) }
    var screenScrub by remember(player) { mutableStateOf(false) }
    var scrubMs by remember(player) { mutableLongStateOf(0L) }
    var scrubStartMs by remember(player) { mutableLongStateOf(0L) }
    var scrubDeltaMs by remember(player) { mutableLongStateOf(0L) }
    var wasPlayingBeforeScrub by remember(player) { mutableStateOf(false) }
    val seekCh = remember(player) { Channel<Long>(Channel.CONFLATED) }
    var tapCueSide by remember(player) { mutableIntStateOf(0) }
    var tapCueSec by remember(player) { mutableIntStateOf(0) }
    var tapCueSeq by remember(player) { mutableIntStateOf(0) }
    val z = remember(e.id) { ZoomState() }
    val isVideo = e.kind == "video"

    var playerActive by remember(player) { mutableStateOf(true) }
    DisposableEffect(player) {
        playerActive = true
        model.player = player
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) { ended = playbackState == Player.STATE_ENDED }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                vw = videoSize.width; vh = videoSize.height; par = videoSize.pixelWidthHeightRatio
            }
            override fun onPlayerError(error: PlaybackException) { err = "เล่นไฟล์นี้ไม่ได้ (${error.errorCodeName})" }
        }
        player.addListener(l)
        player.playWhenReady = true
        onDispose {
            playerActive = false
            seekCh.close()
            player.removeListener(l)
            if (model.player === player) model.player = null
            try { player.release() } catch (_: Exception) {}
            view.keepScreenOn = false
            if (model.fullscreen) model.fullscreen = false
        }
    }
    LaunchedEffect(player) {
        while (playerActive && model.player === player) {
            try {
                if (!scrubbing) pos = player.currentPosition.coerceAtLeast(0)
                val d = player.duration
                dur = if (d == C.TIME_UNSET || d < 0) 0L else d
            } catch (_: Exception) { break }
            delay(250)
        }
    }
    LaunchedEffect(player) {
        for (ms in seekCh) {
            if (scrubbing && playerActive && model.player === player) {
                try { player.seekTo(ms) } catch (_: Exception) {}
            }
            delay(60)
        }
    }
    LaunchedEffect(tapCueSeq) {
        if (tapCueSeq > 0) {
            delay(750)
            tapCueSide = 0
            tapCueSec = 0
        }
    }
    LaunchedEffect(playing) { view.keepScreenOn = playing }

    fun beginScrub(initialMs: Long, fromScreen: Boolean) {
        if (!scrubbing) {
            wasPlayingBeforeScrub = player.playWhenReady && !ended
            scrubbing = true
            screenScrub = fromScreen
            scrubStartMs = initialMs
            scrubMs = initialMs
            scrubDeltaMs = 0L
            if (playing) player.pause()
            try { player.setSeekParameters(SeekParameters.CLOSEST_SYNC) } catch (_: Exception) {}
        }
    }
    fun updateScrub(targetMs: Long, deltaMs: Long = 0L) {
        val maxDur = dur.coerceAtLeast(0L)
        val clamped = if (maxDur > 0L) targetMs.coerceIn(0L, maxDur) else targetMs.coerceAtLeast(0L)
        scrubMs = clamped
        scrubDeltaMs = deltaMs
        pos = clamped
        seekCh.trySend(clamped)
    }
    fun endScrub() {
        if (!scrubbing) return
        val maxDur = dur.coerceAtLeast(0L)
        val finalMs = if (maxDur > 0L) scrubMs.coerceIn(0L, maxDur) else scrubMs.coerceAtLeast(0L)
        scrubbing = false
        screenScrub = false
        try {
            player.setSeekParameters(SeekParameters.EXACT)
            player.seekTo(finalMs)
        } catch (_: Exception) {}
        pos = finalMs
        if (wasPlayingBeforeScrub) {
            try { player.play() } catch (_: Exception) {}
        }
    }

    val playPause: () -> Unit = {
        when {
            ended -> { player.seekTo(0); player.play() }
            playing -> player.pause()
            else -> player.play()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (isVideo) {
            Box(
                Modifier.fillMaxSize().zoomGestures(
                    z = z,
                    onTap = onTap,
                    onZoom = onZoom,
                    onDoubleTap = { p, sz ->
                        val left = p.x < sz.width / 2f
                        val curPos = player.currentPosition.coerceAtLeast(0)
                        val target = if (left) {
                            (curPos - 5_000).coerceAtLeast(0)
                        } else {
                            if (dur > 0) minOf(dur, curPos + 5_000) else curPos + 5_000
                        }
                        player.seekTo(target)
                        pos = target
                        val side = if (left) -1 else 1
                        if (tapCueSide == side) tapCueSec += 5 else { tapCueSide = side; tapCueSec = 5 }
                        tapCueSeq++
                    },
                    onHorizontalScrubStart = {
                        if (dur > 0L) beginScrub(player.currentPosition.coerceAtLeast(0L), fromScreen = true)
                    },
                    onHorizontalScrub = { dx, width ->
                        if (dur > 0L && scrubbing) {
                            val spanMs = minOf(dur, 120_000L).coerceAtLeast(1_000L).toFloat()
                            val rawDelta = (dx / width.coerceAtLeast(1) * spanMs).toLong()
                            val target = (scrubStartMs + rawDelta).coerceIn(0L, dur)
                            updateScrub(target, target - scrubStartMs)
                        }
                    },
                    onHorizontalScrubEnd = { endScrub() },
                ),
                contentAlignment = Alignment.Center,
            ) {
                val ar = if (vw > 0 && vh > 0) (vw * par / vh).coerceIn(0.1f, 10f) else 16f / 9f
                AndroidView(
                    factory = { c -> TextureView(c).also { tv -> player.setVideoTextureView(tv) } },
                    modifier = Modifier.aspectRatio(ar).zoomLayer(z),
                )
            }
        } else {
            Column(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(iconFor(e), null, tint = Palette.Accent, modifier = Modifier.size(96.dp))
                Spacer(Modifier.height(12.dp))
                Text(e.name, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
        // เอฟเฟกต์แตะสองครั้ง ย้อน/ข้าม 5 วินาที (ซ้าย/ขวา)
        if (isVideo) {
            AnimatedVisibility(
                visible = tapCueSide < 0,
                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(0.42f).fillMaxHeight(0.55f),
            ) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(topEnd = 160.dp, bottomEnd = 160.dp))
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.FastRewind, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("-${tapCueSec} วิ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
            AnimatedVisibility(
                visible = tapCueSide > 0,
                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.42f).fillMaxHeight(0.55f),
            ) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 160.dp, bottomStart = 160.dp))
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.FastForward, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("+${tapCueSec} วิ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
            // แถบเวลาตรงกลางขณะลากนิ้วซ้าย-ขวาบนหน้าจอ
            AnimatedVisibility(
                visible = screenScrub && dur > 0L,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                val deltaSec = scrubDeltaMs / 1000L
                val sign = if (deltaSec >= 0L) "+" else "-"
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xB3000000))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${Media.fmtDuration(scrubMs / 1000.0)} / ${Media.fmtDuration(dur / 1000.0)}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "$sign${abs(deltaSec)} วิ",
                        color = Palette.Accent,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        // วิดีโอจบ → ค้างไว้ที่เฟรมสุดท้าย ไม่เล่นซ้ำเอง ให้ผู้ใช้กดปุ่มวนซ้ำเอง
        if (isVideo && ended && err == null && !scrubbing) {
            Box(Modifier.align(Alignment.Center).size(88.dp).clip(CircleShape).background(Color(0x99000000))
                .clickable { player.seekTo(0); player.play() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Replay, "เล่นซ้ำ", tint = Color.White, modifier = Modifier.size(52.dp))
            }
        }
        err?.let {
            Text(it + "\nใช้เมนู ⋮ → เปิดด้วยแอปอื่น", color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(16.dp))
        }
        if (chrome || !isVideo) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x66000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 8.dp, vertical = 4.dp)) {
                val frac = if (scrubbing && dur > 0L) (scrubMs.toFloat() / dur).coerceIn(0f, 1f)
                           else if (dur > 0L) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                Slider(
                    value = frac,
                    onValueChange = { f ->
                        if (dur <= 0L) return@Slider
                        val ms = (f * dur).toLong().coerceIn(0L, dur)
                        if (!scrubbing) beginScrub(ms, fromScreen = false)
                        updateScrub(ms)
                    },
                    onValueChangeFinished = { endScrub() },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.Accent),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shown = if (scrubbing) scrubMs else pos
                    Text("${Media.fmtDuration(shown / 1000.0)} / ${Media.fmtDuration(dur / 1000.0)}", color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { player.seekTo((pos - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Filled.Replay10, "ย้อน 10 วิ", tint = Color.White) }
                    IconButton(onClick = playPause, modifier = Modifier.size(64.dp)) {   // ปุ่มใหญ่ กดง่าย
                        Icon(if (ended) Icons.Filled.Replay else if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            "เล่น/หยุด", tint = Color.White, modifier = Modifier.size(44.dp))
                    }
                    IconButton(onClick = { player.seekTo(if (dur > 0) minOf(dur, pos + 10_000) else pos + 10_000) }) {
                        Icon(Icons.Filled.Forward10, "ข้าม 10 วิ", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    if (!isVideo) IconButton(onClick = { muted = !muted; player.volume = if (muted) 0f else 1f }) {   // วิดีโอ: ไม่มีปุ่มเสียง
                        Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "เสียง", tint = Color.White)
                    }
                    if (isVideo) {
                        IconButton(onClick = { model.setCover(e.id, pos / 1000.0) }) {
                            Icon(Icons.Filled.Wallpaper, "ตั้งเป็นภาพปก", tint = Color.White)
                        }
                        IconButton(onClick = {
                            model.fsLandscape = vw == 0 || vw * par >= vh
                            model.fullscreen = !model.fullscreen
                        }) {
                            Icon(if (model.fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "เต็มจอ", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** ภาพปกวิดีโอ (ภาพย่อเดียวกับในแกลเลอรี – ถอดรหัสในหน่วยความจำ) + ปุ่มเล่นตรงกลาง */
@Composable
private fun VideoPoster(model: VaultModel, e: Item, onPlay: () -> Unit, onTap: () -> Unit) {
    val thumb by produceState(initialValue = model.thumbCached(e.id), e.id, model.thumbEpoch) {
        value = model.thumb(e.id)
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }, contentAlignment = Alignment.Center) {
        val t = thumb
        if (t != null) Image(t.image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Icon(iconFor(e), null, tint = Palette.Muted, modifier = Modifier.size(64.dp).offset(y = 72.dp))
        Box(Modifier.size(88.dp).clip(CircleShape).background(Color(0x99000000)).clickable { onPlay() },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, "เล่น", tint = Color.White, modifier = Modifier.size(56.dp))
        }
    }
}

// ====================================================================== ข้อความ (อ่าน/แก้ไขในตู้)
@Composable
private fun TextPage(model: VaultModel, key: String, fid: String, active: Boolean, focus: Boolean) {
    var failed by remember(key) { mutableStateOf(false) }
    val loaded by produceState<TextCodec.Decoded?>(null, key) {
        value = try { model.loadText(fid)?.first } catch (e: Throwable) { null }
        if (value == null) failed = true
    }
    val d = loaded
    val session = remember(key, d) {
        d?.let { TextSession(fid, it.enc, it.crlf, it.exact, model.entryOf(fid)?.name ?: "") }
    }
    LaunchedEffect(active, session) { if (active && session != null) model.textSession = session }
    DisposableEffect(session) { onDispose { if (session != null && model.textSession === session) model.textSession = null } }

    Column(Modifier.fillMaxSize().padding(top = 56.dp)) {
        when {
            d != null && session != null -> {
                Text(
                    if (d.exact) "${d.enc.uppercase()}${if (d.crlf) " • CRLF" else ""}  •  แก้ไขได้ – บันทึกแบบเข้ารหัสลงตู้โดยตรง ไม่มีไฟล์ชั่วคราว"
                    else "อ่านอย่างเดียว – ไฟล์นี้ไม่ใช่ข้อความ UTF-8/ไทย (TIS-620) แก้ไขแล้วบันทึกอาจทำให้ข้อมูลเพี้ยน",
                    color = if (d.exact) Palette.Muted else Palette.Warn, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                TextEditorView(
                    initial = d.text, editable = d.exact, autoFocus = focus,
                    onCreated = { et -> session.getText = { et.text.toString() } },
                    onDirty = { session.dirty = true },
                    modifier = Modifier.weight(1f).background(Palette.Panel),
                )
            }
            failed -> {
                val e = model.entryOf(fid)
                if (e != null) OtherPage(model, e, note = "ไฟล์ข้อความใหญ่เกิน 5 MB หรืออ่านไม่ได้ – เปิดด้วยแอปอื่นแทน")
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

// ====================================================================== ไฟล์อื่นๆ
@Composable
private fun OtherPage(model: VaultModel, e: Item, note: String = "") {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(iconFor(e), null, tint = Palette.Muted, modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(12.dp))
        Text(e.name, color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center)
        Text("${Mime.humanSize(e.size)}  •  ${model.location(e.id)}", color = Palette.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Text(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date((e.added * 1000).toLong())), color = Palette.Muted, fontSize = 13.sp)
        if (note.isNotEmpty()) Text(note, color = Palette.Warn, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(20.dp))
        Button(onClick = { model.openExternal(e.id) }, modifier = Modifier.fillMaxWidth()) { Text("↗ เปิดด้วยแอปอื่น (ชั่วคราว)") }
        OutlinedButton(onClick = { model.exportItems(listOf(e.id)) }, modifier = Modifier.fillMaxWidth()) { Text("⇡ ส่งออก (ถอดรหัส)") }
        OutlinedButton(onClick = { model.shareItems(listOf(e.id)) }, modifier = Modifier.fillMaxWidth()) { Text("☁ แชร์แบบเข้ารหัส (.bsx)") }
        Spacer(Modifier.width(1.dp))
    }
}
