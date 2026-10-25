package com.momaninews.app.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.momaninews.app.data.model.NewsItem

/** حرف الأفاتار: أول حرف من اسم المصدر، أو «م» عند غيابه. */
internal fun avatarLetter(source: String): String =
    source.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "م"

/** نص المشاركة: العنوان + رابط صفحة المشاركة (أو رابط المصدر) + اسم التطبيق. */
internal fun shareBody(news: NewsItem, privacyUrl: String): String {
    val page = if (privacyUrl.startsWith("https://") && privacyUrl.endsWith("/privacy.html"))
        privacyUrl.removeSuffix("privacy.html") + "news.html?id=" + news.id else null
    return news.title + (page?.let { "\n" + it } ?: news.sourceUrl?.let { "\n" + it } ?: "") + "\n\nعبر تطبيق المومني نيوز"
}

/**
 * بطاقة خبر بنمط منشور فيسبوك: رأس (أفاتار المصدر + الاسم + التاريخ والمنطقة)، ثم النص،
 * ثم صورة بعرض كامل، ثم شريط إجراءات (موثوق؟ / تعليق / مشاركة). لا أرقام تفاعل مختلقة؛
 * التصويت والتعليق يتمّان في صفحة الخبر.
 */
@Composable
fun NewsCard(
    news: NewsItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    reactions: Map<com.momaninews.app.data.model.Reaction, Int>? = null
) {
    val ctx = LocalContext.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Column {
            // الرأس
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(avatarLetter(news.source), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = news.source.ifBlank { "المومني نيوز" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = news.dateLabel + " • " + news.region.nameAr +
                            (com.momaninews.app.data.model.NewsCategories.of(news.category)?.let { " • ${it.emoji} ${it.label}" } ?: "") +
                            (if (news.videoUrl != null) " • ▶ فيديو" else ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                NewsMenu(news)
            }

            // النص (يفتح الخبر)
            Column(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = news.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                if (news.excerpt.isNotBlank()) {
                    Text(
                        text = news.excerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // الصورة بعرض كامل (تفتح الخبر)
            Box(modifier = Modifier.padding(top = 8.dp).clickable(onClick = onClick)) { NewsImage(news) }

            com.momaninews.app.data.model.ReactionLine.of(reactions)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))

            // شريط الإجراءات
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                TextButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text("👍 موثوق؟") }
                TextButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text("💬 تعليق") }
                TextButton(
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, shareBody(news, com.momaninews.app.BuildConfig.PRIVACY_URL)),
                                    "مشاركة الخبر"
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("↗ مشاركة") }
            }
        }
    }
}
