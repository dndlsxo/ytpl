package com.ytpl.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 앱에 내장된 추출 엔진(yt-dlp + Python + QuickJS + FFmpeg).
 * 초기화·업데이트·다운로드를 한 줄로 세운 단일 스레드에서 처리해서,
 * 다운로드 도중 yt-dlp 파일이 교체되는 일이 없게 합니다.
 */
object Engine {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ytpl-engine") }

    @Volatile
    var ready = false
        private set

    private const val PREFS = "ytpl"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        executor.execute {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                ready = true
                DownloadState.update { it.copy(engineReady = true, ytdlpVersion = versionLabel(app)) }
            } catch (e: Throwable) {
                DownloadState.update { it.copy(engineError = "엔진 초기화 실패: ${e.message}") }
                return@execute
            }
            // 유튜브는 자주 구조를 바꾸므로 하루 한 번 yt-dlp 자동 최신화
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_UPDATE, 0L) > DAY_MS) {
                doUpdate(app, silent = true)
            }
        }
    }

    fun requestUpdate(ctx: Context) {
        val app = ctx.applicationContext
        DownloadState.update { it.copy(updating = true, notice = null) }
        executor.execute {
            if (ready) doUpdate(app, silent = false)
            else DownloadState.update { it.copy(updating = false) }
        }
    }

    private fun doUpdate(app: Context, silent: Boolean) {
        DownloadState.update { it.copy(updating = true) }
        var updated = false
        val msg = try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            updated = status == YoutubeDL.UpdateStatus.DONE
            if (updated) "yt-dlp를 최신 버전으로 업데이트했어요" else "이미 최신 버전이에요"
        } catch (e: Exception) {
            "업데이트 실패: ${e.message?.take(150)}"
        }
        DownloadState.update {
            it.copy(
                updating = false,
                ytdlpVersion = versionLabel(app),
                notice = if (silent && !updated) it.notice else msg,
            )
        }
    }

    private fun versionLabel(ctx: Context): String =
        YoutubeDL.getInstance().versionName(ctx)
            ?: YoutubeDL.getInstance().version(ctx)
            ?: "내장 버전"
}
