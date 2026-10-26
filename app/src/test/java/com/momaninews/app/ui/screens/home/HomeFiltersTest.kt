package com.momaninews.app.ui.screens.home

import com.momaninews.app.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFiltersTest {
    @Test fun none_isZero() = assertEquals(0, HomeFilters.activeCount(false, null, null))
    @Test fun countsEachActiveFilter() {
        assertEquals(1, HomeFilters.activeCount(true, null, null))
        assertEquals(2, HomeFilters.activeCount(false, "death", MediaKind.PHOTOS))
        assertEquals(3, HomeFilters.activeCount(true, "sports", MediaKind.VIDEOS))
    }
}
