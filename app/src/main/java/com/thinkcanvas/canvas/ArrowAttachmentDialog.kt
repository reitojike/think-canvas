package com.thinkcanvas.canvas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class AttachmentAnchor(val u: Float, val v: Float, val label: String) {
    LEFT(0f, .5f, "左側"),
    RIGHT(1f, .5f, "右側"),
    TOP(.5f, 0f, "上側"),
    BOTTOM(.5f, 1f, "下側"),
}

@Composable
fun ArrowAttachmentDialog(
    snapshot: BoardSnapshot,
    arrow: ArrowElement,
    endKind: HandleKind,
    onDismiss: () -> Unit,
    onAttach: (ArrowEnd.Attached) -> Boolean,
) {
    val targets = snapshot.texts.mapIndexed { index, text ->
        text.id to "文字 ${index + 1}: ${text.text.lineSequence().first().take(24)}"
    } + snapshot.shapes.mapIndexed { index, shape ->
        val kind = when (shape.kind) {
            ShapeKind.RECTANGLE -> "四角"
            ShapeKind.ELLIPSE -> "丸"
            ShapeKind.REGION -> "囲み"
        }
        shape.id to "$kind ${index + 1}${shape.name.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}"
    }
    val current = (if (endKind == HandleKind.FROM) arrow.from else arrow.to) as? ArrowEnd.Attached
    var selectedTarget by remember(arrow.id, endKind) {
        mutableStateOf(current?.targetId?.takeIf { id -> targets.any { it.first == id } }
            ?: targets.firstOrNull()?.first)
    }
    var selectedAnchor by remember(arrow.id, endKind) {
        mutableStateOf(if (current == null) AttachmentAnchor.RIGHT else
            AttachmentAnchor.entries.minByOrNull { anchor ->
                kotlin.math.abs(anchor.u - current.u) + kotlin.math.abs(anchor.v - current.v)
            } ?: AttachmentAnchor.RIGHT)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (endKind == HandleKind.FROM) "矢印の始点を接続" else "矢印の終点を接続") },
        text = {
            Column {
                Text("接続先")
                if (targets.isEmpty()) Text("接続できる要素がありません")
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    targets.forEach { (id, label) ->
                        Row(Modifier.fillMaxWidth().clickable { selectedTarget = id }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedTarget == id, onClick = null)
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text("接続する側", modifier = Modifier.padding(top = 12.dp))
                AttachmentAnchor.entries.forEach { anchor ->
                    Row(Modifier.fillMaxWidth().clickable { selectedAnchor = anchor }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedAnchor == anchor, onClick = null)
                        Text(anchor.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = selectedTarget ?: return@TextButton
                if (onAttach(ArrowEnd.Attached(id, selectedAnchor.u, selectedAnchor.v))) onDismiss()
            }, enabled = selectedTarget != null) { Text("接続") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
