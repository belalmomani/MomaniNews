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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import com.momaninews.app.data.auth.AuthRepositories
import kotlinx.coroutines.launch
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
    onNotificationsClick: () -> Unit = {},
    onAdRequestClick: () -> Unit = {},
    onVerificationClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onAdminClick: () -> Unit = {},
    onSignInClick: () -> Unit = {},
    onComposeClick: () -> Unit = {},
    onMyPostsClick: () -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val auth = AuthRepositories.default
    val user by auth.currentUser.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var searchOpen by remember { mutableStateOf(false) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val visibleNews = state.visibleNews
    val errorMessage = state.errorMessage

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HomeDrawerContent(
                user = user,
                selectedRegionId = state.selectedRegionId,
                onEntry = { e ->
                    scope.launch { drawerState.close() }
                    when (e.action) {
                        DrawerAction.HOME -> viewModel.onRegionSelected(null)
                        DrawerAction.REGION -> viewModel.onRegionSelected(e.regionId)
                        DrawerAction.NOTIFICATIONS -> onNotificationsClick()
                        DrawerAction.MY_POSTS -> onMyPostsClick()
                        DrawerAction.AD_REQUEST -> onAdRequestClick()
                        DrawerAction.VERIFICATION -> onVerificationClick()
                        DrawerAction.SETTINGS -> onSettingsClick()
                        DrawerAction.ADMIN -> onAdminClick()
                        DrawerAction.SIGN_IN -> onSignInClick()
                        DrawerAction.SIGN_OUT -> auth.signOut()
                    }
                }
            )
        }
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BannerAd() },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "القائمة")
                    }
                },
                title = {
                    Text(
                        text = "المومني نيوز",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = {
                        if (searchOpen) viewModel.onQueryChange("")
                        searchOpen = !searchOpen
                    }) {
                        Icon(
                            imageVector = if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchOpen) "إغلاق البحث" else "بحث"
                        )
                    }
                    IconButton(onClick = onNotificationsClick) {
                        Icon(imageVector = Icons.Default.Notifications, contentDescription = "الإشعارات")
                    }
                    IconButton(onClick = onProfileClick) {
                        Icon(imageVector = Icons.Default.Person, contentDescription = "الحساب")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
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
            if (searchOpen || state.query.isNotEmpty()) {
                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange
                )
            }
            RegionChips(
                selectedRegionId = state.selectedRegionId,
                onRegionSelected = viewModel::onRegionSelected
            )
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(com.momaninews.app.data.repository.NewsSort.entries.toList()) { option ->
                    FilterChip(
                        selected = state.sort == option,
                        onClick = { viewModel.onSortSelected(option) },
                        label = { Text(option.label) }
                    )
                }
            }

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

                    visibleNews.isEmpty() -> Column(modifier = Modifier.fillMaxSize()) {
                        // حتى بلا أخبار يستطيع المستخدم نشر أول خبر
                        com.momaninews.app.ui.components.ComposerEntry(
                            displayName = user?.displayName,
                            onClick = { if (user == null) onSignInClick() else onComposeClick() }
                        )
                        EmptyState(
                            hasActiveFilter = state.hasActiveFilter,
                            onClearFilters = viewModel::clearFilters
                        )
                    }

                    else -> PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item(key = "composer") {
                                com.momaninews.app.ui.components.ComposerEntry(
                                    displayName = user?.displayName,
                                    onClick = { if (user == null) onSignInClick() else onComposeClick() }
                                )
                            }
                            item(key = "featured") {
                                com.momaninews.app.ui.components.FeaturedStrip(
                                    items = state.featured,
                                    onNewsClick = onNewsClick,
                                    onCreateClick = { if (user == null) onSignInClick() else onComposeClick() }
                                )
                            }
                            item(key = "demo-banner") {
                                Box(Modifier.padding(horizontal = 16.dp)) { DemoBanner() }
                            }
                            item(key = "active-ads") {
                                Box(Modifier.padding(horizontal = 16.dp)) { ActiveAdsSection() }
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
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
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

/** تبويبات المناطق بنمط فيسبوك (خط سفلي للتبويب المحدد) مع «الكل». */
@Composable
private fun RegionChips(
    selectedRegionId: String?,
    onRegionSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to "الكل") + Regions.all.map { it.id to it.nameAr }
    val selected = tabs.indexOfFirst { it.first == selectedRegionId }.coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = selected,
        modifier = modifier.fillMaxWidth(),
        edgePadding = 8.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        tabs.forEachIndexed { i, (id, label) ->
            Tab(
                selected = i == selected,
                onClick = { onRegionSelected(id) },
                text = { Text(label, maxLines = 1, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal) }
            )
        }
    }
}
