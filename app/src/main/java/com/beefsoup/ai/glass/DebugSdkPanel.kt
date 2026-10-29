package com.beefsoup.ai.glass

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun DebugSdkPanel(sdk: SdkDiagnostics, tools: DebugToolsState, connected: Boolean, available: Boolean,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onRefresh: () -> Unit, onPermission: (Boolean) -> Unit,
    onAudio: () -> Unit, onStop: () -> Unit, onPlay: (String) -> Unit, onDelete: () -> Unit,
    onTranscribe: () -> Unit, onSpeak: (String, String) -> Unit, onClear: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    fun feedback(action: () -> Unit): () -> Unit = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); action() }
    val idle = available && connected && !tools.busy && !tools.playing && !sdk.speaking && !sdk.transcribing
    var speech by rememberSaveable { mutableStateOf("這是眼鏡語音測試。") }
    var language by rememberSaveable { mutableStateOf("zh-TW") }
    var delete by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider()
        SectionTitle("連線與 SDK 資訊")
        Text("Android SDK 0.6.0 · ${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.bodySmall)
        Text("連線事件：${sdk.connection}\nApp 連線狀態：${if (connected) "已連線" else "未連線"}\n偏好語言：${sdk.preferredLocale}\n相機權限：${sdk.cameraPermission}\n麥克風權限：${sdk.microphonePermission}\nSDK isConnected：${sdk.sdkConnected ?: "尚未讀取"}\nSDK 音訊串流：${sdk.audioStreaming ?: "尚未讀取"}\nSDK 視訊串流：${sdk.videoStreaming ?: "尚未讀取"}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = feedback(onRefresh)) { Text("重新讀取 SDK 狀態") }
            OutlinedButton(onClick = if (connected) onDisconnect else onConnect, enabled = available) {
                Text(if (connected) "中斷眼鏡連線" else "連接眼鏡")
            }
            TextButton(onClick = { onPermission(true) }, enabled = idle) { Text("要求相機權限") }
            TextButton(onClick = { onPermission(false) }, enabled = idle) { Text("要求麥克風權限") }
        }
        Text("音訊預設：${com.htc.viveglass.sdk.AudioStreamingFormat.DEFAULT_SAMPLE_RATE_AUDIO} Hz · Mono · ${com.htc.viveglass.sdk.AudioStreamingFormat.DEFAULT_BITRATE} bps\n支援取樣率：${com.htc.viveglass.sdk.AudioStreamingFormat.SUPPORTED_SAMPLE_RATES.joinToString()}\n視訊預設位元率：${com.htc.viveglass.sdk.VideoStreamingFormat.DEFAULT_BITRATE} bps",
            style = MaterialTheme.typography.bodySmall)
        Text("目前公開 API 未提供電量百分比、韌體版本或裝置序號。低電量、過熱及 AI 按鍵事件可在事件紀錄查看。狀態欄為手動讀取的快照。",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        SectionTitle("音訊收音測試")
        Text("使用眼鏡環境麥克風。音訊保存為 App 私有 M4A 片段，不送至電腦、不加入會議紀錄。")
        Text("AAC 封包：${tools.audioPackets} · ${tools.audioBytes} bytes\nPCM 峰值：${tools.peak} / 32768（每段更新）",
            style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = feedback(onAudio), enabled = idle) { Text("開始收音並保存") }
            OutlinedButton(onClick = feedback(onStop), enabled = tools.busy || tools.playing) { Text("停止 SDK 測試") }
        }
        Text(tools.status)
        tools.files.forEachIndexed { i, path ->
            TextButton(onClick = { onPlay(path) }, enabled = !tools.busy && available && !sdk.speaking && !sdk.transcribing) {
                Text("播放片段 ${i + 1} · ${File(path).name}")
            }
        }
        if (tools.files.isNotEmpty()) TextButton(onClick = { delete = true }, enabled = !tools.busy && !tools.playing) {
            Text("刪除此測試音訊")
        }
        HorizontalDivider()
        SectionTitle("SDK 語音轉錄")
        Text("直接使用眼鏡 SDK 的連續轉錄，與電腦 Whisper 管線分開。")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = feedback(onTranscribe), enabled = idle) { Text("開始 SDK 轉錄") }
            OutlinedButton(onClick = feedback(onStop), enabled = tools.transcribing) { Text("停止 SDK 轉錄") }
        }
        Text(sdk.transcription.ifBlank { "尚無轉錄內容。" })
        HorizontalDivider()
        SectionTitle("SDK 語音播報")
        OutlinedTextField(speech, { speech = it.take(200) }, label = { Text("播報文字（最多 200 字）") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("zh-TW" to "繁體中文", "en-US" to "English", "ja-JP" to "日本語").forEach { (code, label) ->
                FilterChip(language == code, { language = code }, label = { Text(label) }, enabled = !sdk.speaking)
            }
        }
        Button(onClick = feedback { onSpeak(speech, language) }, enabled = idle && speech.isNotBlank()) { Text("透過 SDK 播報") }
        Text(if (sdk.speaking) "等待 SDK 播報完成。" else "SDK 未回報進行中的播報。")
        Text("此 SDK 沒有取消播報 API；送出後即使離頁，也可能繼續播放。不支援的語言會由事件回報。",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        SectionTitle("最近 SDK 事件")
        TextButton(onClick = feedback(onClear)) { Text("清除事件與轉錄文字") }
        Text(sdk.events.takeLast(40).asReversed().joinToString("\n").ifBlank { "尚無事件。" }, style = MaterialTheme.typography.bodySmall)
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("刪除此測試音訊？") },
        text = { Text("只刪除上列測試片段，不影響會議紀錄。") },
        confirmButton = { TextButton(onClick = { delete = false; onDelete() }) { Text("刪除") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}
