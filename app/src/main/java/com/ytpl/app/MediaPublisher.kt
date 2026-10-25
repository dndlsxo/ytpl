package com.ytpl.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/**
 * 앱 내부 폴더에서 완성된 오디오를 공용 음악 폴더(Music/YTPL/재생목록)로 옮김.
 *
 * yt-dlp가 공용 저장소에 직접 쓰면 안드로이드 저장소 규칙(권한 변경 금지 등) 때문에
 * 이동 단계에서 실패할 수 있어서, 작업은 전부 앱 내부에서 끝내고
 * 마지막에 MediaStore로 등록합니다. 등록된 곡은 음악 앱에 바로 나타납니다.
 */
object MediaPublisher {
    const val ROOT_NAME = "YTPL"
    private val audioExt = setOf("mp3", "m4a", "opus", "ogg", "webm", "aac", "flac", "wav")

    /** outRoot 아래의 완성된 오디오를 모두 공개 폴더로 옮기고, 옮긴 개수를 돌려줌 */
    fun publishAll(ctx: Context, outRoot: File): Int {
        val files = outRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in audioExt }
            .toList()
        var count = 0
        for (f in files) {
            val sub = f.parentFile?.takeIf { it != outRoot }?.name
            if (runCatching { publish(ctx, f, sub) }.isSuccess) {
                f.delete()
                count++
            }
        }
        // 비어 있는 재생목록 폴더 정리
        outRoot.listFiles()?.filter { it.isDirectory && it.list().isNullOrEmpty() }?.forEach { it.delete() }
        return count
    }

    private fun publish(ctx: Context, file: File, sub: String?) {
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext == "opus") "audio/ogg" else "audio/mpeg"
        val relDir = listOfNotNull(Environment.DIRECTORY_MUSIC, ROOT_NAME, sub).joinToString("/")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relDir)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)!!.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            // Android 9 이하: 저장소 권한으로 직접 복사 후 미디어 스캔
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), relDir).apply { mkdirs() }
            var target = File(dir, file.name)
            var n = 1
            while (target.exists()) target = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
            file.copyTo(target)
            MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mime), null)
        }
    }
}
