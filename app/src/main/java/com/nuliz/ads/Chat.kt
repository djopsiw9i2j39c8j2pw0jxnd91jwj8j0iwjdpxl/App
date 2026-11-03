package com.nuliz.ads

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ExifInterface
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

enum class Kind { IMAGE, AUDIO, PDF, TEXT }

class Attachment(val id: Long, val name: String, val kind: Kind, val mime: String, val data: ByteArray?)

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val rating: Int = 0,
)

enum class Problem { NONE, KEY, RETRY }

data class ChatError(val message: String, val problem: Problem)

data class ChatMeta(val id: Long, val title: String, val updated: Long)

class Conversation(val id: Long, var title: String, var updated: Long, var messages: List<ChatMessage>)

/** Mốc thời gian để xoá hàng loạt đoạn chat theo lần cập nhật cuối. */
enum class ChatRange(val label: String, val short: String) {
    ALL("Tất cả đoạn chat", "Tất cả"),
    DAYS_30("30 ngày qua", "30 ngày"),
    DAYS_15("15 ngày qua", "15 ngày"),
    DAY_1("1 ngày qua", "1 ngày"),
    TODAY("Hôm nay", "Hôm nay");

    fun contains(time: Long): Boolean {
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        return when (this) {
            ALL -> true
            DAYS_30 -> time >= now - 30 * day
            DAYS_15 -> time >= now - 15 * day
            DAY_1 -> time >= now - day
            TODAY -> time >= startOfToday()
        }
    }
}

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

data class ChatState(
    val hasKey: Boolean = false,
    val chats: List<ChatMeta> = emptyList(),
    val currentId: Long? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pending: List<Attachment> = emptyList(),
    val busy: Boolean = false,
    val waiting: Boolean = false,
    val recording: Boolean = false,
    val loading: Boolean = false,
    val error: ChatError? = null,
)

object ChatStore {
    private const val MAX_FILE = 15 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 120_000
    private const val MAX_RECORD_MS = 120_000L
    private const val MAX_SAVED = 60
    private const val MAX_CHATS = 100
    private const val IMAGE_EDGE = 1280

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    var draft by mutableStateOf("")

    private var job: Job? = null
    private var recordJob: Job? = null
    private val convs = mutableListOf<Conversation>()
    private var epoch = 0
    private val marks = Regex("\\p{Mn}+")
    private val recorder = Recorder()
    private val ids = AtomicLong(System.nanoTime())

    private val textExtensions = setOf(
        "txt", "md", "csv", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "java", "py", "c", "cpp", "h",
        "cs", "go", "rs", "sh", "yml", "yaml", "toml", "ini", "log", "sql", "gradle", "properties", "tsv", "rtf",
    )

    fun init() {
        convs.clear()
        convs += readAll()
        val latest = convs.maxByOrNull { it.updated }
        _state.update {
            it.copy(hasKey = Prefs.apiKey != null, chats = metas(), currentId = latest?.id, messages = latest?.messages.orEmpty())
        }
    }

    fun saveKey(key: String) {
        Prefs.apiKey = key.trim()
        _state.update { it.copy(hasKey = true, error = null) }
    }

    fun clearKey() {
        stop()
        Prefs.apiKey = null
        _state.update { it.copy(hasKey = false, error = null) }
    }

    fun maskedKey(): String? = Prefs.apiKey?.let { if (it.length > 8) it.take(4) + "••••" + it.takeLast(4) else "••••" }

    /** Mở một đoạn chat trống. Đoạn đang mở được lưu lại, tạo xong tin đầu tiên mới có tên trong danh sách. */
    fun newChat() {
        val s = _state.value
        if (s.currentId == null && s.messages.isEmpty()) return
        leaveCurrent()
        _state.update { it.copy(messages = emptyList(), currentId = null, pending = emptyList(), error = null) }
    }

    fun open(id: Long) {
        if (id == _state.value.currentId) return
        val conv = convs.firstOrNull { it.id == id } ?: return
        leaveCurrent()
        _state.update { it.copy(messages = conv.messages, currentId = id, pending = emptyList(), error = null) }
    }

    fun deleteChats(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val current = _state.value.currentId
        if (current != null && current in ids) {
            epoch++
            job?.cancel()
            cancelRecording()
            Speaker.stop()
            _state.update {
                it.copy(messages = emptyList(), currentId = null, pending = emptyList(), busy = false, waiting = false, error = null)
            }
        }
        convs.removeAll { it.id in ids }
        _state.update { it.copy(chats = metas()) }
        write()
    }

    fun clearAll() = deleteChats(convs.map { it.id }.toSet())

    fun rename(id: Long, title: String) {
        val clean = title.trim().take(60)
        val conv = convs.firstOrNull { it.id == id } ?: return
        if (clean.isEmpty()) return
        conv.title = clean
        _state.update { it.copy(chats = metas()) }
        write()
    }

    /** Tìm theo tên và nội dung, không phân biệt hoa thường và dấu tiếng Việt. */
    fun search(query: String): Set<Long> {
        val q = fold(query)
        if (q.isEmpty()) return convs.map { it.id }.toSet()
        return convs.filter { c -> fold(c.title).contains(q) || c.messages.any { fold(it.text).contains(q) } }.map { it.id }.toSet()
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replace(marks, "").replace('đ', 'd').replace('Đ', 'D').lowercase()

    private fun leaveCurrent() {
        epoch++
        job?.cancel()
        cancelRecording()
        Speaker.stop()
        persist(touch = false)
        _state.update { it.copy(busy = false, waiting = false) }
    }

    private fun metas() = convs.sortedByDescending { it.updated }.map { ChatMeta(it.id, it.title, it.updated) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun showNote(message: String) = _state.update { it.copy(error = ChatError(message, Problem.NONE)) }

    /** Bấm lại đúng nút đang chọn thì bỏ đánh giá. [value]: 1 = thích, -1 = không thích. */
    fun rate(id: Long, value: Int) {
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.id == id) it.copy(rating = if (it.rating == value) 0 else value) else it })
        }
        persist(touch = false)
    }

    /** Gửi lại đúng tin này: bỏ mọi thứ phía sau rồi hỏi lại từ đó. */
    fun resend(id: Long) {
        val s = _state.value
        if (s.busy || s.recording) return
        val index = s.messages.indexOfFirst { it.id == id && it.fromUser }
        if (index < 0) return
        Speaker.stop()
        _state.update { it.copy(messages = it.messages.take(index + 1), error = null) }
        persist()
        request()
    }

    /** Sửa nội dung tin đã gửi rồi hỏi lại từ đó, các tin phía sau bị bỏ. */
    fun edit(id: Long, text: String) {
        val s = _state.value
        if (s.busy || s.recording) return
        val index = s.messages.indexOfFirst { it.id == id && it.fromUser }
        if (index < 0) return
        val old = s.messages[index]
        val clean = text.trim()
        if (clean.isEmpty() && old.attachments.isEmpty()) return
        Speaker.stop()
        val kept = s.messages.take(index) + old.copy(text = clean)
        convs.firstOrNull { it.id == s.currentId }?.let { conv ->
            if (index == 0 && conv.title == titleFor(s.messages)) conv.title = titleFor(kept)
        }
        _state.update { it.copy(messages = kept, error = null) }
        persist()
        request()
    }

    /** Xoá câu trả lời cuối của NaNGPT rồi hỏi lại. */
    fun regenerate() {
        Speaker.stop()
        retry()
    }

    fun send(text: String = draft) {
        val s = _state.value
        val clean = text.trim()
        if (s.busy || s.recording || s.loading || (clean.isEmpty() && s.pending.isEmpty())) return
        if (text === draft) draft = ""
        val message = ChatMessage(newId(), true, clean, s.pending)
        _state.update { it.copy(messages = it.messages + message, pending = emptyList(), error = null) }
        persist()
        request()
    }

    fun retry() {
        if (_state.value.busy) return
        _state.update { s -> s.copy(messages = s.messages.dropLastWhile { !it.fromUser }, error = null) }
        if (_state.value.messages.isNotEmpty()) request()
    }

    fun stop() {
        job?.cancel()
    }

    private fun request() {
        val key = Prefs.apiKey ?: return _state.update { it.copy(hasKey = false) }
        val reply = newId()
        val history = _state.value.messages
        _state.update { it.copy(busy = true, waiting = true) }
        val mine = epoch
        job = scope.launch {
            var started = false
            try {
                Gemini.stream(key, history).collect { piece ->
                    if (epoch != mine) return@collect
                    if (!started) {
                        started = true
                        _state.update { it.copy(waiting = false, messages = it.messages + ChatMessage(reply, false, piece)) }
                    } else {
                        _state.update { s ->
                            s.copy(messages = s.messages.map { if (it.id == reply) it.copy(text = it.text + piece) else it })
                        }
                    }
                }
                if (!started) fail("NaNGPT im re luôn, thử lại giúp tui nha.", Problem.RETRY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                coroutineContext.ensureActive()
                if (e.badKey) fail("Key bị từ chối rồi bro. Kiểm tra hoặc đổi key khác nha.", Problem.KEY)
                else fail(e.message ?: "Lỗi không rõ", Problem.RETRY)
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                fail("Mạng có vấn đề rồi, kiểm tra kết nối rồi thử lại nha.", Problem.RETRY)
            } finally {
                if (epoch == mine) {
                    _state.update { it.copy(busy = false, waiting = false) }
                    persist()
                }
            }
        }
    }

    private fun fail(message: String, problem: Problem) = _state.update { it.copy(error = ChatError(message, problem)) }

    fun attach(context: Context, uri: Uri, asImage: Boolean) {
        if (_state.value.loading || _state.value.pending.size >= 6) return
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { read(context.applicationContext, uri, asImage) } }
            result.onSuccess { a -> _state.update { it.copy(pending = it.pending + a, loading = false) } }
            result.onFailure { e ->
                _state.update { it.copy(loading = false, error = ChatError(e.message ?: "Không đọc được tệp này", Problem.NONE)) }
            }
        }
    }

    fun removePending(id: Long) = _state.update { s -> s.copy(pending = s.pending.filterNot { it.id == id }) }

    fun startRecording(): Boolean {
        if (_state.value.recording || _state.value.busy) return false
        if (!recorder.start()) {
            _state.update { it.copy(error = ChatError("Không mở được micro, thử lại nha.", Problem.NONE)) }
            return false
        }
        _state.update { it.copy(recording = true, error = null) }
        recordJob = scope.launch {
            delay(MAX_RECORD_MS)
            finishRecording(send = false)
        }
        return true
    }

    fun finishRecording(send: Boolean) {
        if (!_state.value.recording) return
        recordJob?.cancel()
        val wav = recorder.stop()
        _state.update { it.copy(recording = false) }
        if (wav == null) return
        val seconds = (wav.size - 44) / 32_000
        val clip = Attachment(newId(), "Ghi âm ${seconds / 60}:${"%02d".format(seconds % 60)}", Kind.AUDIO, "audio/wav", wav)
        _state.update { it.copy(pending = it.pending + clip) }
        if (send) send()
    }

    fun cancelRecording() {
        if (!_state.value.recording) return
        recordJob?.cancel()
        recorder.cancel()
        _state.update { it.copy(recording = false) }
    }

    private fun newId() = ids.incrementAndGet()

    private fun read(context: Context, uri: Uri, asImage: Boolean): Attachment {
        val resolver = context.contentResolver
        var name = "tệp"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) name = it.getString(0)
        }
        val mime = (resolver.getType(uri) ?: "").lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_FILE) throw IOException("Tệp lớn quá (tối đa 15 MB).")
            }
            out.toByteArray()
        } ?: throw IOException("Không mở được tệp này.")

        return when {
            asImage || mime.startsWith("image/") -> Attachment(newId(), name, Kind.IMAGE, "image/jpeg", compress(bytes))
            mime == "application/pdf" || ext == "pdf" -> Attachment(newId(), name, Kind.PDF, "application/pdf", bytes)
            mime.contains("mpeg") || mime.contains("mp3") || ext == "mp3" -> Attachment(newId(), name, Kind.AUDIO, "audio/mp3", bytes)
            mime.contains("wav") || ext == "wav" -> Attachment(newId(), name, Kind.AUDIO, "audio/wav", bytes)
            mime.startsWith("audio/") -> throw IOException("Âm thanh chỉ nhận .mp3 hoặc .wav thôi nha.")
            mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || ext in textExtensions -> {
                val text = String(bytes, Charsets.UTF_8).take(MAX_TEXT_CHARS)
                Attachment(newId(), name, Kind.TEXT, "text/plain", text.toByteArray())
            }
            else -> throw IOException("Chưa đọc được loại tệp này. Thử ảnh, PDF, văn bản hoặc mp3/wav nha.")
        }
    }

    private fun compress(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IOException("Ảnh này không đọc được.")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Ảnh này không đọc được.")
        val scale = IMAGE_EDGE.toFloat() / max(decoded.width, decoded.height)
        val matrix = Matrix()
        if (scale < 1f) matrix.postScale(scale, scale)
        val degrees = when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) matrix.postRotate(degrees)
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private fun persist(touch: Boolean = true) {
        val s = _state.value
        var id = s.currentId
        if (s.messages.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val conv = convs.firstOrNull { it.id == id }
            if (conv == null) {
                id = newConvId()
                convs += Conversation(id, titleFor(s.messages), now, s.messages)
            } else {
                conv.messages = s.messages
                if (touch) conv.updated = now
            }
        }
        if (convs.size > MAX_CHATS) {
            val drop = convs.sortedBy { it.updated }.filter { it.id != id }.take(convs.size - MAX_CHATS)
            convs.removeAll(drop.toSet())
        }
        _state.update { it.copy(currentId = id, chats = metas()) }
        write()
    }

    private fun newConvId(): Long {
        var id = System.currentTimeMillis()
        while (convs.any { it.id == id }) id++
        return id
    }

    private fun titleFor(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.fromUser } ?: return "Đoạn chat mới"
        val text = first.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().replace(Regex("\\s+"), " ")
        if (text.isNotEmpty()) return if (text.length > 40) text.take(40).trimEnd() + "…" else text
        val a = first.attachments.firstOrNull() ?: return "Đoạn chat mới"
        return when (a.kind) {
            Kind.IMAGE -> "Ảnh đính kèm"
            Kind.AUDIO -> "Ghi âm"
            else -> a.name.take(40)
        }
    }

    private fun write() {
        val array = JSONArray()
        convs.forEach { c ->
            val list = JSONArray()
            c.messages.takeLast(MAX_SAVED).forEach { m ->
                val files = JSONArray()
                m.attachments.forEach { files.put(JSONObject().put("n", it.name).put("k", it.kind.name)) }
                list.put(JSONObject().put("u", m.fromUser).put("t", m.text).put("a", files).put("r", m.rating))
            }
            array.put(JSONObject().put("i", c.id).put("n", c.title).put("u", c.updated).put("m", list))
        }
        Prefs.chatsJson = array.toString()
    }

    private fun readAll(): List<Conversation> = try {
        val saved = Prefs.chatsJson
        if (saved != null) {
            val array = JSONArray(saved)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Conversation(o.getLong("i"), o.getString("n"), o.getLong("u"), parseMessages(o.getJSONArray("m")))
            }
        } else {
            // Bản cũ chỉ có một cuộc trò chuyện duy nhất, chuyển nó thành một đoạn chat.
            val old = parseMessages(JSONArray(Prefs.chatJson ?: "[]"))
            Prefs.chatJson = null
            if (old.isEmpty()) emptyList() else listOf(Conversation(System.currentTimeMillis(), titleFor(old), System.currentTimeMillis(), old))
        }
    } catch (_: JSONException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    private fun parseMessages(array: JSONArray): List<ChatMessage> = List(array.length()) { i ->
        val o = array.getJSONObject(i)
        val files = o.optJSONArray("a") ?: JSONArray()
        val attachments = List(files.length()) { j ->
            val f = files.getJSONObject(j)
            Attachment(newId(), f.getString("n"), Kind.valueOf(f.getString("k")), "", null)
        }
        ChatMessage(newId(), o.getBoolean("u"), o.getString("t"), attachments, o.optInt("r", 0))
    }
}

private class Recorder {
    private companion object {
        const val RATE = 16_000
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var live = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val size = max(
            AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            8192,
        )
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (_: IllegalStateException) {
            r.release()
            return false
        }
        pcm.reset()
        record = r
        live = true
        thread = Thread {
            val buf = ByteArray(4096)
            while (live) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
        }.also { it.start() }
        return true
    }

    fun stop(): ByteArray? {
        release()
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.size < RATE / 2) return null
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(data.size)
        return header.array() + data
    }

    fun cancel() = release()

    private fun release() {
        live = false
        thread?.join()
        thread = null
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        record = null
    }
}
