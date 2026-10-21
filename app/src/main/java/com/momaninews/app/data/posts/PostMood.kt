package com.momaninews.app.data.posts

/** «بم تشعر؟» و«ماذا تفعل؟» كما في فيسبوك. تُضاف للمنشور كسطر أخير نصي (لا أعمدة جديدة في القاعدة). */
data class MoodItem(val emoji: String, val label: String)

object PostMood {
    val feelings = listOf(
        MoodItem("🙂", "السعادة"), MoodItem("😇", "اليمن"), MoodItem("😊", "المحبة"), MoodItem("😔", "الحزن"),
        MoodItem("😍", "الجمال"), MoodItem("😃", "العرفان"), MoodItem("🤩", "الحماسة"), MoodItem("🥰", "الغرام"),
        MoodItem("🤪", "الجنون"), MoodItem("🥹", "الامتنان"), MoodItem("😄", "البهجة"), MoodItem("🤩", "حالة عجيبة"),
        MoodItem("😜", "السخف"), MoodItem("🎉", "الاحتفال"), MoodItem("😌", "الروعة"), MoodItem("😎", "الهدوء"),
        MoodItem("🙂", "التسلية"), MoodItem("😌", "الاسترخاء"), MoodItem("😌", "الإيجابية"), MoodItem("😴", "قلّة الحماسة")
    )

    /** الأنشطة تنتهي بـ«…»: يكتب المستخدم تتمتها (مثل «أحتفل بـ نجاح ابني»). */
    val activities = listOf(
        MoodItem("🎉", "أحتفل بـ…"), MoodItem("👓", "أشاهد…"), MoodItem("🍩", "أتناول…"), MoodItem("🥤", "أشرب…"),
        MoodItem("✈️", "أسافر إلى…"), MoodItem("🎧", "أستمع إلى…"), MoodItem("🔍", "أبحث عن…"), MoodItem("📖", "أقرأ…"),
        MoodItem("🎮", "ألعب…"), MoodItem("🤲", "أدعم…")
    )

    const val MAX_EXTRA = 80

    fun filter(items: List<MoodItem>, query: String): List<MoodItem> {
        val q = query.trim()
        return if (q.isEmpty()) items else items.filter { it.label.contains(q) }
    }

    /** «😊 يشعر بالسعادة» (الباء ملتصقة بـ«ال» فتُقرأ «بالسعادة»). */
    fun feelingTag(item: MoodItem) = "${item.emoji} أشعر ب${item.label}"

    /** «🎉 أحتفل بـ نجاح ابني». [extra] فارغ ← بدون تتمة. */
    fun activityTag(item: MoodItem, extra: String): String {
        val base = item.label.removeSuffix("…").trimEnd()
        val e = extra.trim().replace(Regex("\\s+"), " ").take(MAX_EXTRA)
        return "${item.emoji} " + if (e.isEmpty()) base else "$base $e"
    }

    /** يضيف الوسم سطراً أخيراً. يقصّ النص إن لزم ليبقى المجموع ضمن [max]. */
    fun compose(text: String, tag: String?, max: Int = PostRules.MAX_TEXT): String {
        val body = text.trim()
        if (tag.isNullOrBlank()) return body
        val suffix = "\n\n— $tag"
        val room = (max - suffix.length).coerceAtLeast(0)
        val b = if (body.length > room) body.take(room).trimEnd() else body
        return if (b.isEmpty()) tag else b + suffix
    }
}
