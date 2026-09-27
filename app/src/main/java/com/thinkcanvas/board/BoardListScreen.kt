package com.thinkcanvas.board

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thinkcanvas.data.StoredBoard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val paper = Color(0xFFFCFCFB)
private val ink = Color(0xFF23211E)
private val muted = Color(0xFF8D8882)
private val vermilion = Color(0xFFC54B32)

private fun displayName(name: String) = name.ifBlank { "無題のボード" }

private fun editedDate(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    return if (date == LocalDate.now(zone)) "今日"
    else date.format(DateTimeFormatter.ofPattern("M月d日", Locale.JAPAN))
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BoardListScreen(
    boards: List<StoredBoard>,
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit,
    onRename: (Long, String) -> Unit,
    onDuplicate: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onShare: (Long) -> Unit,
    onHelp: () -> Unit,
) {
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    val selected = boards.firstOrNull { it.details.id == selectedId }

    Box(Modifier.fillMaxSize().background(paper).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 14.dp,
                bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ボード", color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onHelp, modifier = Modifier.height(44.dp)) {
                    Text("使い方", color = muted, fontSize = 13.sp)
                }
            }
            if (boards.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("ボードはまだありません", color = muted)
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(boards, key = { it.details.id }) { board ->
                        val name = displayName(board.details.name)
                        Column(Modifier.fillMaxWidth().combinedClickable(
                            onClick = { onOpen(board.details.id) },
                            onLongClick = { selectedId = board.details.id },
                        ).semantics {
                            contentDescription = "$name、最終編集 ${editedDate(board.details.updatedAt)}"
                            onLongClick(label = "$name の操作") {
                                selectedId = board.details.id; true
                            }
                        }) {
                            BoardThumbnail(board.snapshot, Modifier.fillMaxWidth().height(132.dp)
                                .border(1.dp, Color(0xFFEEECE8), RoundedCornerShape(14.dp)))
                            Spacer(Modifier.height(8.dp))
                            Text(name, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 2.dp))
                            Text(editedDate(board.details.updatedAt), color = muted, fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 2.dp))
                        }
                    }
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                        Text("長押しで 名前を変える・複製・削除", color = Color(0xFFB1ACA5),
                            fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp)
            .size(58.dp).background(ink, RoundedCornerShape(18.dp))
            .clickable(onClick = onCreate)
            .semantics { contentDescription = "新しいボード" }, contentAlignment = Alignment.Center) {
            Text("＋", color = paper, fontSize = 26.sp)
        }
    }

    if (selected != null) ModalBottomSheet(onDismissRequest = { selectedId = null; renaming = false },
        containerColor = Color.White, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Text(displayName(selected.details.name), color = muted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp))
            if (renaming) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextField(renameValue, onValueChange = { renameValue = it },
                        placeholder = { Text("名前（なくてもいい）") }, singleLine = true,
                        modifier = Modifier.weight(1f).height(56.dp))
                    Button(onClick = {
                        onRename(selected.details.id, renameValue.trim())
                        selectedId = null; renaming = false
                    }, modifier = Modifier.padding(start = 10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ink)) { Text("保存") }
                }
            } else {
                listOf("名前を変える", "複製", "画像で共有", "削除", "キャンセル").forEach { action ->
                    Text(action, color = if (action == "削除") vermilion else ink,
                        fontSize = 15.sp, modifier = Modifier.fillMaxWidth().height(52.dp)
                            .clickable {
                                when (action) {
                                    "名前を変える" -> {
                                        renameValue = selected.details.name; renaming = true
                                    }
                                    "複製" -> { onDuplicate(selected.details.id); selectedId = null }
                                    "画像で共有" -> { onShare(selected.details.id); selectedId = null }
                                    "削除" -> { deleteId = selected.details.id; selectedId = null }
                                    else -> selectedId = null
                                }
                            }.padding(start = 22.dp, top = 15.dp))
                }
            }
        }
    }

    val deleting = boards.firstOrNull { it.details.id == deleteId }
    if (deleting != null) AlertDialog(
        onDismissRequest = { deleteId = null },
        title = { Text("ボードを削除") },
        text = { Text("「${displayName(deleting.details.name)}」を削除しますか？") },
        confirmButton = { TextButton(onClick = {
            onDelete(deleting.details.id); deleteId = null
        }) { Text("削除", color = vermilion) } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("キャンセル") } },
    )
}
