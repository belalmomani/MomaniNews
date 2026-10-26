package com.momaninews.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.model.Regions
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * منطق الرادار (نقي وقابل للاختبار). لا إحداثيات جغرافية في بياناتنا، فالموقع هنا هو **المنطقة** (عبين/عبلين/صخرة/المومني):
 *  - الاتجاه: لكل منطقة ربع ثابت من الدائرة، وموضع الخبر داخل ربعه ثابت (من رقمه) فلا يقفز بين التحديثات؛
 *  - المسافة: الأحدث قرب الحافة، ويقترب من المركز كلما قدم حتى يختفي بانقضاء مدته (نفس مدة شريط الأخبار).
 */
object RadarLogic {
    data class Blip(val news: NewsItem, val angleDeg: Float, val radiusFrac: Float, val urgent: Boolean, val unread: Boolean)

    private const val OUTER = 0.88f
    private const val SPAN = 0.55f

    fun sectorIndex(regionId: String): Int =
        Regions.all.indexOfFirst { it.id == regionId }.let { if (it < 0) Regions.all.lastIndex else it }

    fun angleOf(n: NewsItem): Float = sectorIndex(n.region.id) * 90f + 12f + Math.floorMod(n.id * 37, 66)

    fun radiusOf(n: NewsItem, nowMillis: Long): Float {
        val window = if (TickerLogic.isUrgent(n)) TickerLogic.URGENT_WINDOW_MS else TickerLogic.NORMAL_WINDOW_MS
        val frac = ((nowMillis - n.publishedAtMillis).toFloat() / window).coerceIn(0f, 1f)
        return OUTER - SPAN * frac
    }

    fun blips(eligible: List<NewsItem>, read: Set<Int>, nowMillis: Long): List<Blip> =
        eligible.map { Blip(it, angleOf(it), radiusOf(it, nowMillis), TickerLogic.isUrgent(it), it.id !in read) }

    /** موضع النقطة داخل مربع بطول [size] (0° = أعلى، باتجاه عقارب الساعة). */
    fun position(b: Blip, size: Float): Pair<Float, Float> {
        val c = size / 2f
        val r = c * b.radiusFrac
        val a = Math.toRadians(b.angleDeg.toDouble())
        return (c + r * sin(a)).toFloat() to (c - r * cos(a)).toFloat()
    }

    /** أقرب نقطة ضمن [threshold] من موضع اللمس؛ null إن لم توجد. */
    fun hit(blips: List<Blip>, x: Float, y: Float, size: Float, threshold: Float): Blip? =
        blips.map { it to position(it, size).let { (px, py) -> hypot(px - x, py - y) } }
            .filter { it.second <= threshold }.minByOrNull { it.second }?.first

    fun ageLabel(nowMillis: Long, publishedAt: Long): String {
        val m = ((nowMillis - publishedAt) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "الآن"
            m < 60 -> "قبل $m دقيقة"
            m < 24 * 60 -> "قبل ${m / 60} ساعة"
            else -> "قبل ${m / (24 * 60)} يوم"
        }
    }
}

private val RadarRed = Color(0xFFC62828)

/** رسم الرادار: حلقات وخطّان متعامدان وكاسحة بطيئة اختيارية ونقاط الأخبار (ممتلئة = غير مقروء، فارغة = مقروء، حمراء = عاجل). */
private fun DrawScope.drawRadar(blips: List<RadarLogic.Blip>, sweepDeg: Float?, line: Color, accent: Color, dot: Float, withAxes: Boolean) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension / 2
    val stroke = Stroke(width = 1.dp.toPx())
    drawCircle(line, r - 0.5.dp.toPx(), c, style = stroke)
    drawCircle(line.copy(alpha = line.alpha * 0.6f), r * 0.62f, c, style = stroke)
    if (withAxes) {
        drawLine(line.copy(alpha = line.alpha * 0.5f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(line.copy(alpha = line.alpha * 0.5f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
    }
    if (sweepDeg != null) {
        drawArc(accent.copy(alpha = 0.22f), startAngle = sweepDeg - 90f - 45f, sweepAngle = 45f, useCenter = true,
            topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2))
    }
    blips.forEach { b ->
        val (x, y) = RadarLogic.position(b, size.width)
        val col = if (b.urgent) RadarRed else accent
        if (b.unread) drawCircle(col, dot, Offset(x, y)) else drawCircle(col.copy(alpha = 0.7f), dot, Offset(x, y), style = Stroke(1.dp.toPx()))
    }
}

/** رادار صغير داخل الشريط: كاسحة بطيئة هادئة (تتوقف مع الإيقاف المؤقت وتعطيل حركات النظام). الضغط يفتح الرادار الكبير. */
@Composable
fun MiniRadar(blips: List<RadarLogic.Blip>, animate: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sweep = if (animate) {
        rememberInfiniteTransition(label = "radar").animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "sweep").value
    } else null
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val accent = MaterialTheme.colorScheme.primary
    val unread = blips.count { it.unread }
    Canvas(
        modifier = modifier.size(30.dp)
            .semantics { contentDescription = "رادار الأخبار، $unread غير مقروء. اضغط للفتح" }
            .clickable(role = Role.Button, onClick = onClick)
    ) { drawRadar(blips, sweep, line, accent, dot = 2.dp.toPx(), withAxes = false) }
}

/**
 * نافذة الرادار الكبير: النقاط قابلة للضغط (يُفتح الخبر)، وأسماء المناطق على أرباعها، وقائمة تحتها بنفس الأخبار
 * (بديل لمن يصعب عليه الضغط على نقطة صغيرة ولقارئات الشاشة).
 */
@Composable
fun RadarDialog(
    blips: List<RadarLogic.Blip>,
    nowMillis: Long,
    onOpen: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("رادار الأخبار", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("إغلاق") }
                }
                // اتجاه ثابت (LTR) كي تبقى الأرباع في أماكنها بغضّ النظر عن لغة الواجهة
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    val accent = MaterialTheme.colorScheme.primary
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(250.dp)) {
                            Canvas(
                                Modifier.size(250.dp).pointerInput(blips) {
                                    detectTapGestures { p ->
                                        RadarLogic.hit(blips, p.x, p.y, size.width.toFloat(), 26.dp.toPx())?.let { onOpen(it.news.id) }
                                    }
                                }
                            ) { drawRadar(blips, null, line, accent, dot = 5.dp.toPx(), withAxes = true) }
                            val label = MaterialTheme.colorScheme.onSurfaceVariant
                            Regions.all.forEachIndexed { i, region ->
                                val align = when (i) { 0 -> Alignment.TopEnd; 1 -> Alignment.BottomEnd; 2 -> Alignment.BottomStart; else -> Alignment.TopStart }
                                Text(region.nameAr, fontSize = 11.sp, color = label, modifier = Modifier.align(align).padding(6.dp))
                            }
                        }
                    }
                }
                Text(
                    "الاتجاه = المنطقة • الأقرب للحافة = الأحدث • ممتلئة = لم تُقرأ • فارغة = مقروءة • حمراء = عاجل",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
                    blips.sortedWith(compareByDescending<RadarLogic.Blip> { it.urgent }.thenByDescending { it.news.publishedAtMillis }).forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(b.news.id) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(if (b.urgent) "●" else if (b.unread) "●" else "○", color = if (b.urgent) RadarRed else MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                            Column(Modifier.weight(1f)) {
                                Text(b.news.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (b.unread) FontWeight.Medium else FontWeight.Normal)
                                Text("${b.news.region.nameAr} • ${RadarLogic.ageLabel(nowMillis, b.news.publishedAtMillis)}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
