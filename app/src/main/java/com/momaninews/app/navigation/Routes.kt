package com.momaninews.app.navigation

object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val PROFILE = "profile"
    const val SIGN_IN = "sign_in"

    const val ARG_NEWS_ID = "newsId"
    const val NEWS_DETAIL = "news/{$ARG_NEWS_ID}"

    fun newsDetail(id: Int): String = "news/$id"
}
