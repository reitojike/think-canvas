package com.reitojike.thinkcanvas.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.reitojike.thinkcanvas.R

enum class SpatialTool(val label: String, val icon: String, @StringRes val labelRes: Int) {
    NONE("閉じる", "＋", R.string.tool_close),
    LASSO("まとめて選ぶ", "⌁", R.string.tool_lasso),
    REGION("囲み", "▢", R.string.tool_region),
    ARROW("矢印", "↗", R.string.tool_arrow),
    ELLIPSE("丸", "◯", R.string.tool_ellipse),
    RECTANGLE("四角", "□", R.string.tool_rectangle),
}

@Composable
fun SpatialTools(tool: SpatialTool, expanded: Boolean, enabled: Boolean,
                 onExpand: () -> Unit, onSelect: (SpatialTool) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        if (expanded) {
            listOf(SpatialTool.LASSO, SpatialTool.REGION, SpatialTool.ARROW,
                SpatialTool.ELLIPSE, SpatialTool.RECTANGLE).forEach { item ->
                val label = stringResource(item.labelRes)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.background(Color(0xFF23211E), RoundedCornerShape(5.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp))
                    ToolButton(item.icon, label, enabled) { onSelect(item) }
                }
            }
        }
        ToolButton(if (tool == SpatialTool.NONE) "+" else tool.icon,
            if (tool == SpatialTool.NONE) stringResource(R.string.tool_open)
            else "${stringResource(tool.labelRes)}${stringResource(R.string.tool_close)}", enabled,
            dark = true, onClick = onExpand)
    }
}

@Composable
private fun ToolButton(icon: String, label: String, enabled: Boolean,
                       dark: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).background(if (dark) Color(0xFF23211E) else Color.White, CircleShape)
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Text(icon, fontSize = 23.sp, color = if (dark) Color.White else Color(0xFF23211E))
    }
}

@Composable
fun Guidance(text: String) {
    Box(Modifier.background(Color(0xFF23211E), RoundedCornerShape(20.dp))
        .padding(horizontal = 15.dp, vertical = 9.dp)) {
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}
