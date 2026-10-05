package com.thinkcanvas.board

import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.BoardSnapshot
import java.util.UUID

fun BoardSnapshot.duplicated(): BoardSnapshot {
    val ids = (texts.map { it.id } + shapes.map { it.id } + arrows.map { it.id } +
        ink.map { it.id } + images.map { it.id }).associateWith { UUID.randomUUID().toString() }
    fun newId(id: String): String = requireNotNull(ids[id]) { "複製元の要素が見つかりません" }
    fun end(source: ArrowEnd): ArrowEnd = when (source) {
        is ArrowEnd.Attached -> source.copy(targetId = newId(source.targetId))
        is ArrowEnd.Free -> source
    }
    return BoardSnapshot(
        texts = texts.map { it.copy(id = newId(it.id)) },
        shapes = shapes.map { it.copy(id = newId(it.id)) },
        arrows = arrows.map { it.copy(id = newId(it.id), from = end(it.from), to = end(it.to)) },
        ink = ink.map { element -> element.copy(id = newId(element.id),
            strokes = element.strokes.map { it.copy(id = UUID.randomUUID().toString()) }) },
        images = images.map { it.copy(id = newId(it.id)) },
    )
}
