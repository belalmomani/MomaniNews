@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.momaninews.app.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momaninews.app.data.model.NewsItem

/** منطق نصّ الشريط (نقي وقابل للاختبار): أحدث العناوين بلا تكرار، بطول محدود، مفصولة برمز هادئ. */
object TickerLogic {
    const val MAX_ITEMS = 15
    const val MAX_TITLE = 90
    private const val SEP = "   •   "

    fun text(items: List<NewsItem>): String? {
        val titles = items.sortedByDescending { it.id }
            .map { it.title.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .map { if (it.length > MAX_TITLE) it.take(MAX_TITLE - 1).trimEnd() + "…" else it }
            .distinct()
            .take(MAX_ITEMS)
        return if (titles.isEmpty()) null else titles.joinToString(SEP)
    }
}

/** شريط أخبار صغير وهادئ أسفل الشاشة (فوق الإعلان): خط صغير، لون خافت، حركة بطيئة مستمرة بلا وميض. */
@Composable
fun NewsTicker(items: List<NewsItem>, modifier: Modifier = Modifier) {
    val line = TickerLogic.text(items) ?: return
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(
            modifier = Modifier.heightIn(min = 26.dp).padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = line,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier
                    .semantics { contentDescription = "شريط آخر الأخبار" }
                    .basicMarquee(iterations = Int.MAX_VALUE, velocity = 22.dp)
            )
        }
    }
}
