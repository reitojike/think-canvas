package com.thinkcanvas

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.LifecycleOwner
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry

/** Issue #74 の単回観測用。待機・UI thread dispatch・clock 操作は行わない。 */
internal class GmdDiagnostic(private val testcase: String) {
    private val start = SystemClock.elapsedRealtime()
    @Volatile private var activity: Activity? = null
    private val callback = ActivityLifecycleCallback { instance, stage ->
        if (instance is MainActivity) {
            activity = instance
            event("activity_lifecycle", "activity=${identity(instance)} stage=$stage")
        }
    }

    init {
        event("test_start")
        safely("lifecycle_registration") {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        }
    }

    fun event(name: String, state: String = "") {
        // logging の失敗を元の testcase の結果へ伝播させない。
        try {
            Log.i("TC_GMD_DIAG", "testcase=$testcase elapsed_ms=${SystemClock.elapsedRealtime() - start}" +
                " monotonic_ms=${SystemClock.elapsedRealtime()} event=$name $state")
        } catch (_: Throwable) { }
    }

    fun safely(name: String, block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            event("diagnostic_unavailable", "operation=$name error=${error.javaClass.simpleName}")
        }
    }

    fun activityState(): String {
        val current = activity ?: return "activity=null"
        val decor = current.window.decorView
        val ime = if (Build.VERSION.SDK_INT >= 30)
            decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) else null
        return "activity=${identity(current)} lifecycle=${(current as? LifecycleOwner)?.lifecycle?.currentState}" +
            " destroyed=${current.isDestroyed} window_focus=${decor.hasWindowFocus()} ime_visible=$ime"
    }

    fun rootState(root: AccessibilityNodeInfo?): String =
        "root_present=${root != null} root_identity=${identity(root)}" +
            " root_package=${root?.packageName} root_window=${root?.windowId}"

    // WindowInspector を使い Activity と dialog の両方の Compose roots を列挙する。
    // UI thread と同期しない best-effort snapshot。更新中の不整合は unavailable として記録する。
    private fun composeNodes(merged: Boolean): List<SemanticsNode> {
        val views = if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews()
            else listOfNotNull(activity?.window?.decorView)
        val roots = mutableListOf<ViewRootForTest>()
        fun visit(view: View) {
            if (view is ViewRootForTest) roots.add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        views.forEach(::visit)
        return roots.flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged) }
    }

    private fun nodeState(node: SemanticsNode): String {
        val config = node.config
        return "id=${node.id} desc=${config.getOrNull(SemanticsProperties.ContentDescription)}" +
            " text=${config.getOrNull(SemanticsProperties.Text)}" +
            " editable=${config.getOrNull(SemanticsProperties.EditableText)}" +
            " state=${config.getOrNull(SemanticsProperties.StateDescription)}" +
            " enabled=${!config.contains(SemanticsProperties.Disabled)}" +
            " click=${config.contains(SemanticsActions.OnClick)}" +
            " set_text=${config.contains(SemanticsActions.SetText)}" +
            " bounds=${node.boundsInWindow} placed=${node.layoutInfo.isPlaced} attached=${node.layoutInfo.isAttached}" +
            " bounds_nonempty=${!node.boundsInWindow.isEmpty} merges=${config.isMergingSemanticsOfDescendants}"
    }

    fun renameUiState(event: String) = safely(event) {
        val nodes = composeNodes(merged = true)
        val fields = nodes.filter { it.config.contains(SemanticsActions.SetText) }
        val saves = nodes.filter { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "保存" } == true
        }
        this.event(event, "${activityState()} field_count=${fields.size} save_count=${saves.size}" +
            " fields=${fields.joinToString { nodeState(it) }} saves=${saves.joinToString { nodeState(it) }}" +
            " observation=raw_unsynchronized")
    }

    fun dumpCompose() {
        for (merged in listOf(true, false)) safely("compose_dump_$merged") {
            val nodes = composeNodes(merged)
            event("compose_dump_start", "merged=$merged count=${nodes.size} observation=raw_unsynchronized")
            nodes.take(160).forEach { event("compose_node", "merged=$merged ${nodeState(it)}") }
            event("compose_dump_end", "merged=$merged truncated=${nodes.size > 160}")
        }
    }

    fun dumpPlatform(root: AccessibilityNodeInfo?, prefix: String) = safely("platform_dump") {
        event("platform_dump_start", rootState(root))
        var visited = 0
        var matches = 0
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || visited >= 160) return
            visited++
            if (node.contentDescription?.toString()?.startsWith(prefix) == true) matches++
            val bounds = Rect().also(node::getBoundsInScreen)
            event("platform_node", "desc=${node.contentDescription} text=${node.text}" +
                " state=${node.stateDescription} visible=${node.isVisibleToUser} enabled=${node.isEnabled}" +
                " clickable=${node.isClickable} editable=${node.isEditable} bounds=$bounds window=${node.windowId}")
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(root)
        event("platform_dump_end", "prefix=$prefix matches=$matches visited=$visited limit=160")
    }

    fun end() {
        safely("lifecycle_removal") {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
        }
        event("test_end")
    }

    companion object {
        fun identity(value: Any?): String = value?.let { System.identityHashCode(it).toString(16) } ?: "null"
    }
}
