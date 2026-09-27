package com.thinkcanvas.data

import androidx.ink.brush.InputToolType
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.thinkcanvas.canvas.InkElement
import com.thinkcanvas.canvas.InkInputType
import com.thinkcanvas.canvas.InkKind
import com.thinkcanvas.canvas.InkPoint
import com.thinkcanvas.canvas.InkStroke
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@Entity(tableName = "ink_strokes")
data class InkStrokeRow(
    @PrimaryKey val id: String,
    val boardId: Long,
    val groupId: String,
    val sequence: Int,
    val kind: String,
    val startedAt: Long,
    val endedAt: Long,
    val inputType: String,
    val inputs: ByteArray,
) {
    fun toStroke(): InkStroke {
        val batch = ByteArrayInputStream(inputs).use { StrokeInputBatch.decode(it) }
        return InkStroke(id, startedAt, endedAt, InkInputType.valueOf(inputType),
            (0 until batch.size).map { index ->
                val point = batch[index]
                InkPoint(point.x, point.y, point.elapsedTimeMillis)
            })
    }

    companion object {
        fun fromModel(boardId: Long, element: InkElement): List<InkStrokeRow> = element.strokes.mapIndexed { index, stroke ->
            val batch = MutableStrokeInputBatch()
            val toolType = if (stroke.inputType == InkInputType.STYLUS) InputToolType.STYLUS else InputToolType.TOUCH
            stroke.points.forEach { point ->
                batch.add(StrokeInput().apply {
                    update(point.x, point.y, point.elapsedMillis, toolType,
                        StrokeInput.NO_STROKE_UNIT_LENGTH, StrokeInput.NO_PRESSURE,
                        StrokeInput.NO_TILT, StrokeInput.NO_ORIENTATION)
                })
            }
            val encoded = ByteArrayOutputStream().use { output ->
                batch.encode(output)
                output.toByteArray()
            }
            InkStrokeRow(stroke.id, boardId = boardId, groupId = element.id, sequence = index,
                kind = element.kind.name, startedAt = stroke.startedAt, endedAt = stroke.endedAt,
                inputType = stroke.inputType.name, inputs = encoded)
        }

        fun toElements(rows: List<InkStrokeRow>): List<InkElement> = rows.groupBy { it.groupId }
            .map { (id, group) ->
                InkElement(id, InkKind.valueOf(group.first().kind),
                    group.sortedBy { it.sequence }.map { it.toStroke() })
            }
    }
}
