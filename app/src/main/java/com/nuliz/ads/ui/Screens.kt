package com.nuliz.ads.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuliz.ads.BuildConfig
import com.nuliz.ads.ChatStore
import com.nuliz.ads.Prefs
import com.nuliz.ads.R
import com.nuliz.ads.Stats
import com.nuliz.ads.StatsState
import java.text.NumberFormat

private val C = NulizColors
private const val CHAT_TAB = 2
private val TABS = listOf(
    TabItem("Trang chủ", Lucide.ShieldCheck),
    TabItem("Nhật ký", Lucide.ScrollText),
    TabItem("NaNGPT", Lucide.MessageCircle),
    TabItem("Cài đặt", Lucide.Settings),
)
private val PAGE_PADDING = 20.dp

@Composable
fun NulizRoot(onToggle: () -> Unit) {
    val state by Stats.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab != CHAT_TAB) drawer = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(C.Background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(C.Violet.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width / 2, 0f),
                        radius = size.width,
                    )
                )
            },
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            if (tab == CHAT_TAB) ChatTopBar(onMenu = { drawer = true }) else Header()
            Box(Modifier.weight(1f)) {
                Crossfade(tab, label = "tab") {
                    when (it) {
                        0 -> HomeScreen(state, onToggle, onSeeAll = { tab = 1 })
                        1 -> LogScreen(state)
                        CHAT_TAB -> ChatScreen()
                        else -> SettingsScreen(onChangeKey = { ChatStore.clearKey(); tab = CHAT_TAB })
                    }
                }
            }
            val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            if (!(keyboardOpen && tab == CHAT_TAB)) TabBar(TABS, tab, onSelect = { tab = it })
        }
        if (tab == CHAT_TAB) ChatDrawer(open = drawer, onClose = { drawer = false })
    }
}

@Composable
private fun Header() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PAGE_PADDING, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Nuliz Ads", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("Chặn quảng cáo thông minh", color = C.Muted, fontSize = 12.sp)
        }
        Pill("v${BuildConfig.VERSION_NAME}")
    }
}

@Composable
private fun HomeScreen(state: StatsState, onToggle: () -> Unit, onSeeAll: () -> Unit) {
    val statusColor by animateColorAsState(if (state.running) C.Violet else C.Muted, label = "status")
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PAGE_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        PowerButton(state.running, onToggle)
        Text(
            if (state.running) "ĐANG BẢO VỆ" else "ĐÃ TẮT",
            color = statusColor, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.running) "Chạm nút để tắt" else "Chạm nút để bật chặn quảng cáo",
            color = C.Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(24.dp))

        CounterCard(state.total)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Quảng cáo", format(state.ads), C.Violet, Modifier.weight(1f))
            StatTile("Mã độc", format(state.malware), C.Danger, Modifier.weight(1f))
            StatTile("Tên miền chặn", compact(state.listSize), C.Indigo, Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Mới chặn", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (state.recent.isNotEmpty()) {
                Text("Xem tất cả", color = C.Violet, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onSeeAll))
            }
        }
        Spacer(Modifier.height(8.dp))
        Panel {
            if (state.recent.isEmpty()) {
                Empty()
            } else {
                state.recent.take(5).forEachIndexed { i, event ->
                    if (i > 0) Hairline()
                    EventRow(event)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LogScreen(state: StatsState) {
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_PADDING)) {
        Text("Nhật ký chặn", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("${state.recent.size} mục gần nhất", color = C.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        if (state.recent.isEmpty()) {
            Panel { Empty() }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.recent, key = { "${it.time}-${it.domain}" }) {
                    EventRow(it)
                    Hairline()
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onChangeKey: () -> Unit) {
    val chat by ChatStore.state.collectAsStateWithLifecycle()
    var notify by remember { mutableStateOf(Prefs.notifyEachBlock) }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        ConfirmDialog(
            title = "Xoá tất cả đoạn chat?",
            text = "Toàn bộ ${chat.chats.size} đoạn chat sẽ mất, không lấy lại được nha.",
            confirm = "Xoá hết",
            onConfirm = { confirmClear = false; ChatStore.clearAll() },
            onDismiss = { confirmClear = false },
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PAGE_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cài đặt", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Thông báo mỗi lần chặn", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Hiện tên miền vừa bị chặn", color = C.Muted, fontSize = 12.sp)
                }
                Switch(
                    checked = notify,
                    onCheckedChange = { notify = it; Prefs.notifyEachBlock = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = C.Violet,
                        uncheckedThumbColor = C.Muted,
                        uncheckedTrackColor = C.SurfaceHigh,
                        uncheckedBorderColor = C.Outline,
                    ),
                )
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Key NaNGPT", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(ChatStore.maskedKey() ?: "Chưa nhập key", color = C.Muted, fontSize = 12.sp)
                }
                Text(
                    if (chat.hasKey) "Đổi key" else "Nhập key",
                    color = C.Violet, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onChangeKey).padding(8.dp),
                )
            }
            if (chat.chats.isNotEmpty()) {
                Hairline()
                Text(
                    "Xoá tất cả đoạn chat",
                    color = C.Danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { confirmClear = true }.padding(vertical = 12.dp),
                )
            }
        }

        Panel {
            InfoRow("Phiên bản", "v${BuildConfig.VERSION_NAME}")
            Hairline()
            InfoRow("Số build", BuildConfig.VERSION_CODE.toString())
            Hairline()
            InfoRow("Cơ chế", "VPN cục bộ • lọc DNS")
        }

        Panel {
            Text("Để chặn hiệu quả nhất", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Vào Cài đặt Android → Mạng → DNS riêng tư và chọn Tắt (hoặc Tự động). " +
                    "Nếu DNS riêng tư được đặt cứng, truy vấn sẽ đi vòng qua Nuliz Ads.",
                color = C.Muted, fontSize = 13.sp,
            )
        }

        Text(
            "Đặt lại bộ đếm",
            color = C.Danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterHorizontally).clickable { Stats.reset() }.padding(12.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, color = C.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Empty() = Text(
    "Chưa có gì bị chặn. Bật bảo vệ rồi dùng điện thoại như bình thường — mọi quảng cáo bị chặn sẽ hiện ở đây.",
    color = C.Muted, fontSize = 13.sp,
)

private fun format(n: Long): String = NumberFormat.getIntegerInstance().format(n)

private fun compact(n: Int): String = when {
    n <= 0 -> "—"
    n >= 1000 -> "${n / 1000}K"
    else -> n.toString()
}
