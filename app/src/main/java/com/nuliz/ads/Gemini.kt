package com.nuliz.ads

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : IOException(message) {
    val badKey get() = code == 401 || code == 403 || (code == 400 && message.orEmpty().contains("key", ignoreCase = true))
}

object Gemini {
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    private const val MODEL = "gemini-3.6-flash"
    private const val MAX_HISTORY = 30
    private const val MAX_MEDIA_MESSAGES = 2

    fun stream(key: String, history: List<ChatMessage>): Flow<String> = flow {
        val body = JSONObject()
            .put("model", MODEL)
            .put("stream", true)
            .put("messages", messages(history))
            .toString()
            .toByteArray()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        val watcher = currentCoroutineContext()[Job]?.invokeOnCompletion { conn.disconnect() }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw ApiException(code, errorMessage(conn.errorStream?.bufferedReader()?.use { it.readText() }, code))
            }
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = try {
                        JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    } catch (_: JSONException) {
                        null
                    }
                    if (delta != null && !delta.isNull("content")) {
                        val piece = delta.optString("content")
                        if (piece.isNotEmpty()) emit(piece)
                    }
                }
            }
        } finally {
            watcher?.dispose()
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun messages(history: List<ChatMessage>): JSONArray {
        val out = JSONArray().put(JSONObject().put("role", "system").put("content", Persona.prompt()))
        val recent = history.filter { it.text.isNotBlank() || it.attachments.isNotEmpty() }.takeLast(MAX_HISTORY)
        val withMedia = recent.filter { it.fromUser && it.attachments.any { a -> a.data != null } }
            .takeLast(MAX_MEDIA_MESSAGES).map { it.id }.toSet()
        for (m in recent) {
            if (!m.fromUser) {
                if (m.text.isNotBlank()) out.put(JSONObject().put("role", "assistant").put("content", m.text))
                continue
            }
            out.put(JSONObject().put("role", "user").put("content", userContent(m, m.id in withMedia)))
        }
        return out
    }

    private fun userContent(m: ChatMessage, withMedia: Boolean): JSONArray {
        val parts = JSONArray()
        val text = StringBuilder(m.text)
        val media = JSONArray()
        for (a in m.attachments) {
            val data = a.data
            if (!withMedia || data == null) {
                text.append("\n[Đã đính kèm: ").append(a.name).append("]")
                continue
            }
            when (a.kind) {
                Kind.TEXT -> text.append("\n\n[Tệp: ").append(a.name).append("]\n").append(String(data, Charsets.UTF_8))
                Kind.IMAGE, Kind.PDF -> media.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url", JSONObject().put("url", "data:${a.mime};base64,${b64(data)}")
                    )
                )
                Kind.AUDIO -> media.put(
                    JSONObject().put("type", "input_audio").put(
                        "input_audio", JSONObject().put("data", b64(data)).put("format", if (a.mime.contains("mp3") || a.mime.contains("mpeg")) "mp3" else "wav")
                    )
                )
            }
        }
        if (text.isBlank()) text.append(blankPrompt(m.attachments))
        parts.put(JSONObject().put("type", "text").put("text", text.toString()))
        for (i in 0 until media.length()) parts.put(media.get(i))
        return parts
    }

    private fun blankPrompt(list: List<Attachment>): String = when {
        list.any { it.kind == Kind.AUDIO } -> "Nghe đoạn âm thanh này rồi trả lời mình nhé."
        list.any { it.kind == Kind.IMAGE } -> "Xem ảnh này giúp mình nhé."
        else -> "Xem tệp này giúp mình nhé."
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun errorMessage(body: String?, code: Int): String {
        val raw = body.orEmpty().trim()
        val message = try {
            val root = if (raw.startsWith("[")) JSONArray(raw).optJSONObject(0) else JSONObject(raw)
            root?.optJSONObject("error")?.optString("message")
        } catch (_: JSONException) {
            null
        }
        return message?.takeIf { it.isNotBlank() } ?: "Lỗi $code"
    }
}
