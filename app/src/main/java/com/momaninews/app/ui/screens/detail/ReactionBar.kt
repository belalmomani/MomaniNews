package com.momaninews.app.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momaninews.app.data.model.Reaction
import com.momaninews.app.data.model.ReactionSummary

/**
 * شريط التفاعلات بنمط فيسبوك: ضغطة = إعجاب (وإلغاؤه إن كان مختاراً)، وضغطة مطوّلة = شريط التفاعلات السبعة.
 * الملخص يعرض أكثر ثلاثة تفاعلات حضوراً مع المجموع. الرموز قياسية من النظام.
 */
@Composable
fun ReactionBar(summary: ReactionSummary, onReact: (Reaction?) -> Unit, modifier: Modifier = Modifier) {
    var pickerOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (summary.total > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(summary.top().joinToString("") { it.emoji }, fontSize = 18.sp)
                Text("${summary.total}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (pickerOpen) {
            Surface(
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shadowElevation = 4.dp
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Reaction.entries.forEach { r ->
                        Text(
                            r.emoji, fontSize = 30.sp,
                            modifier = Modifier.padding(4.dp).pointerInput(r) {
                                detectTapGestures(onTap = { pickerOpen = false; onReact(r) })
                            }
                        )
                    }
                }
            }
        }
        val mine = summary.mine
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (mine != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.pointerInput(mine) {
                detectTapGestures(
                    onTap = { pickerOpen = false; onReact(if (mine != null) null else Reaction.LIKE) },
                    onLongPress = { pickerOpen = !pickerOpen }
                )
            }
        ) {
            Text(
                text = if (mine != null) "${mine.emoji} ${mine.label}" else "👍 إعجاب (اضغط مطولاً للمزيد)",
                fontWeight = if (mine != null) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}
