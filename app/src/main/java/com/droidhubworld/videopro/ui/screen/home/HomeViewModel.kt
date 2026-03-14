package com.droidhubworld.videopro.ui.screen.home

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidhubworld.videopro.utils.FFmpegNative
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val colors = listOf(
        Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFA726),
        Color(0xFFAB47BC), Color(0xFFEF5350), Color(0xFF26A69A)
    )

    fun addVideo(uri: Uri, context: Context) {
        addVideos(listOf(uri), context)
    }

    fun addVideos(uris: List<Uri>, context: Context) {
        val newClips = uris.mapIndexed { index, uri ->
            VideoClip(
                name = "Video ${(_uiState.value.clips.size + index + 1)}",
                durationMs = 0,
                color = colors[Random.nextInt(colors.size)],
                uri = uri
            )
        }
        _uiState.value = _uiState.value.copy(
            clips = _uiState.value.clips + newClips
        )

        // Load data for new clips immediately
        uris.forEach { uri ->
            loadClipData(uri, context)
        }

        if (_uiState.value.selectedVideoUri == null && uris.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(selectedVideoUri = uris.first())
        }
    }

    private fun loadClipData(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLong() ?: 0L

                withContext(Dispatchers.Main) {
                    updateClipDuration(uri, durationMs, context)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }
        }
    }

    fun updateClipDuration(uri: Uri, durationMs: Long, context: Context) {
        val updatedClips = _uiState.value.clips.map {
            if (it.uri == uri) it.copy(durationMs = durationMs) else it
        }
        val totalDuration = updatedClips.sumOf { it.durationMs }
        _uiState.value = _uiState.value.copy(
            clips = updatedClips,
            totalDurationMs = totalDuration
        )

        generateThumbnails(uri, durationMs, context)
    }

    private fun generateThumbnails(uri: Uri, durationMs: Long, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = getRealPathFromURI(context, uri) ?: return@launch
            val thumbnails = mutableListOf<Bitmap>()
            val interval = 1000L // 1 second
            val count = (durationMs / interval).toInt().coerceAtMost(10).coerceAtLeast(1)
            val step = if (count > 1) durationMs / (count - 1) else 0

            for (i in 0 until count) {
                val timeMs = i * step
                val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
                if (FFmpegNative.extractFrame(path, timeMs, bitmap)) {
                    thumbnails.add(bitmap)
                }
            }

            withContext(Dispatchers.Main) {
                val updatedClips = _uiState.value.clips.map {
                    if (it.uri == uri) it.copy(thumbnails = thumbnails) else it
                }
                _uiState.value = _uiState.value.copy(clips = updatedClips)
            }
        }
    }

    private fun getRealPathFromURI(context: Context, contentUri: Uri): String? {
        if (contentUri.scheme == "file") return contentUri.path
        val proj = arrayOf(MediaStore.Video.Media.DATA)
        val cursor = context.contentResolver.query(contentUri, proj, null, null, null)
        val columnIndex = cursor?.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
        cursor?.moveToFirst()
        val path = columnIndex?.let { cursor.getString(it) }
        cursor?.close()
        return path
    }

    fun getClipStartMs(uri: Uri?): Long {
        if (uri == null) return 0L
        var startMs = 0L
        for (clip in _uiState.value.clips) {
            if (clip.uri == uri) return startMs
            startMs += clip.durationMs
        }
        return 0L
    }

    fun updateProgress(globalPositionMs: Long) {
        if (_uiState.value.currentPositionMs != globalPositionMs) {
            val newState = _uiState.value.copy(
                currentPositionMs = globalPositionMs,
                currentTime = formatTime(globalPositionMs)
            )

            // Auto-switch selected video if position moves to another clip
            var accumulatedMs = 0L
            var currentUri: Uri? = null
            for (clip in newState.clips) {
                val clipDuration = if (clip.durationMs > 0) clip.durationMs else 1000L // Fallback for loading state
                if (globalPositionMs >= accumulatedMs && globalPositionMs < accumulatedMs + clipDuration) {
                    currentUri = clip.uri
                    break
                }
                accumulatedMs += clipDuration
            }

            if (currentUri != null && currentUri != newState.selectedVideoUri) {
                _uiState.value = newState.copy(selectedVideoUri = currentUri)
            } else {
                _uiState.value = newState
            }
        }
    }

    fun onPlayPauseClicked() {
        _uiState.value = _uiState.value.copy(isPlaying = !_uiState.value.isPlaying)
    }

    fun updateZoom(zoomFactor: Float) {
        val newMsPerDp = (_uiState.value.msPerDp / zoomFactor).coerceIn(0.5f, 200f)
        _uiState.value = _uiState.value.copy(msPerDp = newMsPerDp)
    }

    fun zoomIn() {
        updateZoom(1.5f)
    }

    fun zoomOut() {
        updateZoom(0.66f)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val milliseconds = (ms % 1000) / 10
        return String.format(Locale.getDefault(), "%02d:%02d.%02d", minutes, seconds, milliseconds)
    }
}

data class HomeUiState(
    val clips: List<VideoClip> = emptyList(),
    val isLoading: Boolean = false,
    val currentTime: String = "00:00.00",
    val currentPositionMs: Long = 0,
    val totalDurationMs: Long = 0,
    val selectedVideoUri: Uri? = null,
    val isPlaying: Boolean = false,
    val msPerDp: Float = 10f // Default zoom level: 1dp = 10ms
)

data class VideoClip(
    val name: String,
    val durationMs: Long,
    val color: Color,
    val uri: Uri? = null,
    val thumbnails: List<Bitmap> = emptyList()
)
