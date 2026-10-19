package com.momaninews.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.momaninews.app.data.model.Regions
import com.momaninews.app.ui.components.ActiveAdsSection
import com.momaninews.app.ui.components.BannerAd
import com.momaninews.app.ui.components.DemoBanner
import com.momaninews.app.ui.components.EmptyState
import com.momaninews.app.ui.components.ErrorState
import com.momaninews.app.ui.components.LoadingState
import com.momaninews.app.ui.components.NewsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNewsClick: (Int) -> Unit,
    onProfileClick: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val visibleNews = state.visibleNews
    val errorMessage = state.errorMessage

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BannerAd() },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "المومني نيوز",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = onProfileClick) {
                        Icon(imageVector = Icons.Default.Person, contentDescription = "الحساب")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SearchField(
                query = state.query,
                onQueryChange = viewModel::onQueryChange
            )
            RegionChips(
                selectedRegionId = state.selectedRegionId,
                onRegionSelected = viewModel::onRegionSelected
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    state.isLoading -> LoadingState()

                    errorMessage != null -> ErrorState(
                        message = errorMessage,
                        onRetry = viewModel::retry
                    )

                    visibleNews.isEmpty() -> EmptyState(
                        hasActiveFilter = state.hasActiveFilter,
                        onClearFilters = viewModel::clearFilters
                    )

                    else -> PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            item(key = "demo-banner") {
                                DemoBanner()
                            }
                            item(key = "active-ads") {
                                ActiveAdsSection()
                            }
                            items(visibleNews, key = { it.id }) { news ->
                                NewsCard(
                                    news = news,
                                    onClick = { onNewsClick(news.id) }
                                )
                            }
                            if (state.canLoadMore) {
                                item(key = "load-more") {
                                    // يُركَّب هذا العنصر فقط عند الوصول لنهاية القائمة، فيبدأ التحميل تلقائياً عند التمرير.
                                    // عند الفشل لا نعيد المحاولة تلقائياً (يبقى الزر للمحاولة اليدوية).
                                    androidx.compose.runtime.LaunchedEffect(visibleNews.size) {
                                        if (!state.loadMoreFailed) viewModel.loadMore()
                                    }
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = viewModel::loadMore,
                                        enabled = !state.isLoadingMore,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            when {
                                                state.isLoadingMore -> "جارٍ التحميل..."
                                                state.loadMoreFailed -> "تعذر التحميل، أعد المحاولة"
                                                else -> "تحميل المزيد"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val clearButton: (@Composable () -> Unit)? = if (query.isNotEmpty()) {
        {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "مسح البحث")
            }
        }
    } else {
        null
    }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        placeholder = { Text(text = "ابحث في العناوين والمحتوى") },
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = clearButton,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionChips(
    selectedRegionId: String?,
    onRegionSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
    )

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "all") {
            FilterChip(
                selected = selectedRegionId == null,
                onClick = { onRegionSelected(null) },
                label = { Text(text = "الكل") },
                colors = chipColors
            )
        }
        items(Regions.all, key = { it.id }) { region ->
            FilterChip(
                selected = selectedRegionId == region.id,
                onClick = { onRegionSelected(region.id) },
                label = { Text(text = region.nameAr) },
                colors = chipColors
            )
        }
    }
}
