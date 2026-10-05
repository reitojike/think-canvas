package com.thinkcanvas.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.thinkcanvas.canvas.ImageElement
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.ImageElementRow
import com.thinkcanvas.data.showBoardOneAtStartup
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

internal fun imageFixture(context: Context): File {
    val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
    for (y in 0 until 40) for (x in 0 until 80) bitmap.setPixel(x, y,
        when { x < 40 && y < 20 -> Color.TRANSPARENT; x >= 40 && y < 20 -> Color.GREEN
            x < 40 -> Color.BLUE; else -> Color.RED })
    return File(context.cacheDir, "fixture-${UUID.randomUUID()}.png").also { file ->
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

internal fun seedImage(context: Context, description: String = "テスト画像"): ImageElement = runBlocking {
    val source = imageFixture(context)
    try {
        CanvasStore.get(context).importImage(Uri.fromFile(source)).await().use { imported ->
            val image = ImageElement(assetId = imported.asset.assetId, x = 200f, y = 300f,
                width = 200f, height = 100f, intrinsicWidth = 80, intrinsicHeight = 40, altText = description)
            val database = CanvasDatabase.open(context)
            try {
                val dao = database.canvasDao()
                if (dao.board(1) == null) dao.putBoard(BoardRow())
                dao.replaceAll(1, emptyList(), emptyList(), emptyList(), images = listOf(ImageElementRow.fromModel(1, image)))
            } finally { database.close() }
            showBoardOneAtStartup(context)
            image
        }
    } finally { source.delete() }
}
