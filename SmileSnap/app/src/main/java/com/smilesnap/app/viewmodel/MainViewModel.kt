package com.smilesnap.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smilesnap.app.analyzer.FrameScore
import com.smilesnap.app.analyzer.VideoProcessor
import com.smilesnap.app.ui.screens.VideoResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val videoProcessor = VideoProcessor(application)

    sealed class UiState {
        data object Home : UiState()
        data class Processing(
            val progress: Float = 0f,
            val framesChecked: Int = 0,
            val currentBestScore: Float = 0f,
            val currentVideoIndex: Int = 0,
            val totalVideos: Int = 1
        ) : UiState()
        data class Results(
            val videoResults: List<VideoResult> = emptyList()
        ) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Home)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var processingJob: Job? = null

    /** Process a single video. */
    fun processVideo(videoUri: Uri) {
        processVideos(listOf(videoUri))
    }

    /** Process multiple videos in sequence (batch mode). */
    fun processVideos(videoUris: List<Uri>) {
        processingJob?.cancel()

        val totalVideos = videoUris.size
        _uiState.value = UiState.Processing(totalVideos = totalVideos)

        processingJob = viewModelScope.launch {
            val allResults = mutableListOf<VideoResult>()

            videoUris.forEachIndexed { index, uri ->
                val label = "Video ${index + 1}"

                _uiState.value = UiState.Processing(
                    progress = 0f,
                    framesChecked = 0,
                    currentBestScore = 0f,
                    currentVideoIndex = index,
                    totalVideos = totalVideos
                )

                var finalCandidates: List<FrameScore> = emptyList()

                videoProcessor.processVideo(uri, topN = 10)
                    .collect { progress ->
                        _uiState.value = UiState.Processing(
                            progress = progress.percentComplete,
                            framesChecked = progress.framesChecked,
                            currentBestScore = progress.currentBestScore,
                            currentVideoIndex = index,
                            totalVideos = totalVideos
                        )
                        finalCandidates = progress.topCandidates
                    }

                allResults.add(VideoResult(videoLabel = label, candidates = finalCandidates))
            }

            _uiState.value = UiState.Results(videoResults = allResults)
        }
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        _uiState.value = UiState.Home
    }

    fun startOver() {
        processingJob?.cancel()
        _uiState.value = UiState.Home
    }

    override fun onCleared() {
        super.onCleared()
        videoProcessor.close()
    }
}
