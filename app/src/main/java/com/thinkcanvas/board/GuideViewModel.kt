package com.thinkcanvas.board

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thinkcanvas.data.CanvasStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class GuideViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(GuideState())
        private set

    internal var save: suspend () -> Unit = { CanvasStore.get(getApplication()).dismissGuide() }

    fun show() { state = state.show() }
    fun dismiss() { state = state.dismiss() }
    fun acknowledgeFailure() { state = state.acknowledgeFailure() }

    fun start() {
        state = state.start() ?: return
        viewModelScope.launch {
            try {
                save()
                state = state.saved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.failed(error.message ?: "案内の状態を保存できません")
            }
        }
    }
}
