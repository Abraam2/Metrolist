package com.metrolist.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

@OptIn(UnstableApi::class)
class RangeChunkingDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long,
) : DataSource {

    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkSize: Long = 10L * 1024 * 1024,
    ) : DataSource.Factory {
        override fun createDataSource() = RangeChunkingDataSource(upstream.createDataSource(), chunkSize)
    }

    private var spec: DataSpec? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var chunkRequested = 0L
    private var chunkRead = 0L
    private var chunkOpen = false
    private var eof = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        remaining = dataSpec.length
        eof = false
        openChunk()
        if (remaining == C.LENGTH_UNSET.toLong()) {
            upstream.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()
                ?.substringAfterLast('/')?.toLongOrNull()
                ?.let { remaining = it - position }
        }
        return remaining
    }

    private fun openChunk() {
        val len = if (remaining == C.LENGTH_UNSET.toLong()) chunkSize else minOf(chunkSize, remaining)
        chunkRequested = len
        chunkRead = 0
        upstream.open(spec!!.buildUpon().setPosition(position).setLength(len).build())
        chunkOpen = true
    }

    private fun closeChunk() {
        if (chunkOpen) { upstream.close(); chunkOpen = false }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (eof || remaining == 0L) return C.RESULT_END_OF_INPUT
            if (!chunkOpen) {
                try { openChunk() } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    if (e.responseCode == 416) { eof = true; return C.RESULT_END_OF_INPUT }
                    throw e
                }
            }
            val toRead = if (remaining == C.LENGTH_UNSET.toLong()) length
                         else minOf(length.toLong(), remaining).toInt()
            val n = upstream.read(buffer, offset, toRead)
            if (n == C.RESULT_END_OF_INPUT) {
                val shortChunk = chunkRead < chunkRequested
                closeChunk()
                if (shortChunk) { eof = true; return C.RESULT_END_OF_INPUT }
                continue
            }
            chunkRead += n
            position += n
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
            return n
        }
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() { closeChunk(); spec = null }
}
