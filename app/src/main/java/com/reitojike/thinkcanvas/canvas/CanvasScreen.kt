package com.reitojike.thinkcanvas.canvas

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var draft by remember { mutableStateOf<Draft?>(null) }
    var preview by remember { mutableStateOf<Pair<String, Pair<Float, Float>>?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var finishDraftAfterSave by remember { mutableStateOf(false) }
    var pendingNewElementId by remember { mutableStateOf<String?>(null) }
    val elementSizes = remember { mutableStateMapOf<String, IntSize>() }
    val uiScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    val imeBottom = WindowInsets.ime.getBottom(density)
    val latestViewport = rememberUpdatedState(viewport)
    val latestElements = rememberUpdatedState(board.elements)
    val latestSelected = rememberUpdatedState(selectedId)
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
            val gripRadius = with(density) { 22.dp.toPx() }
            val inGrip = latestSelected.value == element.id &&
                point.x in (gripCenter - gripRadius)..(gripCenter + gripRadius) &&
                point.y in (y + size.height * view.scale)..(y + size.height * view.scale + gripRadius * 2f)
            inText || inGrip
        }
    }

    fun tap(point: Offset) {
        if (latestDraft.value != null || latestSaveBlocked.value) return
        val element = hitTest(point)
        if (element == null) {
            if (latestSelected.value != null) selectedId = null
            else {
                val (x, y) = latestViewport.value.screenToWorld(point.x, point.y)
                draft = Draft(null, x, y)
            }
        } else if (latestSelected.value == element.id) {
            draft = Draft(element.id, element.x, element.y, element.text, element.kind, element.color)
        } else selectedId = element.id
    }

    fun nudge(id: String, dx: Float, dy: Float): Boolean {
        if (saving || saveFailed) return false
        val element = board.elements.firstOrNull { it.id == id } ?: return false
        if (!board.move(id, element.x + dx, element.y + dy)) return false
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
                val target = hitTest(down.position)
                val startTime = SystemClock.uptimeMillis()
                val start = down.position
                val selectedGrip = target != null && latestSelected.value == target.id &&
                    run {
                        val (_, y) = latestViewport.value.worldToScreen(target.x, target.y)
                        val height = (elementSizes[target.id]?.height ?: 48) * latestViewport.value.scale
                        down.position.y > y + height
                    }
                var mode = if (selectedGrip) "move" else "tap"
                var movedTo: Pair<Float, Float>? = null
                while (true) {
                    val remaining = longPressMillis - (SystemClock.uptimeMillis() - startTime)
                    val event = if (mode == "tap" && target != null && remaining > 0) {
                        withTimeoutOrNull(remaining) { awaitPointerEvent() }
                    } else if (mode == "tap" && target != null && remaining <= 0) null
                    else awaitPointerEvent()
                    if (event == null) { mode = "move"; continue }
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        if (mode == "tap") tap(start)
                        if (mode == "move" && target != null && movedTo != null) {
                            val (x, y) = movedTo
                            if (board.move(target.id, x, y)) saveSnapshot()
                        }
                        preview = null
                        break
                    }
                    if (pressed.size >= 2) {
                        mode = "zoom"
                        preview = null
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
                        val delta = change.position - change.previousPosition
                        if (mode == "tap" && (change.position - start).getDistance() > touchSlop) mode = "pan"
                        when (mode) {
                            "pan" -> viewport = latestViewport.value.pan(delta.x, delta.y)
                            "move" -> if (target != null) {
                                val x = target.x + (change.position.x - start.x) / latestViewport.value.scale
                                val y = target.y + (change.position.y - start.y) / latestViewport.value.scale
                                movedTo = x to y
                                preview = target.id to (x to y)
                            }
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
                } finally {
                    preview = null
                }
            }
        },
    ) {
        if (board.elements.isEmpty() && draft == null) {
            Text(
                stringResource(R.string.empty_hint), color = Color(0xFFB1ACA5), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        board.elements.forEach { element ->
            if (draft?.id != element.id && pendingNewElementId != element.id) {
                val display = preview?.takeIf { it.first == element.id }?.second
                val (screenX, screenY) = viewport.worldToScreen(display?.first ?: element.x, display?.second ?: element.y)
                val selected = selectedId == element.id
                Text(
                    text = element.text,
                    color = if (element.color == TextColor.INK) ink else vermilion,
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
                        },
                )
                if (selected && draft == null) {
                    val size = elementSizes[element.id] ?: IntSize(160, 48)
                    val gripX = screenX + size.width * viewport.scale / 2f - with(density) { 22.dp.toPx() }
                    val gripY = screenY + size.height * viewport.scale + with(density) { 2.dp.toPx() }
                    Box(
                        modifier = Modifier.offsetPx(gripX, gripY).size(44.dp)
                            .semantics { contentDescription = moveElementLabel },
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
                    .pillBorder(24f).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.board_name), color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 16.dp)
                    .background(Color.White, RoundedCornerShape(24.dp)).pillBorder(24f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { if (board.undo()) { selectedId = null; saveSnapshot() } },
                    enabled = board.canUndo && !saving && !saveFailed,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = undoLabel },
                ) { Text("↶", color = if (board.canUndo && !saving && !saveFailed) ink else muted.copy(alpha = 0.4f), fontSize = 25.sp) }
                Box(Modifier.width(1.dp).height(20.dp).background(outline))
                IconButton(
                    onClick = { if (board.redo()) { selectedId = null; saveSnapshot() } },
                    enabled = board.canRedo && !saving && !saveFailed,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = redoLabel },
                ) { Text("↷", color = if (board.canRedo && !saving && !saveFailed) ink else muted.copy(alpha = 0.4f), fontSize = 25.sp) }
            }
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 22.dp)
                    .background(Color.White, RoundedCornerShape(16.dp)).pillBorder(16f)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) { Text("${(viewport.scale * 100).roundToInt()}%  近", color = muted, fontSize = 11.sp) }
        } else {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).imePadding().fillMaxWidth().height(54.dp)
                    .background(toolbar)
                    .drawBehind { drawLine(outline, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorOption(stringResource(R.string.title_kind), draft!!.kind == TextKind.TITLE, false) {
                    if (!saving && !saveFailed) draft = draft?.copy(kind = TextKind.TITLE)
                }
                EditorOption(stringResource(R.string.body_kind), draft!!.kind == TextKind.BODY, false) {
                    if (!saving && !saveFailed) draft = draft?.copy(kind = TextKind.BODY)
                }
                EditorOption(stringResource(R.string.vermilion_short), draft!!.color == TextColor.VERMILION, true) {
                    if (!saving && !saveFailed) draft = draft?.copy(
                        color = if (draft?.color == TextColor.VERMILION) TextColor.INK else TextColor.VERMILION,
                    )
                }
                EditorOption(stringResource(R.string.cancel_short), false, false) {
                    if (!saving && !saveFailed) { draft = null; keyboard?.hide() }
                }
                EditorOption(stringResource(if (saveFailed) R.string.retry else R.string.done), false, true) { commitDraft() }
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
private fun EditorOption(label: String, active: Boolean, accent: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(width = if (label.length > 2) 64.dp else 48.dp, height = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) if (accent) vermilion else ink else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; stateDescription = if (active) "選択中" else "未選択" },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (active) Color.White else if (accent) vermilion else ink, fontSize = 13.sp) }
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
