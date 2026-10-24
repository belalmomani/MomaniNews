package com.momaninews.app.ui.screens.saved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.repository.NewsRepositories
import com.momaninews.app.data.settings.PostPrefs
import com.momaninews.app.ui.components.MessageState
import com.momaninews.app.ui.components.NewsCard
import com.momaninews.app.ui.components.ScreenScaffold
import kotlinx.coroutines.CancellationException

/** الأخبار المحفوظة على هذا الجهاز (الأحدث حفظاً أولاً). الخبر الذي لم يعد متاحاً يُتجاوز بصمت. */
@Composable
fun SavedScreen(onBack: () -> Unit, onNewsClick: (Int) -> Unit) {
    val savedIds by PostPrefs.saved.collectAsStateWithLifecycle()
    var loaded by remember { mutableStateOf<Map<Int, NewsItem>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    val repo = remember { NewsRepositories.default }

    LaunchedEffect(Unit) {
        val got = LinkedHashMap<Int, NewsItem>()
        for (id in PostPrefs.saved.value.take(50)) {
            try {
                repo.getNewsById(id)?.let { got[id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // خبر تعذّر تحميله: نتجاوزه
            }
        }
        loaded = got
        loading = false
    }

    ScreenScaffold(title = "المحفوظات", onBack = onBack) { padding ->
        val items = savedIds.mapNotNull { loaded[it] }
        when {
            loading -> Text("جارٍ التحميل…", modifier = Modifier.padding(padding).padding(16.dp))
            items.isEmpty() -> MessageState("لا توجد أخبار محفوظة", "اضغط ⋯ على أي خبر ثم «حفظ المنشور».", Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { n -> NewsCard(news = n, onClick = { onNewsClick(n.id) }) }
            }
        }
    }
}
