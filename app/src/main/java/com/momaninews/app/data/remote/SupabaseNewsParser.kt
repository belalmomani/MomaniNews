package com.momaninews.app.data.remote

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.model.Region
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * يحوّل استجابات PostgREST (مصفوفة JSON مباشرة) إلى نماذج التطبيق.
 * الاستعلام يضمّن المنطقة: regions(id,name_ar). أي شكل غير متوقع يُسقط JSONException.
 */
object SupabaseNewsParser {

    const val SELECT = "id,title,excerpt,content,source_name,published_at,regions(id,name_ar)"

    fun parseList(json: String): List<NewsItem> {
        val array = JSONArray(json)
        return List(array.length()) { parseItem(array.getJSONObject(it)) }
    }

    /** يُرجع null إذا كانت المصفوفة فارغة (خبر غير موجود أو غير معتمد). */
    fun parseFirstOrNull(json: String): NewsItem? {
        val array = JSONArray(json)
        return if (array.length() == 0) null else parseItem(array.getJSONObject(0))
    }

    private fun parseItem(obj: JSONObject): NewsItem {
        val region = obj.getJSONObject("regions")
        val content = text(obj, "content")
        return NewsItem(
            id = obj.getInt("id"),
            title = obj.getString("title"),
            excerpt = text(obj, "excerpt").ifEmpty { content.take(160) },
            content = content,
            source = text(obj, "source_name"),
            dateLabel = formatDate(text(obj, "published_at")),
            region = Region(id = region.getString("id"), nameAr = region.getString("name_ar")),
            isSample = false
        )
    }

    private fun text(obj: JSONObject, key: String): String =
        if (obj.isNull(key)) "" else obj.optString(key, "")

    fun formatDate(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            OffsetDateTime.parse(iso)
                .format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ar")))
        } catch (e: DateTimeParseException) {
            iso.take(10)
        }
    }
}
