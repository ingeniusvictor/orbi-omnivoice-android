package com.orbiecosystem.omnivoice

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class ModelDownloader(
    private val rootDir: File,
    private val onStatus: (String) -> Unit,
    private val onProgress: (Int) -> Unit
) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun downloadAll() {
        cancelled.set(false)
        rootDir.mkdirs()

        val totalFiles = ModelCatalog.files.size
        ModelCatalog.files.forEachIndexed { index, spec ->
            if (cancelled.get()) error("Descarga cancelada")
            val target = File(rootDir, spec.relativePath)
            if (target.isFile && target.length() >= spec.minBytes) {
                onStatus("✓ ${spec.relativePath}")
                onProgress(((index + 1) * 100) / totalFiles)
                return@forEachIndexed
            }
            target.parentFile?.mkdirs()
            downloadOne(spec, target, index, totalFiles)
        }
        onProgress(100)
        onStatus("Modelos listos: ${rootDir.absolutePath}")
    }

    private fun downloadOne(spec: ModelFile, target: File, index: Int, totalFiles: Int) {
        val part = File(target.absolutePath + ".part")
        var existing = if (part.exists()) part.length() else 0L
        var conn = open(spec.url, existing)

        if (existing > 0 && conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            part.delete()
            existing = 0L
            conn.disconnect()
            conn = open(spec.url, 0L)
        }

        val response = conn.responseCode
        if (response !in 200..299) {
            error("HTTP $response al descargar ${spec.relativePath}")
        }

        val contentLen = conn.getHeaderFieldLong("Content-Length", -1L)
        val expectedTotal = if (contentLen > 0) existing + contentLen else -1L
        onStatus("Descargando ${spec.relativePath} (${index + 1}/$totalFiles)")

        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(existing)
            conn.inputStream.buffered(1024 * 1024).use { input ->
                val buffer = ByteArray(1024 * 1024)
                var downloaded = existing
                var lastPct = -1
                while (true) {
                    if (cancelled.get()) error("Descarga cancelada")
                    val n = input.read(buffer)
                    if (n < 0) break
                    raf.write(buffer, 0, n)
                    downloaded += n
                    if (expectedTotal > 0) {
                        val filePct = ((downloaded * 100) / expectedTotal).toInt().coerceIn(0, 100)
                        if (filePct != lastPct) {
                            lastPct = filePct
                            val global = ((index * 100) + filePct) / totalFiles
                            onProgress(global.coerceIn(0, 99))
                            onStatus("${spec.relativePath}: $filePct%")
                        }
                    }
                }
            }
        }
        conn.disconnect()

        if (part.length() < spec.minBytes) {
            error("${spec.relativePath}: archivo demasiado pequeño (${part.length()} bytes)")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) error("No se pudo finalizar ${target.name}")
    }

    private fun open(url: String, offset: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "ORBI-OmniVoice-EdgeLab/0.1")
        if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
        c.connect()
        return c
    }
}
