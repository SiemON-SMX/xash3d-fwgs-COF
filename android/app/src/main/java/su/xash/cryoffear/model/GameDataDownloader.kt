package su.xash.cryoffear.model

import android.content.Context
import android.os.StatFs
import android.util.Base64
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import su.xash.cryoffear.R
import su.xash.cryoffear.util.Prefs
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ServerUnavailableException(
    val failedServer: GameDataDownloader.DownloadServer,
    message: String
) : Exception(message)

class GameDataDownloader(private val ctx: Context) {

    enum class DownloadServer(val id: String, @StringRes val labelRes: Int) {
        MEGA("mega", R.string.server_mega),
        ARCHIVE_ORG("archive", R.string.server_archive);

        fun alternate(): DownloadServer = when (this) {
            MEGA -> ARCHIVE_ORG
            ARCHIVE_ORG -> MEGA
        }
    }

    private data class MegaKey(val aesKey: ByteArray, val nonce: ByteArray)

    private data class ResolvedDownload(
        val url: String,
        val expectedSize: Long = EXPECTED_ZIP_SIZE,
        val megaKey: MegaKey? = null
    )

    companion object {
        private const val TAG = "GameDataDownloader"
        private const val MEGA_FILE_ID = "GywFHawD"
        private const val MEGA_FILE_KEY = "1aKJRurbIxsYV11r1TDcZ04VKkpUB-wTD2Uuv_lOUgU"
        private const val MEGA_API_URL = "https://g.api.mega.co.nz/cs?id=1"
        const val ARCHIVE_ORG_URL = "https://archive.org/download/223710/5821301.zip"
        const val EXPECTED_ZIP_SIZE = 2774149259L
        const val EXPECTED_UNCOMPRESSED_SIZE = 4695601334L
        private const val BUFFER_SIZE = 64 * 1024
        private const val PREF_FILE = "game_data_downloader"
        private const val PREF_SERVER = "download_server"
        private const val PREF_FIRST_RUN = "first_run_checked"

        fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
                bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
                bytes >= 1_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
                else -> "$bytes B"
            }
        }
    }

    private val prefs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun hasCheckedFirstRun(): Boolean = prefs.getBoolean(PREF_FIRST_RUN, false)

    fun setFirstRunChecked() {
        prefs.edit().putBoolean(PREF_FIRST_RUN, true).apply()
    }

    fun getSelectedServer(): DownloadServer {
        val saved = prefs.getString(PREF_SERVER, DownloadServer.MEGA.id)
        return DownloadServer.entries.firstOrNull { it.id == saved } ?: DownloadServer.MEGA
    }

    fun setSelectedServer(server: DownloadServer) {
        prefs.edit().putString(PREF_SERVER, server.id).apply()
    }

    suspend fun downloadGame(
        server: DownloadServer,
        basedir: File,
        onProgress: suspend (Long, Long) -> Unit,
        onStatus: (String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            basedir.mkdirs()
            val tmpZip = File(basedir, "5821301.zip.tmp")

            val resolved = when (server) {
                DownloadServer.MEGA -> {
                    onStatus(ctx.getString(R.string.download_status_resolving_mega))
                    resolveMegaDownload()
                }
                DownloadServer.ARCHIVE_ORG -> {
                    ResolvedDownload(url = ARCHIVE_ORG_URL, expectedSize = EXPECTED_ZIP_SIZE)
                }
            }

            val stat = StatFs(basedir.absolutePath)
            val neededForZip = (resolved.expectedSize - tmpZip.length()).coerceAtLeast(0L)
            val requiredBytes = neededForZip + EXPECTED_UNCOMPRESSED_SIZE + 64L * 1024L * 1024L
            if (stat.availableBytes < requiredBytes) {
                return@withContext Result.failure(
                    Exception("Not enough free space. Need ~${formatBytes(requiredBytes)}, available ${formatBytes(stat.availableBytes)}")
                )
            }

            onStatus(ctx.getString(R.string.download_status_downloading, formatBytes(tmpZip.length()), formatBytes(resolved.expectedSize)))
            downloadFileWithResume(server, resolved, tmpZip, onProgress, onStatus)

            onStatus(ctx.getString(R.string.download_status_verifying))
            verifyZipArchive(tmpZip)

            extractCryOfFearZip(tmpZip, basedir, onProgress, onStatus)

            tmpZip.delete()
            if (Prefs.findGameDir(ctx) == null) {
                return@withContext Result.failure(Exception("Extraction completed, but cryoffear/liblist.gam was not found"))
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        }
    }

    private fun decodeMegaKey(keyStr: String): MegaKey {
        val raw = Base64.decode(keyStr, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        if (raw.size < 32) throw IllegalArgumentException("Invalid Mega key length: ${raw.size}")
        val aesKey = ByteArray(16)
        for (i in 0 until 16) {
            aesKey[i] = (raw[i].toInt() xor raw[i + 16].toInt()).toByte()
        }
        val nonce = raw.copyOfRange(16, 24)
        return MegaKey(aesKey, nonce)
    }

    private fun resolveMegaDownload(): ResolvedDownload {
        val payload = JSONArray().apply {
            put(JSONObject().apply {
                put("a", "g")
                put("g", 1)
                put("ssl", 2)
                put("p", MEGA_FILE_ID)
            })
        }.toString()

        val conn = (URL(MEGA_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw ServerUnavailableException(DownloadServer.MEGA, "Mega.nz API error (HTTP $code)")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            val first = arr.opt(0)
            if (first is Number) {
                val errCode = first.toInt()
                throw ServerUnavailableException(
                    DownloadServer.MEGA,
                    "Mega.nz returned error $errCode"
                )
            }
            val obj = first as? JSONObject
                ?: throw ServerUnavailableException(DownloadServer.MEGA, "Unexpected Mega.nz response")
            if (obj.has("e")) {
                val errCode = obj.optInt("e")
                throw ServerUnavailableException(
                    DownloadServer.MEGA,
                    "Mega.nz returned error $errCode"
                )
            }
            val rawUrl = obj.optString("g")
            val dlUrl = if (rawUrl.startsWith("http://")) {
                "https://" + rawUrl.removePrefix("http://")
            } else {
                rawUrl
            }
            val size = obj.optLong("s", EXPECTED_ZIP_SIZE)
            if (dlUrl.isBlank()) {
                throw ServerUnavailableException(DownloadServer.MEGA, "Mega.nz did not return a download URL")
            }
            return ResolvedDownload(
                url = dlUrl,
                expectedSize = if (size > 0L) size else EXPECTED_ZIP_SIZE,
                megaKey = decodeMegaKey(MEGA_FILE_KEY)
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun createMegaCtrCipher(key: MegaKey, byteOffset: Long): Cipher {
        val blockCounter = byteOffset / 16L
        val iv = ByteArray(16)
        System.arraycopy(key.nonce, 0, iv, 0, 8)
        ByteBuffer.wrap(iv, 8, 8).putLong(blockCounter)
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key.aesKey, "AES"), IvParameterSpec(iv))
        }
    }

    private suspend fun downloadFileWithResume(
        server: DownloadServer,
        resolved: ResolvedDownload,
        outFile: File,
        onProgress: suspend (Long, Long) -> Unit,
        onStatus: (String) -> Unit
    ) {
        val expectedSize = resolved.expectedSize
        var existingBytes = if (outFile.isFile) outFile.length() else 0L
        if (resolved.megaKey != null && existingBytes > 0L) {
            // Align to 16-byte AES-CTR block boundary
            existingBytes = (existingBytes / 16L) * 16L
        }
        if (existingBytes > expectedSize) {
            outFile.delete()
            existingBytes = 0L
        }
        if (existingBytes == expectedSize) {
            onProgress(expectedSize, expectedSize)
            return
        }

        var currentUrl = resolved.url
        var conn: HttpURLConnection? = null
        for (redirect in 0 until 8) {
            val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw Exception("Redirect with no Location header")
                currentUrl = URL(URL(currentUrl), loc).toString()
                continue
            }
            conn = c
            break
        }
        val activeConn = conn ?: throw Exception("Too many HTTP redirects")

        try {
            val code = activeConn.responseCode
            if (code == 403 || code == 404 || code == 410 || code == 451 || code == 509) {
                throw ServerUnavailableException(server, "${ctx.getString(server.labelRes)} returned HTTP $code")
            }
            if (code != 200 && code != 206) {
                throw Exception("${ctx.getString(server.labelRes)} returned HTTP $code")
            }

            val append = code == 206 && existingBytes > 0L
            if (!append) existingBytes = 0L
            val contentLength = activeConn.contentLengthLong
            val totalBytes = when {
                append && contentLength > 0L -> existingBytes + contentLength
                contentLength > 0L -> contentLength
                else -> expectedSize
            }

            val cipher = resolved.megaKey?.let { createMegaCtrCipher(it, existingBytes) }

            var downloaded = existingBytes
            var lastUiUpdate = 0L
            onProgress(downloaded, totalBytes)
            onStatus(ctx.getString(R.string.download_status_downloading, formatBytes(downloaded), formatBytes(totalBytes)))

            activeConn.inputStream.use { input ->
                RandomAccessFile(outFile, "rw").use { raf ->
                    raf.seek(if (append) existingBytes else 0L)
                    if (!append) raf.setLength(0L)
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (cipher != null) {
                            val decrypted = cipher.update(buffer, 0, read)
                            if (decrypted != null && decrypted.isNotEmpty()) {
                                raf.write(decrypted)
                            }
                        } else {
                            raf.write(buffer, 0, read)
                        }
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate >= 250L || downloaded >= totalBytes) {
                            lastUiUpdate = now
                            onProgress(downloaded, totalBytes)
                            onStatus(ctx.getString(R.string.download_status_downloading, formatBytes(downloaded), formatBytes(totalBytes)))
                        }
                    }
                    if (cipher != null) {
                        val tail = cipher.doFinal()
                        if (tail != null && tail.isNotEmpty()) {
                            raf.write(tail)
                        }
                    }
                }
            }
        } finally {
            activeConn.disconnect()
        }
    }

    private fun verifyZipArchive(zipFile: File) {
        if (!zipFile.isFile) {
            throw Exception("Downloaded archive is missing")
        }
        if (zipFile.length() != EXPECTED_ZIP_SIZE) {
            throw Exception(
                "Archive size mismatch: got ${zipFile.length()} bytes, expected $EXPECTED_ZIP_SIZE bytes"
            )
        }
        ZipFile(zipFile).use { zip ->
            var totalUncompressed = 0L
            var hasLiblist = false
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.size > 0L) totalUncompressed += entry.size
                if (entry.name.equals("cryoffear/liblist.gam", ignoreCase = true)) {
                    hasLiblist = true
                }
            }
            if (totalUncompressed != EXPECTED_UNCOMPRESSED_SIZE) {
                throw Exception(
                    "Archive uncompressed size mismatch: got $totalUncompressed bytes, expected $EXPECTED_UNCOMPRESSED_SIZE bytes"
                )
            }
            if (!hasLiblist) {
                throw Exception("Archive is missing cryoffear/liblist.gam")
            }
        }
    }

    private suspend fun extractCryOfFearZip(
        zipFile: File,
        basedir: File,
        onProgress: suspend (Long, Long) -> Unit,
        onStatus: (String) -> Unit
    ) {
        ZipFile(zipFile).use { zip ->
            val entriesToExtract = mutableListOf<java.util.zip.ZipEntry>()
            var totalBytesToExtract = 0L
            val allEntries = zip.entries()
            while (allEntries.hasMoreElements()) {
                val entry = allEntries.nextElement()
                val normalized = entry.name.replace('\\', '/')
                if (!normalized.lowercase(Locale.ROOT).startsWith("cryoffear/")) continue
                if (!entry.isDirectory && isForeignBinary(normalized)) continue
                entriesToExtract.add(entry)
                if (!entry.isDirectory && entry.size > 0L) {
                    totalBytesToExtract += entry.size
                }
            }

            if (entriesToExtract.isEmpty()) {
                throw Exception("No cryoffear/ files found in archive")
            }

            val canonicalBase = basedir.canonicalFile
            var extractedBytes = 0L
            var lastUiUpdate = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            val crc = CRC32()

            for (entry in entriesToExtract) {
                val normalized = entry.name.replace('\\', '/')
                val relPath = "cryoffear/" + normalized.substring("cryoffear/".length)
                val outFile = File(canonicalBase, relPath).canonicalFile
                if (!outFile.path.startsWith(canonicalBase.path + File.separator)) {
                    throw SecurityException("Blocked unsafe zip path: $relPath")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                    continue
                }

                outFile.parentFile?.mkdirs()
                crc.reset()
                var entryWritten = 0L
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(outFile).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            crc.update(buffer, 0, read)
                            entryWritten += read
                            extractedBytes += read
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 250L) {
                                lastUiUpdate = now
                                onProgress(extractedBytes, totalBytesToExtract)
                                onStatus(ctx.getString(R.string.download_status_extracting, formatBytes(extractedBytes), formatBytes(totalBytesToExtract)))
                            }
                        }
                    }
                }

                if (entry.size >= 0L && entryWritten != entry.size) {
                    outFile.delete()
                    throw Exception("Corrupt entry ${entry.name}: size mismatch")
                }
                if (entry.crc != -1L && crc.value != entry.crc) {
                    outFile.delete()
                    throw Exception("Corrupt entry ${entry.name}: CRC mismatch")
                }
            }
            onProgress(totalBytesToExtract, totalBytesToExtract)
        }
    }

    private fun isForeignBinary(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.endsWith(".exe") ||
            lower.endsWith(".dll") ||
            lower.endsWith(".so") ||
            lower.endsWith(".a") ||
            lower.endsWith(".bat") ||
            lower.endsWith(".dylib") ||
            lower.endsWith(".asi")
    }
}
