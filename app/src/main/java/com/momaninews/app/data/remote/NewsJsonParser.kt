package com.momaninews.app.data.remote

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.model.Region
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import org.json.JSONObject

/**
 * يحوّل استجابات JSON حسب docs/API_CONTRACT.md إلى نماذج التطبيق.
 * أي استجابة لا تطابق العقد (مثل صفحة HTML من الاستضافة) تُسقط JSONException.
 */
object NewsJsonParser {

    fun parseList(json: String): List<NewsItem> {
        val array = JSONObject(json).getJSONArray("items")
        return List(array.length()) { index -> parseItem(array.getJSONObject(index)) }
    }

    fun parseDetail(json: String): NewsItem = parseItem(JSONObject(json))

    private fun parseItem(obj: JSONObject): NewsItem {
        val region = obj.getJSONObject("region")
        return NewsItem(
            id = obj.getInt("id"),
            title = obj.getString("title"),
            excerpt = text(obj, "excerpt"),
            content = text(obj, "content"),
            source = text(obj, "source"),
            dateLabel = formatDate(text(obj, "published_at")),
            region = Region(id = region.getString("id"), nameAr = region.getString("name")),
            isSample = false
        )
    }

    // org.json يُرجع النص "null" لقيمة JSON الفارغة، لذلك نعالجها صراحة.
    private fun text(obj: JSONObject, key: String): String =
        if (obj.isNull(key)) "" else obj.optString(key, "")

    private fun formatDate(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            OffsetDateTime.parse(iso)
                .format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ar")))
        } catch (e: DateTimeParseException) {
            iso.take(10)
        }
    }
}
