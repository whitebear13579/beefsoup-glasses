package com.beefsoup.ai.glass

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import com.htc.viveglass.sdk.VideoStreamingFormat.VideoQuality
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun DebugCameraPanel(state: DebugCameraState, connected: Boolean, demo: Boolean,
    meetingActive: Boolean, onConnect: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onCapture: () -> Unit,
    onQuality: (VideoQuality) -> Unit, onFps: (Int) -> Unit,
    onStillCapture: () -> Unit, onPhotoQuality: (PhotoSaveQuality) -> Unit,
    onStillSave: () -> Unit, onStillDiscard: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("眼鏡即時畫面")
        Text("測試相機取景。擷取畫面後，先在下方確認照片，再儲存到相簿。", style = MaterialTheme.typography.bodyLarge)
        CameraDropdown("影像品質", state.quality.cameraLabel, VideoQuality.values().toList(),
            { it.cameraLabel }, !state.active && !state.takingPhoto, "debug-camera-quality", onQuality)
        CameraDropdown("預覽更新率", "${state.previewFps} fps", previewFrameRates,
            { "$it fps" }, !state.active && !state.takingPhoto, "debug-camera-fps", onFps)
        Text(if (state.active) "請先停止預覽，再調整品質或更新率。" else "選擇後按「開始預覽」套用。高解析度可能降低實際更新率。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
            val frame = state.frame
            if (frame != null) Image(frame.asImageBitmap(), "眼鏡即時預覽",
                Modifier.fillMaxWidth().heightIn(max = 440.dp).aspectRatio(frame.width.toFloat() / frame.height),
                contentScale = ContentScale.Fit)
            else Box(Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(if (state.active) "等待眼鏡畫面…" else "尚未開始預覽", style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(state.status, style = MaterialTheme.typography.bodyMedium)
        state.frame?.let { Text("收到影像：${it.width} × ${it.height} · 預覽更新：約 ${"%.1f".format(state.deliveredFps)} fps\n媒體時間：${state.ptsUs} µs",
            style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.active) OutlinedButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress); onStop()
            }) { Text("停止預覽") }
            else Button(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onStart()
            }, enabled = connected && !demo && !meetingActive && !state.takingPhoto) { Text("開始預覽") }
            FilledTonalButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress); onCapture()
            }, enabled = state.active && state.frame != null && !state.saving) {
                Text(if (state.saving) "處理中…" else "擷取畫面")
            }
        }
        HorizontalDivider()
        SectionTitle("眼鏡拍照")
        Text("拍照後先顯示照片，確認後再儲存。請先停止即時預覽；相機目前僅提供預設拍攝品質。",
            style = MaterialTheme.typography.bodyMedium)
        CameraDropdown("照片儲存品質", state.photoQuality.label, PhotoSaveQuality.values().toList(),
            { it.label }, !state.takingPhoto && !state.saving, "debug-photo-quality", onPhotoQuality)
        Button(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress); onStillCapture()
        }, enabled = connected && !demo && !meetingActive && !state.active && !state.takingPhoto && !state.saving) {
            Text(if (state.takingPhoto) "拍照處理中…" else "拍照預覽")
        }
        state.photoPreview?.let { photo ->
            Image(photo.asImageBitmap(), "拍攝照片預覽", Modifier.fillMaxWidth().heightIn(max = 440.dp)
                .aspectRatio(photo.width.toFloat() / photo.height), contentScale = ContentScale.Fit)
            Text(state.photoInfo, style = MaterialTheme.typography.bodySmall)
            Text("預覽照片暫存於記憶體；關閉 App 後可能遺失。原始影像保留 SDK 格式；JPEG 選項會轉檔壓縮。",
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress); onStillSave()
                }, enabled = !state.saving && !state.takingPhoto && !state.photoSaved) {
                    Text(if (state.saving) "儲存照片中…" else if (state.photoSaved) "照片已儲存" else "儲存照片")
                }
                TextButton(onClick = onStillDiscard, enabled = !state.saving && !state.takingPhoto) { Text("放棄預覽照片") }
            }
        }
        Text(state.photoStatus, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
        if (state.savedUri != null) Text("已儲存至相簿：Pictures/AI Glass Debug",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.primary)
        when {
            meetingActive -> Text("請先停止目前的會議或其他 SDK 測試，再使用相機。")
            demo -> Text("模擬來源不提供相機。請在設定關閉模擬音訊，並連接眼鏡。")
            !connected -> OutlinedButton(onClick = onConnect) { Text("連接眼鏡") }
        }
        Text("眼鏡串流設定為 ${streamFrameRateFor(state.previewFps).value} fps；預覽目標 ${state.previewFps} fps，不執行 OCR 或 AI 推論。離開此頁或切換到背景會停止預覽。SDK 同流提供的音訊不會保存。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


internal val VideoQuality.cameraLabel: String
    get() = "${when (this) {
        VideoQuality.DEFAULT -> "預設"
        VideoQuality.LOW -> "低畫質"
        VideoQuality.MEDIUM -> "中畫質"
        VideoQuality.HIGH -> "高畫質"
        VideoQuality.FULL_HD -> "Full HD"
    }} · $width × $height"

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun <T> CameraDropdown(label: String, value: String, choices: List<T>,
    text: (T) -> String, enabled: Boolean, tag: String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    ExposedDropdownMenuBox(expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(value = value, onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            modifier = Modifier.menuAnchor().fillMaxWidth().testTag(tag))
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(text(choice)) }, onClick = {
                expanded = false
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect(choice)
            }) }
        }
    }
}
