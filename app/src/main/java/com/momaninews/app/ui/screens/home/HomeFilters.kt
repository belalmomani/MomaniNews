package com.momaninews.app.ui.screens.home

/** منطق شريط التصفية النشطة في الرئيسية (نقي وقابل للاختبار). الترتيب لا يُعدّ تصفية. */
object HomeFilters {
    fun activeCount(followedOnly: Boolean, category: String?, media: Any?): Int =
        (if (followedOnly) 1 else 0) + (if (category != null) 1 else 0) + (if (media != null) 1 else 0)
}
