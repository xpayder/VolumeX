package com.fatalpuppet.volumex.ui.screens

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.IOException

/** ExoPlayer has no AIFF extractor, so AIFF is presented to it as WAV (header rewritten, big-endian samples swapped). */
@UnstableApi
class AiffWavDataSource(private val upstream: DataSource) : DataSource {
    private var header = ByteArray(44)
    private var dataStart = 0L
    private var dataLen = 0L
    private var sample = 2
    private var swap = true
    private var offsetBinary8 = false
    private var uri: Uri? = null
    private var pos = 0L
    private var ready = ByteArray(0)
    private var readyPos = 0
    private var readyEnd = 0
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun be32(b: ByteArray, o: Int) = (be16(b, o).toLong() shl 16) or be16(b, o + 2).toLong()

    private fun parse(b: ByteArray, n: Int): Boolean {
        if (n < 12 || String(b, 0, 4, Charsets.ISO_8859_1) != "FORM") return false
        val form = String(b, 8, 4, Charsets.ISO_8859_1)
        if (form != "AIFF" && form != "AIFC") return false
        var p = 12
        var channels = 0; var bits = 0; var rate = 0.0; var float = false; var comm = false
        while (p + 8 <= n) {
            val id = String(b, p, 4, Charsets.ISO_8859_1); val size = be32(b, p + 4)
            val body = p + 8
            if (id == "COMM" && body + 18 <= n) {
                channels = be16(b, body); bits = be16(b, body + 6)
                val exp = be16(b, body + 8) and 0x7FFF
                val mant = be32(b, body + 10).toDouble() * 4294967296.0 + be32(b, body + 14).toDouble()     // unsigned 64-bit mantissa
                rate = mant * Math.pow(2.0, (exp - 16383 - 63).toDouble())
                if (form == "AIFC" && body + 22 <= n) when (String(b, body + 18, 4, Charsets.ISO_8859_1)) {
                    "NONE", "twos" -> {}
                    "sowt" -> swap = false
                    "fl32", "FL32" -> { float = true; bits = 32 }
                    "fl64", "FL64" -> { float = true; bits = 64 }
                    else -> return false
                }
                comm = true
            } else if (id == "SSND") {
                if (!comm || body + 8 > n) return false
                val off = be32(b, body)
                dataStart = body + 8 + off
                dataLen = size - 8 - off
                sample = (bits + 7) / 8
                if (channels < 1 || sample < 1 || sample > 8) return false
                offsetBinary8 = bits == 8
                val rateI = rate.toInt()
                val blockAlign = channels * sample
                val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                h.putShort((if (float) 3 else 1).toShort()).putShort(channels.toShort()).putInt(rateI).putInt(rateI * blockAlign)
                h.putShort(blockAlign.toShort()).putShort((sample * 8).toShort()).put("data".toByteArray()).putInt(dataLen.toInt())
                header = h.array()
                return true
            }
            p = (body + size + (size and 1)).toInt()
        }
        return false
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        upstream.open(DataSpec(dataSpec.uri))
        val head = ByteArray(1 shl 18); var n = 0
        while (n < head.size) { val r = upstream.read(head, n, head.size - n); if (r <= 0) break; n += r }
        upstream.close()
        if (!parse(head, n)) throw IOException("Unsupported AIFF (compressed or unusual layout)")
        pos = dataSpec.position
        ready = ByteArray(0); readyPos = 0; readyEnd = 0
        if (pos >= 44) startUpstream() else upstreamOpen = false
        val remaining = 44 + dataLen - pos
        return if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining else minOf(remaining, dataSpec.length)
    }

    private fun startUpstream() {
        val pcm = pos - 44
        val aligned = pcm - pcm % sample
        upstream.open(DataSpec.Builder().setUri(uri!!).setPosition(dataStart + aligned).build())
        upstreamOpen = true
        ready = ByteArray(0); readyPos = 0; readyEnd = 0
        val skip = (pcm - aligned).toInt()
        if (skip > 0 && fill()) readyPos = minOf(skip, readyEnd)
    }

    /** Reads the next chunk of whole samples from upstream, converted to little-endian WAV bytes. */
    private fun fill(): Boolean {
        val chunk = sample * 4096
        val buf = ByteArray(chunk); var n = 0
        while (n < chunk) { val r = upstream.read(buf, n, chunk - n); if (r <= 0) break; n += r }
        n -= n % sample
        if (n <= 0) return false
        if (offsetBinary8) for (i in 0 until n) buf[i] = (buf[i].toInt() xor 0x80).toByte()
        else if (swap && sample > 1) {
            var i = 0
            while (i < n) { var a = i; var z = i + sample - 1; while (a < z) { val t = buf[a]; buf[a] = buf[z]; buf[z] = t; a++; z-- }; i += sample }
        }
        ready = buf; readyPos = 0; readyEnd = n
        return true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val total = 44 + dataLen
        if (pos >= total) return C.RESULT_END_OF_INPUT
        if (pos < 44) {
            val n = minOf(length.toLong(), 44 - pos).toInt()
            System.arraycopy(header, pos.toInt(), buffer, offset, n); pos += n
            return n
        }
        if (!upstreamOpen) startUpstream()
        if (readyPos >= readyEnd && !fill()) return C.RESULT_END_OF_INPUT
        val n = minOf(length, readyEnd - readyPos, (total - pos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        System.arraycopy(ready, readyPos, buffer, offset, n); readyPos += n; pos += n
        return n
    }

    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
    override fun close() { if (upstreamOpen) { upstream.close(); upstreamOpen = false }; uri = null }
}

/** Picks the AIFF adapter for .aif / .aiff / .aifc files (the drive URI carries the original path) and plain content access otherwise. */
@UnstableApi
class DriveDataSource(private val ctx: Context) : DataSource {
    private var delegate: DataSource = ContentDataSource(ctx)
    private val listeners = ArrayList<TransferListener>()
    override fun addTransferListener(l: TransferListener) { listeners.add(l); delegate.addTransferListener(l) }
    override fun open(dataSpec: DataSpec): Long {
        val ext = dataSpec.uri.getQueryParameter("path")?.substringAfterLast('.', "")?.lowercase()
        delegate = if (ext == "aif" || ext == "aiff" || ext == "aifc") AiffWavDataSource(ContentDataSource(ctx)) else ContentDataSource(ctx)
        listeners.forEach { delegate.addTransferListener(it) }
        return delegate.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate.read(buffer, offset, length)
    override fun getUri(): Uri? = delegate.uri
    override fun getResponseHeaders() = delegate.responseHeaders
    override fun close() = delegate.close()
}

/** The one ExoPlayer configuration used by the video, audio and in-list players. */
@androidx.annotation.OptIn(UnstableApi::class)
fun newDrivePlayer(ctx: Context): ExoPlayer =
    ExoPlayer.Builder(ctx.applicationContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { DriveDataSource(ctx.applicationContext) }))
        .build().apply { setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true) }
