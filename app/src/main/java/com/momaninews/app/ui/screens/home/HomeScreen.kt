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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
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
    onSavedClick: () -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val auth = AuthRepositories.default
    val user by auth.currentUser.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var searchOpen by remember { mutableStateOf(false) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hiddenIds by com.momaninews.app.data.settings.PostPrefs.hidden.collectAsStateWithLifecycle()
    val followedSet by com.momaninews.app.data.settings.FollowStore.followed.collectAsStateWithLifecycle()
    var followedOnly by remember { mutableStateOf(false) }
    val lastSeenId by com.momaninews.app.data.settings.FollowStore.lastSeenId.collectAsStateWithLifecycle()
    val newFollowed = com.momaninews.app.data.settings.FollowLogic.newCount(state.allNews, followedSet, lastSeenId)
    // أول مرة: نقطة البداية = أحدث خبر محمَّل الآن. وأثناء عرض «المتابَعة» تُحدَّث لتختفي الشارة.
    androidx.compose.runtime.LaunchedEffect(state.allNews.size, followedOnly, lastSeenId) {
        if (state.allNews.isNotEmpty() && (lastSeenId <= 0 || followedOnly)) {
            com.momaninews.app.data.settings.FollowStore.markSeen(com.momaninews.app.data.settings.FollowLogic.maxId(state.allNews))
        }
    }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    val notHidden = com.momaninews.app.data.settings.PostPrefsLogic.withoutHidden(state.visibleNews, hiddenIds.toSet())
    val byFollow = if (followedOnly) com.momaninews.app.data.settings.FollowLogic.onlyFollowed(notHidden, followedSet) else notHidden
    var mediaFilter by remember { mutableStateOf<com.momaninews.app.data.model.MediaKind?>(null) }
    val visibleNews = com.momaninews.app.data.model.MediaKind.filter(
        com.momaninews.app.data.model.NewsCategories.filter(byFollow, categoryFilter), mediaFilter
    )

    // في عرض «المتابَعة» نكمل جلب الصفحات تلقائياً (بحد أقصى) حتى تظهر أخبار كافية من المصادر المتابَعة
    androidx.compose.runtime.LaunchedEffect(followedOnly, categoryFilter, mediaFilter, followedSet, state.allNews.size, state.canLoadMore, state.isLoadingMore, state.loadMoreFailed) {
        if ((followedOnly || categoryFilter != null || mediaFilter != null) && (!followedOnly || followedSet.isNotEmpty()) && visibleNews.size < 10 && state.canLoadMore &&
            !state.isLoadingMore && !state.loadMoreFailed && !state.isLoading && state.allNews.size < 300
        ) viewModel.loadMore()
    }
    // عدّادات التفاعل على البطاقات: تُجلب مرة لكل خبر (دفعات ≤ 50)، والفشل صامت.
    val reactionMap = remember { androidx.compose.runtime.mutableStateMapOf<Int, Map<com.momaninews.app.data.model.Reaction, Int>>() }
    val reactionAsked = remember { HashSet<Int>() }
    androidx.compose.runtime.LaunchedEffect(visibleNews.map { it.id }) {
        val todo = visibleNews.map { it.id }.filter { it !in reactionAsked }.take(50)
        if (todo.isNotEmpty()) {
            reactionAsked.addAll(todo)
            val got = com.momaninews.app.data.interaction.InteractionRepositories.default.getReactionCounts(todo)
            reactionMap.putAll(got)
        }
    }
    val errorMessage = state.errorMessage

    // شريط الأخبار وعدّاد الجرس: الوقت يتجدد كل دقيقة فتختفي الأخبار القديمة تلقائياً
    val readIds by com.momaninews.app.data.settings.ReadStore.read.collectAsStateWithLifecycle()
    val tickerNow by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { kotlinx.coroutines.delay(60_000); value = System.currentTimeMillis() }
    }
    val tickerItems = remember(state.featured, state.allNews, tickerNow) {
        com.momaninews.app.ui.components.TickerLogic.eligible((state.featured + state.allNews).distinctBy { it.id }, tickerNow)
    }
    val unreadCount = com.momaninews.app.ui.components.TickerLogic.unreadCount(tickerItems, readIds.toSet())
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) { kotlinx.coroutines.delay(180_000); viewModel.refreshFeatured() }
        }
    }
    val openNews: (Int) -> Unit = { id -> com.momaninews.app.data.settings.ReadStore.markRead(id); onNewsClick(id) }

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
                        DrawerAction.PROFILE -> if (user == null) onSignInClick() else onProfileClick()
                        DrawerAction.NOTIFICATIONS -> onNotificationsClick()
                        DrawerAction.MY_POSTS -> onMyPostsClick()
                        DrawerAction.SAVED -> onSavedClick()
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
        bottomBar = {
            androidx.compose.foundation.layout.Column {
                // شريط الأخبار فوق الإعلان؛ من أحدث الأخبار المحمّلة بغضّ النظر عن التصفية
                com.momaninews.app.ui.components.NewsTicker(items = tickerItems, read = readIds.toSet(), nowMillis = tickerNow, onNewsClick = openNews)
                BannerAd()
            }
        },
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
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                    com.momaninews.app.data.model.MediaKind.entries.forEach { kind ->
                        val on = mediaFilter == kind
                        IconButton(
                            onClick = { mediaFilter = if (on) null else kind },
                            colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                                containerColor = if (on) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f) else androidx.compose.ui.graphics.Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = if (kind == com.momaninews.app.data.model.MediaKind.PHOTOS) com.momaninews.app.ui.components.AppIcons.Photo
                                else com.momaninews.app.ui.components.AppIcons.Video,
                                contentDescription = kind.label
                            )
                        }
                    }
                    IconButton(onClick = onNotificationsClick) {
                        androidx.compose.material3.BadgedBox(badge = {
                            com.momaninews.app.ui.components.TickerLogic.badgeText(unreadCount)?.let { t ->
                                androidx.compose.material3.Badge { Text(t) }
                            }
                        }) {
                            Icon(imageVector = Icons.Default.Notifications, contentDescription = if (unreadCount > 0) "الإشعارات، $unreadCount أخبار جديدة" else "الإشعارات")
                        }
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
            var sortMenuOpen by remember { mutableStateOf(false) }
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "sort-chip") {
                    Box {
                        FilterChip(
                            selected = state.sort != com.momaninews.app.data.repository.NewsSort.NEWEST,
                            onClick = { sortMenuOpen = true },
                            label = { Text("ترتيب: ${state.sort.label} ▾") }
                        )
                        androidx.compose.material3.DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            com.momaninews.app.data.repository.NewsSort.entries.forEach { option ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(option.label, fontWeight = if (state.sort == option) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { viewModel.onSortSelected(option); sortMenuOpen = false }
                                )
                            }
                        }
                    }
                }
                item(key = "followed-chip") {
                    FilterChip(
                        selected = followedOnly,
                        onClick = { followedOnly = !followedOnly },
                        label = {
                            androidx.compose.material3.BadgedBox(badge = {
                                if (newFollowed > 0 && !followedOnly) androidx.compose.material3.Badge { Text(if (newFollowed > 99) "99+" else newFollowed.toString()) }
                            }) { Text("★ متابَعة") }
                        }
                    )
                }
                items(com.momaninews.app.data.model.NewsCategories.all) { c ->
                    FilterChip(
                        selected = categoryFilter == c.wire,
                        onClick = { categoryFilter = if (categoryFilter == c.wire) null else c.wire },
                        label = { Text("${c.emoji} ${c.label}") }
                    )
                }
            }

            // شريط التصفية النشطة: يُظهر عدد النتائج وزر إزالة الكل (ليعرف المستخدم لماذا القائمة قصيرة)
            val activeFilters = com.momaninews.app.ui.screens.home.HomeFilters.activeCount(followedOnly, categoryFilter, mediaFilter)
            if (activeFilters > 0) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = "${visibleNews.size} خبر • تصفية نشطة ($activeFilters)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(onClick = { followedOnly = false; categoryFilter = null; mediaFilter = null }) { Text("إزالة الكل") }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

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
                            onClick = { if (user == null) onSignInClick() else onComposeClick() },
                            onAvatarClick = { if (user == null) onSignInClick() else onProfileClick() }
                        )
                        if (mediaFilter != null) {
                            com.momaninews.app.ui.components.MessageState(
                                title = if (mediaFilter == com.momaninews.app.data.model.MediaKind.VIDEOS) "لا أخبار بالفيديو حالياً" else "لا أخبار بالصور حالياً",
                                message = "تظهر هنا الأخبار التي يتوفر لها ${if (mediaFilter == com.momaninews.app.data.model.MediaKind.VIDEOS) "فيديو" else "صورة"} عند المصدر أو من الناشر.",
                                actionLabel = "عرض كل الأخبار",
                                onAction = { mediaFilter = null }
                            )
                        } else if (categoryFilter != null && !followedOnly) {
                            com.momaninews.app.ui.components.MessageState(
                                title = "لا أخبار في هذا التصنيف حالياً",
                                message = "التصنيف يُحدَّد تلقائياً للأخبار المجلوبة حديثاً فقط.",
                                actionLabel = "عرض كل الأخبار",
                                onAction = { categoryFilter = null }
                            )
                        } else if (followedOnly) {
                            com.momaninews.app.ui.components.MessageState(
                                title = if (followedSet.isEmpty()) "لا تتابع أي مصدر بعد" else "لا أخبار من مصادرك المتابَعة حالياً",
                                message = if (followedSet.isEmpty()) "اضغط ⋯ على أي خبر ثم «مهتم» لمتابعة مصدره." else "جرّب لاحقاً أو أزل التصفية.",
                                actionLabel = "عرض كل الأخبار",
                                onAction = { followedOnly = false }
                            )
                        } else EmptyState(
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
                                    onClick = { if (user == null) onSignInClick() else onComposeClick() },
                                    onAvatarClick = { if (user == null) onSignInClick() else onProfileClick() }
                                )
                            }
                            item(key = "featured") {
                                val followed by com.momaninews.app.data.settings.FollowStore.followed.collectAsStateWithLifecycle()
                                val stories = remember(state.featured, followed, hiddenIds) {
                                    com.momaninews.app.data.settings.FollowLogic.prioritize(
                                        com.momaninews.app.data.settings.PostPrefsLogic.withoutHidden(state.featured, hiddenIds.toSet()), followed
                                    )
                                        .take(com.momaninews.app.data.repository.STORY_COUNT)
                                }
                                com.momaninews.app.ui.components.FeaturedStrip(
                                    items = stories,
                                    onNewsClick = openNews,
                                    onCreateClick = { if (user == null) onSignInClick() else onComposeClick() }
                                )
                            }
                            if (visibleNews.any { it.isSample }) item(key = "demo-banner") {
                                Box(Modifier.padding(horizontal = 16.dp)) { DemoBanner() }
                            }
                            item(key = "active-ads") {
                                Box(Modifier.padding(horizontal = 16.dp)) { ActiveAdsSection() }
                            }
                            items(visibleNews, key = { it.id }) { news ->
                                NewsCard(
                                    news = news,
                                    onClick = { openNews(news.id) },
                                    reactions = reactionMap[news.id]
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
