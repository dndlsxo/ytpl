package com.ytpl.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 재생목록 다운로드를 포그라운드 서비스로 실행.
 * 화면을 끄거나 다른 앱을 써도 끝까지 받도록 알림을 띄워 둡니다.
 */
class DownloadService : Service() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_QUALITY = "quality"
        private const val ACTION_CANCEL = "com.ytpl.app.CANCEL"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1
        private const val DONE_NOTIF_ID = 2

        private val pending = AtomicInteger(0)

        @Volatile
        private var currentProcessId: String? = null

        @Volatile
        private var cancelRequested = false

        fun start(ctx: Context, url: String, quality: Quality) {
            val intent = Intent(ctx, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_QUALITY, quality.name)
            ContextCompat.startForegroundService(ctx, intent)
        }

        /** 진행 중인 작업과 대기열 전체 중지 */
        fun cancelAll() {
            cancelRequested = true
            currentProcessId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "다운로드", NotificationManager.IMPORTANCE_LOW)
        )
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytpl:download")
            .apply {
                setReferenceCounted(false)
                acquire(6L * 60 * 60 * 1000)
            }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelAll()
            if (pending.get() == 0) finish()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            if (pending.get() == 0) finish()
            return START_NOT_STICKY
        }
        val quality = runCatching { Quality.valueOf(intent!!.getStringExtra(EXTRA_QUALITY)!!) }
            .getOrDefault(Quality.ORIGINAL)

        // startForegroundService 후에는 곧바로 startForeground를 호출해야 함
        goForeground(buildProgressNotification(DownloadState.state.value))

        cancelRequested = false
        val count = pending.incrementAndGet()
        DownloadState.update { it.copy(pending = count) }

        Engine.executor.execute {
            try {
                if (!cancelRequested) runJob(url, quality)
            } finally {
                val left = pending.decrementAndGet()
                DownloadState.update { it.copy(pending = left) }
                if (left == 0) mainHandler.post { finish() }
            }
        }
        return START_NOT_STICKY
    }

    // Android 15+: dataSync 서비스 실행 한도(하루 6시간)에 도달하면 호출됨
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelAll()
        finish()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    // ── 실제 다운로드 ─────────────────────────────────────────
    private fun runJob(url: String, quality: Quality) {
        if (!Engine.ready) {
            finishWithResult(
                JobResult(null, 0, 0, emptyList(), "", false, "엔진이 아직 준비되지 않았어요. 잠시 후 다시 시도해 주세요.")
            )
            return
        }

        // yt-dlp는 앱 내부 폴더에서만 작업하고, 완성된 곡만 나중에 음악 폴더로 옮김
        val outRoot = File(filesDir, "out").apply { mkdirs() }
        // 지난번에 앱이 강제 종료되어 못 옮긴 곡이 있으면 먼저 옮김
        MediaPublisher.publishAll(this, outRoot)
        val workDir = File(cacheDir, "work").apply { deleteRecursively(); mkdirs() }
        val archiveDir = File(filesDir, "archive").apply { mkdirs() }
        val archive = File(archiveDir, "${YtArgs.playlistId(url)}-${quality.name}.txt")
        val doneFile = File(workDir, "done.txt")

        val request = YoutubeDLRequest(url)
            // JS 해독 결과를 캐시해서 곡마다 반복 계산하지 않게 (재생목록 속도 향상)
            .addOption("--cache-dir", File(cacheDir, "yt-dlp-cache").absolutePath)
            .addCommands(YtArgs.build(quality, url, outRoot, workDir, archive, doneFile))

        val processId = "ytpl-${System.currentTimeMillis()}"
        currentProcessId = processId
        DownloadState.update {
            it.copy(
                running = true, result = null, playlistTitle = null,
                itemIndex = 0, itemTotal = 0, currentTrack = null,
                stage = "목록 확인 중…", progress = 0f, etaSec = 0, log = emptyList(),
            )
        }

        var playlistTitle: String? = null
        var saved = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        var cancelled = false
        var fatal: String? = null

        try {
            YoutubeDL.getInstance().execute(
                request = request,
                processId = processId,
                redirectErrorStream = true,   // 오류 메시지도 같은 흐름으로 받아 화면에 표시
                callback = { progress, eta, line ->
                    when (val ev = OutputParser.parse(line)) {
                        is OutputParser.Playlist -> {
                            playlistTitle = ev.title
                            DownloadState.update { it.copy(playlistTitle = ev.title) }
                        }
                        is OutputParser.Item -> {
                            // 다음 곡으로 넘어갈 때 앞 곡(완성본)을 바로 음악 폴더로 옮김
                            saved += MediaPublisher.publishAll(this, outRoot)
                            DownloadState.update {
                                it.copy(itemIndex = ev.index, itemTotal = ev.total, progress = 0f,
                                    stage = "정보 확인 중…")
                            }
                        }
                        is OutputParser.Track -> DownloadState.update {
                            it.copy(currentTrack = ev.name, stage = "다운로드 중")
                        }
                        is OutputParser.Converting -> DownloadState.update {
                            it.copy(stage = "변환·커버 삽입 중", progress = 100f)
                        }
                        is OutputParser.Skipped -> skipped++
                        is OutputParser.Error -> errors += ev.message
                        null -> Unit
                    }
                    val isProgressLine = line.startsWith("[download]") && line.contains("% of")
                    DownloadState.update { s ->
                        s.copy(
                            progress = if (isProgressLine) progress.coerceIn(0f, 100f) else s.progress,
                            etaSec = if (isProgressLine) eta else s.etaSec,
                            log = if (isProgressLine || line.isBlank()) s.log else (s.log + line).takeLast(150),
                        )
                    }
                    maybeUpdateNotification()
                },
            )
        } catch (e: YoutubeDL.CanceledException) {
            cancelled = true
        } catch (e: InterruptedException) {
            cancelled = true
        } catch (e: Exception) {
            // --ignore-errors 상태에서 일부 곡이 실패하면 종료 코드가 1이 되어 예외가 옴 → 정상 처리
            if (cancelRequested) cancelled = true
            else if (errors.isEmpty()) fatal = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }
                ?: "알 수 없는 오류"
        } finally {
            currentProcessId = null
        }

        DownloadState.update { it.copy(stage = "음악 폴더로 옮기는 중", progress = 100f) }
        saved += MediaPublisher.publishAll(this, outRoot)
        workDir.deleteRecursively()

        val joined = errors.joinToString("\n")
        val hint = fatal ?: when {
            joined.contains("Sign in to confirm", ignoreCase = true) ||
                joined.contains("not a bot", ignoreCase = true) ||
                joined.contains("HTTP Error 403") ->
                "유튜브가 요청을 막았어요. 아래 ‘yt-dlp 업데이트’를 누른 뒤 잠시 후 다시 시도해 보세요. 받은 곡은 건너뛰고 이어서 받아요."
            joined.contains("Private video") || joined.contains("unavailable", ignoreCase = true) ->
                "삭제되었거나 비공개인 곡은 받을 수 없어요."
            errors.isNotEmpty() -> "같은 링크로 다시 시작하면 실패한 곡만 다시 시도해요."
            else -> null
        }

        finishWithResult(
            JobResult(playlistTitle, saved, skipped, errors.toList(),
                "Music/${MediaPublisher.ROOT_NAME}" + (playlistTitle?.let { "/$it" } ?: ""), cancelled, hint)
        )
    }

    private fun finishWithResult(result: JobResult) {
        DownloadState.update { it.copy(running = false, stage = "", result = result) }
        val title = when {
            result.cancelled -> "다운로드를 중지했어요"
            result.errors.isEmpty() && result.hint == null -> "다운로드 완료"
            else -> "다운로드 완료 (일부 실패)"
        }
        val text = buildString {
            append("저장 ${result.saved}곡")
            if (result.skipped > 0) append(" · 이미 있음 ${result.skipped}곡")
            if (result.errors.isNotEmpty()) append(" · 실패 ${result.errors.size}곡")
        }
        nm.notify(
            DONE_NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(result.playlistTitle?.let { "$title · $it" } ?: title)
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
        )
    }

    // ── 알림 ─────────────────────────────────────────────────
    private fun maybeUpdateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 800) return
        lastNotifyAt = now
        nm.notify(NOTIF_ID, buildProgressNotification(DownloadState.state.value))
    }

    private fun buildProgressNotification(s: UiState): android.app.Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = s.playlistTitle ?: "재생목록 다운로드"
        val text = buildString {
            if (s.itemTotal > 0) append("${s.itemIndex}/${s.itemTotal}곡 · ")
            append(s.currentTrack ?: s.stage.ifEmpty { "준비 중…" })
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(1000, (s.overall * 1000).toInt(), s.itemTotal == 0 && s.progress == 0f)
            .setContentIntent(openAppIntent())
            .addAction(0, "중지", cancelIntent)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
