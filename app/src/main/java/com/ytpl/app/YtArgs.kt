package com.ytpl.app

import java.io.File

/** yt-dlp에 넘길 인자 조립 + 링크 처리 (안드로이드 API에 의존하지 않는 순수 로직) */
object YtArgs {
    private val listRegex = Regex("[?&]list=([A-Za-z0-9_-]+)")
    private val urlRegex = Regex("""https?://\S+""")

    /** 재생목록 ID (없으면 single). 이어받기 기록을 목록별로 나누는 데 사용 */
    fun playlistId(url: String): String = listRegex.find(url)?.groupValues?.get(1) ?: "single"

    /** 공유 텍스트·클립보드에서 유튜브 링크만 추출 */
    fun extractUrl(text: String): String? =
        urlRegex.findAll(text)
            .map { it.value.trimEnd(')', ']', '.', ',', '"', '\'') }
            .firstOrNull { it.contains("youtube.com") || it.contains("youtu.be") }

    fun build(
        quality: Quality,
        url: String,
        homeDir: File,
        workDir: File,
        archive: File,
        doneFile: File,
    ): List<String> {
        val a = mutableListOf<String>()
        a += quality.args
        a += listOf(
            "--yes-playlist",                 // watch?v=..&list=.. 링크도 목록 전체
            "--ignore-errors",                // 삭제·비공개 곡이 있어도 계속
            "--newline", "--no-mtime",
            "--download-archive", archive.absolutePath,   // 받은 곡은 건너뜀(이어받기)
            // temp에서 받고 변환한 뒤 완성본만 home(앱 내부)으로 이동 → 이후 MediaStore로 음악 폴더에 등록
            "-P", "home:${homeDir.absolutePath}",
            "-P", "temp:${workDir.absolutePath}",
            "-o", "%(playlist_title,playlist_id|Singles)s/%(playlist_index&{:03d} - |)s%(title)s.%(ext)s",
            "--windows-filenames", "--trim-filenames", "150",
            // 태그: 재생목록 순서=트랙 번호, 재생목록 이름=앨범, 'XXX - Topic' 꼬리 제거
            "--parse-metadata", "%(playlist_index|)s:%(track_number)s",
            "--parse-metadata", "%(playlist_title,album|)s:%(album)s",
            "--replace-in-metadata", "artist,uploader", " - Topic$", "",
            "--embed-metadata",
            // 앨범 커버: 썸네일을 정사각형으로 잘라서 삽입
            "--embed-thumbnail", "--convert-thumbnails", "jpg",
            "--ppa", "ThumbnailsConvertor:-q:v 2 -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"",
            // 봇 차단 방지용 간격 + 재시도
            "--sleep-requests", "1", "--sleep-interval", "1", "--max-sleep-interval", "4",
            "--retries", "10", "--fragment-retries", "10", "-N", "4",
            // 완성된 파일 경로 기록 → 음악 앱에 바로 보이도록 미디어 스캔
            "--print-to-file", "after_move:filepath", doneFile.absolutePath,
        )
        // 믹스(RD...) 목록은 자동 생성이라 끝이 없을 수 있어 앞 50곡만
        if (playlistId(url).startsWith("RD")) a += listOf("--playlist-items", "1:50")
        return a
    }
}

/** yt-dlp 출력 한 줄을 화면에 쓸 이벤트로 해석 */
object OutputParser {
    sealed interface Event
    data class Playlist(val title: String) : Event
    data class Item(val index: Int, val total: Int) : Event
    data class Track(val name: String) : Event
    data object Skipped : Event
    data object Converting : Event
    data class Error(val message: String) : Event

    private val playlist = Regex("""^\[download] Downloading playlist: (.+)$""")
    private val item = Regex("""^\[download] Downloading item (\d+) of (\d+)""")
    private val dest = Regex("""^\[download] Destination: (.+)$""")
    private val indexPrefix = Regex("""^\d{3} - """)

    fun parse(line: String): Event? {
        val l = line.trim()
        playlist.find(l)?.let { return Playlist(it.groupValues[1]) }
        item.find(l)?.let { return Item(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        dest.find(l)?.let {
            val name = File(it.groupValues[1]).nameWithoutExtension.replace(indexPrefix, "")
            return Track(name)
        }
        if (l.contains("has already been recorded in the archive")) return Skipped
        if (l.startsWith("[ExtractAudio]") || l.startsWith("[EmbedThumbnail]")) return Converting
        if (l.startsWith("ERROR:")) return Error(l.removePrefix("ERROR:").trim())
        return null
    }
}
