package com.smriti.app.ui

import android.app.Application
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.smriti.app.ModelImporter
import com.smriti.app.ModelProvisioner
import com.smriti.app.ai.Enricher
import com.smriti.app.ai.LiteRtLmHolder
import com.smriti.app.capture.AsrFactory
import com.smriti.app.capture.CameraController
import com.smriti.app.capture.CapturePipeline
import com.smriti.app.capture.CaptureStage
import com.smriti.app.capture.PushToTalk
import com.smriti.app.data.RecordDao
import com.smriti.app.data.SmritiDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureViewModel(app: Application) : AndroidViewModel(app) {
    private val dao: RecordDao = SmritiDb.get(app).recordDao()
    private val camera: CameraController = CameraController(app)
    private val asr = AsrFactory.create(app)
    private val pipeline: CapturePipeline = CapturePipeline(app, dao, camera, asr)

    private val _stage = MutableStateFlow<CaptureStage?>(null)
    val stage: StateFlow<CaptureStage?> = _stage.asStateFlow()

    private val _lastRecordId = MutableStateFlow<Long?>(null)
    val lastRecordId: StateFlow<Long?> = _lastRecordId.asStateFlow()

    private var captureJob: Job? = null

    /** What the badge under the pill says: which model is loaded, or how to get one. */
    private val _modelStatus = MutableStateFlow("checking model")
    val modelStatus: StateFlow<String> = _modelStatus.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    init {
        refreshModelStatus()
    }

    fun refreshModelStatus() {
        viewModelScope.launch {
            val model = withContext(Dispatchers.IO) { ModelProvisioner.locate(getApplication()) }
            _modelStatus.value = model?.let { "offline · ${it.file.name.removeSuffix(".litertlm")}" }
                ?: "no model · tap to import"
        }
    }

    /** Copies a picked `.litertlm` into app storage, then reloads the engine on it. */
    fun importModel(uri: Uri) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            _modelStatus.value = "importing model"
            val result = ModelImporter.import(getApplication(), uri) { copied, total ->
                val mb = copied / (1024 * 1024)
                _modelStatus.value = if (total != null && total > 0) {
                    "importing ${copied * 100 / total}% · $mb mb"
                } else {
                    "importing $mb mb"
                }
            }
            _importing.value = false
            result.onSuccess {
                // Drop any engine loaded on the previous file, then let records captured while
                // no model was present get their structure filled in now.
                withContext(Dispatchers.IO) { LiteRtLmHolder.reset() }
                Enricher.request(getApplication())
                refreshModelStatus()
            }.onFailure { t ->
                _modelStatus.value = "import failed · ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    fun bindCamera(owner: LifecycleOwner, view: PreviewView) {
        camera.bind(owner, view)
    }

    fun capture(withVoice: Boolean) {
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            pipeline.run(withVoice).collect { currentStage ->
                _stage.value = currentStage
                if (currentStage is CaptureStage.Done) {
                    _lastRecordId.value = currentStage.recordId
                }
            }
        }
    }

    fun captureVoiceOnly() {
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            pipeline.runVoiceOnly().collect { currentStage ->
                _stage.value = currentStage
                if (currentStage is CaptureStage.Done) {
                    _lastRecordId.value = currentStage.recordId
                }
            }
        }
    }

    fun stopVoice() {
        (asr as? PushToTalk)?.stopListening()
    }

    /**
     * Clears a terminal stage after the UI has acted on it.
     *
     * `stage` is state, but "a record was saved" is an EVENT. Leaving `Done` parked in the
     * StateFlow meant that every time CaptureScreen recomposed — including when the user
     * pressed back to return to it — `LaunchedEffect(stage)` saw `Done` again and navigated
     * straight back to the detail screen. From the outside that looks exactly like a broken
     * back button: the press registers, and is instantly undone.
     *
     * Reported from device testing, 2026-09-01.
     */
    fun consumeTerminalStage() {
        val current = _stage.value
        if (current is CaptureStage.Done || current is CaptureStage.Failed) {
            _stage.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        camera.unbind()
    }
}
