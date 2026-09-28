package com.thinkcanvas.board

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideSheet(onStart: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 22.dp)) {
            Text("基本の操作", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF23211E))
            Spacer(Modifier.height(18.dp))
            Text("空白をタップ → その場所に書く", fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Text("書いたものを長押し → そのままドラッグ → 動かす。離すとメニュー", fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Text("空白を長押し → そのままドラッグ → 余白を作る。囲みの中なら囲みの中だけ", fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            Text("ペン・図形・囲みは右下の＋から、検索は右上から。この案内はボード一覧の「使い方」でまた見られます",
                fontSize = 12.sp, color = Color(0xFF8D8882))
            Spacer(Modifier.height(18.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23211E))) {
                Text("はじめる")
            }
        }
    }
}
