package com.momaninews.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.repository.NewsRepositories
import com.momaninews.app.data.repository.NewsRepository
import com.momaninews.app.data.repository.filterNews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val LOAD_ERROR_MESSAGE = "تعذر تحميل الأخبار. تحقق من الاتصال وحاول مرة أخرى."

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val allNews: List<NewsItem> = emptyList(),
    val errorMessage: String? = null,
    val query: String = "",
    val selectedRegionId: String? = null
) {
    val visibleNews: List<NewsItem>
        get() = filterNews(allNews, query, selectedRegionId)

    val hasActiveFilter: Boolean
        get() = query.isNotBlank() || selectedRegionId != null
}

class HomeViewModel(
    private val repository: NewsRepository = NewsRepositories.default
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(isRefresh = false)
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun onRegionSelected(regionId: String?) {
        _uiState.update { it.copy(selectedRegionId = regionId) }
    }

    fun clearFilters() {
        _uiState.update { it.copy(query = "", selectedRegionId = null) }
    }

    fun retry() = load(isRefresh = false)

    fun refresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean) {
        loadJob?.cancel()
        _uiState.update {
            if (isRefresh) {
                it.copy(isRefreshing = true)
            } else {
                it.copy(isLoading = true, errorMessage = null)
            }
        }
        loadJob = viewModelScope.launch {
            try {
                val items = repository.getNews()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        allNews = items,
                        errorMessage = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // عند فشل التحديث مع وجود أخبار معروضة نُبقيها كما هي؛ الخطأ يظهر فقط إذا لم يوجد شيء نعرضه.
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (it.allNews.isEmpty()) LOAD_ERROR_MESSAGE else null
                    )
                }
            }
        }
    }
}
