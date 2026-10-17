package com.momaninews.app.data.ads

import com.momaninews.app.BuildConfig
import com.momaninews.app.data.remote.SupabaseRest
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** إعلان معتمد وساري (من عرض ads_public الذي يخفي الملاحظات الإدارية وهوية المعلن). */
data class ActiveAd(val id: Int, val title: String, val body: String, val contact: String, val linkUrl: String?)

object ActiveAdsParser {
    fun parse(json: String): List<ActiveAd> {
        val array = JSONArray(json)
        return List(array.length()) { i ->
            val row = array.getJSONObject(i)
            val link = if (row.isNull("link_url")) "" else row.optString("link_url", "")
            ActiveAd(
                id = row.getInt("id"),
                title = row.getString("title"),
                body = if (row.isNull("body")) "" else row.optString("body", ""),
                contact = if (row.isNull("contact")) "" else row.optString("contact", ""),
                // لا نعرض ولا نفتح إلا روابط https (دفاع إضافي فوق قيد القاعدة)
                linkUrl = link.takeIf { it.startsWith("https://") }
            )
        }
    }
}

/** قراءة عامة (لا تحتاج تسجيل دخول). null إن لم يُضبط Supabase. */
object ActiveAdsRepository {
    private val rest: SupabaseRest? =
        if (BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_KEY.isNotBlank()) {
            SupabaseRest(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
        } else null

    val isAvailable: Boolean get() = rest != null

    suspend fun load(): List<ActiveAd> = withContext(Dispatchers.IO) {
        val client = rest ?: return@withContext emptyList()
        val r = client.call("GET", "ads_public?select=id,title,body,contact,link_url&order=starts_at.desc&limit=5")
        if (!r.ok) throw IOException("HTTP ${r.code}")
        ActiveAdsParser.parse(r.body)
    }
}
