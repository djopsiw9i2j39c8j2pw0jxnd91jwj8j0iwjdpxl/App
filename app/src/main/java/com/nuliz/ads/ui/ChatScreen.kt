package com.nuliz.ads.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuliz.ads.Attachment
import com.nuliz.ads.ChatError
import com.nuliz.ads.ChatMessage
import com.nuliz.ads.ChatState
import com.nuliz.ads.ChatStore
import com.nuliz.ads.Kind
import com.nuliz.ads.Persona
import com.nuliz.ads.Problem
import com.nuliz.ads.R
import com.nuliz.ads.Speaker
import kotlinx.coroutines.delay
import java.io.File

private val C = NulizColors
private val MineBrush = Brush.linearGradient(listOf(C.Violet, C.Indigo))
private const val KEY_URL = "https://aistudio.google.com/apikey"
private val SUGGESTIONS = listOf(
    "Nuliz Ads dùng sao vậy?",
    "Sao tui vẫn thấy quảng cáo?",
    "Kể tui nghe chuyện cười đi",
    "Cuối tuần này làm gì cho đỡ chán?",
)

@Composable
fun ChatTopBar(onMenu: () -> Unit) {
    val state by ChatStore.state.collectAsStateWithLifecycle()
    var more by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val current = state.chats.firstOrNull { it.id == state.currentId }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Lucide.AlignLeft, onMenu)
        Row(
            Modifier.weight(1f).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Avatar(28.dp)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    current?.title ?: "Đoạn chat mới",
                    color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(if (state.waiting) "đang gõ..." else Persona.NAME, color = C.Muted, fontSize = 11.sp)
            }
        }
        BarButton(Lucide.SquarePen) { ChatStore.newChat() }
        if (current != null) {
            Box {
                BarButton(Lucide.MoreVertical) { more = true }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }, modifier = Modifier.background(C.SurfaceHigh)) {
                    DropdownMenuItem(
                        text = { Text("Đổi tên", color = C.Text) },
                        leadingIcon = { Icon(Lucide.Pencil, null, Modifier.size(18.dp), tint = C.Text) },
                        onClick = { more = false; rename = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Xoá đoạn chat", color = C.Danger) },
                        leadingIcon = { Icon(Lucide.Trash, null, Modifier.size(18.dp), tint = C.Danger) },
                        onClick = { more = false; confirm = true },
                    )
                }
            }
        } else {
            Spacer(Modifier.size(42.dp))
        }
    }

    if (rename && current != null) {
        RenameDialog(current.title, onDone = { ChatStore.rename(current.id, it); rename = false }, onDismiss = { rename = false })
    }
    if (confirm && current != null) {
        ConfirmDialog(
            title = "Xoá đoạn chat này?",
            text = "“${current.title}” sẽ mất luôn, không lấy lại được nha.",
            confirm = "Xoá",
            onConfirm = { confirm = false; ChatStore.deleteChats(setOf(current.id)) },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun BarButton(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = C.Text)
    }
}

@Composable
fun ChatScreen() {
    val state by ChatStore.state.collectAsStateWithLifecycle()
    if (!state.hasKey) {
        KeyGate()
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            key(state.currentId) {
                if (state.messages.isEmpty() && !state.waiting) EmptyChat() else MessageList(state)
            }
        }
        state.error?.let { ErrorBanner(it) }
        Composer(state)
    }
}

@Composable
private fun MessageList(state: ChatState) {
    val listState = rememberLazyListState()
    val newestFirst = remember(state.messages) { state.messages.asReversed() }
    LaunchedEffect(state.messages.size, state.waiting) { listState.animateScrollToItem(0) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        if (state.waiting) item(key = "typing") { TypingBubble() }
        val lastId = state.messages.lastOrNull()?.id
        itemsIndexed(newestFirst, key = { _, m -> m.id }) { i, m ->
            val streaming = state.busy && m.id == lastId
            Bubble(
                m,
                showActions = !m.fromUser && !streaming && m.text.isNotBlank(),
                canRegenerate = !state.busy && m.id == lastId,
                canEdit = m.fromUser && !state.busy && !state.recording,
                laterCount = i,
            )
        }
    }
}

@Composable
private fun EmptyChat() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 18.dp), contentAlignment = Alignment.Center) { Avatar(72.dp) }
        Row(verticalAlignment = Alignment.Bottom) {
            Avatar(28.dp)
            Spacer(Modifier.size(8.dp))
            Text(
                "ê chào bro =)) tui là NaNGPT nè. hỏi gì cũng được, từ cách xài Nuliz Ads tới chuyện đời luôn á",
                color = C.Text,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                    .background(C.SurfaceHigh)
                    .border(1.dp, C.Outline, RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("Thử hỏi tui nè", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 36.dp))
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SUGGESTIONS.forEach {
                Text(
                    it,
                    color = C.Text,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.dp, C.Outline, CircleShape)
                        .background(C.Surface)
                        .clickable { ChatStore.send(it) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
internal fun Avatar(size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(C.Background).border(1.dp, C.Outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ai_logo), null, Modifier.size(size * 0.68f))
    }
}

@Composable
private fun Bubble(m: ChatMessage, showActions: Boolean, canRegenerate: Boolean, canEdit: Boolean, laterCount: Int) {
    val mine = m.fromUser
    val shape = RoundedCornerShape(20.dp, 20.dp, if (mine) 6.dp else 20.dp, if (mine) 20.dp else 6.dp)
    var editing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Sửa hoặc thử lại một tin cũ sẽ xoá các tin phía sau, nên hỏi lại cho chắc.
    fun guarded(action: () -> Unit) {
        if (laterCount >= 2) confirm = action else action()
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (mine && editing) {
            EditBox(
                m,
                onCancel = { editing = false },
                onSend = { text -> editing = false; guarded { ChatStore.edit(m.id, text) } },
            )
        } else Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!mine) {
                Avatar(28.dp)
                Spacer(Modifier.size(8.dp))
            }
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(shape)
                    .then(if (mine) Modifier.background(MineBrush) else Modifier.background(C.SurfaceHigh).border(1.dp, C.Outline, shape))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                m.attachments.forEach { Attached(it) }
                if (m.text.isNotBlank()) SelectionContainer { Markdown(m.text) }
            }
        }
        if (showActions) MessageActions(m, canRegenerate)
        if (mine && canEdit && !editing) {
            UserActions(m, onEdit = { editing = true }, onRetry = { guarded { ChatStore.resend(m.id) } })
        }
    }
    confirm?.let { action ->
        ConfirmDialog(
            title = "Làm lại từ tin này?",
            text = "Những tin phía sau sẽ bị xoá, không lấy lại được nha.",
            confirm = "Tiếp tục",
            onConfirm = { confirm = null; action() },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun UserActions(m: ChatMessage, onEdit: () -> Unit, onRetry: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (m.text.isNotBlank()) {
            ActionIcon(if (copied) Lucide.Check else Lucide.Copy, if (copied) C.Online else C.Muted) {
                clipboard.setText(AnnotatedString(m.text))
                copied = true
            }
        }
        ActionIcon(Lucide.Pencil, C.Muted, onEdit)
        ActionIcon(Lucide.RotateCcw, C.Muted, onRetry)
    }
}

@Composable
private fun EditBox(m: ChatMessage, onCancel: () -> Unit, onSend: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(m.text, TextRange(m.text.length))) }
    val focus = remember { FocusRequester() }
    val shape = RoundedCornerShape(20.dp)
    val canSend = value.text.isNotBlank() || m.attachments.isNotEmpty()
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(C.SurfaceHigh)
            .border(1.dp, C.Violet.copy(alpha = 0.6f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        m.attachments.forEach { Attached(it) }
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            textStyle = TextStyle(color = C.Text, fontSize = 15.sp, lineHeight = 21.sp),
            cursorBrush = SolidColor(C.Violet),
            maxLines = 8,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).focusRequester(focus),
        )
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Huỷ", color = C.Muted, fontSize = 14.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(horizontal = 14.dp, vertical = 9.dp),
            )
            Text(
                "Gửi lại", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .alpha(if (canSend) 1f else 0.4f)
                    .clip(CircleShape)
                    .background(MineBrush)
                    .clickable(enabled = canSend) { onSend(value.text) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun MessageActions(m: ChatMessage, canRegenerate: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val speaking by Speaker.speaking.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val reading = speaking == m.id
    Row(Modifier.padding(start = 30.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ActionIcon(if (copied) Lucide.Check else Lucide.Copy, if (copied) C.Online else C.Muted) {
            clipboard.setText(AnnotatedString(m.text))
            copied = true
        }
        ActionIcon(Lucide.Share, C.Muted) { shareText(context, m.text) }
        ActionIcon(if (reading) Lucide.Square else Lucide.Play, if (reading) C.Violet else C.Muted) {
            Speaker.toggle(context, m.id, m.text)
        }
        ActionIcon(Lucide.ThumbsUp, if (m.rating > 0) C.Violet else C.Muted) { ChatStore.rate(m.id, 1) }
        ActionIcon(Lucide.ThumbsDown, if (m.rating < 0) C.Violet else C.Muted) { ChatStore.rate(m.id, -1) }
        if (canRegenerate) ActionIcon(Lucide.RotateCcw, C.Muted) { ChatStore.regenerate() }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (_: ActivityNotFoundException) {
        ChatStore.showNote("Máy không có app nào để chia sẻ.")
    }
}

@Composable
private fun Attached(a: Attachment) {
    val bitmap = remember(a.id) { thumbnail(a, 2) }
    if (bitmap != null) {
        Image(bitmap, null, Modifier.size(180.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
    } else {
        FileChip(a, null)
    }
}

private fun thumbnail(a: Attachment, sample: Int) = a.data?.takeIf { a.kind == Kind.IMAGE }?.let {
    BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@Composable
private fun FileChip(a: Attachment, onRemove: (() -> Unit)?) {
    val icon = when (a.kind) {
        Kind.IMAGE -> Lucide.Image
        Kind.AUDIO -> Lucide.Mic
        else -> Lucide.FileText
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = C.Text)
        Spacer(Modifier.size(6.dp))
        Text(a.name, color = C.Text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.widthIn(max = 160.dp))
        if (onRemove != null) {
            Spacer(Modifier.size(8.dp))
            Icon(Lucide.Close, null, Modifier.size(16.dp).clickable(onClick = onRemove), tint = C.Muted)
        }
    }
}

@Composable
private fun TypingBubble() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.Bottom) {
        Avatar(28.dp)
        Spacer(Modifier.size(8.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .background(C.SurfaceHigh)
                .border(1.dp, C.Outline, RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { i ->
                val a by transition.animateFloat(
                    0.25f, 1f,
                    infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse, StartOffset(i * 160)),
                    label = "dot$i",
                )
                Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(C.Muted))
            }
        }
    }
}

@Composable
private fun Markdown(text: String) {
    val parts = remember(text) { text.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val hasLang = part.contains('\n') && LANG_TAG.matches(part.substringBefore('\n').trim())
                val lang = if (hasLang) part.substringBefore('\n').trim() else ""
                CodeBlock(lang, (if (hasLang) part.substringAfter('\n') else part).trim('\n').trimEnd())
            } else if (part.isNotBlank()) {
                Text(styled(part.trim('\n')), color = C.Text, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val tint = if (copied) C.Online else C.Muted
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(C.Background.copy(alpha = 0.6f))
            .border(1.dp, C.Outline, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().background(C.Surface).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(languageLabel(lang), color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (copied) Lucide.Check else Lucide.Copy, null, Modifier.size(14.dp), tint = tint)
                Spacer(Modifier.size(5.dp))
                Text(if (copied) "Đã chép" else "Sao chép", color = tint, fontSize = 12.sp)
            }
        }
        Text(
            code,
            color = C.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
            softWrap = false,
        )
    }
}

private val LANG_TAG = Regex("[A-Za-z0-9_+#.\\-]{0,20}")

private val LANG_NAMES = mapOf(
    "js" to "JavaScript", "javascript" to "JavaScript", "ts" to "TypeScript", "typescript" to "TypeScript",
    "py" to "Python", "python" to "Python", "kt" to "Kotlin", "kotlin" to "Kotlin", "java" to "Java",
    "lua" to "Lua", "luau" to "Luau", "c" to "C", "cpp" to "C++", "c++" to "C++", "cs" to "C#", "csharp" to "C#",
    "go" to "Go", "rs" to "Rust", "rust" to "Rust", "swift" to "Swift", "php" to "PHP", "rb" to "Ruby", "ruby" to "Ruby",
    "sh" to "Bash", "bash" to "Bash", "shell" to "Bash", "zsh" to "Bash", "ps1" to "PowerShell", "powershell" to "PowerShell",
    "json" to "JSON", "html" to "HTML", "css" to "CSS", "xml" to "XML", "sql" to "SQL", "yml" to "YAML", "yaml" to "YAML",
    "toml" to "TOML", "md" to "Markdown", "markdown" to "Markdown", "gradle" to "Gradle", "kts" to "Kotlin",
    "text" to "Text", "txt" to "Text", "plaintext" to "Text", "plain" to "Text", "log" to "Log",
)

private fun languageLabel(lang: String): String {
    if (lang.isBlank()) return "Code"
    return LANG_NAMES[lang.lowercase()] ?: lang.replaceFirstChar { it.uppercase() }
}

private fun styled(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { index, raw ->
        if (index > 0) append('\n')
        var line = raw
        val heading = line.trimStart().startsWith("#")
        if (heading) {
            line = line.trimStart().trimStart('#').trim()
        } else if (line.startsWith("- ") || line.startsWith("* ")) {
            line = "• " + line.drop(2)
        }
        val start = length
        var i = 0
        while (i < line.length) {
            val bold = line.startsWith("**", i)
            val end = if (bold) line.indexOf("**", i + 2) else if (line[i] == '`') line.indexOf('`', i + 1) else -1
            if (end > 0) {
                val skip = if (bold) 2 else 1
                val style = if (bold) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontFamily = FontFamily.Monospace, background = C.Background.copy(alpha = 0.5f))
                withStyle(style) { append(line.substring(i + skip, end)) }
                i = end + skip
            } else {
                append(line[i])
                i++
            }
        }
        if (heading) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
    }
}

@Composable
private fun ErrorBanner(error: ChatError) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(C.Danger.copy(alpha = 0.12f))
            .border(1.dp, C.Danger.copy(alpha = 0.4f), shape)
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(error.message, color = C.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        when (error.problem) {
            Problem.KEY -> TextButton({ ChatStore.clearKey() }) { Text("Đổi key", color = C.Danger) }
            Problem.RETRY -> TextButton({ ChatStore.retry() }) {
                Icon(Lucide.RotateCcw, null, Modifier.size(16.dp), tint = C.Danger)
                Spacer(Modifier.size(6.dp))
                Text("Thử lại", color = C.Danger)
            }
            Problem.NONE -> Unit
        }
        Icon(Lucide.Close, null, Modifier.clickable { ChatStore.dismissError() }.padding(8.dp).size(18.dp), tint = C.Muted)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, tint: Color, background: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(background).border(1.dp, C.Outline, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
    }
}

@Composable
private fun Composer(state: ChatState) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (ok && uri != null) ChatStore.attach(context, uri, true)
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { ChatStore.attach(context, it, true) }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { ChatStore.attach(context, it, false) }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ChatStore.startRecording()
    }
    val record = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) ChatStore.startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val canSend = (ChatStore.draft.isNotBlank() || state.pending.isNotEmpty()) && !state.loading

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.pending.isNotEmpty() || state.loading) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                state.pending.forEach { Pending(it) }
                if (state.loading) Text("đang đọc tệp...", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
        }
        AnimatedVisibility(menu && !state.recording) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuPill("Chụp ảnh", Lucide.Camera) {
                    menu = false
                    val uri = newCameraUri(context)
                    cameraUri = uri
                    try {
                        camera.launch(uri)
                    } catch (_: ActivityNotFoundException) {
                        cameraUri = null
                        ChatStore.showNote("Máy không có app camera.")
                    }
                }
                MenuPill("Ảnh", Lucide.Image) { menu = false; photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                MenuPill("Tệp", Lucide.FileText) { menu = false; file.launch(arrayOf("*/*")) }
            }
        }
        if (state.recording) {
            RecordingBar()
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundButton(if (menu) Lucide.Close else Lucide.Paperclip, C.Text, C.Surface) { menu = !menu }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(C.Surface)
                        .border(1.dp, C.Outline, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = ChatStore.draft,
                        onValueChange = { ChatStore.draft = it },
                        textStyle = TextStyle(color = C.Text, fontSize = 15.sp, lineHeight = 21.sp),
                        cursorBrush = SolidColor(C.Violet),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (ChatStore.draft.isEmpty()) Text("Nhắn gì đó đi bro...", color = C.Muted, fontSize = 15.sp)
                                inner()
                            }
                        },
                    )
                }
                when {
                    state.busy -> ActionButton(Lucide.Square, Brush.linearGradient(listOf(C.Danger, C.Danger))) { ChatStore.stop() }
                    canSend -> ActionButton(Lucide.Send, MineBrush) { menu = false; ChatStore.send() }
                    else -> RoundButton(Lucide.Mic, C.Text, C.Surface) { menu = false; record() }
                }
            }
        }
    }
}

private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val stale = System.currentTimeMillis() - 60 * 60 * 1000
    dir.listFiles()?.forEach { if (it.lastModified() < stale) it.delete() }
    val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
private fun Pending(a: Attachment) {
    val bitmap = remember(a.id) { thumbnail(a, 4) }
    if (bitmap != null) {
        Box {
            Image(bitmap, null, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
            Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f))
                    .clickable { ChatStore.removePending(a.id) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Close, null, Modifier.size(12.dp), tint = Color.White)
            }
        }
    } else {
        FileChip(a) { ChatStore.removePending(a.id) }
    }
}

@Composable
private fun MenuPill(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(C.Surface)
            .border(1.dp, C.Outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = C.Violet)
        Spacer(Modifier.size(8.dp))
        Text(label, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionButton(icon: ImageVector, brush: Brush, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(brush).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = Color.White)
    }
}

@Composable
private fun RecordingBar() {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            seconds = ((System.currentTimeMillis() - start) / 1000).toInt()
            delay(250)
        }
    }
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundButton(Lucide.Close, C.Muted, C.Surface) { ChatStore.cancelRecording() }
        Row(
            Modifier
                .weight(1f)
                .height(44.dp)
                .clip(CircleShape)
                .background(C.Surface)
                .border(1.dp, C.Outline, CircleShape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(C.Danger))
            Spacer(Modifier.size(10.dp))
            Text("%d:%02d".format(seconds / 60, seconds % 60), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(10.dp))
            Text("đang nghe bro nói...", color = C.Muted, fontSize = 14.sp)
        }
        ActionButton(Lucide.Send, MineBrush) { ChatStore.finishRecording(send = true) }
    }
}

@Composable
private fun KeyGate() {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var key by rememberSaveable { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val valid = key.trim().length >= 20

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(C.Brand), contentAlignment = Alignment.Center) {
                Icon(Lucide.KeyRound, null, Modifier.size(24.dp), tint = Color.White)
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text("Khoan, thiếu cái key =))", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("Có key tui mới nói chuyện được nè", color = C.Muted, fontSize = 13.sp)
            }
        }

        Panel {
            Text("3 bước, tầm 1 phút thôi", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Step(1, "Bấm nút bên dưới để mở trang tạo key, đăng nhập tài khoản Google nếu được hỏi.")
            Spacer(Modifier.height(10.dp))
            Step(2, "Bấm Create API key rồi sao chép dãy key vừa tạo.")
            Spacer(Modifier.height(10.dp))
            Step(3, "Quay lại app, dán key vào ô bên dưới rồi bấm Lưu.")
        }

        WideButton("Mở trang tạo key", Lucide.ExternalLink, true) {
            try {
                uri.openUri(KEY_URL)
            } catch (_: IllegalArgumentException) {
            }
        }

        Panel {
            Text("Dán key vào đây", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(C.Background)
                    .border(1.dp, C.Outline, RoundedCornerShape(16.dp))
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = key,
                    onValueChange = { key = it.filterNot(Char::isWhitespace) },
                    singleLine = true,
                    textStyle = TextStyle(color = C.Text, fontSize = 15.sp),
                    cursorBrush = SolidColor(C.Violet),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (key.isEmpty()) Text("AIza...", color = C.Muted, fontSize = 15.sp)
                            inner()
                        }
                    },
                )
                Icon(
                    Lucide.ClipboardPaste, null,
                    Modifier.clickable { clipboard.getText()?.text?.let { key = it.filterNot(Char::isWhitespace) } }.padding(10.dp).size(20.dp),
                    tint = C.Muted,
                )
                Icon(
                    if (visible) Lucide.EyeOff else Lucide.Eye, null,
                    Modifier.clickable { visible = !visible }.padding(10.dp).size(20.dp),
                    tint = C.Muted,
                )
            }
        }

        WideButton("Lưu & bắt đầu chat", Lucide.Send, valid) { ChatStore.saveKey(key) }

        Text(
            "Key chỉ lưu trong máy bạn. Tin nhắn được gửi thẳng tới dịch vụ AI để trả lời, không đi qua server nào khác.",
            color = C.Muted, fontSize = 12.sp, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(C.Brand), contentAlignment = Alignment.Center) {
            Text(number.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(12.dp))
        Text(text, color = C.Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WideButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(18.dp))
            .background(MineBrush)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
        Spacer(Modifier.size(10.dp))
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
