package com.momaninews.app.data.model

data class Region(
    val id: String,
    val nameAr: String
)

object Regions {
    val Abeen = Region(id = "abeen", nameAr = "عبين")
    val Abelin = Region(id = "abelin", nameAr = "عبلين")
    val Sakhra = Region(id = "sakhra", nameAr = "صخرة")

    // قابلة للتوسع لاحقاً بإضافة مناطق أخرى.
    val all: List<Region> = listOf(Abeen, Abelin, Sakhra)
}

data class NewsItem(
    val id: Int,
    val title: String,
    val excerpt: String,
    /** المحتوى الكامل. الفقرات مفصولة بسطر فارغ. */
    val content: String,
    val source: String,
    val dateLabel: String,
    val region: Region,
    val isSample: Boolean = false,
    /** رابط https لصورة الخبر (اختياري). */
    val imageUrl: String? = null,
    /** رابط https لفيديو MP4 (اختياري). */
    val videoUrl: String? = null
)
