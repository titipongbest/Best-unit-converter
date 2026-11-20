package com.bestspace.vault.ui

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bestspace.vault.core.DecryptedReader

/** ส่งข้อมูลวิดีโอ/เสียงจากตู้ให้ ExoPlayer: ถอดรหัสทีละก้อนในหน่วยความจำ ไม่มีไฟล์ถอดรหัสลงดิสก์
 *  (ไม่ใช้ cache ของ ExoPlayer, บัฟเฟอร์อยู่ในหน่วยความจำเท่านั้น) */
@OptIn(UnstableApi::class)
class VaultExoSource(private val opener: () -> DecryptedReader) : BaseDataSource(false) {
    private var reader: DecryptedReader? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var left = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val r = opener()
        reader = r
        pos = dataSpec.position
        if (pos > r.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, r.size - pos) else r.size - pos
        opened = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left <= 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: return C.RESULT_END_OF_INPUT
        val n = r.readAt(pos, buffer, offset, minOf(length.toLong(), left).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n; left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val r = reader
        reader = null
        uri = null
        try { r?.close() } catch (_: Exception) {}
        if (opened) { opened = false; transferEnded() }
    }
}

object PlayerFactory {
    @OptIn(UnstableApi::class)
    fun create(ctx: Context, fid: String, opener: () -> DecryptedReader): ExoPlayer {
        val p = ExoPlayer.Builder(ctx).build()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        val factory = DataSource.Factory { VaultExoSource(opener) }
        val src = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri("bestspace://file/$fid"))
        p.setMediaSource(src)
        p.prepare()
        return p
    }
}
