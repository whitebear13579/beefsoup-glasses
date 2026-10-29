package com.beefsoup.ai.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun MeetingScreen(
    state: MeetingUiState, useDemo: Boolean, onDemo: (Boolean) -> Unit,
    pcEnabled: Boolean, onPcEnabled: (Boolean) -> Unit, url: String, onUrl: (String) -> Unit,
    token: String, onToken: (String) -> Unit, onConnect: () -> Unit, onStart: (StreamMode) -> Unit,
    onStop: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit,
    onStartTranslation: () -> Unit = { onStart(StreamMode.AUDIO) },
    targetLanguage: String = "zh-TW", onTargetLanguage: (String) -> Unit = {},
    onCapture: () -> Unit = {}, onAutomaticSpeech: (Boolean) -> Unit = {},
    onReplay: (TranslatedUtterance) -> Unit = {},
    onHighlightUpdate: (MeetingHighlight, String, Boolean, Boolean) -> Unit = { _, _, _, _ -> },
    onOpenSystemSettings: () -> Unit = {}, onOpenViveConnect: () -> Unit = {}, onDismissIssue: () -> Unit = {},
    debugCameraState: DebugCameraState = DebugCameraState(),
    onDebugCameraStart: () -> Unit = {}, onDebugCameraStop: () -> Unit = {}, onDebugCameraCapture: () -> Unit = {},
    onDebugCameraQuality: (com.htc.viveglass.sdk.VideoStreamingFormat.VideoQuality) -> Unit = {},
    onDebugCameraFps: (Int) -> Unit = {},
    onDebugStillSave: () -> Unit = {}, onDebugStillDiscard: () -> Unit = {},
    onDebugStillCapture: () -> Unit = {}, onDebugPhotoQuality: (PhotoSaveQuality) -> Unit = {},
    debugToolsState: DebugToolsState = DebugToolsState(), sdkDiagnostics: SdkDiagnostics = SdkDiagnostics(),
    onDebugLeave: () -> Unit = onDebugCameraStop,
    onDebugRefresh: () -> Unit = {}, onDebugDisconnect: () -> Unit = {}, onDebugClearEvents: () -> Unit = {},
    onDebugPermission: (Boolean) -> Unit = {}, onDebugAudioStart: () -> Unit = {}, onDebugToolsStop: () -> Unit = {},
    onDebugAudioPlay: (String) -> Unit = {}, onDebugAudioDelete: () -> Unit = {},
    onDebugTranscribe: () -> Unit = {}, onDebugSpeak: (String, String) -> Unit = { _, _ -> },
) {
    val haptics = LocalHapticFeedback.current
    var destination by rememberSaveable { mutableStateOf("會議") }
    var includeImages by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(destination, state.userIssue) { listState.requestScrollToItem(0) }
    val active = state.session.phase in setOf(SessionPhase.STARTING, SessionPhase.RECORDING, SessionPhase.STOPPING)
    val validEndpoint = remember(url) { runCatching { InferenceProtocol.validateEndpoint(url, BuildConfig.DEBUG) }.isSuccess }
    val serviceConfigured = pcEnabled && token.isNotBlank() && validEndpoint
    val elapsed by produceState(0L, active, state.recordingSinceMs) {
        while (active) {
            value = ((System.currentTimeMillis() - state.recordingSinceMs) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    BackHandler(destination != "會議") { destination = "會議" }
    if (BuildConfig.DEBUG && destination == "測試") DisposableEffect(Unit) {
        onDispose { onDebugLeave() }
    }
    LaunchedEffect(destination) {
        if (!BuildConfig.DEBUG && destination == "測試") destination = "會議"
    }
    val destinations = listOf("會議" to Icons.Default.Home, "翻譯" to Icons.Default.PlayArrow,
        "重點" to Icons.Default.Star, "設定" to Icons.Default.Settings) +
        if (BuildConfig.DEBUG) listOf("測試" to Icons.Default.Build) else emptyList()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (useRail) NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Spacer(Modifier.height(24.dp))
                destinations.forEach { (title, icon) ->
                    NavigationRailItem(selected = destination == title, onClick = {
                        if (destination != title) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        destination = title
                    }, icon = { Icon(icon, null) }, label = { Text(title) })
                    Spacer(Modifier.height(8.dp))
                }
            }
            Scaffold(modifier = Modifier.weight(1f).imePadding(),
                topBar = { TopAppBar(title = { Text("AI 眼鏡會議紀錄", style = MaterialTheme.typography.titleMedium) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
                bottomBar = {
                    Column(Modifier.fillMaxWidth().then(if (useRail) Modifier.navigationBarsPadding() else Modifier), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (active) {
                            Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onStop() }, enabled = state.session.phase != SessionPhase.STOPPING,
                                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 56.dp)) {
                                Text(if (state.session.phase == SessionPhase.STOPPING) "正在保存…" else "停止並保存")
                            }
                        } else if (destination in setOf("會議", "翻譯")) {
                            val translating = destination == "翻譯"
                            Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); if (translating) onStartTranslation()
                                else onStart(if (includeImages) StreamMode.AUDIO_VIDEO else StreamMode.AUDIO) },
                                enabled = state.connected && !debugCameraState.active && !debugCameraState.takingPhoto && !debugToolsState.busy && !debugToolsState.playing &&
                                    !sdkDiagnostics.speaking && !sdkDiagnostics.transcribing && (!translating || serviceConfigured),
                                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 56.dp)) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (translating) "開始連續翻譯" else "開始會議")
                            }
                        }
                        if (!useRail) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            destinations.forEach { (title, icon) ->
                                NavigationBarItem(selected = destination == title, onClick = {
                                    if (destination != title) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    destination = title
                                },
                                    icon = { Icon(icon, contentDescription = null) }, label = { Text(title) })
                            }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), state = listState, contentPadding = PaddingValues(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        state.userIssue?.let { issue -> item {
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(issue, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (state.needsSystemSettings) TextButton(onClick = onOpenSystemSettings) { Text("開啟系統設定") }
                                        else {
                                            TextButton(onClick = onOpenViveConnect) { Text("開啟 VIVE Connect") }
                                            if (!state.connected) TextButton(onClick = onConnect, enabled = !state.connecting && !active) {
                                                Text("重試連線")
                                            }
                                        }
                                        TextButton(onClick = onDismissIssue) { Text("知道了") }
                                    }
                                }
                            }
                        } }
                        if (destination in setOf("會議", "翻譯")) {
                            item {
                                SessionHero(state, destination == "翻譯", active, elapsed)
                            }
                            item { ConnectionSection(state, onConnect, active) }
                        }
                        when (destination) {
                            "會議" -> {
                                item {
                                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.large) {
                                        Column(Modifier.padding(16.dp)) {
                                            SettingSwitch("保留重點影像", "辨識白板與文件；也可手動保留眼前內容。",
                                                includeImages, { includeImages = it }, !active)
                                        }
                                    }
                                }
                                if (active && state.session.mode == StreamMode.AUDIO_VIDEO) item {
                                    OutlinedButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onCapture() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text("保留眼前內容")
                                    }
                                    Text(state.visionStatus, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (state.session.phase == SessionPhase.ERROR) item {
                                    Text("記錄已中斷。已保存的內容仍在手機，請重新確認眼鏡連線後再開始。",
                                        color = MaterialTheme.colorScheme.error)
                                }
                                if (state.highlights.isNotEmpty()) item {
                                    TextButton(onClick = { destination = "重點" }) { Text("查看 ${state.highlights.size} 張重點照片") }
                                }
                                if (state.summary.isNotBlank()) item {
                                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            SectionTitle("會議摘要")
                                            Text(state.summary, style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                                item { SectionTitle("逐字稿") }
                                if (state.transcript.isEmpty()) item {
                                    Text(if (serviceConfigured) "開始後，辨識內容會出現在這裡。" else "先在設定中連接電腦，或先錄音、稍後轉譯。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else items(state.transcript) { line ->
                                    Text(line, style = MaterialTheme.typography.bodyLarge)
                                    HorizontalDivider(Modifier.padding(top = 16.dp))
                                }
                                if (!active && state.savedDirectory.isNotBlank()) item {
                                    if (state.pendingAudio > 0) {
                                        Text("尚有 ${state.pendingAudio} 段音訊待轉譯。", style = MaterialTheme.typography.bodyMedium)
                                        OutlinedButton(onClick = onRetry, enabled = serviceConfigured) { Text("處理待轉譯內容") }
                                    } else if (state.transcript.isNotEmpty() || state.highlights.any { it.confirmed }) {
                                        OutlinedButton(onClick = onRetry, enabled = serviceConfigured) { Text("更新會議摘要") }
                                    }
                                    TextButton(onClick = onExport) { Text("分享會議紀錄") }
                                }
                            }
                            "翻譯" -> {
                                item {
                                    Text("翻譯成", style = MaterialTheme.typography.titleMedium)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("zh-TW" to "繁體中文", "en" to "English", "ja" to "日本語").forEach { (code, label) ->
                                            FilterChip(selected = targetLanguage == code, onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTargetLanguage(code) },
                                                enabled = !active, label = { Text(label) })
                                        }
                                    }
                                    if (active && state.continuousTranslation)
                                        SettingSwitch("自動播報", state.speechStatus, state.automaticSpeech, onAutomaticSpeech)
                                    else if (active) Text("目前正在記錄會議。停止並保存後，可開始連續翻譯與播報。")
                                    Text("使用耳機可減少回音。眼鏡喇叭與收音同時使用的效果仍需實測。",
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!serviceConfigured) item {
                                    Text("先完成電腦連線設定，才能開始連續翻譯。")
                                    OutlinedButton(onClick = { destination = "設定" }) { Text("設定翻譯服務") }
                                }
                                if (state.utterances.isEmpty()) item {
                                    Text("完成的譯句會保留原文，方便核對與重播。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                items(state.utterances, key = { it.id }) { utterance ->
                                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text(utterance.translation, style = MaterialTheme.typography.headlineSmall)
                                            Text(utterance.source, style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                    TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onReplay(utterance) },
                                        modifier = Modifier.semantics { contentDescription = "重播 ${utterance.translation.take(40)}" }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null); Text("重播")
                                    }
                                    HorizontalDivider()
                                }
                                item { Text(state.inferenceStatus, style = MaterialTheme.typography.bodySmall) }
                            }
                            "重點" -> {
                                item {
                                    PageIntro("照片與文字，\n一起回看", "自動挑出的內容先由你核對。確認後，才會作為摘要的引用來源。",
                                        MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                                    if (state.highlights.isNotEmpty()) {
                                        Spacer(Modifier.height(16.dp))
                                        Text("${state.highlights.count { !it.confirmed }} 張待核對 · ${state.highlights.count { it.confirmed }} 張已確認",
                                            style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                if (state.highlights.isEmpty()) item {
                                    Text("目前沒有重點照片", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(8.dp))
                                    Text("會議開始前啟用重點影像。清楚、穩定且與討論相關的畫面會送交辨識；也可按「保留眼前內容」。")
                                }
                                items(state.highlights, key = { it.id }) { item -> HighlightSection(item, onHighlightUpdate) }
                                item { Text(state.visionStatus, style = MaterialTheme.typography.bodySmall) }
                            }
                            "測試" -> if (BuildConfig.DEBUG) {
                                item { DebugCameraPanel(debugCameraState, state.connected, state.demo,
                                    active || debugToolsState.busy || debugToolsState.playing || sdkDiagnostics.speaking || sdkDiagnostics.transcribing,
                                    onConnect, onDebugCameraStart, onDebugCameraStop, onDebugCameraCapture,
                                    onDebugCameraQuality, onDebugCameraFps, onDebugStillCapture, onDebugPhotoQuality, onDebugStillSave, onDebugStillDiscard) }
                                item { DebugSdkPanel(sdkDiagnostics, debugToolsState, state.connected,
                                    !active && !state.demo && !debugCameraState.active && !debugCameraState.takingPhoto,
                                    onConnect, onDebugDisconnect, onDebugRefresh, onDebugPermission,
                                    onDebugAudioStart, onDebugToolsStop, onDebugAudioPlay, onDebugAudioDelete,
                                    onDebugTranscribe, onDebugSpeak, onDebugClearEvents) }
                            }
                            "設定" -> {
                                item { PageIntro("準備好，\n就能開始。", "連接你的電腦，設定翻譯與重點辨識。",
                                    MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface) }
                                item { SectionTitle("翻譯與重點辨識") }
                                item { SettingSwitch("使用本機電腦", "語音與候選影像會送到你指定的電腦。", pcEnabled, onPcEnabled, !active) }
                                if (pcEnabled) item {
                                    OutlinedTextField(url, onUrl, label = { Text("電腦服務位址") }, singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                        enabled = !active, isError = url.isNotBlank() && !validEndpoint,
                                        supportingText = { if (url.isNotBlank() && !validEndpoint) Text("請使用有效且安全的服務位址，路徑為 /ws") },
                                        modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedTextField(token, onToken, label = { Text("連線碼") }, singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                        visualTransformation = PasswordVisualTransformation(), enabled = !active,
                                        modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(8.dp))
                                    Text("連線碼只保留在這次開啟的 App 中。正式區網連線需使用已信任的安全憑證。",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                item {
                                    SectionTitle("語音")
                                    Text(state.speechStatus)
                                    Text("自動播報使用手機已安裝的離線語音。缺少語音時仍會保存文字。",
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                                if (state.savedDirectory.isNotBlank() && !active) item {
                                    HorizontalDivider()
                                    TextButton(onClick = onExport) { Text("分享會議紀錄") }
                                    TextButton(onClick = { deleteDialog = true }, colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error)) { Text("刪除目前會議") }
                                }
                                if (BuildConfig.DEBUG) item {
                                    TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "收合開發診斷" else "開發診斷") }
                                    if (diagnostics) {
                                        SettingSwitch("使用模擬音訊", "僅供測試，並非眼鏡收音。", useDemo, onDemo, !active)
                                        Text(state.status, style = MaterialTheme.typography.bodySmall)
                                        Text(state.savedDirectory, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("刪除目前會議？") },
        text = { Text("音訊、照片、逐字稿與待轉譯內容會從手機刪除。") },
        confirmButton = { TextButton(onClick = { deleteDialog = false; onDelete() }) { Text("刪除") } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
}

@Composable private fun ConnectionSection(state: MeetingUiState, onConnect: () -> Unit, active: Boolean) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (state.connected) Icons.Default.CheckCircle else Icons.Default.Info, null,
                tint = MaterialTheme.colorScheme.primary)
            Text(when { state.connected -> "眼鏡已連線"; state.connecting -> "正在連接眼鏡…"; else -> "眼鏡尚未連線" },
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        if (!state.connected) {
            Text("請先配戴眼鏡，並在 VIVE Connect 完成配對。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onConnect() },
                enabled = !active && !state.connecting) {
                Text(if (state.connecting) "連線中…" else "連接眼鏡")
            }
        }
    }
}

@Composable private fun SettingSwitch(title: String, description: String, checked: Boolean,
    onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current)
        }
        Switch(checked, { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChange(it) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun HighlightSection(item: MeetingHighlight,
    onUpdate: (MeetingHighlight, String, Boolean, Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var editing by remember { mutableStateOf(false) }
    var excluding by remember { mutableStateOf(false) }
    var draft by remember(item.text) { mutableStateOf(item.text) }
    var viewing by remember { mutableStateOf(false) }
    var reasonVisible by remember { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, item.imagePath) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(item.imagePath, BitmapFactory.Options().apply { inSampleSize = 2 }) }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${mediaTime(item.ptsUs)} · ${if (item.confirmed) "已確認" else "待你核對"}", style = MaterialTheme.typography.titleLarge)
            bitmap?.let { image -> Image(image.asImageBitmap(), contentDescription = "${mediaTime(item.ptsUs)} 的重點照片，點選放大",
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp)
                    .clickable(onClickLabel = "放大照片") { viewing = true }, contentScale = ContentScale.Fit) }
                ?: Text("照片目前無法讀取，辨識文字仍保留。")
            Text(item.text.ifBlank { "尚無可讀的辨識文字，可手動補充。" }, style = MaterialTheme.typography.bodyLarge)
            Text(if (item.model == "phone-ocr") "你手動保留的影格，請對照照片核對文字。"
                else "模型建議保留的片段，請確認與本次會議的關聯。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { reasonVisible = !reasonVisible }) { Text(if (reasonVisible) "收合判斷原因" else "查看判斷原因") }
            if (reasonVisible) Text(item.reason, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onUpdate(item, item.text, true, false) }, enabled = !item.confirmed && item.text.isNotBlank()) {
                    Text(if (item.confirmed) "已確認" else "確認重點")
                }
                TextButton(onClick = { editing = true }) { Text("修改文字") }
                TextButton(onClick = { excluding = true }) { Text("排除") }
            }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("核對辨識文字") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            bitmap?.let { image -> Image(image.asImageBitmap(), "修正時的來源照片，點選放大",
                Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp).clickable { viewing = true }, contentScale = ContentScale.Fit) }
            Text("可點照片放大，關閉照片後繼續修改。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft, { draft = it.take(4000) }, label = { Text("照片中的文字") },
                minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); editing = false; onUpdate(item, draft, true, false) },
            enabled = draft.isNotBlank()) { Text("儲存並確認") } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
    if (excluding) AlertDialog(onDismissRequest = { excluding = false }, title = { Text("排除此片段？") },
        text = { Text("照片會刪除，辨識內容不再作為摘要來源。") },
        confirmButton = { TextButton(onClick = { excluding = false; onUpdate(item, item.text, false, true) }) { Text("排除並刪除照片") } },
        dismissButton = { TextButton(onClick = { excluding = false }) { Text("取消") } })
    if (viewing) Dialog(onDismissRequest = { viewing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            TextButton(onClick = { viewing = false }) { Text("關閉照片") }
            // Read the full image only while the user is inspecting it.
            val full by produceState<Bitmap?>(null, item.imagePath) {
                value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(item.imagePath) }
            }
            full?.let { Image(it.asImageBitmap(), "重點照片原始影格", Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit) }
        } }
    }
}

fun mediaTime(ptsUs: Long): String {
    val seconds = (ptsUs / 1_000_000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
}
