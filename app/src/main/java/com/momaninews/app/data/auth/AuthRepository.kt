package com.momaninews.app.data.auth

import android.content.Context
import com.momaninews.app.BuildConfig
import com.momaninews.app.data.model.User
import com.momaninews.app.data.model.VerificationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** يُرمى عندما يغلق المستخدم نافذة اختيار حساب Google دون إكمال الدخول (ليس خطأً يُعرض). */
class SignInCancelledException : Exception()

interface AuthRepository {
    val currentUser: StateFlow<User?>

    /** true للدخول التجريبي المحلي فقط. */
    val isDemo: Boolean get() = false

    /** [context] يجب أن يكون Activity (يحتاجه Credential Manager لعرض نافذة Google). */
    suspend fun signIn(context: Context): User
    fun signOut()

    /** يعيد قراءة بيانات المستخدم من الخادم (مثلاً بعد تغيّر حالة التوثيق). لا شيء في التجريبي. */
    suspend fun refreshProfile() {}

    /** يحذف الحساب وبياناته نهائياً من الخادم ثم يسجّل الخروج. يرمي استثناء عند الفشل (الجلسة تبقى). */
    suspend fun deleteAccount() {}
}

/** دخول تجريبي محلي في الذاكرة فقط. لا يُخزَّن أي رمز أو بيانات، ولا يتصل بأي خادم. */
object DemoAuthRepository : AuthRepository {
    private val user = MutableStateFlow<User?>(null)
    override val currentUser: StateFlow<User?> = user.asStateFlow()
    override val isDemo: Boolean = true

    override suspend fun signIn(context: Context): User {
        delay(400)
        val demo = User(
            id = "demo-user",
            displayName = "مستخدم تجريبي",
            email = "demo@example.invalid",
            verification = VerificationStatus.NONE,
            isDemo = true
        )
        user.value = demo
        return demo
    }

    override fun signOut() {
        user.value = null
    }

    override suspend fun deleteAccount() {
        user.value = null
    }
}

/**
 * يختار التنفيذ: Supabase + Google إذا ضُبطت (SUPABASE_URL و SUPABASE_KEY و GOOGLE_WEB_CLIENT_ID)،
 * وإلا الدخول التجريبي. [init] تُستدعى مرة من Application.onCreate.
 */
object AuthRepositories {
    @Volatile
    private var impl: AuthRepository? = null

    fun init(appContext: Context) {
        if (impl != null) return
        impl = if (BuildConfig.SUPABASE_URL.startsWith("https://") &&
            BuildConfig.SUPABASE_KEY.isNotBlank() &&
            BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
        ) {
            SupabaseAuthRepository(
                appContext = appContext.applicationContext,
                supabaseUrl = BuildConfig.SUPABASE_URL,
                publicKey = BuildConfig.SUPABASE_KEY,
                webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
            )
        } else {
            DemoAuthRepository
        }
    }

    val default: AuthRepository
        get() = impl ?: DemoAuthRepository
}
