package su.xash.cryoffear.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import su.xash.cryoffear.MainActivity
import java.io.File

class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private val downloader by lazy { GameDataDownloader(this) }
    private var lastProgress = 0
    private var downloadTargetDir: File? = null
    private var completedGamedir: String? = null
    private var displayName: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ensureForeground()
    }

    private fun ensureForeground() {
        val notification = buildNotification(0, "Starting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureForeground()
                val targetPath = intent.getStringExtra(EXTRA_TARGET) ?: return START_NOT_STICKY
                val serverId = intent.getStringExtra(EXTRA_SERVER)
                val server = GameDataDownloader.DownloadServer.entries.firstOrNull { it.id == serverId }
                    ?: GameDataDownloader.DownloadServer.MEGA
                startDownload(File(targetPath), server)
            }
            ACTION_CANCEL -> cancelDownload()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startDownload(basedir: File, server: GameDataDownloader.DownloadServer) {
        if (downloadJob?.isActive == true) return
        completedGamedir = "cryoffear"
        displayName = "Cry of Fear"
        downloadTargetDir = basedir
        _state.value = DownloadState.Downloading(0, 0, "Starting...")
        downloadJob = scope.launch {
            try {
                val result = downloader.downloadGame(
                    server = server,
                    basedir = basedir,
                    onProgress = { current, total ->
                        if (downloadJob?.isActive == true && _state.value is DownloadState.Downloading) {
                            val pct = if (total > 0) ((current * 100) / total).toInt() else 0
                            lastProgress = pct
                            _state.update { state ->
                                if (state is DownloadState.Downloading) {
                                    DownloadState.Downloading(current, total, state.status)
                                } else {
                                    state
                                }
                            }
                        }
                    },
                    onStatus = { status ->
                        if (downloadJob?.isActive == true && _state.value is DownloadState.Downloading) {
                            _state.update { state ->
                                if (state is DownloadState.Downloading) state.copy(status = status) else state
                            }
                            updateNotification(lastProgress, status)
                        }
                    }
                )

                if (result.isSuccess) {
                    val purged = purgeForeignBinaries(File(basedir, "cryoffear"))
                    android.util.Log.i(TAG, "Purged $purged foreign binaries from cryoffear")
                    lastCompletedGamedir = "cryoffear"
                    _state.value = DownloadState.Success
                    showCompletionNotification(true, null)
                } else {
                    val ex = result.exceptionOrNull()
                    val err = ex?.message ?: "Unknown error"
                    val unavailable = ex as? ServerUnavailableException
                    _state.value = DownloadState.Error(err, unavailable?.failedServer, unavailable?.isBandwidthLimit == true)
                    showCompletionNotification(false, err)
                }
            } catch (e: CancellationException) {
                _state.value = DownloadState.Cancelled
            } catch (e: Exception) {
                val unavailable = e as? ServerUnavailableException
                _state.value = DownloadState.Error(
                    e.message ?: "Unknown error",
                    unavailable?.failedServer,
                    unavailable?.isBandwidthLimit == true
                )
                showCompletionNotification(false, e.message)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = DownloadState.Cancelled
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm().cancel(NOTIFICATION_ID)
        stopSelf()
    }

    /**
     * Deletes foreign binaries shipped inside Steam depots (Windows/Linux
     * game DLLs, static libs, installers, scripts). They are useless on
     * Android and must never be loadable from the game dir. Returns the
     * removed file count.
     */
    private fun purgeForeignBinaries(dir: File): Int {
        var removed = 0
        dir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name.lowercase()
            if (name.endsWith(".dll") || name.endsWith(".so") ||
                name.endsWith(".a") ||
                name.endsWith(".exe") || name.endsWith(".bat")) {
                try {
                    if (file.delete()) removed++
                } catch (_: SecurityException) { }
            }
        }
        return removed
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Game Data Download",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val channelDone = NotificationChannel(
                CHANNEL_ID_COMPLETION, "Download Complete",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setShowBadge(true) }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
            nm.createNotificationChannel(channelDone)
        }
    }

    private fun buildNotification(progress: Int, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        val openPend = PendingIntent.getActivity(this, 0, openIntent, flags)

        val cancelIntent = Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL }
        val cancelPend = PendingIntent.getService(this, 1, cancelIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading Game Data")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openPend)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPend)
            .setOngoing(true)
            .apply {
                if (progress > 0) {
                    setProgress(100, progress, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun updateNotification(progress: Int, text: String) {
        nm().notify(NOTIFICATION_ID, buildNotification(progress, text))
    }

    private fun showCompletionNotification(success: Boolean, error: String?) {
        val name = displayName ?: "Game"
        val title = if (success) "$name Installed!" else "Download Failed"
        val text = if (success) "Game data downloaded successfully" else error ?: "Unknown error"
        val icon = if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val openPend = PendingIntent.getActivity(this, 0, openIntent, flags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COMPLETION)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(openPend)
            .setAutoCancel(true)
            .build()

        nm().notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ID = "game_data_download"
        const val CHANNEL_ID_COMPLETION = "game_data_download_complete"
        const val NOTIFICATION_ID = 1001
        const val COMPLETION_NOTIFICATION_ID = 1002
        const val ACTION_START = "su.xash.cryoffear.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "su.xash.cryoffear.action.CANCEL_DOWNLOAD"
        const val ACTION_STOP = "su.xash.cryoffear.action.STOP_DOWNLOAD"
        const val EXTRA_TARGET = "target"
        const val EXTRA_SERVER = "server"

        private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val state: StateFlow<DownloadState> = _state.asStateFlow()

        var lastCompletedGamedir: String? = null
            private set

        fun consumeCompletion(): String? {
            val v = lastCompletedGamedir
            lastCompletedGamedir = null
            return v
        }

        fun resetState() {
            val current = _state.value
            if (current is DownloadState.Downloading) return
            if (!_state.compareAndSet(current, DownloadState.Idle)) return
            lastCompletedGamedir = null
        }

        fun start(context: Context, targetDir: File, server: GameDataDownloader.DownloadServer) {
            val current = _state.value
            if (current is DownloadState.Downloading) return
            val starting = DownloadState.Downloading(0, 0, "Starting...")
            if (!_state.compareAndSet(current, starting)) return
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TARGET, targetDir.absolutePath)
                putExtra(EXTRA_SERVER, server.id)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                _state.compareAndSet(starting, DownloadState.Error(e.message ?: "Could not start download"))
                throw e
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_CANCEL }
            context.startService(intent)
        }
    }
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val current: Long, val total: Long, val status: String) : DownloadState()
    object Success : DownloadState()
    data class Error(
        val message: String,
        val unavailableServer: GameDataDownloader.DownloadServer? = null,
        val isBandwidthLimit: Boolean = false
    ) : DownloadState()
    object Cancelled : DownloadState()
}
