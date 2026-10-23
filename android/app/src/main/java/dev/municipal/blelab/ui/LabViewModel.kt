package dev.municipal.blelab.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.municipal.blelab.session.EmitterConfig
import dev.municipal.blelab.session.LabController
import dev.municipal.blelab.session.LabUiState
import dev.municipal.blelab.session.ReceiverConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class LabViewModel : ViewModel() {
    private val stateFlow = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = stateFlow.asStateFlow()

    private var controller: LabController? = null
    private var pending: String? = null
    private var collectJob: Job? = null

    fun attach(controller: LabController) {
        this.controller = controller
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            controller.ui.collect { stateFlow.value = it }
        }
    }

    fun editEmitter(transform: (EmitterConfig) -> EmitterConfig) {
        controller?.updateEmitter(transform)
    }

    fun editReceiver(transform: (ReceiverConfig) -> ReceiverConfig) {
        controller?.updateReceiver(transform)
    }

    fun startEmitter(ask: (List<String>) -> Unit) {
        val current = controller ?: return
        val missing = current.missingPermissions(emitter = true)
        if (missing.isNotEmpty()) {
            pending = "emitter"
            ask(missing)
        } else {
            current.startEmitter()
        }
    }

    fun startReceiver(ask: (List<String>) -> Unit) {
        val current = controller ?: return
        val missing = current.missingPermissions(emitter = false)
        if (missing.isNotEmpty()) {
            pending = "receiver"
            ask(missing)
        } else {
            current.startReceiver()
        }
    }

    fun startCapture(ask: (List<String>) -> Unit) {
        val current = controller ?: return
        val missing = current.missingPermissions(emitter = false)
        if (missing.isNotEmpty()) {
            pending = "capture"
            ask(missing)
        } else {
            current.startCapture()
        }
    }

    fun onPermissionResult(result: Map<String, Boolean>) {
        val denied = result.filterValues { granted -> !granted }.keys.toList()
        val current = controller
        if (denied.isNotEmpty()) {
            val role = if (pending == "emitter") "emitter" else "receiver"
            current?.logPermissionMissing(role, denied)
            pending = null
            return
        }
        when (pending) {
            "emitter" -> current?.startEmitter()
            "receiver" -> current?.startReceiver()
            "capture" -> current?.startCapture()
        }
        pending = null
    }

    fun stopEmitter() = controller?.stopEmitter()
    fun stopReceiver() = controller?.stopReceiver()
    fun stopCapture() = controller?.stopCapture()

    fun exportFiles(): List<File> = controller?.exportFiles().orEmpty()

    fun sessionRunning(): Boolean = state.value.emitterDesired || state.value.receiverDesired
}
