package com.thinkcanvas.canvas

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.thinkcanvas.image.ImageDecodePolicy
import com.thinkcanvas.image.ImageResourceKey
import com.thinkcanvas.image.ImageResources
import kotlin.math.roundToInt

@Composable
fun ImageElements(snapshot: BoardSnapshot, viewport: Viewport, projection: SemanticProjection,
                  selected: Set<String>, canvasSize: IntSize, resources: ImageResources?, enabled: Boolean,
                  onSelect: (String) -> Boolean, onAdd: (String) -> Boolean, onRemove: (String) -> Boolean,
                  onMove: (String, Float, Float) -> Boolean, onResize: (String, Float) -> Boolean,
                  onDelete: (String) -> Boolean, onDescribe: (String) -> Boolean) {
    val density = LocalDensity.current
    val visible = snapshot.images.filter { image ->
        val (x, y) = viewport.worldToScreen(image.x, image.y)
        projection.visible(image.id) && x <= canvasSize.width && y <= canvasSize.height &&
            x + image.width * viewport.scale >= 0f && y + image.height * viewport.scale >= 0f
    }
    val keys = visible.associate { image ->
        val side = when (projection.tier) {
            SemanticTier.FAR -> 256
            SemanticTier.MID -> 1024
            SemanticTier.NEAR -> (maxOf(image.width, image.height) * viewport.scale).toInt()
        }
        image.id to ImageResourceKey(image.assetId, ImageDecodePolicy.bucket(side))
    }
    val revision = resources?.revision?.collectAsState()?.value ?: 0L
    LaunchedEffect(keys, resources) { keys.values.forEach { resources?.ensure(it) } }
    Canvas(Modifier.fillMaxSize()) {
        // Read the revision during draw; nodes never own a Bitmap in Compose state.
        @Suppress("UNUSED_VARIABLE") val frame = revision
        visible.forEach { image ->
            val (x, y) = viewport.worldToScreen(image.x, image.y)
            val width = image.width * viewport.scale
            val height = image.height * viewport.scale
            val bitmap = keys[image.id]?.let { resources?.bitmap(it) }
            if (bitmap == null) {
                drawRect(Color(0xFFE9E6E1), Offset(x, y), Size(width, height))
                drawLine(Color(0xFF8D8882), Offset(x, y), Offset(x + width, y + height))
                drawLine(Color(0xFF8D8882), Offset(x + width, y), Offset(x, y + height))
            } else drawContext.canvas.nativeCanvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            if (image.id in selected) drawRect(Color(0xFFC54B32), Offset(x, y), Size(width, height),
                style = Stroke(1.5.dp.toPx()))
        }
    }
    visible.forEach { image ->
        val (x, y) = viewport.worldToScreen(image.x + image.width / 2f, image.y + image.height / 2f)
        Box(Modifier.offset { IntOffset(x.roundToInt() - 24.dp.roundToPx(), y.roundToInt() - 24.dp.roundToPx()) }
            .size(48.dp).semantics {
                contentDescription = image.altText.ifBlank { "画像" } +
                    if (keys[image.id]?.let { resources?.failed(it) } == true) "、読み込めません" else ""
                stateDescription = if (image.id in selected) "選択中" else "未選択"
                if (!enabled) disabled()
                else {
                    onClick(label = "選択") { onSelect(image.id) }
                    customActions = listOf(
                        if (image.id in selected) CustomAccessibilityAction("選択から外す") { onRemove(image.id) }
                        else CustomAccessibilityAction("選択に追加") { onAdd(image.id) },
                        CustomAccessibilityAction("左へ移動") { onMove(image.id, -16f, 0f) },
                        CustomAccessibilityAction("右へ移動") { onMove(image.id, 16f, 0f) },
                        CustomAccessibilityAction("上へ移動") { onMove(image.id, 0f, -16f) },
                        CustomAccessibilityAction("下へ移動") { onMove(image.id, 0f, 16f) },
                        CustomAccessibilityAction("大きくする") { onResize(image.id, 1.2f) },
                        CustomAccessibilityAction("小さくする") { onResize(image.id, 1f / 1.2f) },
                        CustomAccessibilityAction("代替テキストを編集") { onDescribe(image.id) },
                        CustomAccessibilityAction("削除") { onDelete(image.id) },
                    )
                }
            })
        if (image.id in selected) {
            val (handleX, handleY) = viewport.worldToScreen(image.x + image.width, image.y + image.height)
            Box(Modifier.offset { IntOffset(handleX.roundToInt() - 24.dp.roundToPx(), handleY.roundToInt() - 24.dp.roundToPx()) }
                .size(48.dp).semantics {
                    contentDescription = "画像のサイズ変更"
                    if (!enabled) disabled() else onClick { onResize(image.id, 1.2f) }
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).background(Color(0xFFC54B32), CircleShape))
            }
        }
    }
}
