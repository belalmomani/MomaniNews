package com.momaninews.app.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.settings.FollowLogic
import com.momaninews.app.data.settings.FollowStore
import com.momaninews.app.data.settings.PostPrefs

/** نص بلاغ عن خبر يُرسل بأي تطبيق يختاره المستخدم (لا خادم): رقم الخبر وعنوانه ومصدره. */
internal fun reportBody(news: NewsItem): String =
    "بلاغ عن خبر في تطبيق المومني نيوز\nرقم الخبر: ${news.id}\nالعنوان: ${news.title}\nالمصدر: ${news.source.ifBlank { "غير معروف" }}\nسبب البلاغ: "

/** قائمة «⋯» على بطاقة الخبر: مهتم / غير مهتم / حفظ / إبلاغ. كلها محلية على الجهاز عدا الإبلاغ (يُرسل برسالة). */
@Composable
fun NewsMenu(news: NewsItem, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val saved by PostPrefs.saved.collectAsStateWithLifecycle()
    val isSaved = news.id in saved
    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    Text(
        "⋯", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clickable { open = true }.padding(horizontal = 12.dp, vertical = 4.dp)
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (FollowLogic.key(news.source).isNotEmpty()) {
            DropdownMenuItem(text = { Text("＋ مهتم (تابع هذا المصدر)") }, onClick = {
                open = false; FollowStore.follow(news.source); toast("تتابع الآن: " + news.source)
            })
        }
        DropdownMenuItem(text = { Text("－ غير مهتم (أخفِ هذا الخبر)") }, onClick = {
            open = false; PostPrefs.hide(news.id); toast("تم إخفاء الخبر")
        })
        DropdownMenuItem(text = { Text(if (isSaved) "إلغاء حفظ المنشور" else "حفظ المنشور") }, onClick = {
            open = false; PostPrefs.toggleSaved(news.id); toast(if (isSaved) "أُلغي الحفظ" else "تم الحفظ")
        })
        DropdownMenuItem(text = { Text("الإبلاغ عن منشور") }, onClick = {
            open = false
            runCatching {
                ctx.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, reportBody(news)),
                        "إرسال البلاغ"
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        })
    }
}
