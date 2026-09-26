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

enum class SpatialTool(val label: String, val icon: String) {
    NONE("閉じる", "＋"),
    LASSO("まとめて選ぶ", "⌁"),
    REGION("囲み", "▢"),
    ARROW("矢印", "↗"),
    ELLIPSE("丸", "◯"),
    RECTANGLE("四角", "□"),
}

@Composable
fun SpatialTools(tool: SpatialTool, expanded: Boolean, enabled: Boolean,
                 onExpand: () -> Unit, onSelect: (SpatialTool) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        if (expanded) {
            listOf(SpatialTool.LASSO, SpatialTool.REGION, SpatialTool.ARROW,
                SpatialTool.ELLIPSE, SpatialTool.RECTANGLE).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.label, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.background(Color(0xFF23211E), RoundedCornerShape(5.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp))
                    ToolButton(item.icon, item.label, enabled) { onSelect(item) }
                }
            }
        }
        ToolButton(if (tool == SpatialTool.NONE) "+" else tool.icon,
            if (tool == SpatialTool.NONE) "図形ツールを開く" else "${tool.label}ツールを閉じる", enabled,
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
