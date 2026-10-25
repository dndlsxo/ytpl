package com.ytpl.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class JobResult(
    val playlistTitle: String?,
    val saved: Int,
    val skipped: Int,
    val errors: List<String>,
    val folder: String,
    val cancelled: Boolean,
    val hint: String?,
)

data class UiState(
    val engineReady: Boolean = false,
    val engineError: String? = null,
    val ytdlpVersion: String? = null,
    val updating: Boolean = false,
    val notice: String? = null,
    val running: Boolean = false,
    val pending: Int = 0,
    val playlistTitle: String? = null,
    val itemIndex: Int = 0,
    val itemTotal: Int = 0,
    val currentTrack: String? = null,
    val stage: String = "",
    val progress: Float = 0f,      // 현재 곡 0~100
    val etaSec: Long = 0,
    val result: JobResult? = null,
    val log: List<String> = emptyList(),
) {
    /** 재생목록 전체 진행률 0~1 */
    val overall: Float
        get() = if (itemTotal <= 0) progress / 100f
        else (((itemIndex - 1).coerceAtLeast(0) + progress / 100f) / itemTotal).coerceIn(0f, 1f)
}

/** 서비스(다운로드)와 화면이 공유하는 상태 */
object DownloadState {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    fun update(f: (UiState) -> UiState) = _state.update(f)
}
