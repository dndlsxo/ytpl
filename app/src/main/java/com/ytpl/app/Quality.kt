package com.ytpl.app

/**
 * 저장 형식.
 * 유튜브는 음원을 이미 손실 압축(Opus/AAC)으로 보관하므로,
 * "최고 음질" = 그 원본을 재인코딩 없이 그대로 꺼내는 것입니다.
 */
enum class Quality(val title: String, val detail: String, val args: List<String>) {
    ORIGINAL(
        title = "원음 그대로 · 최고 음질",
        detail = "유튜브 원본 오디오(주로 Opus)를 변환 없이 저장해요. 음질 손실 0. 확장자 .opus/.m4a",
        args = listOf("-f", "bestaudio/best", "-x"),
    ),
    MP3_320(
        title = "MP3 320kbps",
        detail = "어떤 기기에서도 재생돼요. 원본을 MP3 최고 설정으로 변환(원본보다 좋아지지는 않아요).",
        args = listOf("-f", "bestaudio/best", "-x", "--audio-format", "mp3", "--audio-quality", "320K"),
    ),
    M4A(
        title = "M4A (AAC)",
        detail = "아이폰·차량·구형 기기 호환. AAC 원본이 있으면 변환 없이 저장해요.",
        args = listOf("-f", "bestaudio[ext=m4a]/bestaudio/best", "-x", "--audio-format", "m4a"),
    ),
}
