package com.thinkcanvas.canvas

import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import com.thinkcanvas.board.fittedViewport
import com.thinkcanvas.board.RegionLabelSize
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.thinkcanvas.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.BoardSaveAcknowledgement

private val paper = Color(0xFFFCFCFB)
private val ink = Color(0xFF23211E)
private val vermilion = Color(0xFFC54B32)
private val muted = Color(0xFF8D8882)
private val outline = Color(0xFFE8E6E2)
private val toolbar = Color(0xFFF3F2EF)
private val textEditorWidth = 166.dp
private val textEditorMaxHeight = 150.dp
private val editorToolbarHeight = 54.dp

private class ViewportAnimationBoundary {
    var active = true
    var job: Job? = null
    var origin: ViewportFocus? = null
    var group: Any? = null
    var generation = 0
}

private class BlankTapBoundary {
    var active = true
    var pending: BlankTap? = null
    var confirmation: Job? = null
}

private data class HistoryFocusRequest(
    val before: BoardSnapshot,
    val expected: BoardSnapshot,
    val beforeGeometry: ResolvedRenderedGeometry,
)

@Composable
fun CanvasScreen(
    board: BoardState,
    boardName: String,
    editorSession: TextEditorSession,
    saveState: StateFlow<BoardSaveState>,
    onRequestSave: (BoardSnapshot) -> BoardSaveAcknowledgement?,
    onRetrySave: () -> Unit,
    onOpenList: () -> Unit,
    onShareSelection: (Set<String>) -> Unit,
    edgeAutoPanProfile: EdgeAutoPanProfile = EdgeAutoPanProfile.Default,
    viewportHistory: ViewportHistory? = null,
    externalInteractionBlocked: () -> Boolean = { false },
    onImportReadiness: ((Boolean, () -> Boolean) -> Unit)? = null,
    onAddImage: ((com.thinkcanvas.image.ImagePickerSource, WorldPoint, Float, Float) -> Unit)? = null,
    imageResources: com.thinkcanvas.image.ImageResources? = null,
) {
    val navigation = viewportHistory ?: remember(board) { ViewportHistory() }
    var viewport by navigation.viewportState
    val animationBoundary = remember(navigation) { ViewportAnimationBoundary() }
    var pendingHistoryFocus by remember { mutableStateOf<HistoryFocusRequest?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tool by rememberSaveable { mutableStateOf(SpatialTool.NONE) }
    var inkTool by rememberSaveable { mutableStateOf<InkKind?>(null) }
    var inkPreview by remember { mutableStateOf<InkPreview?>(null) }
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    var spatialPreview by remember { mutableStateOf<SpatialPreview?>(null) }
    var lassoPoints by remember { mutableStateOf<List<WorldPoint>>(emptyList()) }
    var gapPreview by remember { mutableStateOf<Pair<WorldPoint, WorldPoint>?>(null) }
    var menuTarget by remember { mutableStateOf<String?>(null) }
    var attachmentEditor by remember { mutableStateOf<Pair<String, HandleKind>?>(null) }
    var imagePickerOpen by rememberSaveable { mutableStateOf(false) }
    var imageDraft by editorSession.imageDescriptionDraft
    var pendingImageAcknowledgement by editorSession.pendingImageAcknowledgement
    var regionDraft by editorSession.regionNameDraft
    val regionNameId = regionDraft?.id
    val regionName = regionDraft?.name.orEmpty()
    var discardTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var gestureGeneration by remember { mutableStateOf(0) }
    var guidance by remember { mutableStateOf<String?>(null) }
    var draft by editorSession.draft
    var movePreview by remember { mutableStateOf<Pair<Set<String>, WorldPoint>?>(null) }
    var moveOwner by remember { mutableStateOf<MoveDragSession?>(null) }
    var handlePreview by remember { mutableStateOf<BoardSnapshot?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var manualGestureActive by remember { mutableStateOf(false) }
    var viewportAnimating by remember { mutableStateOf(false) }
    var pendingDraftAcknowledgement by editorSession.pendingDraftAcknowledgement
    val currentSaveState by saveState.collectAsState()
    val latestExternalBlock = rememberUpdatedState(externalInteractionBlocked)
    fun saveBlocked(): Boolean = saveState.value != BoardSaveState.Idle || latestExternalBlock.value()
    val saving = currentSaveState is BoardSaveState.Running || latestExternalBlock.value()
    val saveFailed = currentSaveState is BoardSaveState.Failed
    var pendingNewElementId by editorSession.pendingNewElementId
    var searchOpen by remember { mutableStateOf(false) }
    val searchNavigationGroup = remember(searchOpen) { Any() }
    var searchQuery by remember { mutableStateOf("") }
    var searchHistoryNavigation by remember { mutableStateOf<Pair<BoardSnapshot, String>?>(null) }
    var searchPosition by remember { mutableStateOf(0) }
    val blankTapBoundary = remember(board, editorSession) { BlankTapBoundary() }
    val elementSizes = remember { mutableStateMapOf<String, IntSize>() }
    val chromeBounds = remember { mutableStateMapOf<String, Rect>() }
    var textEditorBounds by remember { mutableStateOf<Rect?>(null) }
    var editorToolbarBounds by remember { mutableStateOf<Rect?>(null) }
    val uiScope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val pendingInputRequests = remember { mutableSetOf<Job>() }
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current
    val inputView = LocalView.current
    val regionNameFocusRequester = remember { FocusRequester() }
    val canvasTextStyle = LocalTextStyle.current
    val regionLabelStyle = canvasTextStyle.copy(fontSize = DetailedRenderFacts.REGION_LABEL_SIZE_SP.sp)
    val searchFocusRequester = remember { FocusRequester() }
    val viewConfiguration = LocalViewConfiguration.current
    val context = LocalContext.current
    val touchSlop = viewConfiguration.touchSlop
    val longPressMillis = viewConfiguration.longPressTimeoutMillis
    val doubleTapTimeoutMillis = viewConfiguration.doubleTapTimeoutMillis
    val doubleTapMinTimeMillis = viewConfiguration.doubleTapMinTimeMillis
    val doubleTapSlop = android.view.ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    val lifecycleOwner = LocalLifecycleOwner.current
    val imeInsets = WindowInsets.ime
    val imeBottom = imeInsets.getBottom(density)
    val latestViewport = rememberUpdatedState(viewport)
    val latestElements = rememberUpdatedState(board.elements)
    val latestSnapshot = rememberUpdatedState(board.snapshot())
    val bodyDp = with(density) { 14.sp.toDp().value }
    val titleDp = with(density) { 15.sp.toDp().value }
    val titleTier = semanticTier(bodyDp, viewport.scale)
    val titleMinimumDp = if (titleTier == SemanticTier.FAR) 9f else 11f
    val titleLineHeightWorld = with(density) {
        (maxOf(titleDp, titleMinimumDp / viewport.scale).dp.toSp() * 1.5f).toDp().toPx()
    }
    val rawSearchMatches = if (searchOpen) board.snapshot().searchCanvas(searchQuery) else emptyList()
    val matchIds = rawSearchMatches.map { it.id }.toSet()
    val snapshot = board.snapshot()
    val keptIds = selectedIds + matchIds + listOfNotNull(regionNameId)
    val initialProjection = snapshot.semanticProjection(viewport.scale, bodyDp,
        keptIds, density.density, titleDp, titleLineHeightWorld)
    val boundaryShapes = snapshot.shapes.filter { initialProjection.visible(it.id) }
    fun measureTextExtent(element: TextElement, atScale: Float,
                          keepIds: Set<String>, visibleShapes: List<ShapeElement>): TextExtent {
        val tier = semanticTier(bodyDp, atScale)
        val title = element.kind == TextKind.TITLE
        val minimumDp = when (tier) {
            SemanticTier.NEAR -> if (title) 11f else 10f
            SemanticTier.MID -> if (title) 11f else 9.5f
            SemanticTier.FAR -> if (title) 9f else 10f
        }
        val size = with(density) {
            maxOf(if (title) titleDp else bodyDp, minimumDp / atScale).dp.toSp()
        }
        val lineWorld = with(density) { (size * 1.5f).toDp().toPx() }
        val widthDp = if (title && tier != SemanticTier.NEAR && element.id !in keepIds)
            snapshot.titleAvailableWidth(element, lineWorld, visibleShapes)
                ?.let { (it / density.density).dp } ?: 166.dp
        else 166.dp
        val measured = textMeasurer.measure(
            text = AnnotatedString(element.text),
            style = canvasTextStyle.copy(fontSize = size, lineHeight = size * 1.5f,
                fontWeight = if (title) FontWeight.Bold else FontWeight.Normal),
            maxLines = if (tier == SemanticTier.NEAR) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = with(density) {
                widthDp.roundToPx().coerceAtLeast(0) }),
        ).size
        return TextExtent(measured.width.toFloat(), measured.height.toFloat())
    }
    val measuredTextExtents = remember(board.elements, board.shapes, viewport.scale, density,
        canvasTextStyle, selectedIds, matchIds) {
        board.elements.associate { element ->
            element.id to measureTextExtent(element, viewport.scale, keptIds, boundaryShapes)
        }
    }
    val measuredRegionLabelSizes = remember(snapshot.shapes, canvasSize.width, density, regionLabelStyle) {
        val labelWidth = (canvasSize.width - 40).coerceAtLeast(1)
        snapshot.shapes.filter { it.kind == ShapeKind.REGION && it.name.isNotBlank() }
            .associate { shape ->
                val size = textMeasurer.measure(AnnotatedString(shape.name),
                    style = regionLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = labelWidth)).size
                shape.id to RegionLabelSize(size.width.toFloat(), size.height.toFloat())
            }
    }
    val latestRegionLabelSizes = rememberUpdatedState(measuredRegionLabelSizes)
    fun stopViewportAnimation(record: Boolean = true) {
        viewportAnimating = false
        animationBoundary.job?.cancel()
        animationBoundary.generation++
        val origin = animationBoundary.origin
        animationBoundary.origin = null
        if (record) navigation.record(origin, animationBoundary.group)
        animationBoundary.group = null
    }
    DisposableEffect(navigation) {
        onDispose {
            animationBoundary.active = false
            stopViewportAnimation(record = false)
        }
    }
    LaunchedEffect(board, canvasSize, density, canvasTextStyle) {
        stopViewportAnimation(record = false)
        navigation.resize(canvasSize.width.toFloat(), canvasSize.height.toFloat(), density.density)
        if (!navigation.initialized && canvasSize.width > 0 && canvasSize.height > 0) {
            var fitted = snapshot.fittedViewport(canvasSize.width.toFloat(), canvasSize.height.toFloat())
            for (pass in 0 until 8) {
                val approximateProjection = snapshot.semanticProjection(fitted.scale, bodyDp,
                    pixelsPerDp = density.density, titleDp = titleDp)
                val visibleShapes = snapshot.shapes.filter { approximateProjection.visible(it.id) }
                val textSizes = snapshot.texts.associate { element ->
                    element.id to measureTextExtent(element, fitted.scale, emptySet(), visibleShapes)
                }
                val resolvedTextBounds = textSizes.mapNotNull { (id, extent) ->
                    val text = snapshot.texts.first { it.id == id }
                    id to WorldBounds(text.x, text.y, text.x + extent.width, text.y + extent.height)
                }.toMap()
                val geometry = snapshot.resolveRenderedGeometry(resolvedTextBounds,
                    fitted.scale, density.density)
                val displayProjection = snapshot.semanticProjection(fitted.scale, bodyDp,
                    pixelsPerDp = density.density, titleDp = titleDp,
                    resolvedRenderedBounds = geometry.boundsById)
                val visibleLabels = measuredRegionLabelSizes.filterKeys { id ->
                    displayProjection.visible(id) && !displayProjection.farLikeRegion(id)
                }
                val next = snapshot.fittedViewport(canvasSize.width.toFloat(), canvasSize.height.toFloat(),
                    visibleLabels, textSizes, maximumScale = fitted.scale, renderedGeometry = geometry)
                val settled = next.scale >= fitted.scale - .001f
                fitted = next
                if (settled) break
            }
            navigation.initialize(fitted)
        }
    }
    val resolvedTextBounds = measuredTextExtents.mapNotNull { (id, extent) ->
        val text = snapshot.texts.firstOrNull { it.id == id } ?: return@mapNotNull null
        id to WorldBounds(text.x, text.y, text.x + extent.width, text.y + extent.height)
    }.toMap()
    val renderedGeometry = remember(resolvedTextBounds, snapshot.shapes, snapshot.arrows,
        snapshot.ink, snapshot.images, viewport.scale, density.density) {
        snapshot.resolveRenderedGeometry(resolvedTextBounds, viewport.scale, density.density)
    }
    val latestRenderedGeometry = rememberUpdatedState(renderedGeometry)
    val searchMatches = rawSearchMatches.map { match ->
        val bounds = renderedGeometry.bounds(match.id)
        if (bounds == null) match else match.copy(bounds = bounds)
    }
    val currentMatch = searchMatches.getOrNull(searchPosition)
    val projection = board.snapshot().semanticProjection(viewport.scale, bodyDp,
        keptIds, density.density, titleDp, titleLineHeightWorld,
        renderedGeometry.boundsById)
    val latestProjection = rememberUpdatedState(projection)
    val latestBodyDp = rememberUpdatedState(bodyDp)
    val latestSelected = rememberUpdatedState(selectedId)
    val latestSelectedIds = rememberUpdatedState(selectedIds)
    val latestTool = rememberUpdatedState(tool)
    val latestInkTool = rememberUpdatedState(inkTool)
    val latestDraft = rememberUpdatedState(draft)


    fun baseNavigationAllowed(): Boolean = saveState.value == BoardSaveState.Idle &&
        editorSession.pendingDraftAcknowledgement.value == null &&
        editorSession.draft.value == null && editorSession.regionNameDraft.value == null &&
        editorSession.imageDescriptionDraft.value == null && editorSession.pendingImageAcknowledgement.value == null &&
        !imagePickerOpen &&
        discardTarget == null && menuTarget == null && attachmentEditor == null &&
        tool == SpatialTool.NONE && inkTool == null && !toolsExpanded && moveOwner == null && movePreview == null &&
        handlePreview == null && spatialPreview == null && lassoPoints.isEmpty() &&
        gapPreview == null && inkPreview == null && imeInsets.getBottom(density) == 0

    fun indicatorsAllowed(): Boolean = !latestExternalBlock.value() && baseNavigationAllowed()
    fun importReady(): Boolean = baseNavigationAllowed() && !searchOpen && !manualGestureActive &&
        !viewportAnimating && navigation.focus() != null
    if (onImportReadiness != null) {
        val latestReadiness = rememberUpdatedState(onImportReadiness)
        val readyForImport = importReady()
        SideEffect { onImportReadiness(readyForImport, ::importReady) }
        DisposableEffect(Unit) {
            onDispose { latestReadiness.value(false) { false } }
        }
    }

    val indicatorNames = remember(snapshot.texts, snapshot.shapes, snapshot.ink, snapshot.arrows, snapshot.images) {
        buildMap {
            snapshot.texts.forEach { put(it.id, it.text.take(32)) }
            snapshot.shapes.forEach { put(it.id, when (it.kind) {
                ShapeKind.REGION -> it.name.ifBlank { "囲み" }.take(32)
                ShapeKind.RECTANGLE -> "四角形"
                ShapeKind.ELLIPSE -> "楕円"
            }) }
            snapshot.ink.forEach { put(it.id, "描画") }
            snapshot.arrows.forEach { put(it.id, "矢印") }
            snapshot.images.forEach { put(it.id, it.altText.ifBlank { "画像" }.take(32)) }
        }
    }
    fun targetName(id: String): String = indicatorNames[id].orEmpty()

    val indicatorTargets = if (indicatorsAllowed()) buildList {
        currentMatch?.let { match -> add(IndicatorTarget(IndicatorKind.SEARCH, setOf(match.id),
            match.bounds, "現在の検索結果、${searchPosition + 1}件目、${targetName(match.id)}へ移動")) }
        renderedGeometry.union(selectedIds)?.let { bounds ->
            add(IndicatorTarget(IndicatorKind.SELECTION, selectedIds, bounds,
                if (selectedIds.size == 1) "選択対象、${targetName(selectedIds.single())}へ移動"
                else "選択対象、${selectedIds.size}個のまとまりへ移動"))
        }
    } else emptyList()
    val indicatorGap = with(density) { 8.dp.toPx() }
    val topChromeKeys = setOf("board", "search", "searchButton", "guidance", "shareSelection")
    val viewControlsVisible = indicatorsAllowed() && (navigation.canBack || navigation.canForward)
    val bottomChromeKeys = setOf("history", "zoom", "tools", "viewportHistory")
    val indicatorChrome = chromeBounds.filterKeys {
        !it.startsWith("indicator:") && (it != "viewportHistory" || viewControlsVisible)
    }
    val indicatorSafeBounds = Rect(indicatorGap,
        (indicatorChrome.filterKeys { it in topChromeKeys }.values.maxOfOrNull { it.bottom } ?: 0f) + indicatorGap,
        canvasSize.width - indicatorGap,
        (indicatorChrome.filterKeys { it in bottomChromeKeys }.values.minOfOrNull { it.top }
            ?: canvasSize.height.toFloat()) - indicatorGap)
    val indicatorLayouts = offscreenIndicatorLayouts(indicatorTargets, viewport, canvasSize,
        density.density, indicatorSafeBounds, indicatorChrome.values.toList())
    val latestIndicatorLayouts = rememberUpdatedState(indicatorLayouts)

    fun chromeContains(point: Offset): Boolean =
        chromeBounds.any { (key, bounds) -> !key.startsWith("indicator:") &&
            (key != "viewportHistory" || indicatorsAllowed() && (navigation.canBack || navigation.canForward)) &&
            bounds.contains(point) } ||
            indicatorsAllowed() && latestIndicatorLayouts.value.any { it.touchBounds.contains(point) }

    fun hideEditorIme() {
        pendingInputRequests.toList().forEach { it.cancel() }
        keyboard?.hide()
    }

    fun cancelBlankTap() {
        blankTapBoundary.pending = null
        blankTapBoundary.confirmation?.cancel()
        blankTapBoundary.confirmation = null
    }

    fun blankTapIsLive(pending: BlankTap): Boolean = blankTapBoundary.active &&
        pending.generation == gestureGeneration &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
        editorSession.draft.value == null && editorSession.regionNameDraft.value == pending.regionDraft &&
        editorSession.imageDescriptionDraft.value == null && !imagePickerOpen &&
        editorSession.pendingImageAcknowledgement.value == null &&
        editorSession.pendingDraftAcknowledgement.value == null && !saveBlocked() &&
        tool == SpatialTool.NONE && inkTool == null && discardTarget == null &&
        menuTarget == null && attachmentEditor == null && moveOwner == null &&
        board.snapshot() == pending.content && selectedId == pending.selectedId &&
        selectedIds == pending.selectedIds && searchOpen == pending.searchOpen

    fun confirmBlankTap(pending: BlankTap) {
        if (blankTapBoundary.pending !== pending) return
        val live = blankTapIsLive(pending)
        cancelBlankTap()
        if (!live) return
        if (selectedIds.isNotEmpty()) { selectedId = null; selectedIds = emptySet() }
        else if (!searchOpen) draft = Draft(null, pending.world.x, pending.world.y)
    }

    DisposableEffect(blankTapBoundary) {
        onDispose {
            blankTapBoundary.active = false
            cancelBlankTap()
        }
    }
    LaunchedEffect(board, editorSession, blankTapBoundary) {
        snapshotFlow {
            // Observe authority changes independently of the confirmation job's final check.
            listOf(draft, regionDraft, imageDraft, imagePickerOpen, pendingImageAcknowledgement,
                pendingDraftAcknowledgement, currentSaveState, tool, inkTool,
                discardTarget, menuTarget, attachmentEditor, moveOwner, gestureGeneration,
                board.snapshot(), selectedId, selectedIds, searchOpen, latestExternalBlock.value())
        }.collect {
            blankTapBoundary.pending?.let { if (!blankTapIsLive(it)) cancelBlankTap() }
        }
    }

    fun clearEditorFocus() {
        hideEditorIme()
        focusManager.clearFocus(force = true)
        cancelBlankTap()
    }

    fun closeDraft() {
        clearEditorFocus()
        textEditorBounds = null
        editorToolbarBounds = null
        discardTarget = null
        draft = null
    }

    fun openRegionName(id: String, name: String) {
        cancelBlankTap()
        regionDraft = RegionNameDraft(id, name)
    }

    fun closeRegionName() {
        clearEditorFocus()
        chromeBounds.remove("regionName")
        regionDraft = null
        discardTarget = null
    }

    fun exitBlocked(): Boolean = saveBlocked() ||
        editorSession.pendingDraftAcknowledgement.value != null ||
        editorSession.pendingImageAcknowledgement.value != null

    fun closeImageDescription() {
        clearEditorFocus()
        imageDraft = null
        discardTarget = null
    }

    fun openImageDescription(id: String): Boolean {
        val image = board.images.firstOrNull { it.id == id } ?: return false
        if (saveBlocked() || draft != null || regionDraft != null || imageDraft != null ||
            attachmentEditor != null || imagePickerOpen || pendingDraftAcknowledgement != null) return false
        cancelBlankTap()
        menuTarget = null
        imageDraft = ImageDescriptionDraft(image.id, image.altText)
        return true
    }

    fun requestEditorExit(target: String, changed: Boolean, close: () -> Unit) {
        if (exitBlocked()) return
        if (changed) {
            clearEditorFocus()
            discardTarget = target
        } else close()
    }

    fun clearInteractionPreviews() {
        movePreview = null
        handlePreview = null
        spatialPreview = null
        gapPreview = null
        lassoPoints = emptyList()
        inkPreview = null
    }

    fun invalidatePointerContinuation() {
        // Back can arrive after DOWN, before a preview exists or recomposition runs.
        gestureGeneration++
        moveOwner = null
        cancelBlankTap()
    }

    fun moveIsLive(owner: MoveDragSession): Boolean = moveOwner === owner &&
        gestureGeneration == owner.generation && !exitBlocked() && owner.hasSameContent(board) &&
        editorSession.draft.value == null && editorSession.regionNameDraft.value == null &&
        editorSession.imageDescriptionDraft.value == null && !imagePickerOpen &&
        discardTarget == null && attachmentEditor == null && menuTarget == null && !searchOpen

    fun cancelMove(owner: MoveDragSession) {
        if (moveOwner === owner) {
            invalidatePointerContinuation()
            movePreview = null
        }
    }

    // A frame waiter must live outside the restricted AwaitPointerEventScope.
    LaunchedEffect(moveOwner, edgeAutoPanProfile, density.density, canvasSize) {
        val owner = moveOwner ?: return@LaunchedEffect
        snapshotFlow {
            edgeAutoPanVelocity(owner.pointer, canvasSize, density.density, edgeAutoPanProfile) != Offset.Zero
        }.collectLatest { atEdge ->
            if (atEdge) {
                var previousFrame: Long? = null
                while (moveIsLive(owner)) {
                    withFrameNanos { frame ->
                        // Queued frames can arrive before coroutine cancellation/recomposition.
                        if (moveIsLive(owner)) {
                            val velocity = edgeAutoPanVelocity(owner.pointer, canvasSize,
                                density.density, edgeAutoPanProfile)
                            val seconds = previousFrame?.let { edgeAutoPanFrameSeconds(frame - it) } ?: 0f
                            previousFrame = frame
                            if (velocity != Offset.Zero && seconds > 0f) {
                                viewport = viewport.pan(velocity.x * seconds, velocity.y * seconds)
                                movePreview = owner.ids to worldDragDelta(viewport, owner.anchor, owner.pointer)
                            }
                        }
                    }
                }
                cancelMove(owner)
            }
        }
    }

    // Observe guards even while the pointer is in the central, non-ticking band.
    LaunchedEffect(currentSaveState, pendingDraftAcknowledgement, draft, regionDraft,
        board.elements, board.shapes, board.arrows, board.ink, board.images, imageDraft, imagePickerOpen, discardTarget, attachmentEditor,
        menuTarget, searchOpen) {
        moveOwner?.let { if (!moveIsLive(it)) cancelMove(it) }
    }
    DisposableEffect(board, lifecycleOwner, edgeAutoPanProfile, density.density) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                invalidatePointerContinuation()
                clearInteractionPreviews()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            invalidatePointerContinuation()
            clearInteractionPreviews()
        }
    }

    fun clearToolInteraction(clearGuidance: Boolean) {
        clearInteractionPreviews()
        tool = SpatialTool.NONE
        inkTool = null
        toolsExpanded = false
        if (clearGuidance) guidance = null
        chromeBounds.remove("ink")
        if (clearGuidance) chromeBounds.remove("guidance")
    }

    fun finishToolInteraction(clearGuidance: Boolean = true) {
        invalidatePointerContinuation()
        clearToolInteraction(clearGuidance)
    }

    fun dismissDiscardConfirmation() {
        invalidatePointerContinuation()
        discardTarget = null
    }

    fun cancelDraft() {
        if (saveState.value != BoardSaveState.Idle || pendingDraftAcknowledgement != null) return
        closeDraft()
    }

    LaunchedEffect(pendingDraftAcknowledgement) {
        val acknowledgement = pendingDraftAcknowledgement ?: return@LaunchedEffect
        acknowledgement.await()
        if (pendingDraftAcknowledgement === acknowledgement) {
            closeDraft()
            pendingNewElementId = null
            pendingDraftAcknowledgement = null
        }
    }

    LaunchedEffect(pendingImageAcknowledgement) {
        val acknowledgement = pendingImageAcknowledgement ?: return@LaunchedEffect
        acknowledgement.await()
        if (pendingImageAcknowledgement === acknowledgement) {
            pendingImageAcknowledgement = null
            closeImageDescription()
        }
    }

    fun animateViewport(target: Viewport, group: Any? = null, record: Boolean = true) {
        cancelBlankTap()
        stopViewportAnimation()
        pendingHistoryFocus = null
        val origin = viewport
        animationBoundary.origin = if (record) navigation.focus() else null
        animationBoundary.group = group
        val generation = animationBoundary.generation
        viewportAnimating = true
        animationBoundary.job = uiScope.launch {
            try {
                animate(0f, 1f, animationSpec = tween(340)) { fraction, _ ->
                    viewport = Viewport(
                        (origin.scale + (target.scale - origin.scale) * fraction).coerceIn(.15f, 3f),
                        origin.panX + (target.panX - origin.panX) * fraction,
                        origin.panY + (target.panY - origin.panY) * fraction,
                    )
                }
                if (generation == animationBoundary.generation) {
                    // Float interpolation can round below the legal minimum on its final frame.
                    // A completed navigation must restore the exact target before history observes it.
                    viewport = target
                    val historyOrigin = animationBoundary.origin
                    animationBoundary.origin = null
                    navigation.record(historyOrigin, animationBoundary.group)
                    animationBoundary.group = null
                }
            } finally {
                if (generation == animationBoundary.generation) viewportAnimating = false
            }
        }
    }

    fun focusTarget(match: CanvasMatch, group: Any? = null) {
        if (canvasSize == IntSize.Zero) return
        val width = canvasSize.width.toFloat()
        val height = canvasSize.height.toFloat()
        val initialTarget = viewport.focusMatch(match, width, height)
        val targetMatch = board.elements.firstOrNull { it.id == match.id }?.let { element ->
            val targetTier = semanticTier(bodyDp, initialTarget.scale)
            val targetMinDp = if (targetTier == SemanticTier.FAR) 9f else 11f
            val targetLineWorld = with(density) {
                (maxOf(titleDp, targetMinDp / initialTarget.scale).dp.toSp() * 1.5f).toDp().toPx()
            }
            val targetProjection = snapshot.semanticProjection(initialTarget.scale, bodyDp,
                keptIds, density.density, titleDp, targetLineWorld)
            val targetShapes = snapshot.shapes.filter { targetProjection.visible(it.id) }
            val extent = measureTextExtent(element, initialTarget.scale, keptIds, targetShapes)
            match.copy(bounds = WorldBounds(element.x, element.y,
                element.x + extent.width, element.y + extent.height))
        } ?: match
        animateViewport(viewport.focusMatch(targetMatch, width, height), group)
    }

    fun focusMatch(index: Int, group: Any? = null) {
        if (saveBlocked() || searchMatches.isEmpty()) return
        searchPosition = searchIndex(index, 0, searchMatches.size)
        focusTarget(searchMatches[searchPosition], group)
    }

    fun navigateIndicator(target: IndicatorTarget): Boolean {
        if (!indicatorsAllowed() || board.snapshot() != snapshot || latestIndicatorLayouts.value.none {
            it.target.kind == target.kind && it.target.ids == target.ids }) return false
        when (target.kind) {
            IndicatorKind.SEARCH -> {
                val liveMatch = if (searchOpen) board.snapshot().searchCanvas(searchQuery)
                    .getOrNull(searchPosition) else null
                if (liveMatch?.id !in target.ids) return false
                focusMatch(searchPosition)
            }
            IndicatorKind.SELECTION -> {
                if (selectedIds != target.ids) return false
                val geometry = latestRenderedGeometry.value
                val bounds = geometry.union(selectedIds) ?: return false
                val text = board.elements.singleOrNull { it.id in selectedIds }
                    ?.takeIf { selectedIds.size == 1 }
                if (text != null) focusTarget(CanvasMatch(text.id, bounds, false)) else {
                    val selectedGeometry = ResolvedRenderedGeometry(
                        geometry.boundsById.filterKeys { it in selectedIds })
                    animateViewport(board.snapshot().fittedViewport(canvasSize.width.toFloat(),
                        canvasSize.height.toFloat(), measuredRegionLabelSizes.filterKeys { it in selectedIds },
                        renderedGeometry = selectedGeometry))
                }
            }
        }
        cancelBlankTap()
        return true
    }
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
    val regionNameLabel = stringResource(R.string.region_name)

    fun saveSnapshot(closeDraft: Boolean = false) {
        if (closeDraft && pendingDraftAcknowledgement != null) return
        cancelBlankTap()
        val acknowledgement = onRequestSave(board.snapshot())
        if (closeDraft) pendingDraftAcknowledgement = acknowledgement
    }

    fun editHistory(redo: Boolean) {
        if (exitBlocked() || !(if (redo) board.canRedo else board.canUndo)) return
        stopViewportAnimation()
        val before = board.snapshot()
        val beforeGeometry = latestRenderedGeometry.value
        if (!(if (redo) board.redo() else board.undo())) return
        val after = board.snapshot()
        if (searchOpen && (before.texts != after.texts || before.shapes != after.shapes))
            searchHistoryNavigation = after to searchQuery
        selectedId = null
        selectedIds = emptySet()
        pendingHistoryFocus = HistoryFocusRequest(before, after, beforeGeometry)
        saveSnapshot()
    }

    LaunchedEffect(snapshot, pendingHistoryFocus, canvasSize) {
        val request = pendingHistoryFocus ?: return@LaunchedEffect
        if (board.snapshot() != request.expected) {
            pendingHistoryFocus = null
            return@LaunchedEffect
        }
        if (snapshot != request.expected || !navigation.initialized) return@LaunchedEffect
        val ids = affectedHistoryDisplayIds(request.before, snapshot, request.beforeGeometry,
            renderedGeometry, viewport.scale, density.density)
        val geometry = historyDisplayBounds(ids, request.beforeGeometry, renderedGeometry)
        val offscreen = geometry.boundsById.any { (id, bounds) ->
            val (left, top) = viewport.worldToScreen(bounds.left, bounds.top)
            val (right, bottom) = viewport.worldToScreen(bounds.right, bounds.bottom)
            right < 0f || bottom < 0f || left > canvasSize.width || top > canvasSize.height ||
                renderedGeometry.bounds(id) != null && !projection.visible(id)
        }
        pendingHistoryFocus = null
        if (!offscreen) return@LaunchedEffect
        val bounds = geometry.union() ?: return@LaunchedEffect
        val text = snapshot.texts.singleOrNull { it.id in ids }
            ?.takeIf { ids.size == 1 }
        if (text != null) focusTarget(CanvasMatch(text.id, bounds, false)) else
            animateViewport(snapshot.fittedViewport(canvasSize.width.toFloat(), canvasSize.height.toFloat(),
                measuredRegionLabelSizes.filterKeys { it in ids }, renderedGeometry = geometry))
    }

    fun restoreView(forward: Boolean) {
        if (!animationBoundary.active || !indicatorsAllowed() ||
            !(if (forward) navigation.canForward else navigation.canBack)) return
        stopViewportAnimation()
        pendingHistoryFocus = null
        val target = if (forward) navigation.forward() else navigation.back()
        if (target != null) {
            invalidatePointerContinuation()
            cancelBlankTap()
            animateViewport(target, record = false)
        }
    }

    BackHandler {
        if (imeBottom > 0) {
            invalidatePointerContinuation()
            hideEditorIme()
        } else if (!exitBlocked()) {
            val currentDraft = editorSession.draft.value
            val currentRegion = editorSession.regionNameDraft.value
            // Preserve preview/selection until the existing priority chooses one stage.
            invalidatePointerContinuation()
            when {
                discardTarget != null -> discardTarget = null
                imageDraft != null -> requestEditorExit("image:${imageDraft!!.sessionId}",
                    imageDraft!!.changed, ::closeImageDescription)
                imagePickerOpen -> imagePickerOpen = false
                currentDraft != null -> requestEditorExit("text:${currentDraft.sessionId}",
                    currentDraft.hasUncommittedChanges(board.elements.firstOrNull { it.id == currentDraft.id }),
                    ::closeDraft)
                currentRegion != null -> requestEditorExit("region:${currentRegion.sessionId}",
                    currentRegion.hasUncommittedChanges, ::closeRegionName)
                menuTarget != null -> { menuTarget = null; chromeBounds.remove("menu") }
                searchOpen -> {
                    searchOpen = false; searchQuery = ""; searchPosition = 0
                    stopViewportAnimation()
                    clearEditorFocus()
                    chromeBounds.remove("search")
                }
                tool != SpatialTool.NONE || inkTool != null || spatialPreview != null ||
                    inkPreview != null || movePreview != null || handlePreview != null ||
                    gapPreview != null || lassoPoints.isNotEmpty() -> clearToolInteraction(true)
                toolsExpanded -> toolsExpanded = false
                selectedIds.isNotEmpty() -> {
                    selectedId = null; selectedIds = emptySet(); guidance = null
                }
                else -> onOpenList()
            }
        }
    }

    fun hitTest(point: Offset): TextElement? {
        val view = latestViewport.value
        return latestElements.value.asReversed().firstOrNull { element ->
            if (!latestProjection.value.visible(element.id)) return@firstOrNull false
            val (x, y) = view.worldToScreen(element.x, element.y)
            val size = elementSizes[element.id] ?: IntSize(160, 48)
            val width = maxOf(size.width * view.scale, with(density) { 44.dp.toPx() })
            val height = maxOf(size.height * view.scale, with(density) { 44.dp.toPx() })
            val inText = point.x in x..(x + width) && point.y in y..(y + height)
            val gripCenter = x + size.width * view.scale / 2f
            val gripRadius = with(density) { 24.dp.toPx() }
            val inGrip = element.id in latestSelectedIds.value &&
                point.x in (gripCenter - gripRadius)..(gripCenter + gripRadius) &&
                point.y in (y + size.height * view.scale)..(y + size.height * view.scale + gripRadius * 2f)
            inText || inGrip
        }
    }

    fun hitInk(point: Offset, kind: InkKind): String? {
        val view = latestViewport.value
        val (wx, wy) = view.screenToWorld(point.x, point.y)
        val world = WorldPoint(wx, wy)
        return latestSnapshot.value.ink.asReversed().firstOrNull { element ->
            latestProjection.value.visible(element.id) && element.kind == kind &&
                element.hitStroke(world, kind.hitTolerance(view.scale))
        }?.id
    }

    fun hitSpatial(point: Offset): String? {
        val view = latestViewport.value
        val (wx, wy) = view.screenToWorld(point.x, point.y)
        val world = WorldPoint(wx, wy)
        val snapshot = latestSnapshot.value
        snapshot.arrows.asReversed().firstOrNull { arrow ->
            latestProjection.value.visible(arrow.id) &&
            snapshot.distanceToArrow(world, arrow,
                DetailedRenderFacts.ARROW_ENDPOINT_OFFSET_DP * density.density / view.scale,
                latestRenderedGeometry.value.boundsById) <= 12f / view.scale
        }?.let { return it.id }
        val visibleShapes = snapshot.shapes.asReversed().filter { latestProjection.value.visible(it.id) }
        visibleShapes.firstOrNull { shape ->
            val (nameX, nameY) = view.worldToScreen(
                shape.x + DetailedRenderFacts.REGION_LABEL_LEFT_WORLD,
                shape.y - DetailedRenderFacts.REGION_LABEL_TOP_WORLD)
            val labelSize = latestRegionLabelSizes.value[shape.id]
            val minimumLabelTarget = with(density) { 48.dp.toPx() }
            val nameHit = shape.kind == ShapeKind.REGION && shape.name.isNotBlank() &&
                labelSize != null && point.x in nameX..(nameX + maxOf(minimumLabelTarget, labelSize.width)) &&
                point.y in nameY..(nameY + maxOf(minimumLabelTarget, labelSize.height))
            shape.hitStroke(world, 12f / view.scale) || nameHit
        }?.let { return it.id }
        visibleShapes.firstOrNull { it.kind != ShapeKind.REGION &&
            it.bounds().contains(world) }?.let { return it.id }
        return visibleShapes.filter { it.kind == ShapeKind.REGION &&
            latestProjection.value.farLikeRegion(it.id) && it.bounds().contains(world) }
            .minByOrNull { it.bounds().area }?.id
    }

    fun hitCanvas(point: Offset): Pair<TextElement?, String?> {
        hitInk(point, InkKind.PEN)?.let { return null to it }
        hitTest(point)?.let { return it to null }
        hitSpatial(point)?.let { return null to it }
        hitInk(point, InkKind.MARKER)?.let { return null to it }
        val (x, y) = latestViewport.value.screenToWorld(point.x, point.y)
        return null to latestSnapshot.value.images.asReversed().firstOrNull {
            latestProjection.value.visible(it.id) && it.bounds().contains(WorldPoint(x, y))
        }?.id
    }

    fun endAt(point: Offset): ArrowEnd {
        val (x, y) = latestViewport.value.screenToWorld(point.x, point.y)
        val world = WorldPoint(x, y)
        val target = hitTest(point)?.id ?: latestSnapshot.value.shapes.asReversed()
            .firstOrNull { latestProjection.value.visible(it.id) &&
                (it.containsInterior(world) || it.hitStroke(world, 12f / latestViewport.value.scale)) }?.id
            ?: latestSnapshot.value.images.asReversed().firstOrNull {
                latestProjection.value.visible(it.id) && it.bounds().contains(world) }?.id
        val bounds = target?.let { latestSnapshot.value.boundsOf(it,
            latestRenderedGeometry.value.boundsById) }
        return if (target != null && bounds != null) ArrowEnd.Attached(
            target, ((x - bounds.left) / (bounds.right - bounds.left)).coerceIn(0f, 1f),
            ((y - bounds.top) / (bounds.bottom - bounds.top)).coerceIn(0f, 1f),
        ) else ArrowEnd.Free(x, y)
    }

    fun previewHandle(source: BoardSnapshot, id: String, kind: HandleKind, point: Offset): BoardSnapshot {
        val (x, y) = latestViewport.value.screenToWorld(point.x, point.y)
        return when (kind) {
            HandleKind.MOVE -> source
            HandleKind.RESIZE -> source.copy(shapes = source.shapes.map { shape ->
                if (shape.id == id) shape.copy(width = (x - shape.x).coerceAtLeast(40f),
                    height = (y - shape.y).coerceAtLeast(30f)) else shape
            }, images = source.images.map { image ->
                if (image.id == id) image.resized(x - image.x, y - image.y) else image
            })
            HandleKind.FROM, HandleKind.TO -> source.copy(arrows = source.arrows.map { arrow ->
                if (arrow.id != id) arrow else if (kind == HandleKind.FROM)
                    arrow.copy(from = endAt(point)) else arrow.copy(to = endAt(point))
            })
            HandleKind.BEND -> source.copy(arrows = source.arrows.map { arrow ->
                if (arrow.id != id) arrow else {
                    val points = source.arrowPoints(arrow,
                        DetailedRenderFacts.ARROW_ENDPOINT_OFFSET_DP * density.density /
                            latestViewport.value.scale,
                        latestRenderedGeometry.value.boundsById)
                    if (points == null) arrow else {
                        val dx = points.second.x - points.first.x
                        val dy = points.second.y - points.first.y
                        val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                        val middle = WorldPoint((points.first.x + points.second.x) / 2f,
                            (points.first.y + points.second.y) / 2f)
                        val bend = (-(x - middle.x) * dy + (y - middle.y) * dx) / length
                        arrow.copy(bend = if (kotlin.math.abs(bend) < 8f) 0f else bend)
                    }
                }
            })
        }
    }

    fun tap(point: Offset, eventUptimeMillis: Long?) {
        if (editorSession.draft.value != null || exitBlocked()) return
        if (chromeContains(point)) return
        val (element, spatial) = hitCanvas(point)
        if (latestInkTool.value != null) return
        if (element == null && spatial == null) {
            if (eventUptimeMillis == null || tool != SpatialTool.NONE) return
            cancelBlankTap()
            val (x, y) = latestViewport.value.screenToWorld(point.x, point.y)
            val pending = BlankTap(eventUptimeMillis, point, WorldPoint(x, y), gestureGeneration,
                board.snapshot(), selectedId, selectedIds.toSet(), searchOpen, editorSession.regionNameDraft.value)
            blankTapBoundary.pending = pending
            blankTapBoundary.confirmation = uiScope.launch {
                delay(doubleTapTimeoutMillis)
                confirmBlankTap(pending)
            }
        } else if (element == null && spatial != null) {
            cancelBlankTap()
            val region = board.shapes.firstOrNull { it.id == spatial && it.kind == ShapeKind.REGION }
            if (region != null && latestProjection.value.farLikeRegion(spatial) && canvasSize != IntSize.Zero) {
                animateViewport(latestViewport.value.fitRegion(region,
                    canvasSize.width.toFloat(), canvasSize.height.toFloat(), density.density))
                return
            }
            if (latestSelectedIds.value.size > 1 && spatial in latestSelectedIds.value) {
                selectedIds = latestSelectedIds.value - spatial
            } else if (spatial in latestSelectedIds.value && board.shapes.any { it.id == spatial && it.kind == ShapeKind.REGION }) {
                openRegionName(spatial, board.shapes.first { it.id == spatial }.name)
            } else { selectedId = null; selectedIds = setOf(spatial) }
        } else if (element != null && latestSelectedIds.value.size > 1 && element.id in latestSelectedIds.value) {
            cancelBlankTap()
            selectedIds = latestSelectedIds.value - element.id
        } else if (element != null && latestSelected.value == element.id) {
            cancelBlankTap()
            draft = Draft(element.id, element.x, element.y, element.text, element.kind, element.color)
        } else if (element != null) { cancelBlankTap(); selectedId = element.id; selectedIds = setOf(element.id) }
    }

    fun nudge(id: String, dx: Float, dy: Float): Boolean {
        if (saveBlocked()) return false
        if (board.elements.none { it.id == id }) return false
        val ids = if (id in selectedIds) selectedIds else setOf(id)
        if (!board.moveSelection(ids, dx, dy)) return false
        saveSnapshot()
        return true
    }

    fun removeSelection(id: String): Boolean {
        if (saveBlocked()) return false
        if (id !in selectedIds) return false
        selectedIds = selectedIds - id
        if (selectedId == id) selectedId = null
        guidance = "${selectedIds.size}個を選択"
        return true
    }

    fun commitDraft() {
        if (latestExternalBlock.value()) return
        if (pendingDraftAcknowledgement != null) {
            if (saveFailed) onRetrySave()
            return
        }
        if (saving) return
        if (saveFailed) {
            onRetrySave()
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
            closeDraft()
        }
    }

    fun finalizeDraft() {
        if (saveBlocked() || pendingDraftAcknowledgement != null) return
        val current = draft ?: return
        if (current.id == null && current.text.isEmpty()) cancelDraft()
        else commitDraft()
    }
    val latestFinalizeDraft = rememberUpdatedState({ finalizeDraft() })

    suspend fun focusEditorInput(requester: FocusRequester, isCurrent: () -> Boolean) {
        if (!isCurrent()) return
        val requestJob = currentCoroutineContext().job
        pendingInputRequests.add(requestJob)
        try {
            snapshotFlow { windowInfo.isWindowFocused }.first { it }
            if (!isCurrent()) return
            requester.requestFocus()
            // Read-only editors retain focus, but cannot create an editable input connection.
            if (exitBlocked()) return
            val controller = if (Build.VERSION.SDK_INT >= 30) {
                inputView.windowInsetsController ?: return
            } else null
            val inputMethod = inputView.context.getSystemService(
                android.view.inputmethod.InputMethodManager::class.java)
            val ready = awaitEditorImeWindow(
                isCurrent = { isCurrent() && !exitBlocked() },
                awaitWindowOwnership = {
                    snapshotFlow { windowInfo.isWindowFocused && inputView.hasWindowFocus() }.first { it }
                },
                awaitImeControl = {
                    if (Build.VERSION.SDK_INT >= 30 && controller != null) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            val listener = object : WindowInsetsController.OnControllableInsetsChangedListener {
                                override fun onControllableInsetsChanged(controller: WindowInsetsController, typeMask: Int) {
                                    if (continuation.isActive && typeMask and android.view.WindowInsets.Type.ime() != 0) {
                                        // Ownership is checked after the frame, independently of IME control.
                                        // Do not mutate the platform listener list during its dispatch.
                                        inputView.post { controller.removeOnControllableInsetsChangedListener(this) }
                                        continuation.resume(Unit)
                                    }
                                }
                            }
                            continuation.invokeOnCancellation {
                                controller.removeOnControllableInsetsChangedListener(listener)
                            }
                            if (continuation.isActive) controller.addOnControllableInsetsChangedListener(listener)
                        }
                    }
                },
                awaitFrame = {
                    withFrameNanos { }
                    // A Compose frame can precede the native window/input dispatch.
                    // Hand off to the View queue before the final ownership check and show.
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val dispatch = Runnable {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        continuation.invokeOnCancellation { inputView.removeCallbacks(dispatch) }
                        inputView.post(dispatch)
                    }
                },
                hasWindowFocus = { inputView.hasWindowFocus() },
                isInputReady = { inputMethod.isActive(inputView) && inputMethod.isAcceptingText },
            )
            if (ready) keyboard?.show()
        } finally {
            pendingInputRequests.remove(requestJob)
        }
    }

    val inputAllowed = currentSaveState == BoardSaveState.Idle && pendingDraftAcknowledgement == null &&
        !latestExternalBlock.value()
    LaunchedEffect(draft?.sessionId, draft?.id, draft?.x, draft?.y, discardTarget, inputAllowed) {
        val current = draft
        if (current != null && discardTarget == null) {
            val sessionId = current.sessionId
            focusEditorInput(focusRequester) {
                editorSession.draft.value?.sessionId == sessionId && discardTarget == null
            }
        }
    }
    LaunchedEffect(searchOpen, inputAllowed) {
        if (searchOpen) {
            focusEditorInput(searchFocusRequester) {
                searchOpen && editorSession.draft.value == null && discardTarget == null
            }
        } else chromeBounds.remove("search")
    }
    LaunchedEffect(searchOpen, searchQuery, board.elements, board.shapes) {
        // A content-history change owns this navigation, including an unchanged visible camera.
        val historyNavigation = searchHistoryNavigation
        searchHistoryNavigation = null
        if (searchOpen && historyNavigation?.first == snapshot && historyNavigation.second == searchQuery) {
            searchPosition = searchPosition.coerceIn(0, maxOf(0, searchMatches.size - 1))
            return@LaunchedEffect
        }
        if (searchOpen && searchQuery.isNotBlank() && searchMatches.isNotEmpty())
            focusMatch(0, searchNavigationGroup)
    }

    LaunchedEffect(menuTarget) { if (menuTarget == null) chromeBounds.remove("menu") }
    LaunchedEffect(regionNameId) { if (regionNameId == null) chromeBounds.remove("regionName") }
    LaunchedEffect(discardTarget, draft?.sessionId, regionDraft?.sessionId, imageDraft?.sessionId) {
        val currentTarget = draft?.let { "text:${it.sessionId}" }
            ?: regionDraft?.let { "region:${it.sessionId}" }
            ?: imageDraft?.let { "image:${it.sessionId}" }
        if (discardTarget != null && discardTarget != currentTarget) discardTarget = null
    }
    LaunchedEffect(projection.hidden, menuTarget, attachmentEditor, regionNameId) {
        if (menuTarget?.let { !projection.visible(it) } == true) menuTarget = null
        if (attachmentEditor?.first?.let { !projection.visible(it) } == true)
            attachmentEditor = null
        if (regionNameId?.let { !projection.visible(it) } == true) {
            closeRegionName()
        }
    }
    LaunchedEffect(regionDraft?.sessionId, regionNameId, discardTarget, inputAllowed) {
        val current = regionDraft
        if (regionNameId != null && current != null && discardTarget == null) {
            val sessionId = current.sessionId
            focusEditorInput(regionNameFocusRequester) {
                editorSession.regionNameDraft.value?.sessionId == sessionId &&
                    editorSession.draft.value == null && discardTarget == null
            }
        }
    }
    LaunchedEffect(guidance, tool, inkTool, selectedIds) {
        if (guidance == null && tool == SpatialTool.NONE && inkTool == null && selectedIds.size <= 1)
            chromeBounds.remove("guidance")
    }
    LaunchedEffect(guidance, tool) {
        if (guidance != null && tool == SpatialTool.NONE) {
            delay(1800)
            guidance = null
        }
    }

    val pointerGeneration = gestureGeneration
    Box(
        modifier = Modifier.fillMaxSize().background(paper).safeDrawingPadding()
            .onSizeChanged { canvasSize = it }.clipToBounds()
            .semantics {
                contentDescription = "キャンバス"
                customActions = listOf(
                    CustomAccessibilityAction("中央から右に余白を作る") {
                        if (saveBlocked() || canvasSize == IntSize.Zero) false else {
                            val (x, y) = viewport.screenToWorld(canvasSize.width / 2f, canvasSize.height / 2f)
                            board.insertGap(WorldPoint(x, y), true, 40f).also { if (it) saveSnapshot() }
                        }
                    },
                    CustomAccessibilityAction("中央から下に余白を作る") {
                        if (saveBlocked() || canvasSize == IntSize.Zero) false else {
                            val (x, y) = viewport.screenToWorld(canvasSize.width / 2f, canvasSize.height / 2f)
                            board.insertGap(WorldPoint(x, y), false, 40f).also { if (it) saveSnapshot() }
                        }
                    },
                )
            }.pointerInput(board, editorSession, pointerGeneration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val admittedGeneration = gestureGeneration
                    if (admittedGeneration != pointerGeneration) {
                        down.consume()
                        return@awaitEachGesture
                    }
                    val activeDraft = editorSession.draft.value ?: return@awaitEachGesture
                    val fieldBounds = textEditorBounds ?: return@awaitEachGesture
                    val toolbarBounds = editorToolbarBounds ?: return@awaitEachGesture
                    if (fieldBounds.contains(down.position) || toolbarBounds.contains(down.position))
                        return@awaitEachGesture
                    // Finalize-only: keep this whole gesture away from children and canvas tools.
                    down.consume()
                    var isTap = true
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (gestureGeneration != admittedGeneration) {
                            event.changes.forEach { it.consume() }
                            break
                        }
                        val active = event.changes.firstOrNull { it.id == down.id }
                        if (event.changes.size != 1 || active == null ||
                            (active.position - down.position).getDistance() > touchSlop ||
                            active.uptimeMillis - down.uptimeMillis >= longPressMillis ||
                            active.position.x !in 0f..size.width.toFloat() ||
                            active.position.y !in 0f..size.height.toFloat()) isTap = false
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) {
                            // Compose cancellation can synthesize a release without a native event.
                            // Android also marks canceled pointer-up events with FLAG_CANCELED.
                            val nativeEvent = event.motionEvent
                            val cancelled = nativeEvent == null ||
                                nativeEvent.actionMasked == MotionEvent.ACTION_CANCEL ||
                                nativeEvent.flags and MotionEvent.FLAG_CANCELED != 0
                            if (isTap && !cancelled && event.type == PointerEventType.Release &&
                                active?.previousPressed == true &&
                                editorSession.draft.value?.sessionId == activeDraft.sessionId) latestFinalizeDraft.value()
                            break
                        }
                    }
                }
            }.pointerInput(board, editorSession, pointerGeneration) {
            awaitEachGesture {
                var gestureMove: MoveDragSession? = null
                try {
                val down = awaitFirstDown(requireUnconsumed = false)
                manualGestureActive = true
                if (latestExternalBlock.value()) return@awaitEachGesture
                if (imageDraft != null || imagePickerOpen) return@awaitEachGesture
                val admittedGeneration = gestureGeneration
                if (admittedGeneration != pointerGeneration) {
                    down.consume()
                    return@awaitEachGesture
                }
                if (editorSession.draft.value != null) return@awaitEachGesture
                val requestedInk = down.type == PointerType.Stylus || latestInkTool.value != null
                if (chromeContains(down.position)) { cancelBlankTap(); return@awaitEachGesture }
                stopViewportAnimation()
                pendingHistoryFocus = null
                val previousBlankTap = blankTapBoundary.pending
                if (previousBlankTap != null) {
                    if (!blankTapIsLive(previousBlankTap) || requestedInk) cancelBlankTap()
                    else if (previousBlankTap.matchesSecondDown(down.uptimeMillis, down.position,
                        doubleTapMinTimeMillis, doubleTapTimeoutMillis, doubleTapSlop) &&
                        hitCanvas(down.position).let { it.first == null && it.second == null } &&
                        canvasSize != IntSize.Zero) {
                        cancelBlankTap()
                        animateViewport(latestViewport.value.doubleTapZoom(down.position.x, down.position.y,
                            latestBodyDp.value, canvasSize.width.toFloat(), canvasSize.height.toFloat()))
                        down.consume()
                        return@awaitEachGesture
                    } else {
                        // Two independent taps: preserve the first action before admitting the next.
                        confirmBlankTap(previousBlankTap)
                    }
                }
                // #71 owns only an editor that has actually started. Never create then cancel for zoom.
                if (editorSession.draft.value != null || saveFailed || (saving && !requestedInk))
                    return@awaitEachGesture
                val (target, topId) = hitCanvas(down.position)
                var targetId = target?.id ?: topId
                val startTime = SystemClock.uptimeMillis()
                val start = down.position
                var end = start
                var dragAdmitted = false
                val activeTool = latestTool.value
                var drawingKind = if (down.type == PointerType.Stylus) InkKind.PEN else latestInkTool.value
                var drawingInput = if (down.type == PointerType.Stylus) InkInputType.STYLUS else InkInputType.TOUCH
                var drawingStart = startTime
                var drawingPointer = down.id
                var drawingPoints = if (drawingKind != null)
                    latestViewport.value.screenToWorld(down.position.x, down.position.y).let { (x, y) ->
                        listOf(InkPoint(x, y, 0L))
                    } else emptyList()
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
                    snapshot.images.filter { it.id in latestSelectedIds.value }.firstOrNull { image ->
                        val (x, y) = latestViewport.value.worldToScreen(image.x + image.width, image.y + image.height)
                        (start - Offset(x, y)).getDistance() <= radius
                    }?.let { image -> if (handle == null) { targetId = image.id; handle = HandleKind.RESIZE } }
                    snapshot.shapes.filter { it.id in latestSelectedIds.value }.firstOrNull { shape ->
                        val (x, y) = latestViewport.value.worldToScreen(shape.x + shape.width / 2f,
                            shape.y + shape.height + 14f)
                        (start - Offset(x, y)).getDistance() <= radius
                    }?.let { shape -> if (handle == null) { targetId = shape.id; handle = HandleKind.MOVE } }
                    snapshot.arrows.filter { it.id in latestSelectedIds.value }.forEach { arrow ->
                        snapshot.arrowPoints(arrow,
                            DetailedRenderFacts.ARROW_ENDPOINT_OFFSET_DP * density.density /
                                latestViewport.value.scale,
                            latestRenderedGeometry.value.boundsById)?.let { (a, b) ->
                            val (ax, ay) = latestViewport.value.worldToScreen(a.x, a.y)
                            val (bx, by) = latestViewport.value.worldToScreen(b.x, b.y)
                            if ((start - Offset(ax, ay)).getDistance() <= radius) {
                                targetId = arrow.id; handle = HandleKind.FROM
                            } else if ((start - Offset(bx, by)).getDistance() <= radius) {
                                targetId = arrow.id; handle = HandleKind.TO
                            }
                        }
                        snapshot.arrowControl(arrow,
                            DetailedRenderFacts.ARROW_ENDPOINT_OFFSET_DP * density.density /
                                latestViewport.value.scale,
                            latestRenderedGeometry.value.boundsById)?.let { c ->
                            val (x, y) = latestViewport.value.worldToScreen(c.x, c.y)
                            if ((start - Offset(x, y)).getDistance() <= radius) {
                                targetId = arrow.id; handle = HandleKind.BEND
                            }
                        }
                    }
                }
                val activeId = targetId
                val gestureSnapshot = latestSnapshot.value
                val moveIds = if (activeId in latestSelectedIds.value) latestSelectedIds.value.toSet()
                    else activeId?.let { setOf(it) }.orEmpty()
                var mode = when {
                    drawingKind != null -> "ink"
                    activeTool == SpatialTool.LASSO -> "lasso"
                    activeTool != SpatialTool.NONE -> "create"
                    handle != null -> "handle"
                    selectedGrip -> "move"
                    else -> "tap"
                }
                var manualViewportOrigin: ViewportFocus? = null
                val startWorld = viewport.screenToWorld(start.x, start.y).let { WorldPoint(it.first, it.second) }
                fun previewMove(pointer: Offset) {
                    if (moveIds.isEmpty()) return
                    if (!dragAdmitted) {
                        movePreview = moveIds to worldDragDelta(viewport, startWorld, pointer)
                        return
                    }
                    val owner = gestureMove ?: MoveDragSession(moveIds, startWorld, admittedGeneration,
                        gestureSnapshot, pointer).also { gestureMove = it; moveOwner = it }
                    if (moveIsLive(owner)) {
                        owner.pointer = pointer
                        movePreview = owner.ids to worldDragDelta(viewport, owner.anchor, pointer)
                    } else cancelMove(owner)
                }
                if (mode == "ink") inkPreview = InkPreview(drawingKind!!, drawingInput, drawingPoints)
                if (mode == "lasso") lassoPoints = listOf(startWorld)
                while (true) {
                    val remaining = longPressMillis - (SystemClock.uptimeMillis() - startTime)
                    val event = if (mode == "tap" && remaining > 0) {
                        withTimeoutOrNull(remaining) { awaitPointerEvent() }
                    } else if (mode == "tap" && remaining <= 0) null
                    else awaitPointerEvent()
                    if (gestureGeneration != admittedGeneration) {
                        event?.changes?.forEach { it.consume() }
                        break
                    }
                    if (gestureMove?.let { !moveIsLive(it) } == true) {
                        gestureMove?.let(::cancelMove)
                        event?.changes?.forEach { it.consume() }
                        break
                    }
                    if (event == null) {
                        mode = "longPressPending"
                        // 長押し成立は視覚表示と振動で知らせる。drag の admission は touchSlop だけが決める。
                        if (activeId == null) guidance = "ドラッグして余白を作る"
                        else movePreview = (if (activeId in latestSelectedIds.value)
                            latestSelectedIds.value else setOf(activeId)) to WorldPoint(0f, 0f)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        continue
                    }
                    val pressed = event.changes.filter { it.pressed }
                    val activeReleased = mode == "ink" && event.changes.any { it.id == drawingPointer && !it.pressed }
                    if (pressed.isEmpty() || activeReleased) {
                        if (event.type != PointerEventType.Release) break
                        val feedbackRelease = event.motionEvent?.let {
                            it.actionMasked == MotionEvent.ACTION_UP &&
                                it.flags and MotionEvent.FLAG_CANCELED == 0
                        } == true
                        if (mode == "pan" || mode == "zoom") {
                            val native = event.motionEvent
                            if (native?.actionMasked == MotionEvent.ACTION_UP &&
                                native.flags and MotionEvent.FLAG_CANCELED == 0)
                                navigation.record(manualViewportOrigin)
                        }
                        if (mode == "move" || mode == "longPressPending" ||
                            mode == "handle" && handle == HandleKind.MOVE) {
                            val native = event.motionEvent
                            if (exitBlocked() || native == null || native.actionMasked == MotionEvent.ACTION_CANCEL ||
                                native.flags and MotionEvent.FLAG_CANCELED != 0) break
                        }
                        end = event.changes.firstOrNull { it.id == drawingPointer }?.position
                            ?: event.changes.firstOrNull()?.position ?: end
                        when (mode) {
                            "ink" -> if (drawingKind != null && drawingPoints.isNotEmpty()) {
                                val endTime = SystemClock.uptimeMillis()
                                val (x, y) = latestViewport.value.screenToWorld(end.x, end.y)
                                val last = drawingPoints.last()
                                if (last.x != x || last.y != y) drawingPoints = drawingPoints +
                                    InkPoint(x, y, (endTime - drawingStart).coerceAtLeast(last.elapsedMillis))
                                val stroke = InkStroke(startedAt = drawingStart, endedAt = endTime,
                                    inputType = drawingInput, points = drawingPoints)
                                board.addInkStroke(drawingKind, stroke)
                                saveSnapshot()
                            }
                            "tap" -> {
                                val native = event.motionEvent
                                if (native == null || native.actionMasked == MotionEvent.ACTION_CANCEL ||
                                    native.flags and MotionEvent.FLAG_CANCELED != 0) break
                                val release = checkNotNull(event.changes.firstOrNull {
                                    it.id == down.id && it.previousPressed && !it.pressed
                                }) { "Tap release for active pointer is missing" }
                                tap(start, release.uptimeMillis)
                            }
                            "create" -> {
                                guidance = null
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
                                        if (kind == ShapeKind.REGION) openRegionName(shape.id, "")
                                        true
                                    }
                                    SpatialTool.ARROW -> if (distance >= touchSlop) {
                                        val arrow = board.addArrow(endAt(start), endAt(end))
                                        if (arrow != null) {
                                            selectedIds = setOf(arrow.id); selectedId = null
                                            guidance = if (arrow.from is ArrowEnd.Attached || arrow.to is ArrowEnd.Attached)
                                                "矢印を接続しました" else "矢印を作成しました"
                                        }
                                        arrow != null
                                    } else false
                                    else -> false
                                }
                                if (created) {
                                    if (feedbackRelease) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    saveSnapshot()
                                }
                                finishToolInteraction(clearGuidance = false)
                            }
                            "lasso" -> {
                                val (x, y) = latestViewport.value.screenToWorld(end.x, end.y)
                                selectedIds = latestSnapshot.value.visibleLassoSelection(
                                    lassoPoints + WorldPoint(x, y), latestProjection.value,
                                    latestRenderedGeometry.value.boundsById,
                                    DetailedRenderFacts.ARROW_ENDPOINT_OFFSET_DP * density.density /
                                        latestViewport.value.scale)
                                selectedId = selectedIds.singleOrNull()?.takeIf { id -> board.elements.any { it.id == id } }
                                guidance = "${selectedIds.size}個を選択"
                                finishToolInteraction(clearGuidance = false)
                            }
                            "move", "longPressPending" -> if (activeId != null) {
                                if (dragAdmitted) {
                                    val owner = gestureMove ?: break
                                    if (!moveIsLive(owner)) break
                                    val delta = worldDragDelta(viewport, owner.anchor, end)
                                    moveOwner = null
                                    val beforeSnapshot = board.snapshot()
                                    if (board.moveSelection(owner.ids, delta.x, delta.y)) {
                                        val afterSnapshot = board.snapshot()
                                        val changedIds = (beforeSnapshot.texts.zip(afterSnapshot.texts)
                                            .filter { (old, new) -> old != new }.map { it.first.id } +
                                            beforeSnapshot.shapes.zip(afterSnapshot.shapes)
                                                .filter { (old, new) -> old != new }.map { it.first.id } +
                                            beforeSnapshot.arrows.zip(afterSnapshot.arrows)
                                                .filter { (old, new) -> old != new }.map { it.first.id } +
                                            beforeSnapshot.images.zip(afterSnapshot.images)
                                                .filter { (old, new) -> old != new }.map { it.first.id } +
                                            beforeSnapshot.ink.zip(afterSnapshot.ink)
                                                .filter { (old, new) -> old != new }.map { it.first.id })
                                        val transition = changedIds.firstNotNullOfOrNull { id ->
                                            val beforeRegion = beforeSnapshot.centerOf(id)
                                                ?.let { beforeSnapshot.smallestRegionAt(it)?.id }
                                            val afterRegion = afterSnapshot.centerOf(id)
                                                ?.let { afterSnapshot.smallestRegionAt(it)?.id }
                                            if (beforeRegion != afterRegion) beforeRegion to afterRegion else null
                                        }
                                        if (transition != null) {
                                            val (beforeRegion, afterRegion) = transition
                                            val id = afterRegion ?: beforeRegion
                                            val label = board.shapes.firstOrNull { it.id == id }?.name
                                                ?.takeIf { it.isNotBlank() } ?: "囲み"
                                            guidance = if (afterRegion == null) "${label}から出ました" else "${label}に入りました"
                                        }
                                        saveSnapshot()
                                    }
                                } else if (activeId !in latestSelectedIds.value && latestSelectedIds.value.isNotEmpty()) {
                                    selectedIds = latestSelectedIds.value + activeId
                                    guidance = "${selectedIds.size}個を選択"
                                } else menuTarget = activeId
                            } else guidance = null
                            "handle" -> if (activeId != null && handle != null) {
                                val changed = when (handle) {
                                    HandleKind.MOVE -> {
                                        val owner = gestureMove
                                        if (owner != null && !moveIsLive(owner)) break
                                        val delta = worldDragDelta(viewport, startWorld, end)
                                        moveOwner = null
                                        board.moveSelection(owner?.ids ?: moveIds, delta.x, delta.y)
                                    }
                                    else -> board.apply(previewHandle(gestureSnapshot, activeId, handle, end))
                                }
                                if (changed) {
                                    if (handle == HandleKind.FROM || handle == HandleKind.TO) {
                                        guidance = "端点を変更しました"
                                        if (feedbackRelease) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    }
                                    saveSnapshot()
                                }
                            }
                            "gap" -> {
                                val dx = (end.x - start.x) / latestViewport.value.scale
                                val dy = (end.y - start.y) / latestViewport.value.scale
                                val horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
                                if (board.insertGap(startWorld, horizontal, if (horizontal) dx else dy)) {
                                    guidance = "余白を作りました"
                                    saveSnapshot()
                                }
                            }
                        }
                        movePreview = null
                        handlePreview = null
                        spatialPreview = null
                        gapPreview = null
                        lassoPoints = emptyList()
                        inkPreview = null
                        break
                    }
                    val activeStylus = pressed.firstOrNull { it.type == PointerType.Stylus }
                    if (activeStylus != null && drawingInput != InkInputType.STYLUS) {
                        if (moveOwner === gestureMove) moveOwner = null
                        gestureMove = null
                        movePreview = null
                        handlePreview = null
                        mode = "ink"
                        drawingKind = InkKind.PEN
                        drawingInput = InkInputType.STYLUS
                        drawingPointer = activeStylus.id
                        drawingStart = SystemClock.uptimeMillis()
                        drawingPoints = listOf(viewport.screenToWorld(
                            activeStylus.position.x, activeStylus.position.y).let { (x, y) -> InkPoint(x, y, 0L) })
                        inkPreview = InkPreview(InkKind.PEN, InkInputType.STYLUS, drawingPoints)
                    } else if (pressed.size >= 2 && activeStylus == null) {
                        if (moveOwner === gestureMove) moveOwner = null
                        gestureMove = null
                        mode = "zoom"
                        inkPreview = null
                        movePreview = null
                        handlePreview = null
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
                            if (before > 0f) {
                                if (manualViewportOrigin == null) manualViewportOrigin = navigation.focus()
                                viewport = viewport.zoomAt(
                                    previousCenter.x, previousCenter.y, after / before,
                                    center.x - previousCenter.x, center.y - previousCenter.y,
                                )
                            }
                        }
                    } else {
                        val change = if (mode == "ink") pressed.firstOrNull { it.id == drawingPointer }
                            ?: pressed.first() else pressed.first()
                        end = change.position
                        val delta = change.position - change.previousPosition
                        if (mode == "tap" && (change.position - start).getDistance() > touchSlop) mode = "pan"
                        if (mode == "move" && !dragAdmitted && (change.position - start).getDistance() > touchSlop) {
                            dragAdmitted = true
                        }
                        if (mode == "handle" && handle == HandleKind.MOVE &&
                            (change.position - start).getDistance() > touchSlop) dragAdmitted = true
                        if (mode == "longPressPending" && (change.position - start).getDistance() > touchSlop) {
                            dragAdmitted = true
                            if (activeId == null) {
                                mode = "gap"
                                gapPreview = startWorld to startWorld
                            } else mode = "move"
                        }
                        when (mode) {
                            "ink" -> {
                                val (x, y) = latestViewport.value.screenToWorld(end.x, end.y)
                                val elapsed = (SystemClock.uptimeMillis() - drawingStart).coerceAtLeast(0L)
                                val last = drawingPoints.lastOrNull()
                                if (last == null || (last.x != x || last.y != y) && elapsed >= last.elapsedMillis) {
                                    drawingPoints = drawingPoints + InkPoint(x, y, elapsed)
                                    inkPreview = InkPreview(
                                        if (drawingInput == InkInputType.STYLUS) InkKind.PEN else drawingKind!!,
                                        drawingInput, drawingPoints)
                                }
                            }
                            "pan" -> {
                                if (manualViewportOrigin == null) manualViewportOrigin = navigation.focus()
                                viewport = latestViewport.value.pan(delta.x, delta.y)
                            }
                            "move" -> if (activeId != null) {
                                previewMove(change.position)
                            }
                            "handle" -> if (activeId != null && handle != null) {
                                if (handle == HandleKind.MOVE) {
                                    previewMove(end)
                                } else handlePreview = previewHandle(gestureSnapshot, activeId, handle, end)
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
                    manualGestureActive = false
                    if (moveOwner === gestureMove) moveOwner = null
                    clearInteractionPreviews()
                }
            }
        },
    ) {
        if (board.elements.isEmpty() && board.shapes.isEmpty() && board.arrows.isEmpty() &&
            board.ink.isEmpty() && board.images.isEmpty() && draft == null) {
            Text(
                stringResource(R.string.empty_hint), color = Color(0xFFB1ACA5), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        val sourceSnapshot = board.snapshot()
        val movingPreview = movePreview
        val pendingGap = gapPreview
        val displaySnapshot = when {
            handlePreview != null -> handlePreview!!
            movingPreview != null -> movingPreview.let { (ids, delta) ->
                sourceSnapshot.translatedSelection(ids, delta.x, delta.y)
            }
            pendingGap != null -> pendingGap.let { (start, end) ->
                val dx = end.x - start.x
                val dy = end.y - start.y
                val horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
                sourceSnapshot.withGap(start, horizontal, if (horizontal) dx else dy)
            }
            else -> sourceSnapshot
        }
        val ghostIds = if (pendingGap == null) emptySet() else {
            (displaySnapshot.texts.filterIndexed { index, it -> it != sourceSnapshot.texts[index] }.map { it.id } +
                displaySnapshot.shapes.filterIndexed { index, it -> it != sourceSnapshot.shapes[index] }.map { it.id } +
                displaySnapshot.images.filterIndexed { index, it -> it != sourceSnapshot.images[index] }.map { it.id }).toSet()
        }
        val movingIds = movingPreview?.first ?: emptySet()
        val displayTextBounds = displaySnapshot.texts.mapNotNull { text ->
            val extent = measuredTextExtents[text.id] ?: return@mapNotNull null
            text.id to WorldBounds(text.x, text.y, text.x + extent.width, text.y + extent.height)
        }.toMap()
        val displayGeometry = displaySnapshot.resolveRenderedGeometry(displayTextBounds,
            viewport.scale, density.density)
        val displayProjection = displaySnapshot.semanticProjection(viewport.scale, bodyDp,
            keptIds + movingIds, density.density, titleDp, titleLineHeightWorld,
            displayGeometry.boundsById)
        val displayBoundaryShapes = displaySnapshot.shapes.filter { displayProjection.visible(it.id) }
        fun imageActionAllowed(id: String): Boolean = !latestExternalBlock.value() &&
            baseNavigationAllowed() && !searchOpen && !manualGestureActive && !viewportAnimating && board.images.any { it.id == id }
        fun admitImageAction(id: String): Boolean {
            if (!imageActionAllowed(id)) return false
            cancelBlankTap()
            return true
        }
        ImageElements(displaySnapshot, viewport, displayProjection, selectedIds, canvasSize,
            imageResources, enabled = indicatorsAllowed() && !searchOpen,
            onSelect = { id -> if (!admitImageAction(id)) false else {
                selectedIds = setOf(id); selectedId = null; true
            } },
            onAdd = { id -> if (!admitImageAction(id) || id in selectedIds) false else {
                selectedIds = selectedIds + id; selectedId = null; true
            } },
            onRemove = { id -> if (admitImageAction(id)) removeSelection(id) else false },
            onMove = { id, dx, dy -> if (!admitImageAction(id)) false else
                board.moveSelection(if (id in selectedIds) selectedIds else setOf(id), dx, dy)
                    .also { if (it) saveSnapshot() } },
            onResize = { id, factor -> if (!admitImageAction(id)) false else {
                val image = board.images.first { it.id == id }
                board.resizeImage(id, image.width * factor, image.height * factor).also { if (it) saveSnapshot() }
            } },
            onDelete = { id -> if (!admitImageAction(id)) false else board.delete(setOf(id)).also {
                if (it) { selectedIds = emptySet(); selectedId = null; saveSnapshot() }
            } },
            onDescribe = { id -> if (admitImageAction(id)) openImageDescription(id) else false })
        InkLayer(displaySnapshot.ink.filter { displayProjection.visible(it.id) }, InkKind.MARKER,
            viewport, selectedIds, movingIds, inkPreview, dimmed = searchOpen && searchQuery.isNotBlank(),
            onSelect = { id -> if (!saveBlocked()) { selectedIds = setOf(id); selectedId = null } },
            onMove = { id, dx, dy ->
                if (saveBlocked()) false else board.moveSelection(
                    if (id in selectedIds) selectedIds else setOf(id), dx, dy,
                ).also { if (it) saveSnapshot() }
            },
            onDelete = { id ->
                if (saveBlocked()) false else board.delete(setOf(id)).also {
                    if (it) { selectedIds = emptySet(); selectedId = null; saveSnapshot() }
                }
            })
        SpatialElements(displaySnapshot, viewport, selectedIds, movingIds, spatialPreview, lassoPoints,
            gapPreview, ghostIds, displayProjection, matchIds, currentMatch?.id,
            searchOpen && searchQuery.isNotBlank(), canvasSize.width,
            onHandle = { id, kind ->
            if (saveBlocked()) false else {
                val changed = when (kind) {
                    HandleKind.MOVE -> board.moveSelection(setOf(id), 16f, 0f)
                    HandleKind.RESIZE -> board.shapes.firstOrNull { it.id == id }?.let {
                        board.resizeShape(id, it.width + 16f, it.height + 16f)
                    } ?: false
                    HandleKind.FROM, HandleKind.TO -> board.arrows.firstOrNull { it.id == id }?.let { arrow ->
                        val end = board.snapshot().detachedEnd(arrow, kind == HandleKind.FROM,
                            DetailedRenderFacts.ARROW_ENDPOINT_OFFSET_DP * density.density /
                                viewport.scale, displayGeometry.boundsById)
                        if (end == null) false else if (kind == HandleKind.FROM)
                            board.updateArrow(id, from = end)
                        else board.updateArrow(id, to = end)
                    } ?: false
                    HandleKind.BEND -> board.arrows.firstOrNull { it.id == id }?.let {
                        board.updateArrow(id, bend = it.bend + 16f)
                    } ?: false
                }
                if (changed) saveSnapshot()
                changed
            }
            },
            onConnect = { id, kind ->
                if (saveBlocked() || board.arrows.none { it.id == id }) false else {
                    attachmentEditor = id to kind
                    true
                }
            },
            onSelect = { id ->
                if (!saveBlocked()) {
                    val region = board.shapes.firstOrNull { it.id == id && it.kind == ShapeKind.REGION }
                    if (region != null && projection.farLikeRegion(id) && canvasSize != IntSize.Zero)
                        animateViewport(viewport.fitRegion(region,
                            canvasSize.width.toFloat(), canvasSize.height.toFloat(), density.density))
                    else { selectedIds = setOf(id); selectedId = null }
                }
            },
            onAdd = { id ->
                if (saveBlocked() || id in selectedIds) false else {
                    selectedIds = selectedIds + id
                    guidance = "${selectedIds.size}個を選択"
                    true
                }
            },
            onRemove = { id -> removeSelection(id) },
            onRename = { id ->
                val shape = board.shapes.firstOrNull { it.id == id && it.kind == ShapeKind.REGION }
                if (shape == null || saveBlocked()) false else {
                    openRegionName(id, shape.name)
                    true
                }
            },
            onColor = { id ->
                val shape = board.shapes.firstOrNull { it.id == id && it.kind != ShapeKind.REGION }
                if (shape == null || saveBlocked()) false else
                    board.updateShape(id, color = if (shape.color == TextColor.INK)
                        TextColor.VERMILION else TextColor.INK).also { if (it) saveSnapshot() }
            },
            onMove = { id, dx, dy ->
                val ids = if (id in selectedIds) selectedIds else setOf(id)
                if (saveBlocked()) false else board.moveSelection(ids, dx, dy).also {
                    if (it) saveSnapshot()
                }
            },
            onDelete = { id ->
                if (saveBlocked()) false else board.delete(setOf(id)).also {
                    if (it) { selectedIds = emptySet(); selectedId = null; saveSnapshot() }
                }
            },
            onReverse = { id ->
                if (saveBlocked()) false else board.updateArrow(id, reverse = true).also {
                    if (it) saveSnapshot()
                }
            },
            renderedBounds = displayGeometry.boundsById)

        attachmentEditor?.takeIf { displayProjection.visible(it.first) }?.let { (arrowId, endKind) ->
            board.arrows.firstOrNull { it.id == arrowId }?.let { arrow ->
                ArrowAttachmentDialog(board.snapshot(), projection, arrow, endKind,
                    onDismiss = { attachmentEditor = null },
                    onAttach = { end ->
                        if (saveBlocked() || !projection.visible(end.targetId)) false else {
                            val current = if (endKind == HandleKind.FROM) arrow.from else arrow.to
                            if (current == end) return@ArrowAttachmentDialog true
                            val changed = if (endKind == HandleKind.FROM)
                                board.updateArrow(arrowId, from = end)
                            else board.updateArrow(arrowId, to = end)
                            if (changed) saveSnapshot()
                            changed
                        }
                    },
                )
            }
        }

        displaySnapshot.texts.forEach { element ->
            if (displayProjection.visible(element.id) && draft?.id != element.id &&
                pendingNewElementId != element.id) {
                val title = element.kind == TextKind.TITLE
                val tier = displayProjection.tier
                val availableWorld = if (title && tier != SemanticTier.NEAR)
                    displaySnapshot.titleAvailableWidth(element, titleLineHeightWorld,
                        displayBoundaryShapes) else null
                val availableDp = availableWorld?.takeUnless { element.id in selectedIds || element.id in matchIds }
                    ?.let { with(density) { it.toDp() } }
                val (screenX, screenY) = viewport.worldToScreen(element.x, element.y)
                val selected = element.id in selectedIds || element.id in movingIds
                val elementActionsEnabled = !saveBlocked()
                val baseSize = if (title) 15.sp else 14.sp
                val minimumDp = when (tier) {
                    SemanticTier.NEAR -> if (title) 11f else 10f
                    SemanticTier.MID -> if (title) 11f else 9.5f
                    SemanticTier.FAR -> if (title) 9f else 10f
                }
                val visibleSize = with(density) {
                    maxOf(baseSize.toDp().value, minimumDp / viewport.scale).dp.toSp()
                }
                val faded = searchOpen && searchQuery.isNotBlank() &&
                    element.id !in matchIds && !selected
                val tierAlpha = if (!title && tier == SemanticTier.MID)
                    1f - .55f * displayProjection.midProgress
                else if (title && tier == SemanticTier.FAR) .55f else 1f
                val elementColor = (if (element.color == TextColor.INK) ink else vermilion)
                    .copy(alpha = if (faded) .25f else tierAlpha)
                Text(
                    text = element.text,
                    color = if (element.id in ghostIds) vermilion.copy(alpha = .65f)
                        else elementColor,
                    fontSize = visibleSize,
                    lineHeight = visibleSize * 1.5f,
                    maxLines = if (tier == SemanticTier.NEAR) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (title) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.offsetPx(screenX, screenY)
                        .graphicsLayer {
                            scaleX = viewport.scale
                            scaleY = viewport.scale
                            transformOrigin = TransformOrigin(0f, 0f)
                            shadowElevation = if (element.id in movingIds) 8.dp.toPx() else 0f
                        }
                        .widthIn(max = if (tier == SemanticTier.NEAR) 166.dp else availableDp ?: 166.dp)
                        .then(if (element.id in matchIds) Modifier
                            .background(vermilion.copy(alpha = if (element.id == currentMatch?.id) .32f else .14f))
                            .drawBehind {
                                if (element.id == currentMatch?.id) drawRect(vermilion,
                                    style = Stroke(2.dp.toPx()))
                            } else Modifier)
                        .then(if (selected) Modifier.selectionFrame(element.id in movingIds) else Modifier)
                        .onSizeChanged { elementSizes[element.id] = it }
                        .semantics {
                            contentDescription = element.text
                            stateDescription = if (selected) selectedLabel else unselectedLabel
                            if (elementActionsEnabled) {
                                onClick(label = if (selected) editLabel else selectLabel) {
                                    tap(Offset(screenX + 1, screenY + 1), null)
                                    true
                                }
                                customActions = listOf(
                                    if (element.id in selectedIds)
                                        CustomAccessibilityAction("選択から外す") { removeSelection(element.id) }
                                    else CustomAccessibilityAction("選択に追加") {
                                        if (saveBlocked()) false else {
                                            selectedIds = selectedIds + element.id
                                            guidance = "${selectedIds.size}個を選択"
                                            true
                                        }
                                    },
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
            DisposableEffect(editorSession) {
                onDispose { textEditorBounds = null; editorToolbarBounds = null }
            }
            val (worldScreenX, worldScreenY) = viewport.worldToScreen(current.x, current.y)
            // IME avoidance owns only field presentation, never the world camera or draft position.
            // safeDrawingPadding already excludes the IME; reserve the actual toolbar and scaled field.
            val maxX = (canvasSize.width - with(density) {
                textEditorWidth.toPx() * viewport.scale
            }).coerceAtLeast(0f)
            val maxY = (canvasSize.height - with(density) {
                editorToolbarHeight.toPx() + textEditorMaxHeight.toPx() * viewport.scale
            }).coerceAtLeast(0f)
            val screenX = if (imeBottom > 0) worldScreenX.coerceIn(0f, maxX) else worldScreenX
            val screenY = if (imeBottom > 0) worldScreenY.coerceIn(0f, maxY) else worldScreenY
            BasicTextField(
                value = current.text,
                onValueChange = { if (!latestExternalBlock.value()) draft = current.copy(text = it) },
                readOnly = saveBlocked(),
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
                    .width(textEditorWidth)
                    .heightIn(max = textEditorMaxHeight)
                    .background(vermilion.copy(alpha = 0.07f), RoundedCornerShape(3.dp))
                    .drawBehind { drawLine(vermilion, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                    .onGloballyPositioned { textEditorBounds = it.boundsInParent() }
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

        InkLayer(displaySnapshot.ink.filter { displayProjection.visible(it.id) }, InkKind.PEN,
            viewport, selectedIds, movingIds, inkPreview, dimmed = searchOpen && searchQuery.isNotBlank(),
            onSelect = { id -> if (!saveBlocked()) { selectedIds = setOf(id); selectedId = null } },
            onMove = { id, dx, dy ->
                if (saveBlocked()) false else board.moveSelection(
                    if (id in selectedIds) selectedIds else setOf(id), dx, dy,
                ).also { if (it) saveSnapshot() }
            },
            onDelete = { id ->
                if (saveBlocked()) false else board.delete(setOf(id)).also {
                    if (it) { selectedIds = emptySet(); selectedId = null; saveSnapshot() }
                }
            })

        if (indicatorLayouts.isNotEmpty()) {
            Box(Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
                indicatorLayouts.forEach { layout ->
                    key(layout.target.kind) {
                        val boundsKey = "indicator:${layout.target.kind}"
                        DisposableEffect(boundsKey) {
                            onDispose { chromeBounds.remove(boundsKey) }
                        }
                        Box(Modifier.offsetPx(layout.touchBounds.left, layout.touchBounds.top)
                            .size(48.dp).clip(CircleShape).background(Color.White).pillBorder(24f)
                            .clickable(role = Role.Button, onClickLabel = layout.target.description) {
                                navigateIndicator(layout.target)
                            }
                            .semantics {
                                contentDescription = layout.target.description
                                traversalIndex = layout.target.kind.ordinal.toFloat()
                            }
                            .onGloballyPositioned { chromeBounds[boundsKey] = it.boundsInParent() },
                            contentAlignment = Alignment.Center) {
                            Text("➜", color = if (layout.target.kind == IndicatorKind.SEARCH) vermilion else ink,
                                fontSize = 22.sp, modifier = Modifier
                                    .graphicsLayer { rotationZ = layout.angleDegrees }
                                    .clearAndSetSemantics { })
                        }
                    }
                }
            }
        }

        if (draft == null) {
            if (searchOpen) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp)
                    .height(44.dp).background(Color.White, RoundedCornerShape(24.dp))
                    .pillBorder(24f).padding(horizontal = 4.dp)
                    .onGloballyPositioned { chromeBounds["search"] = it.boundsInParent() },
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(searchQuery, onValueChange = {
                        if (!latestExternalBlock.value()) {
                            cancelBlankTap()
                            searchQuery = it
                            searchPosition = 0
                        }
                    }, singleLine = true,
                        textStyle = TextStyle(color = ink, fontSize = 15.sp),
                        cursorBrush = SolidColor(vermilion),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusMatch(searchIndex(searchPosition, 1, searchMatches.size))
                        }),
                        modifier = Modifier.weight(1f).padding(start = 12.dp)
                            .focusRequester(searchFocusRequester)
                            .semantics { contentDescription = "ボード内を探す" },
                        decorationBox = { inner ->
                            Box {
                                if (searchQuery.isEmpty()) Text("ボード内を探す", color = muted, fontSize = 15.sp)
                                inner()
                            }
                        })
                    if (searchQuery.isNotBlank()) Text(
                        if (searchMatches.isEmpty()) "0件" else "${searchPosition + 1} / ${searchMatches.size}",
                        color = muted, fontSize = 12.sp,
                        modifier = Modifier.semantics { contentDescription =
                            if (searchMatches.isEmpty()) "0件" else "${searchPosition + 1}件目、全${searchMatches.size}件" })
                    IconButton(onClick = { focusMatch(searchIndex(searchPosition, -1, searchMatches.size)) },
                        enabled = searchMatches.isNotEmpty(), modifier = Modifier.size(44.dp)
                            .semantics { contentDescription = "前の検索結果" }) {
                        Text("‹", color = ink, fontSize = 24.sp)
                    }
                    IconButton(onClick = { focusMatch(searchIndex(searchPosition, 1, searchMatches.size)) },
                        enabled = searchMatches.isNotEmpty(), modifier = Modifier.size(44.dp)
                            .semantics { contentDescription = "次の検索結果" }) {
                        Text("›", color = ink, fontSize = 24.sp)
                    }
                    IconButton(onClick = {
                        cancelBlankTap()
                        searchOpen = false; searchQuery = ""; searchPosition = 0; hideEditorIme()
                    }, modifier = Modifier.size(44.dp).semantics { contentDescription = "検索を閉じる" }) {
                        Text("×", color = ink, fontSize = 22.sp)
                    }
                }
            } else {
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 8.dp)
                    .height(44.dp).background(Color.White, RoundedCornerShape(24.dp))
                    .pillBorder(24f).padding(horizontal = 14.dp)
                    .clickable(enabled = !saveBlocked() && draft == null && regionNameId == null) {
                        cancelBlankTap()
                        onOpenList()
                    }
                    .semantics { contentDescription = "ボード一覧を開く" }
                    .onGloballyPositioned { chromeBounds["board"] = it.boundsInParent() },
                contentAlignment = Alignment.Center,
            ) { Text("‹ $boardName", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) }

            if (inkTool == null) IconButton(onClick = {
                if (!saveBlocked()) {
                    finishToolInteraction()
                    searchOpen = true; searchQuery = ""; searchPosition = 0
                    selectedId = null; selectedIds = emptySet()
                }
            }, modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 8.dp)
                .size(44.dp).background(Color.White, CircleShape).pillBorder(22f)
                .onGloballyPositioned { chromeBounds["searchButton"] = it.boundsInParent() }
                .semantics { contentDescription = "ボード内を検索" }) {
                Text("⌕", color = ink, fontSize = 25.sp)
            }
            }

            if (inkTool != null) {
                Row(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 14.dp)
                    .background(Color.White, RoundedCornerShape(24.dp)).pillBorder(24f)
                    .padding(horizontal = 4.dp)
                    .onGloballyPositioned { chromeBounds["ink"] = it.boundsInParent() },
                    verticalAlignment = Alignment.CenterVertically) {
                    EditorOption("ペン", inkTool == InkKind.PEN, false, !saveBlocked()) {
                        if (!saveBlocked()) inkTool = InkKind.PEN
                    }
                    EditorOption("マーカー", inkTool == InkKind.MARKER, true, !saveBlocked()) {
                        if (!saveBlocked()) inkTool = InkKind.MARKER
                    }
                    EditorOption("やめる", false, false, true) { finishToolInteraction() }
                }
            } else chromeBounds.remove("ink")

            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 16.dp)
                    .background(Color.White, RoundedCornerShape(24.dp)).pillBorder(24f)
                    .onGloballyPositioned { chromeBounds["history"] = it.boundsInParent() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { editHistory(redo = false) },
                    enabled = board.canUndo && !saveBlocked(),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = undoLabel },
                ) { Text("↶", color = if (board.canUndo && !saveBlocked()) ink else muted.copy(alpha = 0.4f), fontSize = 25.sp) }
                Box(Modifier.width(1.dp).height(20.dp).background(outline))
                IconButton(
                    onClick = { editHistory(redo = true) },
                    enabled = board.canRedo && !saveBlocked(),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = redoLabel },
                ) { Text("↷", color = if (board.canRedo && !saveBlocked()) ink else muted.copy(alpha = 0.4f), fontSize = 25.sp) }
            }
            if (viewControlsVisible) {
                DisposableEffect(navigation) {
                    onDispose { chromeBounds.remove("viewportHistory") }
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 70.dp)
                        .background(Color.White, RoundedCornerShape(24.dp)).pillBorder(24f)
                        .onGloballyPositioned { chromeBounds["viewportHistory"] = it.boundsInParent() },
                ) {
                    IconButton(onClick = { restoreView(forward = false) }, enabled = navigation.canBack,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = "前の視点へ戻る"
                        }) { Text("←", color = if (navigation.canBack) ink else muted, fontSize = 23.sp) }
                    IconButton(onClick = { restoreView(forward = true) }, enabled = navigation.canForward,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = "次の視点へ進む"
                        }) { Text("→", color = if (navigation.canForward) ink else muted, fontSize = 23.sp) }
                }
            }
            val zoomText = "${(viewport.scale * 100).roundToInt()}%  ${when (projection.tier) {
                SemanticTier.NEAR -> "近"
                SemanticTier.MID -> "中"
                SemanticTier.FAR -> "遠"
            }}"
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 22.dp)
                    .background(Color.White, RoundedCornerShape(16.dp)).pillBorder(16f)
                    .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 5.dp)
                    .clickable(enabled = !saveBlocked()) {
                        if (!saveBlocked() && canvasSize != IntSize.Zero) animateViewport(viewport.cycleZoom(bodyDp,
                            canvasSize.width.toFloat(), canvasSize.height.toFloat()))
                    }
                    .semantics { contentDescription = "倍率を切り替える、$zoomText" }
                    .onGloballyPositioned { chromeBounds["zoom"] = it.boundsInParent() },
                contentAlignment = Alignment.Center,
            ) { Text(zoomText, color = muted, fontSize = 11.sp) }

            if (inkTool == null) Column(
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = if (viewControlsVisible) 126.dp else 70.dp)
                    .onGloballyPositioned { chromeBounds["tools"] = it.boundsInParent() },
                horizontalAlignment = Alignment.End,
            ) {
                DisposableEffect(Unit) {
                    onDispose { chromeBounds.remove("tools") }
                }
                SpatialTools(tool, toolsExpanded, !saveBlocked(),
                    onExpand = {
                        if (!saveBlocked()) {
                            cancelBlankTap()
                            if (tool == SpatialTool.NONE) toolsExpanded = !toolsExpanded
                            else finishToolInteraction()
                        }
                    }, onSelect = {
                        if (!saveBlocked()) {
                            finishToolInteraction()
                            tool = it
                            guidance = "${it.label}を配置"
                        }
                    },
                    onAccessibleAction = { item ->
                        if (saveBlocked() || canvasSize == IntSize.Zero) false else {
                            val (x, y) = viewport.screenToWorld(
                                canvasSize.width / 2f, canvasSize.height / 2f)
                            val created = when (item) {
                                SpatialTool.RECTANGLE, SpatialTool.ELLIPSE, SpatialTool.REGION -> {
                                    val kind = when (item) {
                                        SpatialTool.RECTANGLE -> ShapeKind.RECTANGLE
                                        SpatialTool.ELLIPSE -> ShapeKind.ELLIPSE
                                        else -> ShapeKind.REGION
                                    }
                                    val width = if (kind == ShapeKind.REGION) 200f else if (kind == ShapeKind.RECTANGLE) 120f else 110f
                                    val height = if (kind == ShapeKind.REGION) 150f else 80f
                                    val shape = board.addShape(kind, x - width / 2f, y - height / 2f, width, height)
                                    selectedIds = setOf(shape.id)
                                    selectedId = null
                                    if (kind == ShapeKind.REGION) openRegionName(shape.id, "")
                                    saveSnapshot()
                                    true
                                }
                                SpatialTool.ARROW -> {
                                    val arrow = board.addArrow(ArrowEnd.Free(x - 60f, y), ArrowEnd.Free(x + 60f, y))
                                    if (arrow != null) {
                                        selectedIds = setOf(arrow.id)
                                        selectedId = null
                                        saveSnapshot()
                                    }
                                    arrow != null
                                }
                                SpatialTool.LASSO -> {
                                    val (left, top) = viewport.screenToWorld(0f, 0f)
                                    val (right, bottom) = viewport.screenToWorld(
                                        canvasSize.width.toFloat(), canvasSize.height.toFloat())
                                    selectedIds = board.snapshot().visibleLassoSelection(listOf(
                                        WorldPoint(left, top), WorldPoint(right, top),
                                        WorldPoint(right, bottom), WorldPoint(left, bottom),
                                    ), projection, renderedGeometry.boundsById,
                                        DetailedRenderFacts.ARROW_ENDPOINT_OFFSET_DP * density.density /
                                            viewport.scale)
                                    selectedId = null
                                    guidance = "${selectedIds.size}個を選択"
                                    true
                                }
                                SpatialTool.NONE -> false
                            }
                            if (created) finishToolInteraction(clearGuidance = false)
                            created
                        }
                    }, onInkSelect = { kind ->
                        if (!saveBlocked()) {
                            finishToolInteraction()
                            inkTool = kind
                            selectedIds = emptySet()
                            selectedId = null
                        }
                    }, onImageAdd = if (onAddImage == null) null else {
                        {
                            if (!saveBlocked()) {
                                finishToolInteraction()
                                if (importReady()) imagePickerOpen = true
                            }
                        }
                    })
            }

            val message = if (inkTool != null) "1本指で描く ・ 2本指で移動" else guidance ?: when (tool) {
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

            if (selectedIds.isNotEmpty() && draft == null && regionNameId == null && imageDraft == null) {
                Row(Modifier.align(Alignment.TopCenter)
                    .padding(top = if (message == null) 62.dp else 100.dp)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .pillBorder(10f).padding(6.dp)
                    .onGloballyPositioned { chromeBounds["shareSelection"] = it.boundsInParent() }) {
                    DisposableEffect(Unit) {
                        onDispose { chromeBounds.remove("shareSelection") }
                    }
                    val enabled = !saveBlocked()
                    Button(onClick = {
                        cancelBlankTap()
                        onShareSelection(selectedIds.toSet())
                    }, enabled = enabled,
                        modifier = Modifier.height(48.dp).semantics {
                            contentDescription = "選択範囲を画像で共有"
                            if (!enabled) disabled()
                        }, shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White, contentColor = if (enabled) ink else muted),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp,
                            pressedElevation = 0.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text("選択範囲を画像で共有", fontSize = 13.sp)
                    }
                }
            }

            menuTarget?.takeIf(projection::visible)?.let { id ->
                val center = renderedGeometry.bounds(id)?.center
                    ?: board.snapshot().centerOf(id) ?: WorldPoint(0f, 0f)
                val (anchorX, anchorY) = viewport.worldToScreen(center.x, center.y)
                val menuX = anchorX.coerceIn(8f, maxOf(8f, canvasSize.width - with(density) { 320.dp.toPx() }))
                val menuY = (anchorY + with(density) { 22.dp.toPx() })
                    .coerceIn(8f, maxOf(8f, canvasSize.height - with(density) { 70.dp.toPx() }))
                Row(Modifier.align(Alignment.TopStart).offsetPx(menuX, menuY)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .pillBorder(10f).padding(6.dp)
                    .onGloballyPositioned { chromeBounds["menu"] = it.boundsInParent() }) {
                    if (board.arrows.any { it.id == id }) {
                        EditorOption(stringResource(R.string.menu_reverse), false, false, enabled = !saveBlocked()) {
                            if (saveBlocked()) return@EditorOption
                            if (board.updateArrow(id, reverse = true)) saveSnapshot()
                            menuTarget = null
                        }
                    } else if (board.shapes.any { it.id == id }) {
                        if (board.shapes.any { it.id == id && it.kind != ShapeKind.REGION }) {
                            EditorOption(stringResource(R.string.menu_color), false, true,
                                enabled = !saveBlocked()) {
                                if (saveBlocked()) return@EditorOption
                                val shape = board.shapes.first { it.id == id }
                                if (board.updateShape(id, color = if (shape.color == TextColor.INK)
                                    TextColor.VERMILION else TextColor.INK)) saveSnapshot()
                                menuTarget = null
                            }
                        }
                        if (board.shapes.any { it.id == id && it.kind == ShapeKind.REGION }) {
                            EditorOption(stringResource(R.string.menu_name), false, false, enabled = !saveBlocked()) {
                                if (saveBlocked()) return@EditorOption
                                openRegionName(id, board.shapes.first { it.id == id }.name)
                                menuTarget = null
                            }
                        }
                    } else if (board.images.any { it.id == id }) {
                        TextButton(enabled = !saveBlocked(), onClick = { openImageDescription(id) }) {
                            Text("代替テキストを編集")
                        }
                    } else if (board.elements.any { it.id == id }) {
                        EditorOption(stringResource(R.string.edit), false, false, enabled = !saveBlocked()) {
                            if (saveBlocked()) return@EditorOption
                            val element = board.elements.first { it.id == id }
                            draft = Draft(id, element.x, element.y, element.text, element.kind, element.color)
                            menuTarget = null
                        }
                    }
                    EditorOption(stringResource(R.string.menu_delete), false, true, enabled = !saveBlocked()) {
                        if (saveBlocked()) return@EditorOption
                        if (board.delete(if (id in selectedIds) selectedIds else setOf(id))) saveSnapshot()
                        selectedIds = emptySet(); selectedId = null; menuTarget = null
                    }
                    EditorOption(stringResource(R.string.menu_close), false, false, enabled = true) { menuTarget = null }
                }
            }

            val editingRegionId = regionNameId?.takeIf(projection::visible)
            if (editingRegionId != null) {
                Row(Modifier.align(Alignment.Center).background(Color.White, RoundedCornerShape(10.dp))
                    .pillBorder(10f).padding(8.dp).onGloballyPositioned {
                        chromeBounds["regionName"] = it.boundsInParent()
                    }, verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(regionName, onValueChange = {
                        if (!latestExternalBlock.value()) {
                            cancelBlankTap()
                            regionDraft = regionDraft?.copy(name = it)
                        }
                    },
                        singleLine = true, modifier = Modifier.width(140.dp).padding(8.dp)
                            .focusRequester(regionNameFocusRequester)
                            .semantics { contentDescription = regionNameLabel },
                        decorationBox = { inner ->
                            Box {
                                if (regionName.isEmpty()) Text(stringResource(R.string.region_name_hint), color = muted)
                                inner()
                            }
                        })
                    EditorOption(stringResource(R.string.done), false, true, enabled = !saveBlocked()) {
                        if (saveBlocked()) return@EditorOption
                        if (board.updateShape(editingRegionId, name = regionName)) saveSnapshot()
                        closeRegionName()
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).imePadding().fillMaxWidth().height(editorToolbarHeight)
                    .onGloballyPositioned { editorToolbarBounds = it.boundsInParent() }
                    .background(toolbar)
                    .drawBehind { drawLine(outline, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val editingEnabled = !saveBlocked()
                EditorOption(stringResource(R.string.title_kind), draft!!.kind == TextKind.TITLE, false, enabled = editingEnabled) {
                    if (!saveBlocked()) draft = draft?.copy(kind = TextKind.TITLE)
                }
                EditorOption(stringResource(R.string.body_kind), draft!!.kind == TextKind.BODY, false, enabled = editingEnabled) {
                    if (!saveBlocked()) draft = draft?.copy(kind = TextKind.BODY)
                }
                EditorOption(stringResource(R.string.vermilion_short), draft!!.color == TextColor.VERMILION, true, enabled = editingEnabled) {
                    if (!saveBlocked()) draft = draft?.copy(
                        color = if (draft?.color == TextColor.VERMILION) TextColor.INK else TextColor.VERMILION,
                    )
                }
                EditorOption(stringResource(R.string.cancel_short), false, false, enabled = editingEnabled) {
                    cancelDraft()
                }
                EditorOption(stringResource(if (saveFailed) R.string.retry else R.string.done), false, true, enabled = !saving) { commitDraft() }
            }
        }
        if (imagePickerOpen) {
            fun choose(source: com.thinkcanvas.image.ImagePickerSource) {
                if (!imagePickerOpen || saveBlocked()) return
                imagePickerOpen = false
                if (!importReady() || canvasSize == IntSize.Zero) return
                val (x, y) = viewport.screenToWorld(canvasSize.width / 2f, canvasSize.height / 2f)
                onAddImage?.invoke(source, WorldPoint(x, y), canvasSize.width / viewport.scale,
                    canvasSize.height / viewport.scale)
            }
            AlertDialog(onDismissRequest = { if (!saveBlocked()) imagePickerOpen = false },
                title = { Text("画像を追加") },
                text = { Text("端末から画像を一枚選びます。") },
                confirmButton = { Column {
                    TextButton(onClick = { choose(com.thinkcanvas.image.ImagePickerSource.PHOTO) },
                        enabled = !saveBlocked()) { Text("写真から") }
                    TextButton(onClick = { choose(com.thinkcanvas.image.ImagePickerSource.FILE) },
                        enabled = !saveBlocked()) { Text("ファイルから") }
                } },
                dismissButton = { TextButton(onClick = { if (!saveBlocked()) imagePickerOpen = false }) {
                    Text("キャンセル") } })
        }
        imageDraft?.let { editing ->
            val sameEditor = { editorSession.imageDescriptionDraft.value?.sessionId == editing.sessionId &&
                discardTarget == null }
            ImageDescriptionDialog(editing, editable = !saveBlocked() && pendingImageAcknowledgement == null && discardTarget == null,
                cancellable = !exitBlocked() && discardTarget == null, completable = !saving && discardTarget == null,
                failed = saveFailed,
                onChange = { if (sameEditor() && !saveBlocked() && pendingImageAcknowledgement == null)
                    imageDraft = imageDraft?.copy(text = it) },
                onComplete = {
                    if (sameEditor() && !latestExternalBlock.value()) {
                        if (pendingImageAcknowledgement != null && saveFailed) onRetrySave()
                        else if (!saveBlocked() && pendingImageAcknowledgement == null) {
                            val currentDraft = editorSession.imageDescriptionDraft.value
                            val current = board.images.firstOrNull { it.id == currentDraft?.id }
                            if (currentDraft != null && current != null && current.altText == currentDraft.original) {
                                if (board.describeImage(currentDraft.id, currentDraft.text))
                                    pendingImageAcknowledgement = onRequestSave(board.snapshot())
                                else closeImageDescription()
                            }
                        }
                    }
                },
                onCancel = { if (sameEditor() && !exitBlocked()) closeImageDescription() },
                onDismiss = {
                    if (sameEditor()) editorSession.imageDescriptionDraft.value?.let { current ->
                        requestEditorExit("image:${current.sessionId}", current.changed, ::closeImageDescription)
                    }
                })
        }
        val currentEditorTarget = draft?.let { "text:${it.sessionId}" }
            ?: regionDraft?.let { "region:${it.sessionId}" }
            ?: imageDraft?.let { "image:${it.sessionId}" }
        if (discardTarget != null && discardTarget == currentEditorTarget) {
            val target = discardTarget
            AlertDialog(
                onDismissRequest = { dismissDiscardConfirmation() },
                title = { Text(stringResource(R.string.discard_edit_title)) },
                confirmButton = {
                    TextButton(enabled = !exitBlocked(), onClick = {
                        if (!exitBlocked()) {
                            when (target) {
                                editorSession.draft.value?.let { "text:${it.sessionId}" } -> cancelDraft()
                                editorSession.regionNameDraft.value?.let { "region:${it.sessionId}" } -> closeRegionName()
                                editorSession.imageDescriptionDraft.value?.let { "image:${it.sessionId}" } -> closeImageDescription()
                            }
                        }
                    }) { Text(stringResource(R.string.discard_edit)) }
                },
                dismissButton = {
                    TextButton(onClick = { dismissDiscardConfirmation() }) {
                        Text(stringResource(R.string.continue_edit))
                    }
                },
            )
        }
        if (currentSaveState is BoardSaveState.Running || saveFailed) {
            val saveFailedLabel = stringResource(R.string.save_failed)
            Box(
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = if (inkTool != null) 70.dp else 8.dp, end = 8.dp)
                    .height(44.dp)
                    .then(if (saveFailed && draft == null) Modifier.clickable { onRetrySave() } else Modifier)
                    .semantics {
                        if (saveFailed && draft == null) contentDescription = saveFailedLabel
                    }
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
        modifier = Modifier.size(width = if (label.length > 2) 64.dp else 48.dp, height = 48.dp)
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

private fun Modifier.selectionFrame(solid: Boolean = false): Modifier = this.then(
    Modifier.background(vermilion.copy(alpha = 0.06f), RoundedCornerShape(3.dp))
        .drawBehind {
            drawRoundRect(
                color = vermilion,
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = if (solid) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }.padding(6.dp),
)

private fun Modifier.offsetPx(x: Float, y: Float): Modifier = this.then(
    Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
)
