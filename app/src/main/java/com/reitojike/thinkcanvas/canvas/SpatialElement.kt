package com.reitojike.thinkcanvas.canvas

import java.util.UUID

enum class ShapeKind { RECTANGLE, ELLIPSE, REGION }

data class ShapeElement(
    val id: String = UUID.randomUUID().toString(),
    val kind: ShapeKind,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: TextColor = TextColor.INK,
    val name: String = "",
) {
    init {
        require(x.isFinite() && y.isFinite())
        require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
    }
}

sealed interface ArrowEnd {
    data class Free(val x: Float, val y: Float) : ArrowEnd {
        init { require(x.isFinite() && y.isFinite()) }
    }

    data class Attached(val targetId: String, val u: Float, val v: Float) : ArrowEnd {
        init {
            require(targetId.isNotBlank())
            require(u.isFinite() && u in 0f..1f && v.isFinite() && v in 0f..1f)
        }
    }
}

data class ArrowElement(
    val id: String = UUID.randomUUID().toString(),
    val from: ArrowEnd,
    val to: ArrowEnd,
    val bend: Float = 0f,
) {
    init { require(bend.isFinite()) }

    fun reversed() = copy(from = to, to = from, bend = -bend)
}

data class BoardSnapshot(
    val texts: List<TextElement> = emptyList(),
    val shapes: List<ShapeElement> = emptyList(),
    val arrows: List<ArrowElement> = emptyList(),
)
