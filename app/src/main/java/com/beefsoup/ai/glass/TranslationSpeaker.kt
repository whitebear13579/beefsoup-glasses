package com.beefsoup.ai.glass

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Uses Android TTS rather than the HTC speech API that conflicts with streaming. */
class TranslationSpeaker(context: Context, private val onStatus: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val queue = TranslationQueue()
    private var ready = false
    private var automatic = false
    private var generation = 0L
    private var active: String? = null
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change < 0) main.post {
                val resume = automatic
                stop()
                automatic = resume
                onStatus("播報已暫停，其他 App 正在使用音訊")
            }
        }.build()
    private val tts = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            onStatus(if (ready) "語音已就緒" else "語音引擎無法啟動；仍可閱讀譯文")
            if (ready) drain()
        }
    }

    init {
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { if (id == active) onStatus("正在播報譯文") } }
            override fun onDone(id: String?) = complete(id, null)
            @Deprecated("Legacy callback") override fun onError(id: String?) = complete(id, "語音播放失敗")
            override fun onError(id: String?, code: Int) = complete(id, "語音播放失敗（$code）")
        })
    }

    fun begin(enabled: Boolean) {
        stop()
        queue.reset()
        automatic = enabled
        onStatus(if (enabled) "等待已完成的譯句" else "自動播報已關閉")
    }

    fun setAutomatic(enabled: Boolean) {
        automatic = enabled
        if (!enabled) stop()
        onStatus(if (enabled) "等待新的譯句" else "已靜音；逐字稿與翻譯繼續")
    }

    fun offer(item: TranslatedUtterance) {
        val offeredGeneration = generation
        main.post {
        if (!automatic || offeredGeneration != generation) return@post
        if (!queue.offer(item)) {
            onStatus("播報稍有延遲；完整譯文已保留，可稍後重播")
            return@post
        }
        drain()
    } }

    fun replay(item: TranslatedUtterance) { main.post {
        val resume = automatic
        stop()
        automatic = resume
        queue.offer(item.copy(id = "replay:${++generation}:${item.id}"))
        drain()
    } }

    fun stop() {
        automatic = false
        generation++
        active = null
        queue.clear()
        tts.stop()
        audio.abandonAudioFocusRequest(focus)
        onStatus("播報已停止")
    }

    private fun drain() {
        if (!ready || active != null) return
        val item = queue.next() ?: return
        val language = Locale.forLanguageTag(item.language)
        val voice = tts.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == language.language }
            ?.sortedByDescending { it.locale.country == language.country }?.firstOrNull()
        if (voice == null || tts.setLanguage(language) < 0) {
            queue.clear()
            onStatus("缺少${language.getDisplayLanguage(Locale.TAIWAN)}離線語音，請至系統語音設定安裝；譯文仍會保存")
            return
        }
        tts.voice = voice
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            queue.clear()
            onStatus("音訊正被使用；譯文仍會保存")
            return
        }
        val id = "$generation:${item.id}"
        active = id
        if (tts.speak(item.translation, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS)
            complete(id, "無法播放譯文")
    }

    private fun complete(id: String?, error: String?) { main.post {
        if (active != id || id == null) return@post
        active = null
        audio.abandonAudioFocusRequest(focus)
        onStatus(error ?: "等待下一句譯文")
        drain()
    } }
}
