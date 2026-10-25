package com.ytpl.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** 유튜브 앱 '공유'로 받은 링크 */
    private val sharedUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            YtplTheme {
                MainScreen(sharedUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { YtArgs.extractUrl(it) }
                ?.let { sharedUrl.value = it }
        }
    }
}

// ── 테마 ─────────────────────────────────────────────────────
private val Brand = Color(0xFFD7263D)

@Composable
fun YtplTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFFFF8A94),
            onPrimary = Color(0xFF4A0010),
            primaryContainer = Color(0xFF6E1222),
            onPrimaryContainer = Color(0xFFFFDADC),
            background = Color(0xFF151313),
            surface = Color(0xFF151313),
            surfaceVariant = Color(0xFF2A2424),
        )
    } else {
        lightColorScheme(
            primary = Brand,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDADC),
            onPrimaryContainer = Color(0xFF40000B),
            background = Color(0xFFFBF8F6),
            surface = Color(0xFFFBF8F6),
            surfaceVariant = Color(0xFFF1E7E6),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ── 메인 화면 ────────────────────────────────────────────────
@Composable
fun MainScreen(shared: MutableState<String?>) {
    val ctx = LocalContext.current
    val state by DownloadState.state.collectAsState()
    val prefs = remember { ctx.getSharedPreferences("ytpl", Context.MODE_PRIVATE) }

    var url by rememberSaveable { mutableStateOf("") }
    var quality by rememberSaveable {
        mutableStateOf(
            runCatching { Quality.valueOf(prefs.getString("quality", null)!!) }
                .getOrDefault(Quality.ORIGINAL)
        )
    }
    var showLog by rememberSaveable { mutableStateOf(false) }

    // 공유로 들어온 링크 자동 입력
    LaunchedEffect(shared.value) {
        shared.value?.let {
            url = it
            shared.value = null
        }
    }

    // 알림(13+)·저장소(10 이하) 권한
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val need = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    val validUrl = YtArgs.extractUrl(url)

    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 헤더
            Column {
                Text("YTPL", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "유튜브 재생목록을 한 번에, 최고 음질로",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 링크 입력
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("재생목록 또는 영상 링크") },
                placeholder = { Text("https://youtube.com/playlist?list=…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                        val found = YtArgs.extractUrl(text)
                        if (found != null) url = found
                        else Toast.makeText(ctx, "클립보드에 유튜브 링크가 없어요", Toast.LENGTH_SHORT).show()
                    }) { Text("붙여넣기") }
                },
                supportingText = {
                    if (url.isNotBlank() && validUrl == null) Text("유튜브 링크가 아니에요")
                    else Text("유튜브 앱에서 공유 → YTPL 로 바로 보낼 수도 있어요")
                },
            )

            // 음질
            Text("저장 형식", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Quality.entries.forEach { q ->
                    QualityOption(q, selected = q == quality) {
                        quality = q
                        prefs.edit().putString("quality", q.name).apply()
                    }
                }
            }

            // 시작 버튼
            Button(
                onClick = {
                    if (validUrl != null) {
                        DownloadService.start(ctx, validUrl, quality)
                        url = ""
                    }
                },
                enabled = validUrl != null && state.engineReady && !state.updating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(if (state.running) "대기열에 추가" else "다운로드 시작", fontSize = 16.sp)
            }

            // 엔진 상태
            when {
                state.engineError != null -> Text(state.engineError!!, color = MaterialTheme.colorScheme.error)
                !state.engineReady -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "다운로드 엔진 준비 중… (첫 실행은 수십 초 걸려요)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (state.running) ProgressCard(state)
            state.result?.takeIf { !state.running }?.let { ResultCard(it) }

            // 로그
            if (state.log.isNotEmpty()) {
                TextButton(onClick = { showLog = !showLog }) {
                    Text(if (showLog) "로그 숨기기" else "로그 보기")
                }
                if (showLog) {
                    Text(
                        state.log.takeLast(60).joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            // 엔진 정보·업데이트
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("yt-dlp ${state.ytdlpVersion ?: "-"}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "저장 위치: 음악(Music)/YTPL/재생목록 이름",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { Engine.requestUpdate(ctx) },
                    enabled = state.engineReady && !state.updating && !state.running,
                ) { Text(if (state.updating) "확인 중…" else "yt-dlp 업데이트") }
            }
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            // 이어받기 기록 초기화: 음악 폴더에서 곡을 지운 뒤 다시 받고 싶을 때
            TextButton(
                onClick = {
                    java.io.File(ctx.filesDir, "archive").deleteRecursively()
                    Toast.makeText(ctx, "받은 기록을 지웠어요. 다음 다운로드는 처음부터 받아요.", Toast.LENGTH_SHORT).show()
                },
                enabled = !state.running,
            ) { Text("받은 기록 초기화") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QualityOption(q: Quality, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(q.title, fontWeight = FontWeight.SemiBold)
                Text(
                    q.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(s: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                s.playlistTitle ?: "재생목록 불러오는 중…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 1초마다 갱신 → 남은 시간이 실시간으로 줄어들고 경과 시간이 늘어남
            val now by produceState(System.currentTimeMillis()) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1000)
                }
            }
            if (s.itemTotal > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${s.itemIndex} / ${s.itemTotal}곡",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(s.overall * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LinearProgressIndicator(progress = { s.overall }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 예상 시간
            val remain = s.totalRemainingSec(now)
            Row(Modifier.fillMaxWidth()) {
                TimeStat("전체 남은 시간", remain?.let { formatDuration(it) } ?: "계산 중…", Modifier.weight(1f))
                TimeStat("완료 예정", remain?.let { clockText(now + it * 1000) } ?: "-", Modifier.weight(1f))
                TimeStat("경과", formatClock((now - s.jobStartedAt) / 1000), Modifier.weight(1f))
            }
            if (remain == null && s.itemTotal > 1) {
                Text(
                    "첫 곡을 받고 나면 예상 시간이 표시돼요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            s.currentTrack?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val trackEta = if (s.etaSec > 0) " · ${formatClock(s.etaSec)} 남음" else ""
            Text(
                "${s.stage} · 현재 곡 ${s.progress.toInt()}%$trackEta",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.pending > 1) {
                Text("대기 중인 목록 ${s.pending - 1}개", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { DownloadService.cancelAll() }) { Text("중지") }
        }
    }
}

@Composable
private fun TimeStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** 시각 → "오전 1:23" */
private fun clockText(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN))

@Composable
private fun ResultCard(r: JobResult) {
    val ok = !r.cancelled && r.errors.isEmpty() && r.hint == null
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    r.cancelled -> "중지됨"
                    ok -> "완료"
                    else -> "완료 (확인 필요)"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            r.playlistTitle?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(
                buildString {
                    append("새로 저장 ${r.saved}곡")
                    if (r.skipped > 0) append(" · 이미 있음 ${r.skipped}곡")
                    if (r.errors.isNotEmpty()) append(" · 실패 ${r.errors.size}곡")
                }
            )
            if (r.elapsedSec > 0) {
                Text(
                    "걸린 시간 ${formatClock(r.elapsedSec)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            r.hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            r.errors.take(5).forEach {
                Text(
                    "• $it",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (r.folder.isNotEmpty()) {
                Text(
                    r.folder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
