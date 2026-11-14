package app.zaid.flow.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Reads a sequential .part file and waits at its current end while the downloader appends bytes. */
class GrowingFileDataSource : BaseDataSource(false) {
    private var file: File? = null
    private var input: RandomAccessFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val path = dataSpec.uri.path ?: throw IOException("Missing partial file path")
        file = File(path)
        if (!file!!.exists()) throw IOException("Partial download is no longer available")
        val sourceFile = file!!
        input = RandomAccessFile(sourceFile, "r")
        position = dataSpec.position
        input!!.seek(position)
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else C.LENGTH_UNSET.toLong()
        opened = true
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val source = file ?: return C.RESULT_END_OF_INPUT
        val raf = input ?: return C.RESULT_END_OF_INPUT
        while (true) {
            val available = source.length() - position
            if (available > 0L) {
                val count = minOf(length.toLong(), available, if (bytesRemaining == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else bytesRemaining).toInt()
                raf.seek(position)
                val read = raf.read(buffer, offset, count)
                if (read > 0) {
                    position += read
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
                    bytesTransferred(read)
                    return read
                }
            }
            when (PartialDownloadState.status(source)) {
                PartialDownloadState.Status.COMPLETE -> return C.RESULT_END_OF_INPUT
                PartialDownloadState.Status.FAILED,
                PartialDownloadState.Status.PAUSED -> throw IOException("Partial download is paused or unavailable")
                PartialDownloadState.Status.RUNNING -> PartialDownloadState.waitForChange(source, 250L)
            }
            if (Thread.currentThread().isInterrupted) throw IOException("Partial playback interrupted")
        }
    }

    override fun getUri(): Uri? = uri
    override fun close() {
        try { input?.close() } finally {
            input = null
            file = null
            uri = null
            if (opened) { opened = false; transferEnded() }
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = GrowingFileDataSource()
    }
}
