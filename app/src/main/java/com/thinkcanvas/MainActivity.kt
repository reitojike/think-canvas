package com.thinkcanvas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.thinkcanvas.board.BoardListScreen
import com.thinkcanvas.board.GuideSheet
import com.thinkcanvas.canvas.BoardState
import com.thinkcanvas.canvas.CanvasScreen
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.StoredBoard
import kotlinx.coroutines.launch

private sealed interface Page {
    data object Loading : Page
    data object List : Page
    data class Board(val id: Long, val name: String, val state: BoardState) : Page
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )
        val store = CanvasStore.get(this)
        val page = mutableStateOf<Page>(Page.Loading)
        val cards = mutableStateOf<List<StoredBoard>>(emptyList())
        val guideVisible = mutableStateOf(false)

        fun openBoard(stored: StoredBoard) {
            page.value = Page.Board(stored.details.id, stored.details.name,
                BoardState(stored.snapshot.texts, stored.snapshot.shapes,
                    stored.snapshot.arrows, stored.snapshot.ink))
            guideVisible.value = !store.guideDismissed() &&
                stored.snapshot.texts.isEmpty() && stored.snapshot.shapes.isEmpty() &&
                stored.snapshot.arrows.isEmpty() && stored.snapshot.ink.isEmpty()
        }

        fun showList() {
            lifecycleScope.launch {
                cards.value = store.boardsWithContent()
                page.value = Page.List
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF23211E),
                    secondary = Color(0xFFC54B32),
                    background = Color(0xFFFCFCFB),
                    surface = Color(0xFFFCFCFB),
                ),
            ) {
                when (val current = page.value) {
                    Page.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.loading_board))
                    }
                    Page.List -> BoardListScreen(cards.value,
                        onOpen = { id -> lifecycleScope.launch {
                            store.open(id)?.let(::openBoard)
                        } },
                        onCreate = { lifecycleScope.launch { openBoard(store.create()) } },
                        onRename = { id, name -> lifecycleScope.launch {
                            store.rename(id, name)
                            showList()
                        } },
                        onDuplicate = { id -> lifecycleScope.launch {
                            store.duplicate(id)
                            showList()
                        } },
                        onDelete = { id -> lifecycleScope.launch {
                            store.delete(id)
                            showList()
                        } },
                        onShare = { /* 共有シートは次の作業で接続する */ },
                        onHelp = { guideVisible.value = true },
                    )
                    is Page.Board -> key(current.id) {
                        CanvasScreen(current.state,
                            boardName = current.name.ifBlank { "無題のボード" },
                            onOpenList = ::showList,
                            onCommittedChange = { store.save(current.id, current.state.snapshot()) })
                    }
                }
                if (guideVisible.value) GuideSheet {
                    guideVisible.value = false
                    store.dismissGuide()
                }
            }
        }

        lifecycleScope.launch {
            val restored = store.restore()
            if (restored == null) showList() else openBoard(restored)
        }
    }
}
