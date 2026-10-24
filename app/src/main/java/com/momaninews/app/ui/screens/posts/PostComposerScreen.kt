package com.momaninews.app.ui.screens.posts

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.model.Regions
import com.momaninews.app.data.posts.PostException
import com.momaninews.app.data.posts.PostMood
import com.momaninews.app.data.posts.PostRules
import com.momaninews.app.data.posts.PostsRepositories
import com.momaninews.app.ui.components.MessageState
import com.momaninews.app.ui.screens.home.DrawerLogic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * شاشة «منشور جديد» بنمط فيسبوك: الاسم والمنطقة في الأعلى، حقل «بم تفكر؟» الكبير، معاينة الصورة،
 * وبطاقات الإضافات (المعرض، المنطقة). لا يظهر المنشور لأحد قبل اعتماد المدير.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostComposerScreen(onBack: () -> Unit, onSignInClick: () -> Unit) {
    val ctx = LocalContext.current
    val repo = PostsRepositories.default
    val user by AuthRepositories.default.currentUser.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var region by remember { mutableStateOf<String?>(null) }
    var image by remember { mutableStateOf<Uri?>(null) }
    var moodTag by remember { mutableStateOf<String?>(null) }
    var moodOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) image = uri }

    val finalText = PostMood.compose(text, moodTag)
    if (moodOpen) MoodPicker(onDismiss = { moodOpen = false }, onPick = { moodTag = it; moodOpen = false })

    val valid = PostRules.cleanText(finalText) != null && region != null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.Default.Close, contentDescription = "إغلاق") }
                },
                title = { Text("منشور جديد", fontWeight = FontWeight.Bold) },
                actions = {
                    Button(
                        enabled = valid && !busy && repo != null && user != null,
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                try {
                                    repo!!.submit(ctx, finalText, region!!, image)
                                    Toast.makeText(ctx, "أُرسل منشورك وسيظهر بعد اعتماد المدير", Toast.LENGTH_LONG).show()
                                    onBack()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: PostException) {
                                    error = when (e.reason) {
                                        PostException.Reason.TOO_MANY_PENDING -> "لديك 5 منشورات بانتظار الاعتماد. انتظر مراجعتها أولاً."
                                        PostException.Reason.TOO_MANY_TODAY -> "بلغت الحد اليومي للنشر (10 منشورات). حاول غداً."
                                        PostException.Reason.BAD_IMAGE -> "تعذر استخدام هذه الصورة. اختر صورة أخرى."
                                        PostException.Reason.INVALID -> "اكتب نصاً (حتى 5000 حرف) واختر المنطقة."
                                        else -> "تعذر إرسال المنشور. حاول مرة أخرى."
                                    }
                                } catch (e: Exception) {
                                    error = "تعذر إرسال المنشور. تحقق من الاتصال."
                                }
                                busy = false
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) { Text(if (busy) "جارٍ النشر…" else "نشر") }
                }
            )
        }
    ) { padding ->
        when {
            repo == null -> MessageState("غير متاح في الوضع التجريبي", "النشر يحتاج تسجيل الدخول بحساب Google الحقيقي.", Modifier.padding(padding))
            user == null -> MessageState("لم تسجّل الدخول", "سجّل الدخول لتشارك خبراً من منطقتك.", Modifier.padding(padding), "تسجيل الدخول", onSignInClick)
            else -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    // الرأس: أفاتار + الاسم
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(DrawerLogic.initial(user?.displayName ?: ""), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(user?.displayName ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            moodTag?.let {
                                Text("— $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (moodTag != null) {
                            IconButton(onClick = { moodTag = null }) { Icon(Icons.Default.Close, contentDescription = "إزالة") }
                        }
                    }
                    // شرائح المنطقة (مثل «الموقع/الجمهور» في فيسبوك) — مطلوبة
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Regions.all.forEach { r ->
                            FilterChip(selected = region == r.id, onClick = { region = r.id }, label = { Text("📍 " + r.nameAr) })
                        }
                    }
                    TextField(
                        value = text,
                        onValueChange = { text = it.take(PostRules.MAX_TEXT) },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        placeholder = { Text("بم تفكر؟", style = MaterialTheme.typography.titleLarge) },
                        textStyle = MaterialTheme.typography.titleMedium,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.surface
                        )
                    )
                    if (text.length > PostRules.MAX_TEXT - 500) {
                        Text("${text.length} / ${PostRules.MAX_TEXT}", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                    }
                    image?.let { uri ->
                        Box(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            AsyncImage(
                                model = uri, contentDescription = "الصورة المختارة", contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
                            )
                            IconButton(
                                onClick = { image = null },
                                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(32.dp)
                                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                            ) { Icon(Icons.Default.Close, contentDescription = "إزالة الصورة") }
                        }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "يُراجع المدير كل منشور قبل ظهوره للجميع. اختر المنطقة، واكتب خبراً صحيحاً يخصها.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                // بطاقات الإضافات (أسفل الشاشة كما في فيسبوك)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AddonCard("🖼️", if (image == null) "المعرض" else "تغيير الصورة", Modifier.weight(1f)) { picker.launch("image/*") }
                    AddonCard("😊", if (moodTag == null) "المشاعر والنشاط" else "تغيير", Modifier.weight(1f)) { moodOpen = true }
                    AddonCard("📍", if (region == null) "المنطقة" else Regions.all.first { it.id == region }.nameAr, Modifier.weight(1f)) {
                        // التبديل بين المناطق بالتدوير (الشرائح أعلاه للاختيار المباشر)
                        val all = Regions.all
                        val next = all.indexOfFirst { it.id == region } + 1
                        region = all[next % all.size].id
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoodPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var tab by remember { mutableStateOf(0) } // 0 مشاعر، 1 نشاطات
    var query by remember { mutableStateOf("") }
    var pendingActivity by remember { mutableStateOf<com.momaninews.app.data.posts.MoodItem?>(null) }
    var extra by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (pendingActivity != null) pendingActivity = null else onDismiss() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                    Text(
                        if (tab == 0) "بم تشعر؟" else "ماذا تفعل؟",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                    )
                }
                val act = pendingActivity
                if (act != null) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${act.emoji} ${act.label.removeSuffix("…")}", style = MaterialTheme.typography.titleMedium)
                        androidx.compose.material3.OutlinedTextField(
                            value = extra, onValueChange = { extra = it.take(PostMood.MAX_EXTRA) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("أكمل… (اختياري)") }
                        )
                        Button(onClick = { onPick(PostMood.activityTag(act, extra)) }, modifier = Modifier.fillMaxWidth()) { Text("تم") }
                    }
                } else {
                    Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = tab == 0, onClick = { tab = 0; query = "" }, label = { Text("المشاعر") })
                        FilterChip(selected = tab == 1, onClick = { tab = 1; query = "" }, label = { Text("النشاطات") })
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("بحث") }
                    )
                    val shown = PostMood.filter(if (tab == 0) PostMood.feelings else PostMood.activities, query)
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(shown.size) { i ->
                            val m = shown[i]
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .border(0.5.dp, MaterialTheme.colorScheme.outline)
                                    .clickable {
                                        if (tab == 0) onPick(PostMood.feelingTag(m)) else { extra = ""; pendingActivity = m }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(m.emoji, style = MaterialTheme.typography.headlineSmall)
                                Text(m.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonCard(icon: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedCard(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(14.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(icon, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
