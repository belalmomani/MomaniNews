package com.momaninews.app.data.ads

import com.momaninews.app.BuildConfig
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.auth.SupabaseAuthRepository
import com.momaninews.app.data.remote.SupabaseNewsParser
import com.momaninews.app.data.remote.SupabaseRest
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AdRequestException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_SIGNED_IN, INVALID, TOO_MANY_PENDING, FAILED }
}

data class AdDraft(
    val title: String,
    val body: String,
    val contact: String,
    val linkUrl: String,
    val regionId: String?
)

data class MyAd(val title: String, val statusLabel: String, val note: String, val dateLabel: String)

object AdRules {
    const val MAX_TITLE = 120
    const val MAX_BODY = 1000
    const val MAX_CONTACT = 120

    /** يُرجع المسودة بعد القص والتحقق، أو null إن كانت غير صالحة (عنوان فارغ/طويل، رابط غير https...). */
    fun clean(d: AdDraft): AdDraft? {
        val title = d.title.trim()
        val body = d.body.trim()
        val contact = d.contact.trim()
        val link = d.linkUrl.trim()
        if (title.isEmpty() || title.length > MAX_TITLE) return null
        if (body.length > MAX_BODY || contact.length > MAX_CONTACT) return null
        if (link.isNotEmpty() && !Regex("^https://[^\\s]+$").matches(link)) return null
        return AdDraft(title, body, contact, link, d.regionId)
    }
}

object AdsParser {
    fun parseMine(json: String): List<MyAd> {
        val array = JSONArray(json)
        return List(array.length()) { i ->
            val row = array.getJSONObject(i)
            MyAd(
                title = row.getString("title"),
                statusLabel = when (row.optString("status")) {
                    "approved" -> "معتمد"
                    "rejected" -> "مرفوض"
                    else -> "قيد المراجعة"
                },
                note = if (row.isNull("admin_note")) "" else row.optString("admin_note", ""),
                dateLabel = SupabaseNewsParser.formatDate(if (row.isNull("created_at")) "" else row.optString("created_at", ""))
            )
        }
    }
}

class AdsRepository(
    private val rest: SupabaseRest,
    private val session: suspend () -> Pair<String, String>?
) {
    suspend fun mine(): List<MyAd> = withContext(Dispatchers.IO) {
        val (token, uid) = session() ?: throw AdRequestException(AdRequestException.Reason.NOT_SIGNED_IN)
        val r = rest.call(
            "GET", "advertisements?select=title,status,admin_note,created_at&user_id=eq.$uid&order=created_at.desc&limit=50",
            bearer = token
        )
        if (!r.ok) throw IOException("HTTP ${r.code}")
        AdsParser.parseMine(r.body)
    }

    suspend fun submit(draft: AdDraft) = withContext(Dispatchers.IO) {
        val d = AdRules.clean(draft) ?: throw AdRequestException(AdRequestException.Reason.INVALID)
        val (token, _) = session() ?: throw AdRequestException(AdRequestException.Reason.NOT_SIGNED_IN)
        val json = JSONObject().put("title", d.title)
        if (d.body.isNotEmpty()) json.put("body", d.body)
        if (d.contact.isNotEmpty()) json.put("contact", d.contact)
        if (d.linkUrl.isNotEmpty()) json.put("link_url", d.linkUrl)
        if (!d.regionId.isNullOrEmpty()) json.put("region_id", d.regionId)
        val r = rest.call("POST", "advertisements", json.toString(), bearer = token, prefer = "return=minimal")
        if (r.code == 400 && r.body.contains("too_many_pending_ads")) {
            throw AdRequestException(AdRequestException.Reason.TOO_MANY_PENDING)
        }
        if (!r.ok) throw AdRequestException(AdRequestException.Reason.FAILED)
    }
}

object AdsRepositories {
    val default: AdsRepository? by lazy {
        val auth = AuthRepositories.default
        if (auth is SupabaseAuthRepository) {
            AdsRepository(SupabaseRest(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)) { auth.tokenAndUserId() }
        } else null
    }
}
