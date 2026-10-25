package com.momaninews.app.data.model

/** التصويت على مصداقية الخبر. صوت واحد لكل مستخدم لكل خبر (يُفرض على الخادم وقاعدة البيانات لاحقاً). */
enum class CredibilityVote { CREDIBLE, NOT_CREDIBLE }

data class VoteSummary(
    val credible: Int,
    val notCredible: Int,
    val myVote: CredibilityVote?
)

data class Comment(
    val id: Int,
    val authorName: String,
    val text: String,
    val dateLabel: String,
    val isSample: Boolean = false
)

/** التفاعلات السبعة. رموز يونيكود قياسية (لا رسوم مملوكة لجهة)، و[wire] هو القيمة المخزّنة في القاعدة. */
enum class Reaction(val wire: String, val emoji: String, val label: String) {
    LIKE("like", "👍", "إعجاب"),
    LOVE("love", "❤️", "أحببته"),
    CARE("care", "🤗", "اهتمام"),
    HAHA("haha", "😆", "أضحكني"),
    WOW("wow", "😮", "أدهشني"),
    SAD("sad", "😢", "أحزنني"),
    ANGRY("angry", "😡", "أغضبني");

    companion object {
        fun fromWire(value: String?): Reaction? = entries.firstOrNull { it.wire == value }
    }
}

data class ReactionSummary(val counts: Map<Reaction, Int>, val mine: Reaction?) {
    val total: Int get() = counts.values.sum()

    /** أكثر التفاعلات حضوراً (بترتيب العدد ثم ترتيب التعريف)، حتى [limit]. */
    fun top(limit: Int = 3): List<Reaction> =
        counts.filterValues { it > 0 }.entries.sortedWith(compareByDescending<Map.Entry<Reaction, Int>> { it.value }.thenBy { it.key.ordinal }).take(limit).map { it.key }

    companion object { val EMPTY = ReactionSummary(emptyMap(), null) }
}

/** سطر التفاعلات الموجز لبطاقة الرئيسية: أكثر 3 رموز ثم المجموع («👍❤️😮 12»). null إن لم توجد تفاعلات. */
object ReactionLine {
    fun of(counts: Map<Reaction, Int>?): String? {
        if (counts.isNullOrEmpty()) return null
        val s = ReactionSummary(counts, null)
        if (s.total <= 0) return null
        return s.top(3).joinToString("") { it.emoji } + " " + s.total
    }
}
