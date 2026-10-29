package com.beefsoup.ai.glass

import kotlinx.coroutines.flow.MutableStateFlow

data class SdkDiagnostics(
    val connection: String = "尚未讀取",
    val cameraPermission: String = "尚未讀取",
    val microphonePermission: String = "尚未讀取",
    val preferredLocale: String = "尚未讀取",
    val sdkConnected: Boolean? = null,
    val audioStreaming: Boolean? = null,
    val videoStreaming: Boolean? = null,
    val transcribing: Boolean = false,
    val speaking: Boolean = false,
    val transcription: String = "",
    val events: List<String> = emptyList(),
)
internal val noSdkDiagnostics = MutableStateFlow(SdkDiagnostics())
