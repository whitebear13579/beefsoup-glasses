package edu.ncku.aiglass

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val runtime get() = (application as MeetingApplication).runtime
    private var pendingAfterPermission: (() -> Unit)? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val nearbyGranted = if (Build.VERSION.SDK_INT >= 31) {
            result[Manifest.permission.BLUETOOTH_SCAN] != false &&
                result[Manifest.permission.BLUETOOTH_CONNECT] != false
        } else result[Manifest.permission.ACCESS_FINE_LOCATION] != false
        if (nearbyGranted) pendingAfterPermission?.invoke()
        else runtime.showPermissionDenied()
        pendingAfterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by runtime.state.collectAsState()
            val useDemo = state.demo
            var pcEnabled by rememberSaveable { mutableStateOf(false) }
            var url by rememberSaveable { mutableStateOf("ws://10.0.2.2:8765/ws") }
            var token by remember { mutableStateOf("") }
            MaterialTheme {
                MeetingScreen(
                    state = state,
                    useDemo = useDemo,
                    onDemo = { selected -> runtime.setDemo(selected) },
                    pcEnabled = pcEnabled,
                    onPcEnabled = { pcEnabled = it },
                    url = url,
                    onUrl = { url = it },
                    token = token,
                    onToken = { token = it },
                    onConnect = { withPermissions { runtime.connect() } },
                    onStart = { mode ->
                        withPermissions {
                            lifecycleScope.launch {
                                runtime.start(this@MainActivity, mode,
                                    if (pcEnabled) OffloadSettings(url, token) else null)
                            }
                        }
                    },
                    onStop = { runtime.stop() },
                    onRetry = { runtime.retryPending(OffloadSettings(url, token)) },
                    onDelete = { runtime.deleteCurrent() },
                    onExport = {
                        val text = runtime.exportTimeline()
                        if (text.isNotBlank()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }, "匯出會議時間軸"))
                    },
                )
            }
        }
    }

    private fun withPermissions(action: () -> Unit) {
        val required = if (Build.VERSION.SDK_INT >= 31) mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
        ) else mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action() else {
            pendingAfterPermission = action
            permissions.launch(missing.toTypedArray())
        }
    }
}

@Composable
fun MeetingScreen(
    state: MeetingUiState,
    useDemo: Boolean,
    onDemo: (Boolean) -> Unit,
    pcEnabled: Boolean,
    onPcEnabled: (Boolean) -> Unit,
    url: String,
    onUrl: (String) -> Unit,
    token: String,
    onToken: (String) -> Unit,
    onConnect: () -> Unit,
    onStart: (StreamMode) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val active = state.session.phase in setOf(SessionPhase.STARTING, SessionPhase.RECORDING, SessionPhase.STOPPING)
    var confirmDelete by remember { mutableStateOf(false) }
    val phaseLabel = when (state.session.phase) {
        SessionPhase.IDLE -> "尚未開始"
        SessionPhase.STARTING -> "正在啟動"
        SessionPhase.RECORDING -> "錄製中"
        SessionPhase.STOPPING -> "正在停止"
        SessionPhase.READY -> "已保存"
        SessionPhase.ERROR -> "發生錯誤"
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("AI 眼鏡會議紀錄", style = MaterialTheme.typography.headlineMedium)
        Text("VIVE Eagle · 音訊優先 · OCR 按需啟用", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("目前狀態：$phaseLabel", style = MaterialTheme.typography.titleMedium)
                Text(state.status)
                Text(if (state.connected) "來源已連線" else "來源尚未連線")
                Text("待轉譯音訊：${state.pendingAudio} 段")
                if (state.savedDirectory.isNotBlank()) Text("資料位置：${state.savedDirectory}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("使用模擬音訊", style = MaterialTheme.typography.titleMedium)
            Switch(checked = useDemo, onCheckedChange = onDemo, enabled = !active,
                modifier = Modifier.semantics { contentDescription = "使用模擬音訊" })
        }
        if (useDemo) Text("模擬音訊為合成聲，僅用於測試接收、封存與轉譯管線。")
        Button(onClick = onConnect, enabled = !active, modifier = Modifier.fillMaxWidth()) {
            Text(if (useDemo) "連接模擬來源" else "連接 VIVE Eagle")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("啟用電腦推論", style = MaterialTheme.typography.titleMedium)
            Switch(checked = pcEnabled, onCheckedChange = onPcEnabled, enabled = !active,
                modifier = Modifier.semantics { contentDescription = "啟用電腦推論" })
        }
        if (pcEnabled) {
            Text("語音片段會送至指定電腦。正式部署請使用 WSS。")
            OutlinedTextField(value = url, onValueChange = onUrl, label = { Text("推論網址 /ws") },
                singleLine = true, enabled = !active, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = token, onValueChange = onToken, label = { Text("連線權杖") },
                singleLine = true, enabled = !active, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
        }
        if (active) {
            Button(onClick = onStop, enabled = state.session.phase != SessionPhase.STOPPING,
                modifier = Modifier.fillMaxWidth()) { Text("停止並保存會議") }
        } else {
            Button(onClick = { onStart(StreamMode.AUDIO) }, enabled = state.connected,
                modifier = Modifier.fillMaxWidth()) { Text("開始音訊會議") }
            OutlinedButton(onClick = { onStart(StreamMode.AUDIO_VIDEO) },
                enabled = state.connected && !useDemo, modifier = Modifier.fillMaxWidth()) {
                Text("開始相機 OCR + 音訊會議")
            }
        }
        if (state.transcript.isNotEmpty()) {
            Text("逐字稿與翻譯", style = MaterialTheme.typography.titleLarge)
            state.transcript.takeLast(8).forEach { Text(it) }
        }
        if (state.ocr.isNotEmpty()) {
            Text("相機 OCR", style = MaterialTheme.typography.titleLarge)
            state.ocr.takeLast(4).forEach { Text(it) }
        }
        if (state.summary.isNotBlank()) {
            Text("會議摘要", style = MaterialTheme.typography.titleLarge)
            Text(state.summary)
        }
        if (!active && pcEnabled && state.pendingAudio > 0) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("補送待轉譯音訊並產生摘要")
            }
        }
        OutlinedButton(onClick = onExport, enabled = state.savedDirectory.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("匯出時間軸 JSONL") }
        if (!active && state.savedDirectory.isNotBlank()) {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("刪除此會議資料")
            }
        }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除會議？") },
            text = { Text("音訊、逐字稿、OCR 與待轉譯片段將從手機刪除。") },
            confirmButton = {
                Button(onClick = { confirmDelete = false; onDelete() }) { Text("確認刪除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
        Spacer(Modifier.height(8.dp))
    }
}
