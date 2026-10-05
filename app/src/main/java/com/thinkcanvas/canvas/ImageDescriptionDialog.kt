package com.thinkcanvas.canvas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

internal data class ImageDescriptionDraft(val id: String, val text: String,
    val original: String = text, val sessionId: String = UUID.randomUUID().toString()) {
    val changed: Boolean get() = text != original
}

@Composable
internal fun ImageDescriptionDialog(draft: ImageDescriptionDraft, editable: Boolean,
                                    cancellable: Boolean, completable: Boolean, failed: Boolean,
                                    onChange: (String) -> Unit, onComplete: () -> Unit,
                                    onCancel: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("代替テキストを編集") },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                Text("読み上げに使う説明です。空欄でも画像を使えます。")
                TextField(draft.text, onValueChange = onChange, enabled = editable,
                    label = { Text("画像の説明") })
                if (failed) Text("保存できませんでした。再試行してください。")
            }
        }, confirmButton = {
            TextButton(onClick = onComplete, enabled = completable) { Text(if (failed) "再試行" else "完了") }
        }, dismissButton = {
            TextButton(onClick = onCancel, enabled = cancellable) { Text("キャンセル") }
        })
}
