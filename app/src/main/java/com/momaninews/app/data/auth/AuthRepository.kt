package com.momaninews.app.data.auth

import com.momaninews.app.data.model.User
import com.momaninews.app.data.model.VerificationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * مصدر حالة تسجيل الدخول. التنفيذ الحقيقي (Google عبر Credential Manager ثم رمز جلسة من الخادم)
 * يُضاف بعد إنشاء Client ID وربط الخادم، دون تغيير الشاشات.
 */
interface AuthRepository {
    val currentUser: StateFlow<User?>
    suspend fun signIn(): User
    fun signOut()
}

/** دخول تجريبي محلي في الذاكرة فقط. لا يُخزَّن أي رمز أو بيانات، ولا يتصل بأي خادم. */
object DemoAuthRepository : AuthRepository {
    private val user = MutableStateFlow<User?>(null)
    override val currentUser: StateFlow<User?> = user.asStateFlow()

    override suspend fun signIn(): User {
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
}

object AuthRepositories {
    val default: AuthRepository = DemoAuthRepository
}
