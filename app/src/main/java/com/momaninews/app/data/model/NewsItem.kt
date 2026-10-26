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
    val Momani = Region(id = "momani", nameAr = "أخبار المومني")
    val all: List<Region> = listOf(Abeen, Abelin, Sakhra, Momani)
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
    val videoUrl: String? = null,
    /** الرابط الأصلي للخبر عند المصدر (للأخبار المجلوبة). https فقط. */
    val sourceUrl: String? = null,
    /** تصنيف الجالب (death/congrats/...)؛ null = غير مصنّف. */
    val category: String? = null,
    /** وقت النشر بالميلي ثانية (UTC)؛ 0 = غير معروف. يُستخدم لإخفاء الأخبار القديمة من شريط الأخبار. */
    val publishedAtMillis: Long = 0L
)

/** تصفية الأخبار بحسب الوسائط: صور أو فيديو. null = كل الأخبار. */
enum class MediaKind(val label: String) {
    PHOTOS("أخبار بالصور"), VIDEOS("أخبار بالفيديو");

    fun matches(n: NewsItem): Boolean = when (this) {
        PHOTOS -> !n.imageUrl.isNullOrBlank()
        VIDEOS -> !n.videoUrl.isNullOrBlank()
    }

    companion object {
        fun filter(items: List<NewsItem>, kind: MediaKind?): List<NewsItem> = if (kind == null) items else items.filter { kind.matches(it) }
    }
}

/** تصنيفات الأخبار (نفس قيم عمود news.category). الترتيب هو ترتيب أزرار التصفية. */
object NewsCategories {
    data class Cat(val wire: String, val emoji: String, val label: String)
    val all: List<Cat> = listOf(
        Cat("death", "⚫", "وفيات"), Cat("congrats", "🎉", "تهاني"), Cat("achievement", "🏆", "نجاح وتفوق"),
        Cat("services", "🛠", "خدمات"), Cat("sports", "⚽", "رياضة"), Cat("weather", "🌧", "طقس"), Cat("incident", "🚨", "حوادث")
    )
    fun of(wire: String?): Cat? = all.firstOrNull { it.wire == wire }
    fun filter(items: List<NewsItem>, wire: String?): List<NewsItem> = if (wire == null) items else items.filter { it.category == wire }
}
