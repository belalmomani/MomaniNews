package com.momaninews.app.data.auth

import android.content.Context
import com.momaninews.app.data.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * تسجيل الدخول الحقيقي: Google (Credential Manager) ثم تبادل الرمز مع Supabase Auth.
 * الجلسة تُحفظ مشفّرة، وتُستعاد عند فتح التطبيق وتُجدَّد عند الانتهاء.
 */
class SupabaseAuthRepository(
    appContext: Context,
    supabaseUrl: String,
    publicKey: String,
    private val webClientId: String
) : AuthRepository {

    private val api = SupabaseAuthApi(supabaseUrl, publicKey)
    private val store = SecureSessionStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val user = MutableStateFlow<User?>(null)
    private var session: AuthSession? = null

    override val currentUser: StateFlow<User?> = user.asStateFlow()

    init {
        scope.launch { restore() }
    }

    override suspend fun signIn(context: Context): User {
        val nonce = Nonce.random()
        val idToken = GoogleIdTokenProvider.get(context, webClientId, nonce)
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val fresh = api.exchangeGoogleIdToken(idToken, nonce, nowSec())
                // صف users يُنشأ بمشغّل في القاعدة عند أول دخول؛ غيابه يعني خللاً في الإعداد.
                val profile = api.fetchProfile(fresh)
                    ?: throw IllegalStateException("Profile row missing")
                session = fresh
                store.save(fresh.toJson())
                user.value = profile
                profile
            }
        }
    }

    override fun signOut() {
        val old = session
        session = null
        user.value = null
        store.clear()
        if (old != null) {
            scope.launch { runCatching { api.logout(old.accessToken) } } // أفضل جهد؛ الجلسة المحلية حُذفت أصلاً
        }
    }

    /** رمز وصول صالح للاستدعاءات المصادَق عليها (التصويت والتعليقات لاحقاً). null إن لم يسجَّل الدخول. */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = session ?: return@withLock null
            if (!current.isExpired(nowSec())) return@withLock current.accessToken
            refreshLocked(current)?.accessToken
        }
    }

    private suspend fun restore() {
        lock.withLock { restoreLocked() }
    }

    private fun restoreLocked() {
        val stored = store.load()?.let { runCatching { SupabaseAuthParser.parseStored(it) }.getOrNull() }
        if (stored == null) {
            store.clear()
            return
        }
        val active = if (stored.isExpired(nowSec())) refreshLocked(stored) else stored
        if (active == null) return
        try {
            val profile = api.fetchProfile(active) ?: return
            session = active
            user.value = profile
        } catch (e: HttpStatusException) {
            if (e.code == 401 || e.code == 403) { store.clear(); session = null }
        } catch (e: Exception) {
            // بلا شبكة: تبقى الجلسة محفوظة وتُستعاد عند فتح التطبيق القادم.
        }
    }

    /** يجدّد الجلسة. إن رفض الخادم الرمز (400/401) تُحذف الجلسة؛ وأي خطأ شبكة يُبقيها. */
    private fun refreshLocked(old: AuthSession): AuthSession? = try {
        val fresh = api.refresh(old.refreshToken, nowSec())
        session = fresh
        store.save(fresh.toJson())
        fresh
    } catch (e: HttpStatusException) {
        if (e.code in 400..403) { session = null; user.value = null; store.clear() }
        null
    } catch (e: Exception) {
        null
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000
}
