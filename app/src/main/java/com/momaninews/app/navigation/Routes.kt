package com.momaninews.app.navigation

object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val PROFILE = "profile"
    const val SIGN_IN = "sign_in"
    const val VERIFICATION = "verification"
    const val NOTIFICATIONS = "notifications"
    const val AD_REQUEST = "ad_request"
    const val SETTINGS = "settings"
    const val POST_NEW = "post/new"
    const val MY_POSTS = "my_posts"
    const val ADMIN = "admin"
    const val ADMIN_NEWS = "admin/news"
    const val ADMIN_ADD_NEWS = "admin/addnews"
    const val ADMIN_COMMENTS = "admin/comments"
    const val ADMIN_VERIFICATION = "admin/verification"
    const val ADMIN_ADS = "admin/ads"
    const val ADMIN_USERS = "admin/users"
    const val ADMIN_NOTIFY = "admin/notify"
    const val ADMIN_STATS = "admin/stats"

    const val ARG_NEWS_ID = "newsId"
    const val NEWS_DETAIL = "news/{$ARG_NEWS_ID}"

    fun newsDetail(id: Int): String = "news/$id"
}
