package com.nuliz.ads

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Đọc to câu trả lời bằng TextToSpeech của máy. Mỗi lúc chỉ đọc một tin. */
object Speaker {
    private const val LIMIT = 3500

    private var engine: TextToSpeech? = null
    private var ready = false
    private var queued: (() -> Unit)? = null

    private val _speaking = MutableStateFlow<Long?>(null)
    val speaking: StateFlow<Long?> = _speaking.asStateFlow()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (utteranceId?.endsWith("#end") == true) clear(utteranceId)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = clear(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = clear(utteranceId)
    }

    private fun clear(utteranceId: String?) {
        val id = utteranceId?.substringBefore('#')?.toLongOrNull() ?: return
        _speaking.compareAndSet(id, null)
    }

    @Synchronized
    fun toggle(context: Context, id: Long, text: String) {
        if (_speaking.value == id) {
            stop()
            return
        }
        stop()
        val chunks = chunks(speakable(text))
        if (chunks.isEmpty()) {
            ChatStore.showNote("Tin này toàn code nên tui không đọc được nha.")
            return
        }
        _speaking.value = id
        val job = { say(id, chunks) }
        when {
            engine == null -> {
                queued = job
                start(context.applicationContext)
            }
            ready -> job()
            else -> queued = job
        }
    }

    @Synchronized
    fun stop() {
        queued = null
        if (ready) engine?.stop()
        _speaking.value = null
    }

    private fun start(context: Context) {
        engine = TextToSpeech(context) { status -> onReady(status) }
    }

    @Synchronized
    private fun onReady(status: Int) {
        val e = engine
        if (status != TextToSpeech.SUCCESS || e == null) {
            e?.shutdown()
            engine = null
            ready = false
            queued = null
            _speaking.value = null
            ChatStore.showNote("Máy chưa có công cụ đọc giọng nói.")
            return
        }
        e.setOnUtteranceProgressListener(listener)
        val result = e.setLanguage(Locale.forLanguageTag("vi-VN"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            e.setLanguage(Locale.getDefault())
            ChatStore.showNote("Máy chưa có giọng tiếng Việt, tui đọc bằng giọng mặc định nha.")
        }
        ready = true
        queued?.invoke()
        queued = null
    }

    private fun say(id: Long, chunks: List<String>) {
        val e = engine ?: return
        chunks.forEachIndexed { i, chunk ->
            val tag = if (i == chunks.lastIndex) "$id#$i#end" else "$id#$i"
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (e.speak(chunk, mode, null, tag) == TextToSpeech.ERROR) {
                _speaking.compareAndSet(id, null)
                return
            }
        }
    }

    /** Bỏ khối code và ký hiệu markdown để giọng đọc không đọc "sao sao thăng". */
    private fun speakable(text: String): String {
        val prose = text.split("```").filterIndexed { i, _ -> i % 2 == 0 }.joinToString("\n")
        return prose
            .replace(Regex("[*#`>]+"), "")
            .replace(Regex("(?m)^\\s*-\\s+"), "")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
    }

    private fun chunks(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.lines()) {
            var rest = line
            while (rest.length > LIMIT) {
                if (current.isNotEmpty()) {
                    out += current.toString()
                    current.clear()
                }
                out += rest.take(LIMIT)
                rest = rest.drop(LIMIT)
            }
            if (current.length + rest.length + 1 > LIMIT) {
                out += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(rest)
        }
        if (current.isNotBlank()) out += current.toString()
        return out.filter { it.isNotBlank() }
    }
}
