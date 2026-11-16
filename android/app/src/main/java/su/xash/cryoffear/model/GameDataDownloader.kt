package su.xash.cryoffear.model

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import su.xash.cryoffear.util.Prefs
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GofileFileDeletedException(message: String) : Exception(message)

class GameDataDownloader(private val ctx: Context) {

    enum class DownloadServer(val id: String, val label: String) {
        GOFILE("gofile", "GoFile (fast)"),
        ARCHIVE_ORG("archive", "Internet Archive (slow)")
    }

    data class ResolvedDownload(
        val url: String,
        val cookie: String? = null,
        val expectedSize: Long = EXPECTED_ZIP_SIZE
    )

    companion object {
        private const val TAG = "GameDataDownloader"
        const val GOFILE_FOLDER_ID = "47VUwgny"
        const val GOFILE_PAGE_URL = "https://gofile.io/d/$GOFILE_FOLDER_ID"
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
        val saved = prefs.getString(PREF_SERVER, DownloadServer.GOFILE.id)
        return DownloadServer.entries.firstOrNull { it.id == saved } ?: DownloadServer.GOFILE
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
                DownloadServer.GOFILE -> {
                    onStatus("Resolving Gofile.io link...")
                    resolveGofileDownload()
                }
                DownloadServer.ARCHIVE_ORG -> {
                    ResolvedDownload(url = ARCHIVE_ORG_URL, expectedSize = EXPECTED_ZIP_SIZE)
                }
            }

            // ensure enough space for the zip (plus extracted files if zip isn't already downloaded)
            val stat = StatFs(basedir.absolutePath)
            val neededForZip = (resolved.expectedSize - tmpZip.length()).coerceAtLeast(0L)
            val requiredBytes = neededForZip + EXPECTED_UNCOMPRESSED_SIZE + 64L * 1024L * 1024L
            if (stat.availableBytes < requiredBytes) {
                return@withContext Result.failure(
                    Exception("Not enough free space. Need ~${formatBytes(requiredBytes)}, available ${formatBytes(stat.availableBytes)}")
                )
            }

            onStatus("Downloading 5821301.zip...")
            downloadFileWithResume(resolved.url, resolved.cookie, tmpZip, resolved.expectedSize, onProgress, onStatus)

            onStatus("Verifying downloaded archive...")
            verifyZipArchive(tmpZip)

            onStatus("Extracting Cry of Fear...")
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

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveGofileDownload(): ResolvedDownload {
        return try {
            withTimeout(25_000L) {
                resolveGofileDownloadInner()
            }
        } catch (e: TimeoutCancellationException) {
            throw Exception("Timed out resolving Gofile.io link. Please try again or switch Server to Archive.org.", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveGofileDownloadInner(): ResolvedDownload =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var webView: WebView? = null
                var finished = false
                val mainHandler = Handler(Looper.getMainLooper())

                fun finish(result: Result<ResolvedDownload>) {
                    if (finished) return
                    finished = true
                    mainHandler.post {
                        try {
                            webView?.stopLoading()
                            webView?.destroy()
                        } catch (_: Throwable) {
                        }
                    }
                    result.onSuccess { cont.resume(it) }
                        .onFailure { cont.resumeWithException(it) }
                }

                try {
                    val wv = WebView(ctx.applicationContext)
                    webView = wv
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    val webViewUa = wv.settings.userAgentString
                    val intercepted = AtomicBoolean(false)

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val urlObj = request?.url ?: return null
                            val reqUrl = urlObj.toString()
                            if (reqUrl.contains("api.gofile.io/contents/$GOFILE_FOLDER_ID")) {
                                val headers = request.requestHeaders ?: emptyMap()
                                var auth = headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
                                if (auth.isNullOrBlank()) {
                                    val cookieStr = CookieManager.getInstance().getCookie("https://gofile.io") ?: ""
                                    val tokenMatch = Regex("accountToken=([^;\\s]+)").find(cookieStr)
                                    if (tokenMatch != null) {
                                        auth = "Bearer ${tokenMatch.groupValues[1]}"
                                    }
                                }
                                val wt = headers.entries.firstOrNull { it.key.equals("X-Website-Token", ignoreCase = true) }?.value
                                    ?: urlObj.getQueryParameter("wt")
                                val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                                    ?: webViewUa
                                if (!auth.isNullOrBlank() && !wt.isNullOrBlank()) {
                                    if (intercepted.compareAndSet(false, true)) {
                                        try {
                                            val resolved = fetchGofileApi(reqUrl, auth, wt, ua)
                                            finish(Result.success(resolved))
                                        } catch (e: Exception) {
                                            finish(Result.failure(e))
                                        }
                                    }
                                    return WebResourceResponse(
                                        "application/json",
                                        "utf-8",
                                        ByteArrayInputStream("{\"status\":\"ok\"}".toByteArray())
                                    )
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            fun pollDom(attempt: Int) {
                                if (finished || attempt > 8) return
                                mainHandler.postDelayed({
                                    if (finished) return@postDelayed
                                    view?.evaluateJavascript(
                                        """
                                        (function(){
                                            var a = document.querySelector('a[href*="5821301.zip"], a[href*="gofile.io/download"]');
                                            var href = a ? a.href : '';
                                            var txt = document.body ? document.body.innerText : '';
                                            return JSON.stringify({href: href, txt: txt});
                                        })()
                                        """.trimIndent()
                                    ) { raw ->
                                        if (finished) return@evaluateJavascript
                                        try {
                                            val unquoted = JSONObject("{\"v\":$raw}").optString("v")
                                            val obj = JSONObject(unquoted)
                                            val href = obj.optString("href")
                                            val lower = obj.optString("txt").lowercase()
                                            if (href.startsWith("http")) {
                                                val cookieStr = CookieManager.getInstance().getCookie("https://gofile.io")
                                                finish(Result.success(ResolvedDownload(href, cookieStr, EXPECTED_ZIP_SIZE)))
                                                return@evaluateJavascript
                                            }
                                            if (lower.contains("does not exist") ||
                                                lower.contains("not found") ||
                                                lower.contains("has been deleted") ||
                                                lower.contains("removed")
                                            ) {
                                                finish(Result.failure(GofileFileDeletedException("The file on Gofile.io was deleted or does not exist.")))
                                                return@evaluateJavascript
                                            }
                                        } catch (_: Exception) {
                                        }
                                        pollDom(attempt + 1)
                                    }
                                }, 1500L)
                            }
                            pollDom(0)
                        }
                    }

                    cont.invokeOnCancellation {
                        mainHandler.post {
                            try {
                                wv.stopLoading()
                                wv.destroy()
                            } catch (_: Throwable) {
                            }
                        }
                    }

                    wv.loadUrl(GOFILE_PAGE_URL)
                } catch (e: Exception) {
                    finish(Result.failure(e))
                }
            }
        }

    private fun fetchGofileApi(
        apiUrl: String,
        authHeader: String,
        websiteToken: String,
        userAgent: String
    ): ResolvedDownload {
        for (attempt in 0..1) {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("X-Website-Token", websiteToken)
                setRequestProperty("User-Agent", userAgent)
            }
            try {
                val code = conn.responseCode
                if (code == 429) {
                    if (attempt == 0) {
                        val retrySec = conn.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1L, 5L) ?: 2L
                        Thread.sleep(retrySec * 1000L)
                        continue
                    }
                    throw Exception("Gofile.io rate limited (HTTP 429). Please try again shortly or switch Server to Archive.org.")
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 404 || code == 410) {
                    throw GofileFileDeletedException("The file on Gofile.io was deleted (HTTP $code).")
                }
                if (code !in 200..299) {
                    throw Exception("Gofile API error (HTTP $code)")
                }
                val json = JSONObject(body)
                val status = json.optString("status")
                if (status == "error-notFound" || status == "error-notPublic") {
                    throw GofileFileDeletedException("The file on Gofile.io was deleted or is no longer public.")
                }
                if (status != "ok") {
                    throw Exception("Gofile returned status: $status")
                }
                val children = json.getJSONObject("data").optJSONObject("children")
                    ?: throw GofileFileDeletedException("No files found in Gofile folder.")
                val keys = children.keys()
                var bestUrl: String? = null
                var bestSize = EXPECTED_ZIP_SIZE
                while (keys.hasNext()) {
                    val child = children.optJSONObject(keys.next()) ?: continue
                    val name = child.optString("name")
                    val link = child.optString("link")
                    if (link.isNotBlank() && (name.equals("5821301.zip", ignoreCase = true) || bestUrl == null)) {
                        bestUrl = link
                        val s = child.optLong("size", 0L)
                        if (s > 0L) bestSize = s
                    }
                }
                if (bestUrl.isNullOrBlank()) {
                    throw GofileFileDeletedException("5821301.zip was not found in Gofile folder.")
                }
                val token = authHeader.removePrefix("Bearer ").trim()
                return ResolvedDownload(
                    url = bestUrl,
                    cookie = if (token.isNotEmpty()) "accountToken=$token" else null,
                    expectedSize = bestSize
                )
            } finally {
                conn.disconnect()
            }
        }
        throw Exception("Gofile API error (HTTP 429)")
    }

    private suspend fun downloadFileWithResume(
        startUrl: String,
        cookie: String?,
        outFile: File,
        expectedSize: Long,
        onProgress: suspend (Long, Long) -> Unit,
        onStatus: (String) -> Unit
    ) {
        var existingBytes = if (outFile.isFile) outFile.length() else 0L
        if (existingBytes > expectedSize) {
            outFile.delete()
            existingBytes = 0L
        }
        if (existingBytes == expectedSize) {
            onProgress(expectedSize, expectedSize)
            return
        }

        var currentUrl = startUrl
        var conn: HttpURLConnection? = null
        for (redirect in 0 until 8) {
            val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
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
            if (code == 404 || code == 410) {
                if (startUrl.contains("gofile.io")) {
                    throw GofileFileDeletedException("The file on Gofile.io was deleted (HTTP $code).")
                }
                throw Exception("Download URL returned HTTP $code")
            }
            if (code != 200 && code != 206) {
                throw Exception("Server returned HTTP $code")
            }

            val append = code == 206 && existingBytes > 0L
            if (!append) existingBytes = 0L
            val contentLength = activeConn.contentLengthLong
            val totalBytes = when {
                append && contentLength > 0L -> existingBytes + contentLength
                contentLength > 0L -> contentLength
                else -> expectedSize
            }

            var downloaded = existingBytes
            var lastUiUpdate = 0L
            onProgress(downloaded, totalBytes)
            onStatus("Downloading: ${formatBytes(downloaded)} / ${formatBytes(totalBytes)}")

            activeConn.inputStream.use { input ->
                RandomAccessFile(outFile, "rw").use { raf ->
                    raf.seek(if (append) existingBytes else 0L)
                    if (!append) raf.setLength(0L)
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate >= 250L || downloaded >= totalBytes) {
                            lastUiUpdate = now
                            onProgress(downloaded, totalBytes)
                            onStatus("Downloading: ${formatBytes(downloaded)} / ${formatBytes(totalBytes)}")
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
                                onStatus("Extracting: ${formatBytes(extractedBytes)} / ${formatBytes(totalBytesToExtract)}")
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
