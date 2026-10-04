package com.thinkcanvas.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.hypot

data class ViewportFocus(val centerX: Float, val centerY: Float, val scale: Float) {
    internal fun valid() = centerX.isFinite() && centerY.isFinite() &&
        scale.isFinite() && scale in .15f..3f
}

/** Session-local camera history. It has no reference to content, selection or saving. */
class ViewportHistory(private val capacity: Int = 80) {
    init { require(capacity > 0) }
    val viewportState = mutableStateOf(Viewport())
    var initialized by mutableStateOf(false)
        private set
    private var width = 0f
    private var height = 0f
    private var density = 1f
    private val previous = ArrayDeque<ViewportFocus>()
    private val next = ArrayDeque<ViewportFocus>()
    var canBack by mutableStateOf(false)
        private set
    var canForward by mutableStateOf(false)
        private set
    private var lastGroup: Any? = null

    fun resize(width: Float, height: Float, density: Float = 1f) {
        if (!width.isFinite() || width <= 0f || !height.isFinite() || height <= 0f ||
            !density.isFinite() || density <= 0f) return
        val current = focus()
        this.width = width
        this.height = height
        this.density = density
        if (current != null) viewportState.value = camera(current)
    }

    fun initialize(viewport: Viewport) {
        if (initialized || width <= 0f || height <= 0f || !valid(viewport)) return
        viewportState.value = viewport
        initialized = true
    }

    fun focus(): ViewportFocus? {
        if (!initialized || width <= 0f || height <= 0f || !valid(viewportState.value)) return null
        val view = viewportState.value
        val (x, y) = view.screenToWorld(width / 2f, height / 2f)
        return ViewportFocus(x, y, view.scale).takeIf { it.valid() }
    }

    fun record(origin: ViewportFocus?, group: Any? = null): Boolean {
        val current = focus() ?: return false
        if (origin == null || !origin.valid()) return false
        if (near(origin, current)) {
            if (group !== lastGroup) lastGroup = null
            return false
        }
        if (group == null || group !== lastGroup || previous.isEmpty()) {
            if (previous.lastOrNull()?.let { near(it, origin) } != true) push(previous, origin)
        }
        next.clear()
        lastGroup = group
        refresh()
        return true
    }

    fun back(): Viewport? = restore(previous, next)
    fun forward(): Viewport? = restore(next, previous)

    private fun restore(source: ArrayDeque<ViewportFocus>, destination: ArrayDeque<ViewportFocus>): Viewport? {
        val current = focus() ?: return null
        val target = source.removeLastOrNull() ?: return null
        push(destination, current)
        lastGroup = null
        refresh()
        return camera(target)
    }

    private fun camera(focus: ViewportFocus) = Viewport(focus.scale,
        width / 2f - focus.centerX * focus.scale, height / 2f - focus.centerY * focus.scale)

    private fun push(stack: ArrayDeque<ViewportFocus>, focus: ViewportFocus) {
        stack.addLast(focus)
        if (stack.size > capacity) stack.removeFirst()
    }

    private fun refresh() {
        canBack = previous.isNotEmpty()
        canForward = next.isNotEmpty()
    }

    private fun near(a: ViewportFocus, b: ViewportFocus): Boolean =
        hypot(a.centerX - b.centerX, a.centerY - b.centerY) * maxOf(a.scale, b.scale) <= 2f * density &&
            abs(a.scale - b.scale) <= maxOf(a.scale, b.scale) * .01f

    private fun valid(viewport: Viewport) = viewport.scale.isFinite() && viewport.scale in .15f..3f &&
        viewport.panX.isFinite() && viewport.panY.isFinite()
}
