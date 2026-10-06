package com.thinkcanvas.canvas

import android.view.ViewConfiguration
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// 標準DefaultFlingBehaviorと同じく、touch physicsはViews相当の1xで進める。
private object PanFlingDurationScale : MotionDurationScale { override val scaleFactor = 1f }

/** 通常の表示animationとpan continuationが共有する、唯一のcamera停止・履歴境界。 */
internal class ViewportAnimationBoundary(private val navigation: ViewportHistory) {
    var active = true
    var job: Job? = null
    var origin: ViewportFocus? = null
    var group: Any? = null
    var generation = 0
    val animatingState = mutableStateOf(false)
    var fling by mutableStateOf(false)

    fun stop(record: Boolean = true) {
        val historyOrigin = origin
        val historyGroup = group
        origin = null
        group = null
        generation++
        fling = false
        animatingState.value = false
        val previousJob = job
        job = null
        previousJob?.cancel()
        if (record) navigation.record(historyOrigin, historyGroup)
    }

    fun stopFling() { if (fling) stop() }
}

internal data class PanFlingContext(
    val board: BoardState,
    val editor: TextEditorSession,
    val content: BoardSnapshot,
    val selection: Set<String>,
    val surface: IntSize,
    val density: Float,
    val allowed: Boolean,
)

internal class PanFlingController(
    private val boundary: ViewportAnimationBoundary,
    private val navigation: ViewportHistory,
    private val configuration: ViewConfiguration,
    private val density: Float,
    private val decay: DecayAnimationSpec<Offset>,
    private val scope: CoroutineScope,
    private val stopMotion: () -> Unit,
    private val currentContext: () -> PanFlingContext,
) {
    private var source: PanFlingContext? = null
    private var ownerGeneration = -1
    val ownsMotion get() = boundary.fling && boundary.generation == ownerGeneration

    fun isLive(): Boolean {
        val original = source ?: return false
        if (!ownsMotion || !boundary.active) return false
        val current = currentContext()
        return current.allowed && current.board === original.board && current.editor === original.editor &&
            current.content == original.content && current.selection == original.selection &&
            current.surface == original.surface && current.density == original.density
    }

    fun stop() { if (ownsMotion) stopMotion() }

    fun start(velocity: Offset, origin: ViewportFocus?): Boolean {
        val speed = velocity.getDistance()
        val minimum = maxOf(configuration.scaledMinimumFlingVelocity.toFloat(), 400f * density)
        val context = currentContext()
        if (origin == null || !speed.isFinite() || speed < minimum || !context.allowed) return false
        val maximum = minOf(configuration.scaledMaximumFlingVelocity.toFloat(), 1800f * density)
        val boundedVelocity = velocity * minOf(1f, maximum / speed)
        stopMotion()
        val releasedViewport = navigation.viewportState.value
        source = context
        val generation = boundary.generation
        ownerGeneration = generation
        boundary.origin = origin
        boundary.fling = true
        boundary.animatingState.value = true
        boundary.job = scope.launch(context = PanFlingDurationScale) {
            try {
                AnimationState(typeConverter = Offset.VectorConverter, initialValue = Offset.Zero,
                    initialVelocity = boundedVelocity).animateDecay(decay) {
                    if (generation != boundary.generation || !isLive()) {
                        if (generation == boundary.generation) stop()
                        cancelAnimation()
                    } else navigation.viewportState.value = releasedViewport.pan(value.x, value.y)
                }
            } finally {
                if (ownerGeneration == generation) source = null
                if (generation == boundary.generation && boundary.fling) {
                    val historyOrigin = boundary.origin
                    boundary.origin = null
                    boundary.fling = false
                    boundary.animatingState.value = false
                    navigation.record(historyOrigin)
                }
            }
        }
        return true
    }
}

@Composable
internal fun rememberPanFling(
    boundary: ViewportAnimationBoundary,
    navigation: ViewportHistory,
    scope: CoroutineScope,
    stopMotion: () -> Unit,
    context: () -> PanFlingContext,
): PanFlingController {
    val androidContext = LocalContext.current
    val density = LocalDensity.current
    val configuration = remember(androidContext) { ViewConfiguration.get(androidContext) }
    val decay = remember(density) { splineBasedDecay<Offset>(density) }
    val latestContext = rememberUpdatedState(context)
    val controller = remember(boundary, navigation, scope, configuration, density, decay) {
        PanFlingController(boundary, navigation, configuration, density.density, decay, scope,
            stopMotion, { latestContext.value() })
    }
    LaunchedEffect(controller) {
        snapshotFlow { controller.ownsMotion && !controller.isLive() }.collect { invalid ->
            if (invalid) controller.stop()
        }
    }
    DisposableEffect(controller) { onDispose { controller.stop() } }
    return controller
}
