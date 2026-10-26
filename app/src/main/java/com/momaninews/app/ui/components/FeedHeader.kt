package com.momaninews.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.ui.screens.home.DrawerLogic

/** شريط «بم تفكر؟»: أفاتار المستخدم + حقل على شكل حبة + أيقونة المعرض، يفتح شاشة النشر. */
@Composable
fun ComposerEntry(displayName: String?, onClick: () -> Unit, modifier: Modifier = Modifier, onAvatarClick: (() -> Unit)? = null) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.clip(CircleShape).let { if (onAvatarClick != null) it.clickable(onClick = onAvatarClick) else it }) {
                Avatar(DrawerLogic.initial(displayName ?: ""), 40)
            }
            Box(
                modifier = Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(21.dp))
                    .clickable(onClick = onClick).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("بم تفكر؟ شارك خبراً من منطقتك", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.material3.Icon(
                imageVector = AppIcons.Photo, contentDescription = "إضافة صورة",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp).clickable(onClick = onClick)
            )
        }
    }
}

@Composable
private fun Avatar(letter: String, size: Int) {
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) { Text(letter, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
}

/**
 * «أبرز الأخبار» بنمط قصص فيسبوك: بطاقات رأسية بصورة الخبر وحلقة أفاتار المصدر وعنوانه فوق تدرّج داكن.
 * البطاقة الأولى «شارك خبراً» (مثل «إنشاء قصة»). الضغط على بطاقة خبر يفتحه.
 */
@Composable
fun FeaturedStrip(
    items: List<NewsItem>,
    onNewsClick: (Int) -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Column(modifier = Modifier.padding(top = 10.dp, bottom = 12.dp)) {
            Text(
                "آخر الأخبار", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "create") { CreateCard(onCreateClick) }
                items(items, key = { it.id }) { n -> StoryCard(n) { onNewsClick(n.id) } }
            }
        }
    }
}

private val CardW = 112.dp
private val CardH = 190.dp

@Composable
private fun CreateCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(CardW).height(CardH).clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) { Text("+", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineMedium) }
        Text("شارك خبراً", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StoryCard(news: NewsItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier.width(CardW).height(CardH).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onClick)
    ) {
        news.imageUrl?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC000000))
            )
        )
        // حلقة أفاتار المصدر
        Box(
            modifier = Modifier.padding(8.dp).size(36.dp).clip(CircleShape)
                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Text(DrawerLogic.initial(news.source), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Text(
            news.title,
            color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
        )
    }
}
