package com.bestspace.vault.ui

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.bestspace.vault.core.Mime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

enum class PickMode { MEDIA, FILES, FOLDER, BSX }

sealed class InAppPickResult {
    data class Sources(val items: List<Source>, val folderBases: List<File> = emptyList()) : InAppPickResult()
    data object UseSystemPicker : InAppPickResult()
}

/** ตัวเลือกไฟล์/รูป/วิดีโอ/โฟลเดอร์ภายในแอป (ไม่ออกจาก MainActivity → ย่อแอป/ปิดจอเมื่อไรล็อคทันที) */
class AskInAppPick(val mode: PickMode) : Ask<InAppPickResult?>()

private data class PickerMediaItem(
    val key: String,
    val mediaId: Long,
    val path: String?,
    val name: String,
    val mime: String,
    val size: Long,
    val modifiedSec: Long,
    val durationMs: Long,
    val isVideo: Boolean,
    val bucketId: String,
    val bucketName: String,
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaId,
        )
}

private data class PickerAlbum(
    val id: String,
    val name: String,
    val count: Int,
    val cover: PickerMediaItem,
)

private data class PickerDirEntry(
    val file: File,
    val isDir: Boolean,
    val size: Long,
    val modifiedMs: Long,
)

private data class PickerCandidate(
    val key: String,
    val name: String,
    val size: Long,
    val mime: String?,
    val file: File?,
    val uri: Uri?,
    val isVideo: Boolean,
    val durationMs: Long = 0L,
    val path: String? = null,
) {
    fun resolveFile(): File? = file ?: path?.let { File(it) }?.takeIf { it.isFile && it.canRead() }
    fun toSource(): Source = Source(name, size, mime, resolveFile(), uri, emptyList())
}

private class PickerThumbStore {
    private val gate = Semaphore(4)
    private val bitmaps = Collections.synchronizedList(ArrayList<Bitmap>())
    private val cache = object : LruCache<String, ImageBitmap>(120) {}

    fun cached(key: String): ImageBitmap? = cache.get(key)

    suspend fun load(ctx: Context, key: String, uri: Uri?, file: File?, path: String?, isVideo: Boolean): ImageBitmap? {
        cache.get(key)?.let { return it }
        return gate.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val bmp = withContext(Dispatchers.IO) { decodePickerThumb(ctx, uri, file, path, isVideo) } ?: return@withPermit null
            bitmaps.add(bmp)
            val img = bmp.asImageBitmap()
            cache.put(key, img)
            img
        }
    }

    fun clear() {
        cache.evictAll()
        val copy = synchronized(bitmaps) { ArrayList(bitmaps).also { bitmaps.clear() } }
        copy.forEach { Media.wipeBitmap(it) }
    }
}

private fun decodePickerThumb(ctx: Context, uri: Uri?, file: File?, path: String?, isVideo: Boolean): Bitmap? {
    if (uri != null && Build.VERSION.SDK_INT >= 29) {
        try {
            return ctx.contentResolver.loadThumbnail(uri, Size(280, 280), null)
        } catch (_: Exception) {}
    }
    val f = (file ?: path?.let { File(it) })?.takeIf { it.isFile && it.canRead() } ?: return null
    return if (isVideo) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = if (durMs > 3000) 1_000_000L else 0L
            val fr = mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime(0) ?: return null
            Media.fitLong(fr, 280)
        } catch (_: Exception) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    } else {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 280) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inMutable = true
            }) ?: return null
            val orient = try {
                ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val rotated = rotateBitmap(bmp, orient)
            Media.fitLong(rotated, 280)
        } catch (_: Exception) {
            null
        }
    }
}

private fun decodePickerPreview(ctx: Context, c: PickerCandidate): Bitmap? {
    val f = c.resolveFile()
    if (c.isVideo) {
        if (f != null) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(f.absolutePath)
                val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val atUs = if (durMs > 3000) 1_000_000L else 0L
                val fr = mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: mmr.getFrameAtTime(0)
                if (fr != null) return Media.fitLong(fr, 1280)
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        }
        if (c.uri != null && Build.VERSION.SDK_INT >= 29) {
            return try { ctx.contentResolver.loadThumbnail(c.uri, Size(960, 960), null) } catch (_: Exception) { null }
        }
        return null
    }
    return try {
        if (f != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1440) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inMutable = true
            }) ?: return null
            val orient = try {
                ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
            Media.fitLong(rotateBitmap(bmp, orient), 1440)
        } else if (c.uri != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(c.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1440) sample *= 2
            ctx.contentResolver.openInputStream(c.uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true })
            }?.let { Media.fitLong(it, 1440) }
        } else null
    } catch (_: Exception) { null }
}

private fun rotateBitmap(b: Bitmap, orient: Int): Bitmap {
    val m = Matrix()
    when (orient) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        else -> return b
    }
    val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    if (out !== b) Media.wipeBitmap(b)
    return out
}

private fun queryMediaTable(
    ctx: Context,
    model: VaultModel,
    vaultRoots: List<String>,
    isVideo: Boolean,
    limit: Int?,
    sig: CancellationSignal? = null,
): Pair<List<PickerMediaItem>, Boolean> {
    val out = ArrayList<PickerMediaItem>(limit ?: 256)
    val tableUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    @Suppress("DEPRECATION")
    val dataCol = MediaStore.MediaColumns.DATA
    val proj = if (isVideo) {
        arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            dataCol,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
        )
    } else {
        arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            dataCol,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
    }
    var rawRows = 0
    try {
        val cursor = if (limit != null && Build.VERSION.SDK_INT >= 30) {
            val bundle = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns._ID),
                )
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            ctx.contentResolver.query(tableUri, proj, bundle, sig)
        } else {
            val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC" +
                if (limit != null) " LIMIT $limit" else ""
            ctx.contentResolver.query(tableUri, proj, null, null, sort, sig)
        }
        cursor?.use { c ->
            val iId = c.getColumnIndex(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val iMod = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val iData = c.getColumnIndex(dataCol)
            val iBid = c.getColumnIndex(if (isVideo) MediaStore.Video.Media.BUCKET_ID else MediaStore.Images.Media.BUCKET_ID)
            val iBname = c.getColumnIndex(if (isVideo) MediaStore.Video.Media.BUCKET_DISPLAY_NAME else MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val iDur = if (isVideo) c.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
            while (c.moveToNext()) {
                if (sig?.isCanceled == true) break
                rawRows++
                val id = if (iId >= 0) c.getLong(iId) else continue
                val path = if (iData >= 0) c.getString(iData) else null
                val rawName = if (iName >= 0) c.getString(iName) else null
                val name = rawName ?: path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "media_$id"
                if (model.isVaultOwnPath(path, name, vaultRoots)) continue
                val size = if (iSize >= 0) c.getLong(iSize) else 0L
                if (size <= 0L) continue
                val mime = (if (iMime >= 0) c.getString(iMime) else null)
                    ?: (if (isVideo) "video/*" else "image/*")
                val mod = if (iMod >= 0) c.getLong(iMod) else 0L
                val dur = if (isVideo && iDur >= 0) c.getLong(iDur) else 0L
                val bid = (if (iBid >= 0) c.getString(iBid) else null) ?: "other"
                val parentName = path?.substringBeforeLast('/', "")?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
                val bname = (if (iBname >= 0) c.getString(iBname) else null)?.takeIf { it.isNotBlank() }
                    ?: parentName ?: "อื่นๆ"
                val key = if (!path.isNullOrEmpty()) "f:$path" else if (isVideo) "v:$id" else "i:$id"
                out.add(PickerMediaItem(key, id, path, name, mime, size, mod, dur, isVideo, bid, bname))
                if (limit != null && rawRows >= limit) break
            }
        }
    } catch (_: Exception) {}
    return out to (limit != null && rawRows >= limit)
}

private fun buildAlbums(items: List<PickerMediaItem>): List<PickerAlbum> {
    val byAlbum = LinkedHashMap<String, ArrayList<PickerMediaItem>>()
    for (it in items) byAlbum.getOrPut(it.bucketId) { ArrayList() }.add(it)
    return byAlbum.entries.map { (id, list) ->
        PickerAlbum(id, list.first().bucketName, list.size, list.first())
    }.sortedByDescending { it.count }
}

private suspend fun queryMediaParallel(
    ctx: Context,
    model: VaultModel,
    vaultRoots: List<String>,
    limit: Int?,
): Triple<List<PickerMediaItem>, List<PickerAlbum>, Boolean> = coroutineScope {
    val sig = CancellationSignal()
    try {
        val imgJob = async(Dispatchers.IO) { queryMediaTable(ctx, model, vaultRoots, isVideo = false, limit = limit, sig = sig) }
        val vidJob = async(Dispatchers.IO) { queryMediaTable(ctx, model, vaultRoots, isVideo = true, limit = limit, sig = sig) }
        val (imgs, imgMore) = imgJob.await()
        val (vids, vidMore) = vidJob.await()
        val merged = ArrayList<PickerMediaItem>(imgs.size + vids.size).apply {
            addAll(imgs)
            addAll(vids)
            sortWith(compareByDescending<PickerMediaItem> { it.modifiedSec }.thenByDescending { it.mediaId })
        }
        Triple(merged, buildAlbums(merged), imgMore || vidMore)
    } finally {
        sig.cancel()
    }
}

private fun storageRoots(ctx: Context): List<Pair<String, File>> {
    val out = ArrayList<Pair<String, File>>()
    val seen = HashSet<String>()
    fun add(label: String, f: File?) {
        if (f == null || !f.isDirectory || !f.canRead()) return
        val k = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }
        if (seen.add(k)) out.add(label to f)
    }
    add("ที่เก็บข้อมูลเครื่อง", Storage.mainRoot())
    try {
        ctx.getExternalFilesDirs(null).forEachIndexed { idx, d ->
            val p = d?.absolutePath ?: return@forEachIndexed
            val marker = p.indexOf("/Android/")
            if (marker > 0) {
                val root = File(p.substring(0, marker))
                add(if (idx == 0) "ที่เก็บข้อมูลเครื่อง" else "การ์ดความจำ (${root.name})", root)
            }
        }
    } catch (_: Exception) {}
    return out
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InAppPickerScreen(model: VaultModel, a: AskInAppPick) {
    val ctx = LocalContext.current
    val thumbStore = remember { PickerThumbStore() }
    DisposableEffect(Unit) { onDispose { thumbStore.clear() } }

    val roots = remember { storageRoots(ctx) }
    val primaryRoot = remember(roots) { roots.firstOrNull()?.second ?: Storage.mainRoot() }
    var activeRoot by remember { mutableStateOf(primaryRoot) }
    var currentDir by remember {
        mutableStateOf(
            when (a.mode) {
                PickMode.FILES, PickMode.BSX -> File(primaryRoot, "Download").takeIf { it.isDirectory && it.canRead() } ?: primaryRoot
                else -> primaryRoot
            }
        )
    }

    // ในโหมด MEDIA สลับระหว่างหน้าตารางสื่อ (อัลบั้ม) กับหน้าเดินโฟลเดอร์ได้ในคลิกเดียว
    var browseFolders by remember { mutableStateOf(a.mode != PickMode.MEDIA) }
    var mediaFilter by remember { mutableStateOf("all") }   // "all" | "image" | "video"
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var showAlbumSheet by remember { mutableStateOf(false) }
    var gridCols by remember { mutableIntStateOf(3) }
    var bsxOnly by remember { mutableStateOf(a.mode == PickMode.BSX) }
    var menuOpen by remember { mutableStateOf(false) }
    var refreshEpoch by remember { mutableIntStateOf(0) }
    var previewCandidate by remember { mutableStateOf<PickerCandidate?>(null) }
    val mediaGridState = rememberLazyGridState()
    val dirListState = rememberLazyListState()
    LaunchedEffect(currentDir) { try { dirListState.scrollToItem(0) } catch (_: Exception) {} }

    // รายการที่เลือก (รักษาลำดับการแตะ)
    val pickedOrder = remember { mutableStateListOf<String>() }
    val pickedMap = remember { mutableStateMapOf<String, PickerCandidate>() }

    fun isPicked(key: String) = pickedMap.containsKey(key)
    fun togglePick(c: PickerCandidate) {
        if (pickedMap.containsKey(c.key)) {
            pickedMap.remove(c.key)
            pickedOrder.remove(c.key)
        } else {
            pickedMap[c.key] = c
            pickedOrder.add(c.key)
        }
    }
    fun clearPicks() {
        pickedMap.clear()
        pickedOrder.clear()
    }

    // โหลดรายการสื่อจาก MediaStore แบบ 2 จังหวะ (ขึ้น 160 รายการล่าสุดทันที แล้วค่อยเติมส่วนที่เหลือในพื้นหลัง)
    val vaultRoots = remember(model) { model.vaultRootPaths() }
    var mediaLoading by remember { mutableStateOf(a.mode == PickMode.MEDIA) }
    var mediaMoreLoading by remember { mutableStateOf(false) }
    var allMedia by remember { mutableStateOf<List<PickerMediaItem>>(emptyList()) }
    var albums by remember { mutableStateOf<List<PickerAlbum>>(emptyList()) }
    LaunchedEffect(a.mode, refreshEpoch) {
        if (a.mode == PickMode.MEDIA) {
            mediaLoading = true
            mediaMoreLoading = false
            val (fastItems, fastAlbums, hasMore) = queryMediaParallel(ctx, model, vaultRoots, limit = 160)
            ensureActive()
            allMedia = fastItems
            albums = fastAlbums
            mediaLoading = false
            if (hasMore) {
                mediaMoreLoading = true
                try {
                    val (fullItems, fullAlbums, _) = queryMediaParallel(ctx, model, vaultRoots, limit = null)
                    ensureActive()
                    allMedia = fullItems
                    albums = fullAlbums
                } finally {
                    mediaMoreLoading = false
                }
            }
        }
    }

    // โหลดรายการไฟล์ใน currentDir เมื่อ browseFolders == true (ไม่สแกน f.list() โฟลเดอร์ย่อยล่วงหน้า)
    var dirFiles by remember { mutableStateOf<List<PickerDirEntry>>(emptyList()) }
    var dirFileCount by remember { mutableIntStateOf(0) }
    var dirLoading by remember { mutableStateOf(false) }
    LaunchedEffect(browseFolders, currentDir, refreshEpoch, bsxOnly) {
        if (browseFolders) {
            dirLoading = true
            val (entries, fCount) = withContext(Dispatchers.IO) {
                val raw = currentDir.listFiles() ?: emptyArray()
                val out = ArrayList<PickerDirEntry>(raw.size)
                var regFiles = 0
                for (f in raw) {
                    if (model.isVaultOwnPath(f.absolutePath, f.name, vaultRoots)) continue
                    val isDir = f.isDirectory
                    if (!isDir) {
                        if (f.isFile) regFiles++
                        when (a.mode) {
                            PickMode.FOLDER -> continue
                            PickMode.BSX -> if (bsxOnly && !f.name.endsWith(".bsx", ignoreCase = true)) continue
                            else -> {}
                        }
                    }
                    if (!f.canRead()) continue
                    out.add(PickerDirEntry(f, isDir, if (isDir) 0L else f.length(), f.lastModified()))
                }
                out.sortWith(compareBy<PickerDirEntry>({ !it.isDir }, { -it.modifiedMs }, { it.file.name.lowercase() }))
                out to regFiles
            }
            ensureActive()
            dirFiles = entries
            dirFileCount = fCount
            dirLoading = false
        }
    }

    val filteredMedia = remember(allMedia, mediaFilter, selectedAlbumId) {
        allMedia.filter { item ->
            (selectedAlbumId == null || item.bucketId == selectedAlbumId) &&
                when (mediaFilter) {
                    "image" -> !item.isVideo
                    "video" -> item.isVideo
                    else -> true
                }
        }
    }

    val canGoUp = remember(browseFolders, currentDir, activeRoot) {
        if (!browseFolders) false
        else try { currentDir.canonicalPath != activeRoot.canonicalPath && currentDir.parentFile != null }
        catch (_: Exception) { currentDir.absolutePath != activeRoot.absolutePath && currentDir.parentFile != null }
    }

    fun handleBack() {
        when {
            previewCandidate != null -> previewCandidate = null
            showAlbumSheet -> showAlbumSheet = false
            canGoUp -> currentDir = currentDir.parentFile ?: activeRoot
            a.mode == PickMode.MEDIA && browseFolders -> browseFolders = false
            else -> a.result.complete(null)
        }
    }

    BackHandler(onBack = ::handleBack)

    val selectedAlbumName = remember(albums, selectedAlbumId) {
        albums.firstOrNull { it.id == selectedAlbumId }?.name
    }

    Scaffold(
        containerColor = Palette.Bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    val title = when (a.mode) {
                        PickMode.MEDIA -> selectedAlbumName ?: "เลือกรูป / วิดีโอ"
                        PickMode.FILES -> "เลือกไฟล์นำเข้า"
                        PickMode.BSX -> "เลือกไฟล์ .bsx"
                        PickMode.FOLDER -> "เลือกโฟลเดอร์นำเข้า"
                    }
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
                        if (pickedOrder.isNotEmpty()) {
                            Text(
                                if (a.mode == PickMode.FOLDER) "เลือกแล้ว ${pickedOrder.size} โฟลเดอร์"
                                else "เลือกแล้ว ${pickedOrder.size} รายการ",
                                fontSize = 12.sp,
                                color = Palette.Accent,
                            )
                        } else if (a.mode == PickMode.MEDIA && !browseFolders) {
                            Text(
                                "${filteredMedia.size}${if (mediaMoreLoading) "+" else ""} รายการ • กดค้างเพื่อดูตัวอย่าง",
                                fontSize = 11.sp,
                                color = Palette.Muted,
                            )
                        } else if (a.mode == PickMode.FOLDER) {
                            Text(
                                "แตะช่อง ✓ เพื่อเลือกหลายโฟลเดอร์ • แตะชื่อเพื่อเปิด",
                                fontSize = 11.sp,
                                color = Palette.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::handleBack) {
                        Icon(
                            if (canGoUp || (a.mode == PickMode.MEDIA && browseFolders)) Icons.AutoMirrored.Filled.ArrowBack
                            else Icons.Filled.Close,
                            contentDescription = "กลับ",
                        )
                    }
                },
                actions = {
                    if (a.mode == PickMode.MEDIA && !browseFolders) {
                        IconButton(onClick = { gridCols = if (gridCols == 3) 4 else 3 }) {
                            Icon(Icons.Filled.GridView, "สลับขนาดตาราง")
                        }
                        if (filteredMedia.isNotEmpty()) {
                            val allInViewSelected = filteredMedia.all { isPicked(it.key) }
                            IconButton(onClick = {
                                if (allInViewSelected) {
                                    filteredMedia.forEach {
                                        pickedMap.remove(it.key)
                                        pickedOrder.remove(it.key)
                                    }
                                } else {
                                    filteredMedia.forEach { m ->
                                        if (!pickedMap.containsKey(m.key)) {
                                            val cand = PickerCandidate(m.key, m.name, m.size, m.mime, null, m.uri, m.isVideo, m.durationMs, m.path)
                                            pickedMap[m.key] = cand
                                            pickedOrder.add(m.key)
                                        }
                                    }
                                }
                            }) {
                                Icon(
                                    Icons.Filled.SelectAll,
                                    "เลือกทั้งหมด",
                                    tint = if (allInViewSelected) Palette.Accent else Palette.Fg,
                                )
                            }
                        }
                    } else {
                        if (a.mode == PickMode.FOLDER) {
                            val dirsInView = dirFiles.filter { it.isDir }
                            if (dirsInView.isNotEmpty()) {
                                val allDirsSelected = dirsInView.all { isPicked("d:${it.file.absolutePath}") }
                                IconButton(onClick = {
                                    if (allDirsSelected) {
                                        dirsInView.forEach { d ->
                                            val k = "d:${d.file.absolutePath}"
                                            pickedMap.remove(k)
                                            pickedOrder.remove(k)
                                        }
                                    } else {
                                        dirsInView.forEach { d ->
                                            val f = d.file
                                            val k = "d:${f.absolutePath}"
                                            if (!pickedMap.containsKey(k)) {
                                                val cand = PickerCandidate(k, f.name, 0L, null, f, null, false, path = f.absolutePath)
                                                pickedMap[k] = cand
                                                pickedOrder.add(k)
                                            }
                                        }
                                    }
                                }) {
                                    Icon(
                                        Icons.Filled.SelectAll,
                                        "เลือกทั้งหมด",
                                        tint = if (allDirsSelected) Palette.Accent else Palette.Fg,
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { refreshEpoch++ }) {
                            Icon(Icons.Filled.Refresh, "รีเฟรช")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, "เพิ่มเติม")
                        }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("📁 เลือกจากระบบ / Cloud (Drive)…") },
                                onClick = {
                                    menuOpen = false
                                    a.result.complete(InAppPickResult.UseSystemPicker)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("🔄 รีเฟรชรายการ") },
                                onClick = {
                                    menuOpen = false
                                    refreshEpoch++
                                },
                            )
                            if (pickedOrder.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("✕ ล้างที่เลือกทั้งหมด (${pickedOrder.size})") },
                                    onClick = {
                                        menuOpen = false
                                        clearPicks()
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg),
            )
        },
        bottomBar = {
            if (a.mode == PickMode.FOLDER) {
                Surface(color = Palette.Panel, tonalElevation = 4.dp) {
                    if (pickedOrder.isNotEmpty()) {
                        val names = pickedOrder.mapNotNull { pickedMap[it]?.name }.joinToString(", ")
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("เลือก ${pickedOrder.size} โฟลเดอร์", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(names, fontSize = 12.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = ::clearPicks) { Text("ล้าง") }
                            Spacer(Modifier.width(6.dp))
                            Button(onClick = {
                                val dirs = pickedOrder.mapNotNull { pickedMap[it]?.let { c -> c.file ?: c.path?.let(::File) } }
                                a.result.complete(InAppPickResult.Sources(emptyList(), folderBases = dirs))
                            }) {
                                Text("นำเข้า (${pickedOrder.size})")
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(currentDir.name.ifBlank { activeRoot.name }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("ในชั้นนี้มี $dirFileCount ไฟล์ (รวมโฟลเดอร์ย่อยทั้งหมด)", fontSize = 12.sp, color = Palette.Muted)
                            }
                            Spacer(Modifier.width(12.dp))
                            Button(onClick = {
                                a.result.complete(InAppPickResult.Sources(emptyList(), folderBases = listOf(currentDir)))
                            }) {
                                Text("เลือกโฟลเดอร์นี้")
                            }
                        }
                    }
                }
            } else if (pickedOrder.isNotEmpty()) {
                val totalSize = pickedOrder.sumOf { maxOf(0L, pickedMap[it]?.size ?: 0L) }
                Surface(color = Palette.Panel, tonalElevation = 6.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("เลือก ${pickedOrder.size} รายการ", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(Mime.humanSize(totalSize), fontSize = 12.sp, color = Palette.Muted)
                        }
                        TextButton(onClick = ::clearPicks) { Text("ล้าง") }
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = {
                            val list = pickedOrder.mapNotNull { pickedMap[it]?.toSource() }
                            a.result.complete(InAppPickResult.Sources(list))
                        }) {
                            Text("นำเข้า (${pickedOrder.size})")
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (a.mode == PickMode.MEDIA) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = !browseFolders && mediaFilter == "all" && selectedAlbumId == null,
                        onClick = { browseFolders = false; mediaFilter = "all"; selectedAlbumId = null },
                        label = { Text("ทั้งหมด") },
                    )
                    FilterChip(
                        selected = !browseFolders && mediaFilter == "image",
                        onClick = { browseFolders = false; mediaFilter = "image" },
                        label = { Text("รูปภาพ") },
                    )
                    FilterChip(
                        selected = !browseFolders && mediaFilter == "video",
                        onClick = { browseFolders = false; mediaFilter = "video" },
                        label = { Text("วิดีโอ") },
                    )
                    FilterChip(
                        selected = !browseFolders && selectedAlbumId != null,
                        onClick = { browseFolders = false; showAlbumSheet = true },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(selectedAlbumName ?: "อัลบั้ม")
                                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp))
                            }
                        },
                    )
                    FilterChip(
                        selected = browseFolders,
                        onClick = { browseFolders = true },
                        label = { Text("📂 โฟลเดอร์") },
                    )
                }
            } else if (a.mode == PickMode.BSX) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = bsxOnly, onClick = { bsxOnly = true }, label = { Text("เฉพาะ .bsx") })
                    FilterChip(selected = !bsxOnly, onClick = { bsxOnly = false }, label = { Text("ทุกไฟล์") })
                }
            }

            if (!browseFolders && a.mode == PickMode.MEDIA) {
                if (mediaLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(10.dp))
                            Text("กำลังโหลดคลังรูปและวิดีโอ…", color = Palette.Muted, fontSize = 13.sp)
                        }
                    }
                } else if (filteredMedia.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Image, null, tint = Palette.Muted, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("ไม่พบรูปภาพหรือวิดีโอในหมวดนี้", color = Palette.Muted)
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = { browseFolders = true }) { Text("ค้นหาในโฟลเดอร์เครื่อง →") }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridCols),
                            state = mediaGridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            items(filteredMedia, key = { it.key }) { item ->
                                val selIdx = pickedOrder.indexOf(item.key)
                                val uri = remember(item.key) { item.uri }
                                val cand = PickerCandidate(item.key, item.name, item.size, item.mime, null, uri, item.isVideo, item.durationMs, item.path)
                                MediaGridTile(
                                    ctx = ctx,
                                    store = thumbStore,
                                    key = item.key,
                                    uri = uri,
                                    path = item.path,
                                    isVideo = item.isVideo,
                                    durationMs = item.durationMs,
                                    selectedNumber = if (selIdx >= 0) selIdx + 1 else null,
                                    onToggle = { togglePick(cand) },
                                    onPreview = { previewCandidate = cand },
                                )
                            }
                        }
                        GridFastScrollbar(
                            state = mediaGridState,
                            totalItems = filteredMedia.size,
                            columns = gridCols,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            spacingDp = 3.dp,
                            bottomInset = 8.dp,
                        )
                    }
                }
            } else {
                // ตัวเลือกโฟลเดอร์ / ไฟล์ในเครื่อง
                FolderShortcutBar(
                    roots = roots,
                    activeRoot = activeRoot,
                    currentDir = currentDir,
                    onPickRoot = { r -> activeRoot = r; currentDir = r },
                    onPickDir = { d -> currentDir = d },
                )
                HorizontalDivider(color = Palette.Panel2)
                if (dirLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(state = dirListState, modifier = Modifier.fillMaxSize()) {
                        if (canGoUp) {
                            item(key = "__up__") {
                                Row(
                                    Modifier.fillMaxWidth().clickable { currentDir = currentDir.parentFile ?: activeRoot }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.FolderOpen, null, tint = Palette.Accent, modifier = Modifier.size(26.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text("..  (กลับโฟลเดอร์ก่อนหน้า)", color = Palette.Accent, fontSize = 14.sp)
                                }
                                HorizontalDivider(color = Palette.Panel)
                            }
                        }
                        if (dirFiles.isEmpty()) {
                            item(key = "__empty__") {
                                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        when {
                                            a.mode == PickMode.FOLDER && pickedOrder.isNotEmpty() -> "ไม่มีโฟลเดอร์ย่อยในชั้นนี้"
                                            a.mode == PickMode.FOLDER -> "ไม่มีโฟลเดอร์ย่อยในชั้นนี้ (กด “เลือกโฟลเดอร์นี้” ด้านล่างได้เลย)"
                                            else -> "โฟลเดอร์นี้ว่างเปล่า"
                                        },
                                        color = Palette.Muted,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                        items(dirFiles, key = { it.file.absolutePath }) { entry ->
                            val f = entry.file
                            if (entry.isDir) {
                                if (a.mode == PickMode.FOLDER) {
                                    val key = "d:${f.absolutePath}"
                                    val picked = isPicked(key)
                                    val cand = PickerCandidate(
                                        key = key,
                                        name = f.name,
                                        size = 0L,
                                        mime = null,
                                        file = f,
                                        uri = null,
                                        isVideo = false,
                                        path = f.absolutePath,
                                    )
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .background(if (picked) Palette.Accent.copy(alpha = 0.14f) else Color.Transparent)
                                            .combinedClickable(
                                                onClick = { currentDir = f },
                                                onLongClick = { togglePick(cand) },
                                            )
                                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.Folder, null, tint = Palette.Folder, modifier = Modifier.size(30.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(f.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("โฟลเดอร์ • แตะเพื่อเปิด", fontSize = 12.sp, color = Palette.Muted)
                                        }
                                        Checkbox(checked = picked, onCheckedChange = { togglePick(cand) })
                                    }
                                } else {
                                    Row(
                                        Modifier.fillMaxWidth().clickable { currentDir = f }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.Folder, null, tint = Palette.Folder, modifier = Modifier.size(30.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(f.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("โฟลเดอร์", fontSize = 12.sp, color = Palette.Muted)
                                        }
                                    }
                                }
                            } else {
                                val mimeGuess = Mime.guess(f.name, null)
                                val kind = Mime.kind(f.name, mimeGuess)
                                val isImg = kind == "image"
                                val isVid = kind == "video"
                                val key = "f:${f.absolutePath}"
                                val picked = isPicked(key)
                                val cand = PickerCandidate(
                                    key = key,
                                    name = f.name,
                                    size = entry.size,
                                    mime = mimeGuess,
                                    file = f,
                                    uri = null,
                                    isVideo = isVid,
                                    path = f.absolutePath,
                                )
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (picked) Palette.Accent.copy(alpha = 0.14f) else Color.Transparent)
                                        .combinedClickable(
                                            onClick = { togglePick(cand) },
                                            onLongClick = { if (isImg || isVid) previewCandidate = cand },
                                        )
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Panel2),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isImg || isVid) {
                                            val thumb by produceState(initialValue = thumbStore.cached(key), key) {
                                                value = thumbStore.load(ctx, key, null, f, f.absolutePath, isVid)
                                            }
                                            val t = thumb
                                            if (t != null) Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                            else Icon(if (isVid) Icons.Filled.Movie else Icons.Filled.Image, null, tint = Palette.Muted)
                                        } else {
                                            Icon(fileIconForName(f.name), null, tint = Palette.Muted)
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(f.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${Mime.humanSize(entry.size)}  •  ${fmtDate(entry.modifiedMs)}",
                                            fontSize = 12.sp,
                                            color = Palette.Muted,
                                        )
                                    }
                                    Checkbox(checked = picked, onCheckedChange = { togglePick(cand) })
                                }
                            }
                        }
                        }
                        ListFastScrollbar(
                            state = dirListState,
                            totalItems = dirFiles.size + (if (canGoUp) 1 else 0),
                            modifier = Modifier.align(Alignment.CenterEnd),
                            bottomInset = 8.dp,
                        )
                    }
                }
            }
        }
    }

    if (showAlbumSheet) {
        AlbumPickerDialog(
            ctx = ctx,
            store = thumbStore,
            albums = albums,
            totalCount = allMedia.size,
            selectedId = selectedAlbumId,
            onSelect = { id -> selectedAlbumId = id; showAlbumSheet = false },
            onDismiss = { showAlbumSheet = false },
        )
    }

    previewCandidate?.let { cand ->
        MediaPreviewDialog(
            ctx = ctx,
            candidate = cand,
            picked = isPicked(cand.key),
            onToggle = { togglePick(cand) },
            onDismiss = { previewCandidate = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridTile(
    ctx: Context,
    store: PickerThumbStore,
    key: String,
    uri: Uri,
    path: String?,
    isVideo: Boolean,
    durationMs: Long,
    selectedNumber: Int?,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
) {
    val thumb by produceState(initialValue = store.cached(key), key) {
        value = store.load(ctx, key, uri, null, path, isVideo)
    }
    val selected = selectedNumber != null
    Box(
        Modifier.aspectRatio(1f)
            .background(Palette.Panel2)
            .then(if (selected) Modifier.border(3.dp, Palette.Accent) else Modifier)
            .combinedClickable(onClick = onToggle, onLongClick = onPreview),
    ) {
        val t = thumb
        if (t != null) {
            Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                if (isVideo) Icons.Filled.Movie else Icons.Filled.Image,
                null,
                tint = Palette.Muted,
                modifier = Modifier.align(Alignment.Center).size(30.dp),
            )
        }
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color(0x40000000)))
        }
        if (isVideo) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                if (durationMs > 0) {
                    Text(Media.fmtDuration(durationMs / 1000.0), color = Color.White, fontSize = 10.sp)
                }
            }
        }
        if (selectedNumber != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp)
                    .clip(CircleShape).background(Palette.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("$selectedNumber", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)
                    .clip(CircleShape).background(Color(0x55000000))
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
            )
        }
    }
}

@Composable
private fun FolderShortcutBar(
    roots: List<Pair<String, File>>,
    activeRoot: File,
    currentDir: File,
    onPickRoot: (File) -> Unit,
    onPickDir: (File) -> Unit,
) {
    val quickDirs = remember(activeRoot) {
        listOf("DCIM", "Pictures", "Movies", "Download", "Documents").mapNotNull { name ->
            File(activeRoot, name).takeIf { it.isDirectory && it.canRead() }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (roots.size > 1) {
                roots.forEach { (label, r) ->
                    FilterChip(
                        selected = activeRoot.absolutePath == r.absolutePath,
                        onClick = { onPickRoot(r) },
                        leadingIcon = { Icon(Icons.Filled.SdStorage, null, Modifier.size(16.dp)) },
                        label = { Text(label) },
                    )
                }
            }
            FilterChip(
                selected = currentDir.absolutePath == activeRoot.absolutePath,
                onClick = { onPickDir(activeRoot) },
                label = { Text("หน้าหลัก") },
            )
            quickDirs.forEach { d ->
                FilterChip(
                    selected = currentDir.absolutePath == d.absolutePath,
                    onClick = { onPickDir(d) },
                    label = { Text(d.name) },
                )
            }
        }
        val rel = remember(currentDir, activeRoot) {
            val base = activeRoot.absolutePath.trimEnd('/')
            val cur = currentDir.absolutePath.trimEnd('/')
            val raw = if (cur == base) "/" else cur.removePrefix(base)
            if (raw.length > 48) "…" + raw.takeLast(47) else raw
        }
        Text(
            rel,
            fontSize = 11.sp,
            color = Palette.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AlbumPickerDialog(
    ctx: Context,
    store: PickerThumbStore,
    albums: List<PickerAlbum>,
    totalCount: Int,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("เลือกอัลบั้ม") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                item(key = "__all__") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (selectedId == null) Palette.Accent.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { onSelect(null) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Panel2), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Image, null, tint = Palette.Accent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("รูปและวิดีโอทั้งหมด", fontWeight = FontWeight.SemiBold)
                            Text("$totalCount รายการ", fontSize = 12.sp, color = Palette.Muted)
                        }
                        if (selectedId == null) Icon(Icons.Filled.Check, null, tint = Palette.Accent)
                    }
                }
                items(albums, key = { it.id }) { alb ->
                    val cov = alb.cover
                    val covUri = remember(cov.key) { cov.uri }
                    val thumb by produceState(initialValue = store.cached(cov.key), cov.key) {
                        value = store.load(ctx, cov.key, covUri, null, cov.path, cov.isVideo)
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (selectedId == alb.id) Palette.Accent.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { onSelect(alb.id) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Panel2), contentAlignment = Alignment.Center) {
                            val t = thumb
                            if (t != null) Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Icon(Icons.Filled.Folder, null, tint = Palette.Folder)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(alb.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text("${alb.count} รายการ", fontSize = 12.sp, color = Palette.Muted)
                        }
                        if (selectedId == alb.id) Icon(Icons.Filled.Check, null, tint = Palette.Accent)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
    )
}

@Composable
private fun MediaPreviewDialog(
    ctx: Context,
    candidate: PickerCandidate,
    picked: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    var bmp by remember(candidate.key) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(candidate.key) { mutableStateOf(true) }
    DisposableEffect(candidate.key) {
        onDispose {
            Media.wipeBitmap(bmp)
            bmp = null
        }
    }
    LaunchedEffect(candidate.key) {
        loading = true
        val decoded = withContext(Dispatchers.IO) { decodePickerPreview(ctx, candidate) }
        bmp = decoded
        loading = false
    }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize()
            .background(Color(0xF5000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Palette.Panel.copy(alpha = 0.92f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "ปิด", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(candidate.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    val meta = buildString {
                        append(Mime.humanSize(candidate.size))
                        if (candidate.isVideo && candidate.durationMs > 0) append(" • ${Media.fmtDuration(candidate.durationMs / 1000.0)}")
                    }
                    Text(meta, color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val curBmp = bmp
                when {
                    loading -> CircularProgressIndicator()
                    curBmp != null -> Image(
                        curBmp.asImageBitmap(),
                        candidate.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                    else -> Text("แสดงตัวอย่างไม่ได้", color = Palette.Muted)
                }
                if (candidate.isVideo && !loading) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                            .background(Color(0xAA000000), RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (candidate.durationMs > 0) "วิดีโอ • ${Media.fmtDuration(candidate.durationMs / 1000.0)}" else "วิดีโอ",
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Surface(color = Palette.Panel) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("ปิด", maxLines = 1) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onToggle(); onDismiss() }) {
                        Text(
                            if (picked) "✓ เอาออกจากที่เลือก" else "เลือกไฟล์นี้",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun fileIconForName(name: String): ImageVector = when {
    Mime.isAudio(name, "") -> Icons.Filled.Audiotrack
    Mime.ext(name) == "pdf" -> Icons.Filled.PictureAsPdf
    Mime.ext(name) in Mime.ARCHIVE_EXT -> Icons.Filled.Archive
    Mime.isText(name, "") -> Icons.Filled.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun fmtDate(ms: Long): String = if (ms <= 0L) "" else
    SimpleDateFormat("d MMM yy", Locale("th")).format(Date(ms))
