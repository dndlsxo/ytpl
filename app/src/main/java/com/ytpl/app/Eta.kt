package com.ytpl.app

/**
 * 재생목록 전체 남은 시간 추정 (안드로이드 API에 의존하지 않는 순수 로직, 시간은 ms로 주입).
 *
 * 곡 하나에 걸리는 실제 시간(정보 확인 + 다운로드 + 변환 + 대기)을 재서,
 * 최근 곡들의 평균 × 남은 곡 수로 계산합니다.
 * 이미 받은 곡(이어받기로 건너뛰는 곡)은 거의 0초라서 평균과 남은 곡 수에서 뺍니다.
 */
class EtaEstimator(private val alreadyArchived: Int) {
    private val durations = ArrayList<Long>()
    private var itemStart = -1L
    private var itemSkipped = false
    private var skippedSoFar = 0
    private var index = 0
    private var total = 0

    /** "Downloading item i of n" 이 나올 때 = 앞 곡이 끝나고 다음 곡 시작 */
    fun onItem(i: Int, n: Int, now: Long) {
        finishCurrent(now)
        index = i
        total = n
        itemStart = now
        itemSkipped = false
    }

    /** 현재 곡이 이미 받은 곡이라 건너뜀 */
    fun onSkipped() {
        if (!itemSkipped) {
            itemSkipped = true
            skippedSoFar++
        }
    }

    private fun finishCurrent(now: Long) {
        if (index > 0 && itemStart >= 0 && !itemSkipped) durations += (now - itemStart)
        itemStart = -1L
    }

    /** 전체가 끝날 때까지 남은 시간(ms). 아직 끝까지 받은 곡이 없으면 null */
    fun remainingMs(now: Long): Long? {
        if (durations.isEmpty() || total <= 0) return null
        val avg = durations.takeLast(10).average()          // 최근 10곡 평균 → 속도 변화에 적응
        val current = when {
            itemSkipped || itemStart < 0 -> 0.0
            else -> maxOf(avg * 0.1, avg - (now - itemStart)) // 평균을 넘겨도 0이 되진 않게
        }
        val archivedAhead = maxOf(0, alreadyArchived - skippedSoFar)
        val after = maxOf(0, (total - index) - archivedAhead)
        return (current + avg * after).toLong()
    }
}

/** 초 → "약 12분", "약 1시간 5분" */
fun formatDuration(sec: Long): String {
    if (sec < 60) return "1분 미만"
    val totalMin = Math.round(sec / 60.0)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> "약 ${m}분"
        m == 0L -> "약 ${h}시간"
        else -> "약 ${h}시간 ${m}분"
    }
}

/** 초 → "3:07" 또는 "1:02:07" */
fun formatClock(sec: Long): String {
    val s = maxOf(0L, sec)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}
