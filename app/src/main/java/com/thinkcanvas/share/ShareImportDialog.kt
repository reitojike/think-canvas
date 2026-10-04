package com.thinkcanvas.share

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thinkcanvas.data.BoardRow

@Composable
fun ShareImportDialog(state: ShareImportState, boards: List<BoardRow>, destinationName: String?,
                      destinationReady: Boolean, onPick: (Long) -> Unit, onChange: () -> Unit,
                      onCreate: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit,
                      onRetry: () -> Unit) {
    if (!state.blocksCanvas || state.phase == ShareImportPhase.EMPTY) return
    val picking = state.phase == ShareImportPhase.PICKER
    val preview = state.phase == ShareImportPhase.PREVIEW
    val enabled = !state.writing
    val cancelable = (picking || preview) && enabled
    AlertDialog(
        onDismissRequest = { if (cancelable) onCancel() },
        title = { Text(if (picking) "取り込み先を選択" else "共有テキスト") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (picking || preview) {
                    Text(checkNotNull(state.request).text, Modifier.padding(bottom = 16.dp))
                    if (picking) {
                        if (boards.isEmpty()) Text("ボードがありません")
                        boards.forEach { board ->
                            val selected = board.id == state.request.destinationId
                            Row(Modifier.fillMaxWidth().semantics { this.selected = selected }
                                .clickable(enabled = enabled, role = Role.RadioButton) { onPick(board.id) }
                                .padding(vertical = 8.dp)) {
                                RadioButton(selected, onClick = null)
                                Text(board.name.ifBlank { "無題のボード" }, Modifier.padding(top = 12.dp))
                            }
                        }
                        TextButton(enabled = enabled, onClick = onCreate) { Text("新しいボード") }
                    } else {
                        Text("取り込み先: ${destinationName?.ifBlank { "無題のボード" } ?: "読み込み中"}")
                        TextButton(enabled = enabled, onClick = onChange) { Text("取り込み先を変更") }
                    }
                } else Text(when (state.phase) {
                    ShareImportPhase.FAILED -> "取り込みを完了できませんでした。同じ内容で再試行できます"
                    ShareImportPhase.SAVING -> "保存しています"
                    else -> "取り込み先を準備しています"
                })
            }
        },
        confirmButton = {
            when {
                preview -> TextButton(enabled = enabled && destinationReady && destinationName != null,
                    onClick = onConfirm) { Text("取り込む") }
                state.phase == ShareImportPhase.FAILED -> TextButton(enabled = enabled,
                    onClick = onRetry) { Text(if (state.request?.accepted == true) "保存を再試行" else "再試行") }
            }
        },
        dismissButton = if (picking || preview) ({
            TextButton(enabled = enabled, onClick = onCancel) { Text("キャンセル") }
        }) else null,
    )
}
