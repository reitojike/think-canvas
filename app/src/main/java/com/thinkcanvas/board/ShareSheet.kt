package com.thinkcanvas.board

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    title: String,
    bitmap: Bitmap?,
    message: String?,
    busy: Boolean,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ink = Color(0xFF23211E)
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("画像で共有", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(title.ifBlank { "無題のボード" }, color = Color(0xFF8D8882), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp))
            }
            Box(Modifier.fillMaxWidth().height(218.dp)
                .background(Color(0xFFFCFCFB), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFEEECE8), RoundedCornerShape(12.dp))
                .semantics { contentDescription = "$title の共有画像プレビュー" },
                contentAlignment = Alignment.Center) {
                when {
                    bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(216.dp))
                    message != null -> Text(message, color = Color(0xFF8D8882), fontSize = 13.sp)
                    else -> CircularProgressIndicator(color = ink)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("画像を保存", onSave, "画像を端末に保存"),
                    Triple("コピー", onCopy, "画像をコピー"),
                    Triple("ほかのアプリ", onShare, "Android の共有メニューを開く"))
                    .forEach { (label, action, description) ->
                        Button(onClick = action, enabled = bitmap != null && !busy,
                            modifier = Modifier.weight(1f).height(48.dp)
                                .semantics { contentDescription = description },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF3F2EF), contentColor = ink)) {
                            Text(label, fontSize = 12.sp, maxLines = 1)
                        }
                    }
            }
        }
    }
}
