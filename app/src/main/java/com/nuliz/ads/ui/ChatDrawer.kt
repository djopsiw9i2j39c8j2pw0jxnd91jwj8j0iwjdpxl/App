package com.nuliz.ads.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuliz.ads.ChatMeta
import com.nuliz.ads.ChatRange
import com.nuliz.ads.ChatStore
import com.nuliz.ads.startOfToday

private val C = NulizColors
private val AccentBrush = Brush.linearGradient(listOf(C.Violet, C.Indigo))

private data class DeleteAsk(val ids: Set<Long>, val title: String, val text: String)

/**
 * Ngăn kéo danh sách đoạn chat kiểu Claude: trượt từ trái, có tìm kiếm, xoá theo mốc thời gian
 * (dropdown + nút xoá bên cạnh) hoặc giữ một đoạn chat để chọn rồi xoá.
 */
@Composable
fun ChatDrawer(open: Boolean, onClose: () -> Unit) {
    val state by ChatStore.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var range by remember { mutableStateOf(ChatRange.ALL) }
    var rangeMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf<DeleteAsk?>(null) }

    LaunchedEffect(open) {
        if (!open) {
            selected = emptySet()
            query = ""
            rangeMenu = false
        }
    }
    BackHandler(enabled = open) { if (selected.isNotEmpty()) selected = emptySet() else onClose() }

    val visible = remember(state.chats, query) {
        if (query.isBlank()) state.chats else ChatStore.search(query).let { ids -> state.chats.filter { it.id in ids } }
    }
    val groups = remember(visible) { groupByDay(visible) }
    val selecting = selected.isNotEmpty()
    val inRange = state.chats.filter { range.contains(it.updated) }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(open, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.86f)
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                    .background(C.Surface)
                    .pointerInput(Unit) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total < -80f) onClose() },
                            onHorizontalDrag = { _, delta -> total += delta },
                        )
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(top = 10.dp),
            ) {
                if (selecting) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DrawerIcon(Lucide.Close, C.Text) { selected = emptySet() }
                        Text("Đã chọn ${selected.size}", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        val everything = visible.isNotEmpty() && visible.all { it.id in selected }
                        Text(
                            if (everything) "Bỏ chọn" else "Chọn hết",
                            color = C.Violet, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { selected = if (everything) emptySet() else visible.map { it.id }.toSet() }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                        DrawerIcon(Lucide.Trash, C.Danger) {
                            ask = DeleteAsk(selected, "Xoá ${selected.size} đoạn chat?", "Xoá rồi là mất luôn, không lấy lại được nha.")
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("NaNGPT", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                }

                Spacer(Modifier.size(10.dp))
                Row(
                    Modifier
                        .padding(horizontal = 14.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AccentBrush)
                        .clickable { ChatStore.newChat(); onClose() }
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.SquarePen, null, Modifier.size(18.dp), tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text("Đoạn chat mới", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.size(10.dp))
                Row(
                    Modifier
                        .padding(horizontal = 14.dp)
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(C.Background)
                        .border(1.dp, C.Outline, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Search, null, Modifier.size(18.dp), tint = C.Muted)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it.take(60) },
                        singleLine = true,
                        textStyle = TextStyle(color = C.Text, fontSize = 14.sp),
                        cursorBrush = SolidColor(C.Violet),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text("Tìm đoạn chat", color = C.Muted, fontSize = 14.sp)
                                inner()
                            }
                        },
                    )
                    if (query.isNotEmpty()) {
                        Icon(Lucide.Close, null, Modifier.size(18.dp).clickable { query = "" }, tint = C.Muted)
                    }
                }

                if (!selecting) {
                    Spacer(Modifier.size(8.dp))
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Đoạn chat · ${state.chats.size}", color = C.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Box {
                            Row(
                                Modifier
                                    .clip(CircleShape)
                                    .border(1.dp, C.Outline, CircleShape)
                                    .clickable { rangeMenu = true }
                                    .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(range.short, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(2.dp))
                                Icon(Lucide.ChevronDown, null, Modifier.size(16.dp), tint = C.Muted)
                            }
                            DropdownMenu(
                                expanded = rangeMenu,
                                onDismissRequest = { rangeMenu = false },
                                modifier = Modifier.background(C.SurfaceHigh),
                            ) {
                                ChatRange.values().forEach { r ->
                                    val n = state.chats.count { r.contains(it.updated) }
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(r.label, color = if (r == range) C.Violet else C.Text, fontSize = 14.sp)
                                                Spacer(Modifier.width(18.dp))
                                                Text(n.toString(), color = C.Muted, fontSize = 12.sp)
                                            }
                                        },
                                        onClick = { range = r; rangeMenu = false },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        val canDelete = inRange.isNotEmpty()
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (canDelete) C.Danger.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable(enabled = canDelete) {
                                    ask = DeleteAsk(
                                        inRange.map { it.id }.toSet(),
                                        "Xoá ${inRange.size} đoạn chat?",
                                        if (range == ChatRange.ALL) "Toàn bộ đoạn chat sẽ mất, không lấy lại được nha."
                                        else "Những đoạn chat cập nhật trong ${range.label.lowercase()} sẽ mất, không lấy lại được nha.",
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.Trash, null, Modifier.size(18.dp), tint = if (canDelete) C.Danger else C.Muted.copy(alpha = 0.4f))
                        }
                    }
                }

                if (visible.isEmpty()) {
                    Text(
                        if (query.isBlank()) "Chưa có đoạn chat nào.\nGửi một tin để bắt đầu nha." else "Không tìm thấy đoạn chat nào.",
                        color = C.Muted, fontSize = 13.sp, lineHeight = 19.sp,
                        modifier = Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 28.dp),
                    )
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        groups.forEach { (label, list) ->
                            item(key = "h$label") {
                                Text(label, color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 4.dp))
                            }
                            items(list, key = { it.id }) { chat ->
                                ChatRow(
                                    chat = chat,
                                    current = chat.id == state.currentId,
                                    selecting = selecting,
                                    checked = chat.id in selected,
                                    onTap = {
                                        if (selecting) selected = if (chat.id in selected) selected - chat.id else selected + chat.id
                                        else {
                                            ChatStore.open(chat.id)
                                            onClose()
                                        }
                                    },
                                    onLongPress = { selected = if (chat.id in selected) selected - chat.id else selected + chat.id },
                                )
                            }
                        }
                    }
                    if (!selecting) {
                        Text(
                            "Giữ một đoạn chat để chọn và xoá",
                            color = C.Muted.copy(alpha = 0.7f), fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    ask?.let { a ->
        ConfirmDialog(
            title = a.title,
            text = a.text,
            confirm = "Xoá",
            onConfirm = {
                ChatStore.deleteChats(a.ids)
                selected = selected - a.ids
                ask = null
            },
            onDismiss = { ask = null },
        )
    }
}

@Composable
private fun DrawerIcon(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
    }
}

@Composable
private fun ChatRow(
    chat: ChatMeta,
    current: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val background = when {
        checked -> C.Violet.copy(alpha = 0.18f)
        current && !selecting -> C.SurfaceHigh
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        long()
                    },
                )
            }
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .then(if (checked) Modifier.background(C.Violet) else Modifier.border(1.5.dp, C.Muted, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Icon(Lucide.Check, null, Modifier.size(14.dp), tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
        }
        Text(
            chat.title,
            color = C.Text,
            fontSize = 15.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun groupByDay(chats: List<ChatMeta>): List<Pair<String, List<ChatMeta>>> {
    val today = startOfToday()
    val day = 24L * 60 * 60 * 1000
    fun label(time: Long) = when {
        time >= today -> "Hôm nay"
        time >= today - day -> "Hôm qua"
        time >= today - 7 * day -> "7 ngày qua"
        time >= today - 30 * day -> "30 ngày qua"
        else -> "Cũ hơn"
    }
    return chats.groupBy { label(it.updated) }.toList()
}

@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.SurfaceHigh,
        title = { Text(title, color = C.Text, fontWeight = FontWeight.Bold) },
        text = { Text(text, color = C.Muted) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, color = C.Danger) } },
        dismissButton = { TextButton(onDismiss) { Text("Thôi", color = C.Muted) } },
    )
}

@Composable
internal fun RenameDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val shape = RoundedCornerShape(14.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.SurfaceHigh,
        title = { Text("Đổi tên đoạn chat", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Box(Modifier.fillMaxWidth().clip(shape).background(C.Background).border(1.dp, C.Outline, shape).padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(60) },
                    singleLine = true,
                    textStyle = TextStyle(color = C.Text, fontSize = 15.sp),
                    cursorBrush = SolidColor(C.Violet),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton({ onDone(text) }, enabled = text.isNotBlank()) { Text("Lưu", color = C.Violet) } },
        dismissButton = { TextButton(onDismiss) { Text("Thôi", color = C.Muted) } },
    )
}
