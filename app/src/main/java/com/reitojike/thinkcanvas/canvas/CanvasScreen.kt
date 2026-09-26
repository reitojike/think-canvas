package com.reitojike.thinkcanvas.canvas

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.reitojike.thinkcanvas.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val paper = Color(0xFFFCFCFB)
private val ink = Color(0xFF23211E)
private val vermilion = Color(0xFFC54B32)
private val muted = Color(0xFF8D8882)
private val outline = Color(0xFFE8E6E2)
private val toolbar = Color(0xFFF3F2EF)

private data class Draft(
    val id: String?,
    val x: Float,
    val y: Float,
    val text: String = "",
    val kind: TextKind = TextKind.BODY,
    val color: TextColor = TextColor.INK,
)

@Composable
fun CanvasScreen(board: BoardState, onCommittedChange: () -> Deferred<Unit>) {
    var viewport by remember { mutableStateOf(Viewport()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tool by remember { mutableStateOf(SpatialTool.NONE) }
    var toolsExpanded by remember { mutableStateOf(false) }
    var spatialPreview by remember { mutableStateOf<SpatialPreview?>(null) }
    var lassoPoints by remember { mutableStateOf<List<WorldPoint>>(emptyList()) }
    var gapPreview by remember { mutableStateOf<Pair<WorldPoint, WorldPoint>?>(null) }
    var menuTarget by remember { mutableStateOf<String?>(null) }
    var regionNameId by remember { mutableStateOf<String?>(null) }
    var regionName by remember { mutableStateOf("") }
    var guidance by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var preview by remember { mutableStateOf<Pair<String, Pair<Float, Float>>?>(null) }
    var movePreview by remember { mutableStateOf<Pair<Set<String>, WorldPoint>?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var finishDraftAfterSave by remember { mutableStateOf(false) }
    var pendingNewElementId by remember { mutableStateOf<String?>(null) }
    val elementSizes = remember { mutableStateMapOf<String, IntSize>() }
    val chromeBounds = remember { mutableStateMapOf<String, Rect>() }
    val uiScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    val imeBottom = WindowInsets.ime.getBottom(density)
    val latestViewport = rememberUpdatedState(viewport)
    val latestElements = rememberUpdatedState(board.elements)
    val latestSnapshot = rememberUpdatedState(board.snapshot())
    val latestSelected = rememberUpdatedState(selectedId)
    val latestSelectedIds = rememberUpdatedState(selectedIds)
    val latestTool = rememberUpdatedState(tool)
    val latestDraft = rememberUpdatedState(draft)
    val latestSaveBlocked = rememberUpdatedState(saving || saveFailed)
    val latestCommit = rememberUpdatedState(onCommittedChange)
    val selectedLabel = stringResource(R.string.selection_state_selected)
    val unselectedLabel = stringResource(R.string.unselected)
    val selectLabel = stringResource(R.string.select)
    val editLabel = stringResource(R.string.edit)
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    val moveLeftLabel = stringResource(R.string.move_left)
    val moveRightLabel = stringResource(R.string.move_right)
    val moveElementLabel = stringResource(R.string.move_element)
    val newTextLabel = stringResource(R.string.new_text)
    val editTextLabel = stringResource(R.string.edit_text)
    val undoLabel = stringResource(R.string.undo)
    val redoLabel = stringResource(R.string.redo)

    fun saveSnapshot(closeDraft: Boolean = false) {
        if (saving) return
        finishDraftAfterSave = closeDraft
        saving = true
        saveFailed = false
        val pending = try {
            latestCommit.value()
        } catch (_: Exception) {
            saving = false
            saveFailed = true
            return
        }
        uiScope.launch {
            try {
                pending.await()
                if (finishDraftAfterSave) {
                    draft = null
                    keyboard?.hide()
                }
                pendingNewElementId = null
                finishDraftAfterSave = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveFailed = true
            } finally {
                saving = false
            }
        }
    }

    fun hitTest(point: Offset): TextElement? {
        val view = latestViewport.value
        return latestElements.value.asReversed().firstOrNull { element ->
            val (x, y) = view.worldToScreen(element.x, element.y)
            val size = elementSizes[element.id] ?: IntSize(160, 48)
            val width = maxOf(size.width * view.scale, with(density) { 44.dp.toPx() })
            val height = maxOf(size.height * view.scale, with(density) { 44.dp.toPx() })
            val inText = point.x in x..(x + width) && point.y in y..(y + height)
            val gripCenter = x + size.width * view.scale / 2f
            val gripRadius = with(density) { 24.dp.toPx() }
            val inGrip = latestSelected.value == element.id &&
                point.x in (gripCenter - gripRadius)..(gripCenter + gripRadius) &&
                point.y in (y + size.height * view.scale)..(y + size.height * view.scale + gripRadius * 2f)
            inText || inGrip
        }
    }

    fun hitSpatial(point: Offset): String? {
        val view = latestViewport.value
        val (wx, wy) = view.screenToWorld(point.x, point.y)
        val world = WorldPoint(wx, wy)
        val snapshot = latestSnapshot.value
        snapshot.arrows.asReversed().firstOrNull { arrow ->
            snapshot.distanceToArrow(world, arrow) <= 12f / view.scale
        }?.let { return it.id }
        return snapshot.shapes.asReversed().firstOrNull { it.hitStroke(world, 12f / view.scale) }?.id
    }

    fun endAt(point: Offset): ArrowEnd {
        val target = hitTest(point)?.id ?: hitSpatial(point)?.takeIf { id ->
            latestSnapshot.value.shapes.any { it.id == id }
        }
        val (x, y) = latestViewport.value.screenToWorld(point.x, point.y)
        val bounds = target?.let { latestSnapshot.value.boundsOf(it) }
        return if (target != null && bounds != null) ArrowEnd.Attached(
            target, ((x - bounds.left) / (bounds.right - bounds.left)).coerceIn(0f, 1f),
            ((y - bounds.top) / (bounds.bottom - bounds.top)).coerceIn(0f, 1f),
        ) else ArrowEnd.Free(x, y)
    }

    fun tap(point: Offset) {
        if (latestDraft.value != null || latestSaveBlocked.value) return
        if (chromeBounds.values.any { it.contains(point) }) return
        val element = hitTest(point)
        val spatial = if (element == null) hitSpatial(point) else null
        if (element == null && spatial == null) {
            if (latestSelectedIds.value.isNotEmpty()) { selectedId = null; selectedIds = emptySet() }
            else {
                val (x, y) = latestViewport.value.screenToWorld(point.x, point.y)
                draft = Draft(null, x, y)
            }
        } else if (element == null && spatial != null) {
            if (latestSelectedIds.value.size > 1 && spatial in latestSelectedIds.value) {
                selectedIds = latestSelectedIds.value - spatial
            } else if (spatial in latestSelectedIds.value && board.shapes.any { it.id == spatial && it.kind == ShapeKind.REGION }) {
                regionNameId = spatial
                regionName = board.shapes.first { it.id == spatial }.name
            } else { selectedId = null; selectedIds = setOf(spatial) }
        } else if (element != null && latestSelectedIds.value.size > 1 && element.id in latestSelectedIds.value) {
            selectedIds = latestSelectedIds.value - element.id
        } else if (element != null && latestSelected.value == element.id) {
            draft = Draft(element.id, element.x, element.y, element.text, element.kind, element.color)
        } else if (element != null) { selectedId = element.id; selectedIds = setOf(element.id) }
    }

    fun nudge(id: String, dx: Float, dy: Float): Boolean {
        if (saving || saveFailed) return false
        val element = board.elements.firstOrNull { it.id == id } ?: return false
        if (!board.moveSelection(setOf(id), dx, dy)) return false
        saveSnapshot()
        return true
    }

    fun commitDraft() {
        if (saving) return
        if (saveFailed) {
            saveSnapshot(finishDraftAfterSave)
            return
        }
        val current = draft ?: return
        val changed = if (current.id == null) {
            val created = board.create(current.text, current.kind, current.color, current.x, current.y)
            pendingNewElementId = created?.id
            created != null
        } else board.edit(current.id, current.text, current.kind, current.color)
        if (changed) saveSnapshot(closeDraft = true)
        else {
            draft = null
            keyboard?.hide()
        }
    }

    LaunchedEffect(draft?.id, draft?.x, draft?.y) {
        if (draft != null) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    LaunchedEffect(menuTarget) { if (menuTarget == null) chromeBounds.remove("menu") }
    LaunchedEffect(regionNameId) { if (regionNameId == null) chromeBounds.remove("regionName") }
    LaunchedEffect(guidance, tool, selectedIds) {
        if (guidance == null && tool == SpatialTool.NONE && selectedIds.size <= 1)
            chromeBounds.remove("guidance")
    }

    LaunchedEffect(draft?.id, draft?.x, draft?.y, imeBottom, canvasSize) {
        val current = draft ?: return@LaunchedEffect
        if (canvasSize == IntSize.Zero || imeBottom == 0) return@LaunchedEffect
        val (screenX, screenY) = viewport.worldToScreen(current.x, current.y)
        val maxX = canvasSize.width - with(density) { 174.dp.toPx() }
        val maxY = canvasSize.height - imeBottom - with(density) { 150.dp.toPx() }
        val dx = (maxX - screenX).coerceAtMost(0f)
        val dy = (maxY - screenY).coerceAtMost(0f)
        if (dx != 0f || dy != 0f) viewport = viewport.pan(dx, dy)
    }

    Box(
        modifier = Modifier.fillMaxSize().onSizeChanged { canvasSize = it }
            .background(paper).clipToBounds().pointerInput(board) {
            awaitEachGesture {
                try {
                val down = awaitFirstDown()
                if (latestDraft.value != null || latestSaveBlocked.value) return@awaitEachGesture
                if (chromeBounds.values.any { it.contains(down.position) }) return@awaitEachGesture
                val target = hitTest(down.position)
                var targetId = target?.id ?: hitSpatial(down.position)
                val startTime = SystemClock.uptimeMillis()
                val start = down.position
                var end = start
                val activeTool = latestTool.value
                val selectedGrip = target != null && target.id in latestSelectedIds.value &&
                    run {
                        val (_, y) = latestViewport.value.worldToScreen(target.x, target.y)
                        val height = (elementSizes[target.id]?.height ?: 48) * latestViewport.value.scale
                        down.position.y > y + height
                    }
                var handle: HandleKind? = null
                if (latestSelectedIds.value.isNotEmpty()) {
                    val snapshot = latestSnapshot.value
                    val radius = with(density) { 24.dp.toPx() }
                    snapshot.shapes.filter { it.id in latestSelectedIds.value }.firstOrNull { shape ->
                        val (x, y) = latestViewport.value.worldToScreen(shape.x + shape.width, shape.y + shape.height)
                        (start - Offset(x, y)).getDistance() <= radius
                    }?.let { shape -> targetId = shape.id; handle = HandleKind.RESIZE }
                    snapshot.shapes.filter { it.id in latestSelectedIds.value }.firstOrNull { shape ->
                        val (x, y) = latestViewport.value.worldToScreen(shape.x + shape.width / 2f,
                            shape.y + shape.height + 14f)
                        (start - Offset(x, y)).getDistance() <= radius
                    }?.let { shape -> if (handle == null) { targetId = shape.id; handle = HandleKind.MOVE } }
                    snapshot.arrows.filter { it.id in latestSelectedIds.value }.forEach { arrow ->
                        snapshot.arrowPoints(arrow)?.let { (a, b) ->
                            val (ax, ay) = latestViewport.value.worldToScreen(a.x, a.y)
                            val (bx, by) = latestViewport.value.worldToScreen(b.x, b.y)
                            if ((start - Offset(ax, ay)).getDistance() <= radius) {
                                targetId = arrow.id; handle = HandleKind.FROM
                            } else if ((start - Offset(bx, by)).getDistance() <= radius) {
                                targetId = arrow.id; handle = HandleKind.TO
                            }
                        }
                        snapshot.arrowControl(arrow)?.let { c ->
                            val (x, y) = latestViewport.value.worldToScreen(c.x, c.y)
                            if ((start - Offset(x, y)).getDistance() <= radius) {
                                targetId = arrow.id; handle = HandleKind.BEND
                            }
                        }
                    }
                }
                val activeId = targetId
                var mode = when {
                    activeTool == SpatialTool.LASSO -> "lasso"
                    activeTool != SpatialTool.NONE -> "create"
                    handle != null -> "handle"
                    selectedGrip -> "move"
                    else -> "tap"
                }
                val startWorld = latestViewport.value.screenToWorld(start.x, start.y).let { WorldPoint(it.first, it.second) }
                if (mode == "lasso") lassoPoints = listOf(startWorld)
                while (true) {
                    val remaining = longPressMillis - (SystemClock.uptimeMillis() - startTime)
                    val event = if (mode == "tap" && remaining > 0) {
                        withTimeoutOrNull(remaining) { awaitPointerEvent() }
                    } else if (mode == "tap" && remaining <= 0) null
                    else awaitPointerEvent()
                    if (event == null) {
                        mode = if (activeId == null) "gap" else "move"
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        continue
                    }
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        when (mode) {
                            "tap" -> tap(start)
                            "create" -> {
                                val worldEnd = latestViewport.value.screenToWorld(end.x, end.y)
                                    .let { WorldPoint(it.first, it.second) }
                                val distance = (end - start).getDistance()
                                val created = when (activeTool) {
                                    SpatialTool.RECTANGLE, SpatialTool.ELLIPSE, SpatialTool.REGION -> {
                                        val kind = when (activeTool) {
                                            SpatialTool.RECTANGLE -> ShapeKind.RECTANGLE
                                            SpatialTool.ELLIPSE -> ShapeKind.ELLIPSE
                                            else -> ShapeKind.REGION
                                        }
                                        val width = if (distance < touchSlop) when (kind) {
                                            ShapeKind.RECTANGLE -> 120f
                                            ShapeKind.ELLIPSE -> 110f
                                            ShapeKind.REGION -> 200f
                                        } else kotlin.math.abs(worldEnd.x - startWorld.x)
                                        val height = if (distance < touchSlop) when (kind) {
                                            ShapeKind.RECTANGLE -> 80f
                                            ShapeKind.ELLIPSE -> 80f
                                            ShapeKind.REGION -> 150f
                                        } else kotlin.math.abs(worldEnd.y - startWorld.y)
                                        val shape = board.addShape(kind,
                                            if (distance < touchSlop) startWorld.x else minOf(startWorld.x, worldEnd.x),
                                            if (distance < touchSlop) startWorld.y else minOf(startWorld.y, worldEnd.y),
                                            width, height)
                                        selectedIds = setOf(shape.id)
                                        selectedId = null
                                        if (kind == ShapeKind.REGION) { regionNameId = shape.id; regionName = "" }
                                        true
                                    }
                                    SpatialTool.ARROW -> if (distance >= touchSlop) {
                                        val arrow = board.addArrow(endAt(start), endAt(end))
                                        if (arrow != null) { selectedIds = setOf(arrow.id); selectedId = null }
                                        arrow != null
                                    } else false
                                    else -> false
                                }
                                if (created) saveSnapshot()
                                tool = SpatialTool.NONE
                            }
                            "lasso" -> {
                                selectedIds = latestSnapshot.value.lassoSelection(lassoPoints)
                                selectedId = selectedIds.singleOrNull()?.takeIf { id -> board.elements.any { it.id == id } }
                                guidance = "${selectedIds.size}個を選択"
                                tool = SpatialTool.NONE
                            }
                            "move" -> if (activeId != null) {
                                val dx = (end.x - start.x) / latestViewport.value.scale
                                val dy = (end.y - start.y) / latestViewport.value.scale
                                if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 1f) {
                                    val ids = if (activeId in latestSelectedIds.value) latestSelectedIds.value else setOf(activeId)
                                    val beforeRegion = board.snapshot().centerOf(activeId)?.let { board.snapshot().smallestRegionAt(it)?.id }
                                    if (board.moveSelection(ids, dx, dy)) {
                                        val afterRegion = board.snapshot().centerOf(activeId)?.let { board.snapshot().smallestRegionAt(it)?.id }
                                        if (beforeRegion != afterRegion) {
                                            val id = afterRegion ?: beforeRegion
                                            val label = board.shapes.firstOrNull { it.id == id }?.name
                                                ?.takeIf { it.isNotBlank() } ?: "囲み"
                                            guidance = if (afterRegion == null) "${label}から出ました" else "${label}に入りました"
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        saveSnapshot()
                                    }
                                } else if (activeId !in latestSelectedIds.value && latestSelectedIds.value.isNotEmpty()) {
                                    selectedIds = latestSelectedIds.value + activeId
                                    guidance = "${selectedIds.size}個を選択"
                                } else menuTarget = activeId
                            }
                            "handle" -> if (activeId != null && handle != null) {
                                val worldEnd = latestViewport.value.screenToWorld(end.x, end.y)
                                val changed = when (handle) {
                                    HandleKind.MOVE -> board.moveSelection(
                                        if (activeId in latestSelectedIds.value) latestSelectedIds.value else setOf(activeId),
                                        (end.x - start.x) / latestViewport.value.scale,
                                        (end.y - start.y) / latestViewport.value.scale,
                                    )
                                    HandleKind.RESIZE -> board.shapes.firstOrNull { it.id == activeId }?.let {
                                        board.resizeShape(activeId, worldEnd.first - it.x, worldEnd.second - it.y)
                                    } ?: false
                                    HandleKind.FROM -> board.updateArrow(activeId, from = endAt(end))
                                    HandleKind.TO -> board.updateArrow(activeId, to = endAt(end))
                                    HandleKind.BEND -> board.arrows.firstOrNull { it.id == activeId }?.let { arrow ->
                                        val points = board.snapshot().arrowPoints(arrow)
                                        if (points == null) false else {
                                            val dx = points.second.x - points.first.x
                                            val dy = points.second.y - points.first.y
                                            val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                                            val middle = WorldPoint((points.first.x + points.second.x) / 2f,
                                                (points.first.y + points.second.y) / 2f)
                                            board.updateArrow(activeId, bend = (-(worldEnd.first - middle.x) * dy +
                                                (worldEnd.second - middle.y) * dx) / length)
                                        }
                                    } ?: false
                                    null -> false
                                }
                                if (changed) saveSnapshot()
                            }
                            "gap" -> {
                                val dx = (end.x - start.x) / latestViewport.value.scale
                                val dy = (end.y - start.y) / latestViewport.value.scale
                                if (kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy)) >= 14f) {
                                    val horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
                                    if (board.insertGap(startWorld, horizontal, if (horizontal) dx else dy)) {
                                        guidance = "余白を作りました"
                                        saveSnapshot()
                                    }
                                }
                            }
                        }
                        preview = null
                        movePreview = null
                        spatialPreview = null
                        gapPreview = null
                        lassoPoints = emptyList()
                        break
                    }
                    if (pressed.size >= 2) {
                        mode = "zoom"
                        preview = null
                        movePreview = null
                        spatialPreview = null
                        gapPreview = null
                        lassoPoints = emptyList()
                        val tracked = pressed.filter { it.previousPressed }
                        if (tracked.size >= 2) {
                            val first = tracked[0]
                            val second = tracked[1]
                            val previousCenter = (first.previousPosition + second.previousPosition) / 2f
                            val center = (first.position + second.position) / 2f
                            val before = (first.previousPosition - second.previousPosition).getDistance()
                            val after = (first.position - second.position).getDistance()
                            if (before > 0f) viewport = latestViewport.value.zoomAt(
                                previousCenter.x, previousCenter.y, after / before,
                                center.x - previousCenter.x, center.y - previousCenter.y,
                            )
                        }
                    } else {
                        val change = pressed.first()
                        end = change.position
                        val delta = change.position - change.previousPosition
                        if (mode == "tap" && (change.position - start).getDistance() > touchSlop) mode = "pan"
                        when (mode) {
                            "pan" -> viewport = latestViewport.value.pan(delta.x, delta.y)
                            "move" -> if (activeId != null) {
                                val dx = (change.position.x - start.x) / latestViewport.value.scale
                                val dy = (change.position.y - start.y) / latestViewport.value.scale
                                val ids = if (activeId in latestSelectedIds.value) latestSelectedIds.value else setOf(activeId)
                                movePreview = ids to WorldPoint(dx, dy)
                            }
                            "create" -> {
                                val (x, y) = latestViewport.value.screenToWorld(end.x, end.y)
                                spatialPreview = SpatialPreview(startWorld, WorldPoint(x, y), activeTool)
                            }
                            "lasso" -> {
                                val (x, y) = latestViewport.value.screenToWorld(end.x, end.y)
                                lassoPoints = lassoPoints + WorldPoint(x, y)
                            }
                            "gap" -> {
                                val (x, y) = latestViewport.value.screenToWorld(end.x, end.y)
                                gapPreview = startWorld to WorldPoint(x, y)
                                guidance = if (kotlin.math.abs(x - startWorld.x) >= kotlin.math.abs(y - startWorld.y))
                                    "横に余白を作る" else "縦に余白を作る"
                            }
                        }
                    }
                    if (mode != "tap") event.changes.forEach { it.consume() }
                }
                } finally {
                    preview = null
                    movePreview = null
                    spatialPreview = null
                    gapPreview = null
                    lassoPoints = emptyList()
                }
            }
        },
    ) {
        if (board.elements.isEmpty() && board.shapes.isEmpty() && board.arrows.isEmpty() && draft == null) {
            Text(
                stringResource(R.string.empty_hint), color = Color(0xFFB1ACA5), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        val sourceSnapshot = board.snapshot()
        val displaySnapshot = when {
            movePreview != null -> movePreview!!.let { (ids, delta) ->
                sourceSnapshot.translatedSelection(ids, delta.x, delta.y)
            }
            gapPreview != null -> gapPreview!!.let { (start, end) ->
                val dx = end.x - start.x
                val dy = end.y - start.y
                val horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
                sourceSnapshot.withGap(start, horizontal, if (horizontal) dx else dy)
            }
            else -> sourceSnapshot
        }
        val ghostIds = if (gapPreview == null) emptySet() else {
            (displaySnapshot.texts.filterIndexed { index, it -> it != sourceSnapshot.texts[index] }.map { it.id } +
                displaySnapshot.shapes.filterIndexed { index, it -> it != sourceSnapshot.shapes[index] }.map { it.id }).toSet()
        }
        SpatialElements(displaySnapshot, viewport, selectedIds, spatialPreview, lassoPoints, gapPreview, ghostIds,
            onHandle = { id, kind ->
            if (!saving && !saveFailed) {
                val changed = when (kind) {
                    HandleKind.MOVE -> board.moveSelection(setOf(id), 16f, 0f)
                    HandleKind.RESIZE -> board.shapes.firstOrNull { it.id == id }?.let {
                        board.resizeShape(id, it.width + 16f, it.height + 16f)
                    } ?: false
                    HandleKind.FROM, HandleKind.TO -> board.arrows.firstOrNull { it.id == id }?.let { arrow ->
                        val end = if (kind == HandleKind.FROM) arrow.from else arrow.to
                        val point = board.snapshot().resolve(end)
                        if (point == null) false else if (kind == HandleKind.FROM)
                            board.updateArrow(id, from = ArrowEnd.Free(point.x + 16f, point.y))
                        else board.updateArrow(id, to = ArrowEnd.Free(point.x + 16f, point.y))
                    } ?: false
                    HandleKind.BEND -> board.arrows.firstOrNull { it.id == id }?.let {
                        board.updateArrow(id, bend = it.bend + 16f)
                    } ?: false
                }
                if (changed) saveSnapshot()
            }
            },
            onSelect = { id -> selectedIds = setOf(id); selectedId = null },
            onMove = { id, dx, dy ->
                if (saving || saveFailed) false else board.moveSelection(setOf(id), dx, dy).also {
                    if (it) saveSnapshot()
                }
            },
            onDelete = { id ->
                if (saving || saveFailed) false else board.delete(setOf(id)).also {
                    if (it) { selectedIds = emptySet(); selectedId = null; saveSnapshot() }
                }
            },
            onReverse = { id ->
                if (saving || saveFailed) false else board.updateArrow(id, reverse = true).also {
                    if (it) saveSnapshot()
                }
            },
        )

        displaySnapshot.texts.forEach { element ->
            if (draft?.id != element.id && pendingNewElementId != element.id) {
                val display = preview?.takeIf { it.first == element.id }?.second
                val (screenX, screenY) = viewport.worldToScreen(display?.first ?: element.x, display?.second ?: element.y)
                val selected = element.id in selectedIds
                val elementActionsEnabled = !saving && !saveFailed
                Text(
                    text = element.text,
                    color = if (element.id in ghostIds) vermilion.copy(alpha = .65f)
                        else if (element.color == TextColor.INK) ink else vermilion,
                    fontSize = if (element.kind == TextKind.TITLE) 15.sp else 14.sp,
                    lineHeight = if (element.kind == TextKind.TITLE) 22.sp else 21.sp,
                    fontWeight = if (element.kind == TextKind.TITLE) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.offsetPx(screenX, screenY)
                        .graphicsLayer {
                            scaleX = viewport.scale
                            scaleY = viewport.scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .widthIn(max = 166.dp)
                        .then(if (selected) Modifier.selectionFrame() else Modifier)
                        .onSizeChanged { elementSizes[element.id] = it }
                        .semantics {
                            contentDescription = element.text
                            stateDescription = if (selected) selectedLabel else unselectedLabel
                            if (elementActionsEnabled) {
                                onClick(label = if (selected) editLabel else selectLabel) {
                                    tap(Offset(screenX + 1, screenY + 1))
                                    true
                                }
                                if (selected) customActions = listOf(
                                    CustomAccessibilityAction(moveUpLabel) { nudge(element.id, 0f, -16f) },
                                    CustomAccessibilityAction(moveDownLabel) { nudge(element.id, 0f, 16f) },
                                    CustomAccessibilityAction(moveLeftLabel) { nudge(element.id, -16f, 0f) },
                                    CustomAccessibilityAction(moveRightLabel) { nudge(element.id, 16f, 0f) },
                                )
                            } else {
                                disabled()
                            }
                        },
                )
                if (selected && draft == null) {
                    val size = elementSizes[element.id] ?: IntSize(160, 48)
                    val gripX = screenX + size.width * viewport.scale / 2f - with(density) { 24.dp.toPx() }
                    val gripY = screenY + size.height * viewport.scale + with(density) { 2.dp.toPx() }
                    Box(
                        modifier = Modifier.offsetPx(gripX, gripY).size(48.dp)
                            .semantics {
                                contentDescription = moveElementLabel
                                if (!elementActionsEnabled) disabled()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(30.dp).background(vermilion, CircleShape), contentAlignment = Alignment.Center) {
                            Text("✥", color = Color.White, fontSize = 17.sp)
                        }
                    }
                }
            }
        }

        draft?.let { current ->
            val (screenX, screenY) = viewport.worldToScreen(current.x, current.y)
            BasicTextField(
                value = current.text,
                onValueChange = { draft = current.copy(text = it) },
                readOnly = saving || saveFailed,
                textStyle = TextStyle(
                    color = if (current.color == TextColor.INK) ink else vermilion,
                    fontSize = if (current.kind == TextKind.TITLE) 15.sp else 14.sp,
                    lineHeight = if (current.kind == TextKind.TITLE) 22.sp else 21.sp,
                    fontWeight = if (current.kind == TextKind.TITLE) FontWeight.Bold else FontWeight.Normal,
                ),
                cursorBrush = SolidColor(vermilion),
                modifier = Modifier.offsetPx(screenX, screenY)
                    .graphicsLayer {
                        scaleX = viewport.scale
                        scaleY = viewport.scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .width(166.dp)
                    .heightIn(max = 150.dp)
                    .background(vermilion.copy(alpha = 0.07f), RoundedCornerShape(3.dp))
                    .drawBehind { drawLine(vermilion, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = if (current.id == null) newTextLabel else editTextLabel },
                decorationBox = { inner ->
                    Box {
                        if (current.text.isEmpty()) Text(stringResource(R.string.write_hint), color = muted, fontSize = 14.sp)
                        inner()
                    }
                },
            )
        }

        if (draft == null) {
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 8.dp)
                    .height(44.dp).background(Color.White, RoundedCornerShape(24.dp))
                    .pillBorder(24f).padding(horizontal = 14.dp)
                    .onGloballyPositioned { chromeBounds["board"] = it.boundsInParent() },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.board_name), color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 16.dp)
                    .background(Color.White, RoundedCornerShape(24.dp)).pillBorder(24f)
                    .onGloballyPositioned { chromeBounds["history"] = it.boundsInParent() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { if (board.undo()) { selectedId = null; selectedIds = emptySet(); saveSnapshot() } },
                    enabled = board.canUndo && !saving && !saveFailed,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = undoLabel },
                ) { Text("↶", color = if (board.canUndo && !saving && !saveFailed) ink else muted.copy(alpha = 0.4f), fontSize = 25.sp) }
                Box(Modifier.width(1.dp).height(20.dp).background(outline))
                IconButton(
                    onClick = { if (board.redo()) { selectedId = null; selectedIds = emptySet(); saveSnapshot() } },
                    enabled = board.canRedo && !saving && !saveFailed,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = redoLabel },
                ) { Text("↷", color = if (board.canRedo && !saving && !saveFailed) ink else muted.copy(alpha = 0.4f), fontSize = 25.sp) }
            }
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 22.dp)
                    .background(Color.White, RoundedCornerShape(16.dp)).pillBorder(16f)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .onGloballyPositioned { chromeBounds["zoom"] = it.boundsInParent() },
            ) { Text("${(viewport.scale * 100).roundToInt()}%  近", color = muted, fontSize = 11.sp) }

            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 70.dp)
                    .onGloballyPositioned { chromeBounds["tools"] = it.boundsInParent() },
                horizontalAlignment = Alignment.End,
            ) {
                SpatialTools(tool, toolsExpanded, !saving && !saveFailed,
                    onExpand = {
                        if (tool == SpatialTool.NONE) toolsExpanded = !toolsExpanded
                        else { tool = SpatialTool.NONE; toolsExpanded = false }
                    }, onSelect = { tool = it; toolsExpanded = false; guidance = "${it.label}を配置" })
            }

            val message = guidance ?: when (tool) {
                SpatialTool.NONE -> if (selectedIds.size > 1) "${selectedIds.size}個を選択" else null
                SpatialTool.LASSO -> "指で囲んで選択"
                SpatialTool.ARROW -> "ドラッグして矢印を作成"
                else -> "タップまたはドラッグして${tool.label}を作成"
            }
            if (message != null) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 62.dp)
                    .onGloballyPositioned { chromeBounds["guidance"] = it.boundsInParent() }) {
                    Guidance(message)
                }
            }

            menuTarget?.let { id ->
                Row(Modifier.align(Alignment.Center).background(Color.White, RoundedCornerShape(10.dp))
                    .pillBorder(10f).padding(6.dp)
                    .onGloballyPositioned { chromeBounds["menu"] = it.boundsInParent() }) {
                    if (board.arrows.any { it.id == id }) {
                        EditorOption("反転", false, false, enabled = !saving && !saveFailed) {
                            if (board.updateArrow(id, reverse = true)) saveSnapshot()
                            menuTarget = null
                        }
                    } else if (board.shapes.any { it.id == id }) {
                        EditorOption("朱", false, true, enabled = !saving && !saveFailed) {
                            val shape = board.shapes.first { it.id == id }
                            if (board.updateShape(id, color = if (shape.color == TextColor.INK)
                                TextColor.VERMILION else TextColor.INK)) saveSnapshot()
                            menuTarget = null
                        }
                        if (board.shapes.any { it.id == id && it.kind == ShapeKind.REGION }) {
                            EditorOption("名前", false, false, enabled = !saving && !saveFailed) {
                                regionNameId = id
                                regionName = board.shapes.first { it.id == id }.name
                                menuTarget = null
                            }
                        }
                    } else if (board.elements.any { it.id == id }) {
                        EditorOption("編集", false, false, enabled = !saving && !saveFailed) {
                            val element = board.elements.first { it.id == id }
                            draft = Draft(id, element.x, element.y, element.text, element.kind, element.color)
                            menuTarget = null
                        }
                    }
                    EditorOption("削除", false, true, enabled = !saving && !saveFailed) {
                        if (board.delete(if (id in selectedIds) selectedIds else setOf(id))) saveSnapshot()
                        selectedIds = emptySet(); selectedId = null; menuTarget = null
                    }
                    EditorOption("閉じる", false, false, enabled = true) { menuTarget = null }
                }
            }

            if (regionNameId != null) {
                Row(Modifier.align(Alignment.Center).background(Color.White, RoundedCornerShape(10.dp))
                    .pillBorder(10f).padding(8.dp).onGloballyPositioned {
                        chromeBounds["regionName"] = it.boundsInParent()
                    }, verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(regionName, onValueChange = { regionName = it },
                        singleLine = true, modifier = Modifier.width(140.dp).padding(8.dp)
                            .semantics { contentDescription = "囲みの名前" },
                        decorationBox = { inner ->
                            Box {
                                if (regionName.isEmpty()) Text("名前（任意）", color = muted)
                                inner()
                            }
                        })
                    EditorOption("完了", false, true, enabled = !saving && !saveFailed) {
                        val id = regionNameId!!
                        if (board.updateShape(id, name = regionName)) saveSnapshot()
                        regionNameId = null
                        keyboard?.hide()
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).imePadding().fillMaxWidth().height(54.dp)
                    .background(toolbar)
                    .drawBehind { drawLine(outline, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val editingEnabled = !saving && !saveFailed
                EditorOption(stringResource(R.string.title_kind), draft!!.kind == TextKind.TITLE, false, enabled = editingEnabled) {
                    if (!saving && !saveFailed) draft = draft?.copy(kind = TextKind.TITLE)
                }
                EditorOption(stringResource(R.string.body_kind), draft!!.kind == TextKind.BODY, false, enabled = editingEnabled) {
                    if (!saving && !saveFailed) draft = draft?.copy(kind = TextKind.BODY)
                }
                EditorOption(stringResource(R.string.vermilion_short), draft!!.color == TextColor.VERMILION, true, enabled = editingEnabled) {
                    if (!saving && !saveFailed) draft = draft?.copy(
                        color = if (draft?.color == TextColor.VERMILION) TextColor.INK else TextColor.VERMILION,
                    )
                }
                EditorOption(stringResource(R.string.cancel_short), false, false, enabled = editingEnabled) {
                    if (!saving && !saveFailed) { draft = null; keyboard?.hide() }
                }
                EditorOption(stringResource(if (saveFailed) R.string.retry else R.string.done), false, true, enabled = !saving) { commitDraft() }
            }
        }
        if (saving || saveFailed) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)
                    .height(44.dp)
                    .then(if (saveFailed && draft == null) Modifier.clickable { saveSnapshot() } else Modifier)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(if (saveFailed) R.string.save_failed else R.string.saving),
                    color = if (saveFailed) vermilion else muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun EditorOption(label: String, active: Boolean, accent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(width = if (label.length > 2) 64.dp else 48.dp, height = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) if (accent) vermilion else ink else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = if (active) "選択中" else "未選択"
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (!enabled) muted.copy(alpha = 0.6f) else if (active) Color.White else if (accent) vermilion else ink, fontSize = 13.sp) }
}

private fun Modifier.pillBorder(radius: Float): Modifier = this.then(
    Modifier.drawBehind {
        drawRoundRect(
            color = outline, cornerRadius = CornerRadius(radius.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
    },
)

private fun Modifier.selectionFrame(): Modifier = this.then(
    Modifier.background(vermilion.copy(alpha = 0.06f), RoundedCornerShape(3.dp))
        .drawBehind {
            drawRoundRect(
                color = vermilion,
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }.padding(6.dp),
)

private fun Modifier.offsetPx(x: Float, y: Float): Modifier = this.then(
    Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
)
