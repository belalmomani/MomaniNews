package com.momaninews.app.data.verification

import com.momaninews.app.BuildConfig
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.auth.SupabaseAuthRepository
import com.momaninews.app.data.remote.SupabaseRest

/** null في الوضع التجريبي (لا توثيق بدون خادم). تُقرأ بعد Application.onCreate. */
object VerificationRepositories {
    val default: SupabaseVerificationRepository? by lazy {
        val auth = AuthRepositories.default
        if (auth is SupabaseAuthRepository) {
            SupabaseVerificationRepository(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                publicKey = BuildConfig.SUPABASE_KEY,
                rest = SupabaseRest(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY),
                session = { auth.tokenAndUserId() }
            )
        } else {
            null
        }
    }
}
