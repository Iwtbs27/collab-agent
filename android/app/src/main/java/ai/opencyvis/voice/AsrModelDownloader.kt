package ai.opencyvis.voice

import android.os.StatFs
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object AsrModelDownloader {

    private const val TAG = "AsrModelDownloader"
    private const val MIN_DISK_BYTES = 80L * 1024 * 1024 // 80 MB headroom
    private val downloadLock = Any()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun interface ProgressListener {
        fun onProgress(bytesRead: Long, totalBytes: Long)
    }

    fun downloadIfNeeded(
        url: String,
        targetDir: File,
        listener: ProgressListener? = null
    ) {
        synchronized(downloadLock) {
            if (SherpaOnnxModelFiles.allFilesPresent(targetDir.parentFile!!.parentFile!!)) return

            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IOException("Cannot create directory: $targetDir")
            }

            checkDiskSpace(targetDir)

            val tmpFile = File(targetDir, "download.tmp")
            try {
                download(url, tmpFile, listener)
                extract(tmpFile, targetDir)
            } finally {
                tmpFile.delete()
            }

            Log.i(TAG, "ASR model download complete: $targetDir")
        }
    }

    private fun checkDiskSpace(dir: File) {
        val stat = StatFs(dir.absolutePath)
        if (stat.availableBytes < MIN_DISK_BYTES) {
            throw IOException("Not enough disk space for speech model (need ~80 MB)")
        }
    }

    private fun download(url: String, dest: File, listener: ProgressListener?) {
        Log.i(TAG, "Downloading ASR bundle from $url")
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Download failed: HTTP ${response.code}")
        }
        val body = response.body ?: throw IOException("Empty response body")
        val totalBytes = body.contentLength()

        body.source().use { source ->
            dest.sink().buffer().use { sink ->
                var bytesRead = 0L
                while (true) {
                    val read = source.read(sink.buffer, 8192)
                    if (read == -1L) break
                    bytesRead += read
                    sink.emitCompleteSegments()
                    listener?.onProgress(bytesRead, totalBytes)
                }
            }
        }
        Log.i(TAG, "Download finished: ${dest.length()} bytes")
    }

    private fun extract(zipFile: File, targetDir: File) {
        Log.i(TAG, "Extracting ASR bundle to $targetDir")
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = File(entry.name).name // strip any path prefix
                    val outFile = File(targetDir, name)
                    outFile.outputStream().use { out ->
                        zis.copyTo(out)
                    }
                    if (name.endsWith(".so")) {
                        outFile.setReadOnly()
                    }
                    Log.d(TAG, "Extracted: $name (${outFile.length()} bytes)")
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
