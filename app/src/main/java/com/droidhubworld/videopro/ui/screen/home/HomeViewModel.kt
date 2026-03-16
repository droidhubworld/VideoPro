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
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.random.Random

enum class TransitionType {
    NONE, FADE_BLACK, CROSS_DISSOLVE, BLUR
}

data class VideoTransition(
    val type: TransitionType = TransitionType.NONE,
    val durationMs: Long = 1000L
)

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val undoStack = mutableListOf<List<VideoClip>>()
    private val redoStack = mutableListOf<List<VideoClip>>()

    private val colors = listOf(
        Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFA726),
        Color(0xFFAB47BC), Color(0xFFEF5350), Color(0xFF26A69A)
    )

    private fun saveState() {
        undoStack.add(_uiState.value.clips)
        if (undoStack.size > 20) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
        _uiState.value = _uiState.value.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(_uiState.value.clips)
            val previousState = undoStack.removeAt(undoStack.lastIndex)
            _uiState.value = _uiState.value.copy(
                clips = previousState,
                totalDurationMs = previousState.sumOf { it.durationMs },
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(_uiState.value.clips)
            val nextState = redoStack.removeAt(redoStack.lastIndex)
            _uiState.value = _uiState.value.copy(
                clips = nextState,
                totalDurationMs = nextState.sumOf { it.durationMs },
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    fun addVideo(uri: Uri, context: Context) {
        addVideos(listOf(uri), context)
    }

    fun addVideos(uris: List<Uri>, context: Context) {
        saveState()
        val newClips = uris.mapIndexed { index, uri ->
            VideoClip(
                id = System.currentTimeMillis().toString() + index,
                name = "Video ${(_uiState.value.clips.size + index + 1)}",
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
            if (it.uri == uri) it.copy(
                originalDurationMs = durationMs,
                trimEndMs = durationMs
            ) else it
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

    fun selectClip(id: String?) {
        _uiState.value = _uiState.value.copy(selectedClipId = id)
    }

    fun trimSelectedClip(startMs: Long, endMs: Long, context: Context) {
        val selectedId = _uiState.value.selectedClipId ?: return
        
        saveState()

        val updatedClips = _uiState.value.clips.map {
            if (it.id == selectedId) {
                it.copy(
                    trimStartMs = startMs.coerceAtLeast(0L),
                    trimEndMs = endMs.coerceAtMost(it.originalDurationMs)
                )
            } else it
        }
        
        _uiState.value = _uiState.value.copy(
            clips = updatedClips,
            totalDurationMs = updatedClips.sumOf { it.durationMs }
        )
    }

    fun splitSelectedClip() {
        val selectedId = _uiState.value.selectedClipId ?: return
        val positionMs = _uiState.value.currentPositionMs
        val currentClips = _uiState.value.clips
        
        var accumulatedMs = 0L
        var targetClipIndex = -1
        for (i in currentClips.indices) {
            val clipDur = currentClips[i].durationMs
            if (positionMs >= accumulatedMs && positionMs < accumulatedMs + clipDur) {
                targetClipIndex = i
                break
            }
            accumulatedMs += clipDur
        }
        
        if (targetClipIndex == -1) return
        
        val clip = currentClips[targetClipIndex]
        val splitPointInClipMs = positionMs - accumulatedMs
        val absoluteSplitPointMs = clip.trimStartMs + splitPointInClipMs
        
        if (splitPointInClipMs < 100L || (clip.durationMs - splitPointInClipMs) < 100L) return

        saveState()
        
        val firstPart = clip.copy(id = System.currentTimeMillis().toString() + "_1", trimEndMs = absoluteSplitPointMs)
        val secondPart = clip.copy(id = (System.currentTimeMillis() + 1).toString() + "_2", trimStartMs = absoluteSplitPointMs)
        
        val newList = currentClips.toMutableList()
        newList.removeAt(targetClipIndex)
        newList.add(targetClipIndex, firstPart)
        newList.add(targetClipIndex + 1, secondPart)
        
        _uiState.value = _uiState.value.copy(
            clips = newList,
            totalDurationMs = newList.sumOf { it.durationMs },
            selectedClipId = secondPart.id
        )
    }

    fun deleteSelectedClip() {
        val selectedId = _uiState.value.selectedClipId ?: return
        saveState()
        val newList = _uiState.value.clips.filter { it.id != selectedId }
        _uiState.value = _uiState.value.copy(
            clips = newList,
            totalDurationMs = newList.sumOf { it.durationMs },
            selectedClipId = null
        )
    }

    fun setTransition(clipId: String, type: TransitionType) {
        saveState()
        val updatedClips = _uiState.value.clips.map {
            if (it.id == clipId) it.copy(transitionAfter = VideoTransition(type)) else it
        }
        _uiState.value = _uiState.value.copy(clips = updatedClips)
    }

    fun updateProgress(globalPositionMs: Long) {
        if (_uiState.value.currentPositionMs != globalPositionMs) {
            _uiState.value = _uiState.value.copy(
                currentPositionMs = globalPositionMs,
                currentTime = formatTime(globalPositionMs)
            )
        }
    }

    fun onPlaybackEnded() {
        val totalDur = _uiState.value.totalDurationMs
        _uiState.value = _uiState.value.copy(
            isPlaying = false,
            currentPositionMs = totalDur,
            currentTime = formatTime(totalDur)
        )
    }

    fun onPlayPauseClicked() {
        if (!_uiState.value.isPlaying && _uiState.value.currentPositionMs >= _uiState.value.totalDurationMs) {
            _uiState.value = _uiState.value.copy(currentPositionMs = 0, currentTime = "00:00.00")
        }
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

    fun moveClip(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        saveState()
        val newList = _uiState.value.clips.toMutableList()
        val clip = newList.removeAt(fromIndex)
        newList.add(toIndex, clip)
        _uiState.value = _uiState.value.copy(clips = newList)
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
    val isPlaying: Boolean = false,
    val selectedClipId: String? = null,
    val msPerDp: Float = 10f, // Default zoom level: 1dp = 10ms
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

data class VideoClip(
    val id: String,
    val name: String,
    val originalDurationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val color: Color,
    val uri: Uri? = null,
    val thumbnails: List<Bitmap> = emptyList(),
    val transitionAfter: VideoTransition? = null
) {
    val durationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)
}
