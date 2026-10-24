package com.momaninews.app.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.repository.NewsRepositories
import com.momaninews.app.data.repository.NewsRepository
import com.momaninews.app.ui.components.BannerAd
import com.momaninews.app.ui.components.DemoBanner
import com.momaninews.app.ui.components.ErrorState
import com.momaninews.app.ui.components.LoadingState
import com.momaninews.app.ui.components.MessageState
import com.momaninews.app.ui.components.NewsImage
import com.momaninews.app.ui.components.RegionTag
import com.momaninews.app.ui.components.SourceDateRow
import kotlinx.coroutines.CancellationException

private sealed interface DetailUiState {
    data object Loading : DetailUiState
    data object NotFound : DetailUiState
    data object Error : DetailUiState
    data class Success(val news: NewsItem) : DetailUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsDetailScreen(
    newsId: Int,
    onBack: () -> Unit,
    onSignInClick: () -> Unit,
    repository: NewsRepository = NewsRepositories.default
) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var state by remember(newsId) { mutableStateOf<DetailUiState>(DetailUiState.Loading) }

    LaunchedEffect(newsId, reloadKey) {
        state = DetailUiState.Loading
        state = try {
            val news = repository.getNewsById(newsId)
            if (news != null) {
                if (reloadKey == 0) repository.trackView(newsId)
                DetailUiState.Success(news)
            } else DetailUiState.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DetailUiState.Error
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BannerAd() },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "تفاصيل الخبر",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val current = state) {
                DetailUiState.Loading -> LoadingState()

                DetailUiState.NotFound -> MessageState(
                    title = "الخبر غير متاح",
                    message = "هذا الخبر غير موجود أو لم يعد منشوراً.",
                    actionLabel = "رجوع",
                    onAction = onBack
                )

                DetailUiState.Error -> ErrorState(
                    message = "تعذر تحميل الخبر. تحقق من الاتصال وحاول مرة أخرى.",
                    onRetry = { reloadKey++ }
                )

                is DetailUiState.Success -> NewsDetailContent(
                    news = current.news,
                    onSignInClick = onSignInClick
                )
            }
        }
    }
}

@Composable
private fun NewsDetailContent(news: NewsItem, onSignInClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        NewsImage(news)

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (news.isSample) {
                DemoBanner()
            }

            RegionTag(name = news.region.nameAr)

            Text(
                text = news.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            SourceDateRow(source = news.source, dateLabel = news.dateLabel)

            news.videoUrl?.let { video ->
                val context = androidx.compose.ui.platform.LocalContext.current
                com.momaninews.app.ui.components.NewsVideoPlayer(video)
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    .setData(android.net.Uri.parse(video))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                ) { Text("فتح الفيديو في مشغّل آخر") }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            news.content
                .split("\n\n")
                .filter { it.isNotBlank() }
                .forEach { paragraph ->
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

            val ctx = androidx.compose.ui.platform.LocalContext.current
            news.sourceUrl?.let { link ->
                Text(
                    text = "المصدر: " + news.source.ifBlank { "الموقع الأصلي" } + " — الخبر منقول باختصار، والتفاصيل الكاملة عند المصدر.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.Button(onClick = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setData(android.net.Uri.parse(link))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("فتح المصدر") }
            }
            androidx.compose.material3.OutlinedButton(onClick = {
                val privacy = com.momaninews.app.BuildConfig.PRIVACY_URL
                val page = if (privacy.startsWith("https://") && privacy.endsWith("/privacy.html"))
                    privacy.removeSuffix("privacy.html") + "news.html?id=" + news.id else null
                val body = news.title + (page?.let { "\n" + it } ?: news.sourceUrl?.let { "\n" + it } ?: "") + "\n\nعبر تطبيق المومني نيوز"
                runCatching {
                    ctx.startActivity(
                        android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, body),
                            "مشاركة الخبر"
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) { Text("مشاركة") }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            InteractionSection(
                newsId = news.id,
                onSignInClick = onSignInClick
            )
        }
    }
}
