package com.smilesnap.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smilesnap.app.ui.screens.HomeScreen
import com.smilesnap.app.ui.screens.ProcessingScreen
import com.smilesnap.app.ui.screens.ResultsScreen
import com.smilesnap.app.ui.theme.SmileSnapTheme
import com.smilesnap.app.viewmodel.MainViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private var pendingVideoUri: Uri? = null
    private var viewModelRef: MainViewModel? = null

    // Launcher for recording video via the system camera app
    private val recordVideoLauncher = registerForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success) {
            pendingVideoUri?.let { uri ->
                viewModelRef?.processVideo(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SmileSnapTheme {
                val viewModel: MainViewModel = viewModel()
                viewModelRef = viewModel
                val uiState by viewModel.uiState.collectAsState()

                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val state = uiState) {
                        is MainViewModel.UiState.Home -> {
                            HomeScreen(
                                onVideoSelected = { uri ->
                                    viewModel.processVideo(uri)
                                },
                                onMultipleVideosSelected = { uris ->
                                    viewModel.processVideos(uris)
                                },
                                onRecordVideo = {
                                    launchVideoRecorder()
                                }
                            )
                        }

                        is MainViewModel.UiState.Processing -> {
                            ProcessingScreen(
                                progress = state.progress,
                                framesChecked = state.framesChecked,
                                currentBestScore = state.currentBestScore,
                                currentVideoIndex = state.currentVideoIndex,
                                totalVideos = state.totalVideos,
                                onCancel = { viewModel.cancelProcessing() }
                            )
                        }

                        is MainViewModel.UiState.Results -> {
                            ResultsScreen(
                                videoResults = state.videoResults,
                                onStartOver = { viewModel.startOver() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun launchVideoRecorder() {
        val videoFile = File(cacheDir, "smilesnap_recording_${System.currentTimeMillis()}.mp4")
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            videoFile
        )
        pendingVideoUri = uri
        recordVideoLauncher.launch(uri)
    }
}
