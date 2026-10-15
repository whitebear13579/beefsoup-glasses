package com.beefsoup.ai.glass

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
        enableEdgeToEdge()
        setContent {
            val state by runtime.state.collectAsState()
            val useDemo = state.demo
            var pcEnabled by rememberSaveable { mutableStateOf(false) }
            var url by rememberSaveable { mutableStateOf("ws://10.0.2.2:8765/ws") }
            var token by remember { mutableStateOf("") }
            var targetLanguage by rememberSaveable { mutableStateOf("zh-TW") }
            MeetingTheme {
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
                    onOpenSystemSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"))) },
                    onOpenViveConnect = {
                        packageManager.getLaunchIntentForPackage("com.htc.glassesca")?.let { startActivity(it) }
                            ?: runtime.showViveConnectUnavailable()
                    },
                    onDismissIssue = { runtime.dismissIssue() },
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
                    onStartTranslation = {
                        withPermissions {
                            lifecycleScope.launch {
                                runtime.start(this@MainActivity, StreamMode.AUDIO,
                                    if (pcEnabled) OffloadSettings(url, token, targetLanguage) else null,
                                    continuousTranslation = true, autoSpeak = true)
                            }
                        }
                    },
                    targetLanguage = targetLanguage,
                    onTargetLanguage = { targetLanguage = it },
                    onCapture = { runtime.captureHighlight() },
                    onAutomaticSpeech = { runtime.setAutomaticSpeech(it) },
                    onReplay = { runtime.replay(it) },
                    onHighlightUpdate = { item, text, confirmed, excluded ->
                        runtime.updateHighlight(item.id, text, confirmed, excluded)
                    },
                    onExport = {
                        val text = runtime.exportMeetingText()
                        if (text.isNotBlank()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }, "分享會議紀錄"))
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

