package com.momaninews.app.ui.components

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.scrollBy
import com.momaninews.app.data.model.NewsItem

/**
 * منطق الشريط (نقي وقابل للاختبار):
 *  - لا أخبار تجريبية ولا بلا تاريخ نشر؛
 *  - تظهر الخبر مدة قصيرة بحسب تاريخه ثم يختفي: 24 ساعة للعادي و48 ساعة للعاجل (وفاة/حادث)؛
 *  - الترتيب: العاجل أولاً ثم الأحدث، بلا تكرار عناوين، وبحد أقصى.
 */
object TickerLogic {
    const val MAX_ITEMS = 15
    const val MAX_TITLE = 90
    const val NORMAL_WINDOW_MS = 24L * 60 * 60 * 1000
    const val URGENT_WINDOW_MS = 48L * 60 * 60 * 1000
    private const val FUTURE_SLACK_MS = 5L * 60 * 1000

    fun isUrgent(n: NewsItem): Boolean = n.category == "death" || n.category == "incident"

    fun eligible(items: List<NewsItem>, nowMillis: Long): List<NewsItem> {
        fun clean(t: String) = t.trim().replace(Regex("\\s+"), " ").let { if (it.length > MAX_TITLE) it.take(MAX_TITLE - 1).trimEnd() + "…" else it }
        val live = items.asSequence()
            .filter { !it.isSample && it.publishedAtMillis > 0 && it.title.isNotBlank() }
            .filter {
                val age = nowMillis - it.publishedAtMillis
                age >= -FUTURE_SLACK_MS && age <= (if (isUrgent(it)) URGENT_WINDOW_MS else NORMAL_WINDOW_MS)
            }
            .distinctBy { it.id }
            .map { it.copy(title = clean(it.title)) }
            .toList()
        val (urgent, rest) = live.partition { isUrgent(it) }
        return (urgent.sortedByDescending { it.publishedAtMillis } + rest.sortedByDescending { it.publishedAtMillis })
            .distinctBy { it.title }
            .take(MAX_ITEMS)
    }

    /** عدد الأخبار الصالحة للشريط التي لم يفتحها المستخدم بعد (لشارة الجرس). */
    fun unreadCount(eligible: List<NewsItem>, read: Set<Int>): Int = eligible.count { it.id !in read }

    fun badgeText(count: Int): String? = when {
        count <= 0 -> null
        count > 9 -> "9+"
        else -> count.toString()
    }
}

private val UrgentRed = Color(0xFFC62828)
private val TickerSpeed = 36.dp   // معتدلة ومريحة للقراءة (بالثانية)

/**
 * شريط أخبار هادئ فوق الإعلان: وسم «عاجل» أحمر عند وجود عاجل، وعناوين تتحرك ببطء ومستمرة، والضغط على عنوان يفتح خبره.
 * يتوقف مؤقتاً أثناء لمس الشريط، وبزر إيقاف/تشغيل، وعند تعطيل حركات النظام (إمكانية الوصول). يختفي كلياً بلا أخبار صالحة.
 */
@Composable
fun NewsTicker(
    items: List<NewsItem>,
    read: Set<Int>,
    nowMillis: Long,
    onNewsClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val ctx = LocalContext.current
    val reduceMotion = remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    var paused by rememberSaveable { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val speedPx = with(LocalDensity.current) { TickerSpeed.toPx() }

    LaunchedEffect(items.size, paused, holding, reduceMotion) {
        if (paused || holding || reduceMotion) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            listState.scrollBy(speedPx * ((now - last) / 1_000_000_000f))
            last = now
        }
    }

    val hasUrgent = items.any { TickerLogic.isUrgent(it) }
    val blips = remember(items, read, nowMillis) { RadarLogic.blips(items, read, nowMillis) }
    var radarOpen by rememberSaveable { mutableStateOf(false) }
    if (radarOpen) RadarDialog(blips, nowMillis, onOpen = { radarOpen = false; onNewsClick(it) }, onDismiss = { radarOpen = false })
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.heightIn(min = 34.dp).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniRadar(blips, animate = !(paused || reduceMotion), onClick = { radarOpen = true })
            Box(
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (hasUrgent) UrgentRed else MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (hasUrgent) "عاجل" else "آخر الأخبار", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f).pointerInput(Unit) {
                    detectTapGestures(onPress = { holding = true; tryAwaitRelease(); holding = false })
                },
                userScrollEnabled = true
            ) {
                items(count = Int.MAX_VALUE) { index ->
                    val n = items[index % items.size]
                    val urgent = TickerLogic.isUrgent(n)
                    Text(
                        text = (if (urgent) "● " else "") + n.title,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        fontWeight = if (n.id in read) FontWeight.Normal else FontWeight.Medium,
                        color = if (urgent) UrgentRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (n.id in read) 0.7f else 0.95f),
                        modifier = Modifier
                            .clickable(role = Role.Button) { onNewsClick(n.id) }
                            .padding(end = 32.dp, top = 6.dp, bottom = 6.dp)
                    )
                }
            }
            Box(
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { paused = !paused },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (paused) Icons.Default.PlayArrow else AppIcons.Pause,
                    contentDescription = if (paused) "تشغيل حركة الشريط" else "إيقاف حركة الشريط مؤقتاً",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
