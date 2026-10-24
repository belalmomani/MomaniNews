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
