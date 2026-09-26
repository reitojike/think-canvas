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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.thinkcanvas.canvas.BoardState
import com.thinkcanvas.canvas.CanvasScreen
import com.thinkcanvas.data.CanvasStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )
        val store = CanvasStore.get(this)
        val board = mutableStateOf<BoardState?>(null)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF23211E),
                    secondary = Color(0xFFC54B32),
                    background = Color(0xFFFCFCFB),
                    surface = Color(0xFFFCFCFB),
                ),
            ) {
                val current = board.value
                if (current == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.loading_board))
                    }
                } else {
                    CanvasScreen(current) { store.save(current.snapshot()) }
                }
            }
        }

        lifecycleScope.launch {
            val snapshot = store.load()
            board.value = BoardState(snapshot.texts, snapshot.shapes, snapshot.arrows)
        }
    }
}
