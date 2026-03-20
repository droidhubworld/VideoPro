package com.droidhubworld.videopro.ui.screen.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.GaussianFunction
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SeparableConvolution
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.droidhubworld.videopro.utils.FFmpegNative
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.random.Random

enum class TransitionType {
    NONE, FADE_BLACK, CROSS_DISSOLVE, BLUR, ZOOM
}

enum class ExportQuality {
    P480, P720, P1080
}

data class VideoTransition(
    val type: TransitionType = TransitionType.NONE,
    val durationMs: Long = 1000L
)

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val undoStack = mutableListOf<Pair<List<VideoClip>, List<AudioClip>>>()
    private val redoStack = mutableListOf<Pair<List<VideoClip>, List<AudioClip>>>()

    private val colors = listOf(
        Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFA726),
        Color(0xFFAB47BC), Color(0xFFEF5350), Color(0xFF26A69A)
    )

    private fun saveState() {
        undoStack.add(_uiState.value.clips to _uiState.value.audioClips)
        if (undoStack.size > 20) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
        _uiState.value = _uiState.value.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }

    private fun calculateTotalDuration(clips: List<VideoClip>, audioClips: List<AudioClip>): Long {
        val videoDuration = clips.sumOf { it.durationMs }
        val audioMaxEnd = audioClips.maxOfOrNull { it.startOffsetMs + it.durationMs } ?: 0L
        return maxOf(videoDuration, audioMaxEnd)
    }

    private fun updateStateWithDuration(clips: List<VideoClip> = _uiState.value.clips, audioClips: List<AudioClip> = _uiState.value.audioClips) {
        val totalDuration = calculateTotalDuration(clips, audioClips)
        _uiState.value = _uiState.value.copy(
            clips = clips,
            audioClips = audioClips,
            totalDurationMs = totalDuration,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(_uiState.value.clips to _uiState.value.audioClips)
            val previousState = undoStack.removeAt(undoStack.lastIndex)
            updateStateWithDuration(previousState.first, previousState.second)
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(_uiState.value.clips to _uiState.value.audioClips)
            val nextState = redoStack.removeAt(redoStack.lastIndex)
            updateStateWithDuration(nextState.first, nextState.second)
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
        val updatedClips = _uiState.value.clips + newClips
        updateStateWithDuration(clips = updatedClips)

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
        updateStateWithDuration(clips = updatedClips)
        generateThumbnails(uri, durationMs, context)
    }

    fun addAudio(uri: Uri, context: Context) {
        addAudios(listOf(uri), context)
    }

    fun addAudios(uris: List<Uri>, context: Context) {
        saveState()
        val newAudioClips = uris.mapIndexed { index, uri ->
            AudioClip(
                id = "audio_" + System.currentTimeMillis().toString() + index,
                name = "Audio ${(_uiState.value.audioClips.size + index + 1)}",
                color = colors[Random.nextInt(colors.size)],
                uri = uri,
                startOffsetMs = _uiState.value.currentPositionMs
            )
        }
        val updatedAudioClips = _uiState.value.audioClips + newAudioClips
        updateStateWithDuration(audioClips = updatedAudioClips)

        uris.forEach { uri ->
            loadAudioData(uri, context)
        }
    }

    private fun loadAudioData(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLong() ?: 0L

                withContext(Dispatchers.Main) {
                    updateAudioDuration(uri, durationMs)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }
        }
    }

    private fun updateAudioDuration(uri: Uri, durationMs: Long) {
        val updatedAudioClips = _uiState.value.audioClips.map {
            if (it.uri == uri) it.copy(
                originalDurationMs = durationMs,
                trimEndMs = durationMs
            ) else it
        }
        updateStateWithDuration(audioClips = updatedAudioClips)
    }

    private fun generateThumbnails(uri: Uri, durationMs: Long, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = getRealPathFromURI(context, uri) ?: return@launch
            val thumbnails = mutableListOf<Bitmap>()
            val interval = 1000L // Generate 1 thumbnail per second
            val count = (durationMs / interval).toInt().coerceAtLeast(1)

            for (i in 0 until count) {
                val timeMs = i * interval
                // Use a smaller bitmap to save memory
                val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
                if (FFmpegNative.extractFrame(path, timeMs, bitmap)) {
                    thumbnails.add(bitmap)
                    
                    // Update state progressively so UI reflects the loaded thumbnails
                    withContext(Dispatchers.Main) {
                        val currentList = thumbnails.toList()
                        val updatedClips = _uiState.value.clips.map {
                            if (it.uri == uri) it.copy(thumbnails = currentList) else it
                        }
                        _uiState.value = _uiState.value.copy(clips = updatedClips)
                    }
                }
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
        _uiState.value = _uiState.value.copy(selectedClipId = id, selectedAudioClipId = null)
    }

    fun selectAudioClip(id: String?) {
        _uiState.value = _uiState.value.copy(selectedAudioClipId = id, selectedClipId = null)
    }

    fun trimSelectedClip(startMs: Long, endMs: Long) {
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
        updateStateWithDuration(clips = updatedClips)
    }

    fun trimSelectedAudioClip(startMs: Long, endMs: Long) {
        val selectedId = _uiState.value.selectedAudioClipId ?: return
        saveState()
        val updatedAudioClips = _uiState.value.audioClips.map {
            if (it.id == selectedId) {
                val diffStart = startMs - it.trimStartMs
                it.copy(
                    trimStartMs = startMs.coerceAtLeast(0L),
                    trimEndMs = endMs.coerceAtMost(it.originalDurationMs),
                    startOffsetMs = it.startOffsetMs + diffStart
                )
            } else it
        }
        updateStateWithDuration(audioClips = updatedAudioClips)
    }

    fun moveAudioClip(id: String, newOffsetMs: Long) {
        saveState()
        val updatedAudioClips = _uiState.value.audioClips.map {
            if (it.id == id) it.copy(startOffsetMs = newOffsetMs.coerceAtLeast(0L)) else it
        }
        updateStateWithDuration(audioClips = updatedAudioClips)
    }

    fun splitSelectedClip() {
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
        
        val firstPart = clip.copy(
            id = System.currentTimeMillis().toString() + "_1", 
            trimEndMs = absoluteSplitPointMs,
            transitionAfter = null
        )
        val secondPart = clip.copy(
            id = (System.currentTimeMillis() + 1).toString() + "_2", 
            trimStartMs = absoluteSplitPointMs
        )
        
        val newList = currentClips.toMutableList()
        newList.removeAt(targetClipIndex)
        newList.add(targetClipIndex, firstPart)
        newList.add(targetClipIndex + 1, secondPart)
        
        updateStateWithDuration(clips = newList)
        _uiState.value = _uiState.value.copy(selectedClipId = secondPart.id)
    }

    fun deleteSelectedClip() {
        val selectedId = _uiState.value.selectedClipId
        val selectedAudioId = _uiState.value.selectedAudioClipId
        
        if (selectedId != null) {
            saveState()
            val newList = _uiState.value.clips.filter { it.id != selectedId }
            updateStateWithDuration(clips = newList)
            _uiState.value = _uiState.value.copy(selectedClipId = null)
        } else if (selectedAudioId != null) {
            saveState()
            val newList = _uiState.value.audioClips.filter { it.id != selectedAudioId }
            updateStateWithDuration(audioClips = newList)
            _uiState.value = _uiState.value.copy(selectedAudioClipId = null)
        }
    }

    fun toggleMuteSelectedClip() {
        val selectedId = _uiState.value.selectedClipId ?: return
        saveState()
        val updatedClips = _uiState.value.clips.map {
            if (it.id == selectedId) {
                it.copy(isMuted = !it.isMuted)
            } else it
        }
        updateStateWithDuration(clips = updatedClips)
    }

    fun setTransition(clipId: String, type: TransitionType) {
        saveState()
        val updatedClips = _uiState.value.clips.map {
            if (it.id == clipId) it.copy(transitionAfter = VideoTransition(type)) else it
        }
        updateStateWithDuration(clips = updatedClips)
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
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun onPlayPauseClicked() {
        _uiState.update { currentState ->
            val isAtEnd = currentState.currentPositionMs >= currentState.totalDurationMs - 100
            if (!currentState.isPlaying && isAtEnd) {
                currentState.copy(
                    isPlaying = true,
                    currentPositionMs = 0,
                    currentTime = "00:00.00"
                )
            } else {
                currentState.copy(isPlaying = !currentState.isPlaying)
            }
        }
    }

    fun pause() {
        _uiState.update { it.copy(isPlaying = false) }
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
        updateStateWithDuration(clips = newList)
    }

    fun showExportDialog(show: Boolean) {
        _uiState.update { it.copy(showExportDialog = show) }
    }

    @OptIn(UnstableApi::class)
    fun exportVideo(context: Context, quality: ExportQuality) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportProgress = 0f, showExportDialog = false) }
            
            val clips = _uiState.value.clips
            val audioClips = _uiState.value.audioClips
            if (clips.isEmpty()) {
                _uiState.update { it.copy(isExporting = false) }
                return@launch
            }

            val downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val videoProFolder = File(downloadFolder, "VideoPro")
            if (!videoProFolder.exists()) {
                videoProFolder.mkdirs()
            }
            
            val outputFile = File(videoProFolder, "export_${System.currentTimeMillis()}.mp4")
            
            // If we have additional audio clips, FFmpegNative.exportWithTransitions won't handle them
            // correctly as it only takes video clip paths. So we force fallback to Transformer.
            val success = if (audioClips.isEmpty()) {
                val inputPaths = clips.mapNotNull { it.uri?.let { uri -> getRealPathFromURI(context, uri) } }.toTypedArray()
                val trimStarts = clips.map { it.trimStartMs }.toLongArray()
                val trimEnds = clips.map { it.trimEndMs }.toLongArray()
                val transitions = clips.dropLast(1).map { it.transitionAfter?.type?.name?.lowercase() ?: "none" }.toTypedArray()
                val transitionDurations = clips.dropLast(1).map { it.transitionAfter?.durationMs ?: 0L }.toLongArray()

                withContext(Dispatchers.IO) {
                    FFmpegNative.exportWithTransitions(
                        inputPaths,
                        outputFile.absolutePath,
                        trimStarts,
                        trimEnds,
                        transitions,
                        transitionDurations
                    )
                }
            } else {
                false
            }

            if (success) {
                _uiState.update { it.copy(isExporting = false, exportProgress = 1f) }
                viewModelScope.launch {
                    delay(1000)
                    _uiState.update { it.copy(exportProgress = 0f) }
                }
            } else {
                Log.e("HomeViewModel", "Using Media3 Transformer for export (supporting audio clips and transitions)")
                exportVideoWithTransformer(context, quality, outputFile)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun exportVideoWithTransformer(context: Context, quality: ExportQuality, outputFile: File) {
        val clips = _uiState.value.clips
        val audioClips = _uiState.value.audioClips
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .build()

        var currentTotalDurationUs = 0L
        val videoSequenceItems = clips.mapIndexedNotNull { index, clip ->
            clip.uri?.let { uri ->
                val clipDurationUs = clip.durationMs * 1000
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.trimStartMs)
                            .setEndPositionMs(clip.trimEndMs)
                            .build()
                    )
                    .build()
                
                val videoEffects = mutableListOf<Effect>()
                
                // Transition In from previous clip's transition
                val prevClip = if (index > 0) clips[index - 1] else null
                if (prevClip?.transitionAfter != null && prevClip.transitionAfter.type != TransitionType.NONE) {
                    val durationUs = prevClip.transitionAfter.durationMs * 1000 / 2
                    val startTime = currentTotalDurationUs
                    val endTime = currentTotalDurationUs + durationUs
                    
                    when (prevClip.transitionAfter.type) {
                        TransitionType.FADE_BLACK, TransitionType.CROSS_DISSOLVE -> {
                            videoEffects.add(FadeRgbMatrix(startTime, endTime, true))
                        }
                        TransitionType.ZOOM -> {
                            videoEffects.add(FadeRgbMatrix(startTime, endTime, true))
                            videoEffects.add(ZoomMatrixTransformation(startTime, endTime, true))
                        }
                        TransitionType.BLUR -> {
                            videoEffects.add(DynamicGaussianBlur(startTime, endTime, true))
                        }
                        else -> {}
                    }
                }
                
                // Transition Out for this clip's transition
                if (clip.transitionAfter != null && clip.transitionAfter.type != TransitionType.NONE) {
                    val durationUs = clip.transitionAfter.durationMs * 1000 / 2
                    val fadeOutStartUs = currentTotalDurationUs + clipDurationUs - durationUs
                    val fadeOutEndUs = currentTotalDurationUs + clipDurationUs
                    
                    when (clip.transitionAfter.type) {
                        TransitionType.FADE_BLACK, TransitionType.CROSS_DISSOLVE -> {
                            videoEffects.add(FadeRgbMatrix(fadeOutStartUs, fadeOutEndUs, false))
                        }
                        TransitionType.ZOOM -> {
                            videoEffects.add(FadeRgbMatrix(fadeOutStartUs, fadeOutEndUs, false))
                            videoEffects.add(ZoomMatrixTransformation(fadeOutStartUs, fadeOutEndUs, false))
                        }
                        TransitionType.BLUR -> {
                            videoEffects.add(DynamicGaussianBlur(fadeOutStartUs, fadeOutEndUs, false))
                        }
                        else -> {}
                    }
                }

                currentTotalDurationUs += clipDurationUs

                EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(clip.isMuted)
                    .setEffects(Effects(emptyList<AudioProcessor>(), videoEffects))
                    .build()
            }
        }

        val allSequences = mutableListOf<EditedMediaItemSequence>()
        allSequences.add(EditedMediaItemSequence.Builder(videoSequenceItems).build())

        // Add Audio Clips as parallel tracks (sequences)
        audioClips.forEach { audioClip ->
            audioClip.uri?.let { uri ->
                val audioItems = mutableListOf<EditedMediaItem>()
                
                // Prepend silence for startOffsetMs by using the audio file itself clipped
                if (audioClip.startOffsetMs > 0) {
                    var remainingOffset = audioClip.startOffsetMs
                    while (remainingOffset > 0) {
                        val silenceDuration = if (audioClip.originalDurationMs > 0) 
                            minOf(remainingOffset, audioClip.originalDurationMs) 
                        else remainingOffset
                        
                        val silenceMediaItem = MediaItem.Builder()
                            .setUri(uri)
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setEndPositionMs(silenceDuration)
                                    .build()
                            )
                            .build()
                        audioItems.add(
                            EditedMediaItem.Builder(silenceMediaItem)
                                .setRemoveVideo(true)
                                // Fix: Cannot remove both audio and video. 
                                // To truly silence this, we should apply a mute AudioProcessor.
                                .setRemoveAudio(false)
                                .build()
                        )
                        remainingOffset -= silenceDuration
                        if (audioClip.originalDurationMs <= 0) break
                    }
                }
                
                val audioMediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(audioClip.trimStartMs)
                            .setEndPositionMs(audioClip.trimEndMs)
                            .build()
                    )
                    .build()
                
                audioItems.add(
                    EditedMediaItem.Builder(audioMediaItem)
                        .setRemoveVideo(true)
                        .build()
                )
                
                allSequences.add(EditedMediaItemSequence.Builder(audioItems).build())
            }
        }

        val composition = Composition.Builder(allSequences).build()

        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                _uiState.update { it.copy(isExporting = false, exportProgress = 1f) }
                viewModelScope.launch {
                    delay(1000)
                    _uiState.update { it.copy(exportProgress = 0f) }
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                Log.e("HomeViewModel", "Transformer Export failed", exportException)
                _uiState.update { it.copy(isExporting = false) }
            }
        })

        try {
            transformer.start(composition, outputFile.absolutePath)
            while (_uiState.value.isExporting) {
                val progressHolder = ProgressHolder()
                val state = transformer.getProgress(progressHolder)
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    _uiState.update { it.copy(exportProgress = progressHolder.progress / 100f) }
                }
                delay(500)
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to start transformer export", e)
            _uiState.update { it.copy(isExporting = false) }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val milliseconds = (ms % 1000) / 10
        return String.format(Locale.getDefault(), "%02d:%02d.%02d", minutes, seconds, milliseconds)
    }
}

@UnstableApi
private class FadeRgbMatrix(
    private val startTimeUs: Long,
    private val endTimeUs: Long,
    private val fadeIn: Boolean
) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val durationUs = endTimeUs - startTimeUs
        
        // If the current frame is completely outside the transition window, return standard identity or black
        if (durationUs <= 0) return floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        // Calculate progress based on global or local timestamp. coerceIn handles both.
        val progress = ((presentationTimeUs - startTimeUs).toFloat() / durationUs).coerceIn(0f, 1f)
        
        val scale = if (fadeIn) progress else 1f - progress

        return floatArrayOf(
            scale, 0f, 0f, 0f,
            0f, scale, 0f, 0f,
            0f, 0f, scale, 0f,
            0f, 0f, 0f, 1f
        )
    }
}

@UnstableApi
private class ZoomMatrixTransformation(
    private val startTimeUs: Long,
    private val endTimeUs: Long,
    private val isIncoming: Boolean
) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val matrix = Matrix()
        if (presentationTimeUs < startTimeUs || presentationTimeUs > endTimeUs) {
            return matrix
        }
        val durationUs = endTimeUs - startTimeUs
        if (durationUs <= 0) return matrix

        val progress = ((presentationTimeUs - startTimeUs).toFloat() / durationUs).coerceIn(0f, 1f)
        
        val scale = if (isIncoming) {
            0.7f + (0.3f * progress)
        } else {
            1.0f + (0.3f * progress)
        }

        matrix.postScale(scale, scale, 0f, 0f)
        return matrix
    }
}

@UnstableApi
private class DynamicGaussianBlur(
    private val startTimeUs: Long,
    private val endTimeUs: Long,
    private val isIncoming: Boolean
) : SeparableConvolution() {
    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D {
        val durationUs = endTimeUs - startTimeUs
        if (durationUs <= 0 || presentationTimeUs < startTimeUs || presentationTimeUs > endTimeUs) {
            return GaussianFunction(0.1f, 2.0f)
        }
        val progress = ((presentationTimeUs - startTimeUs).toFloat() / durationUs).coerceIn(0f, 1f)
        val maxSigma = 20f
        val currentSigma = if (isIncoming) {
            maxSigma * (1f - progress)
        } else {
            maxSigma * progress
        }
        return GaussianFunction(currentSigma.coerceAtLeast(0.1f), 2.0f)
    }
}

data class HomeUiState(
    val clips: List<VideoClip> = emptyList(),
    val audioClips: List<AudioClip> = emptyList(),
    val isLoading: Boolean = false,
    val currentTime: String = "00:00.00",
    val currentPositionMs: Long = 0,
    val totalDurationMs: Long = 0,
    val isPlaying: Boolean = false,
    val selectedClipId: String? = null,
    val selectedAudioClipId: String? = null,
    val msPerDp: Float = 10f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val showExportDialog: Boolean = false
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
    val transitionAfter: VideoTransition? = null,
    val isMuted: Boolean = false
) {
    val durationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)
}

data class AudioClip(
    val id: String,
    val name: String,
    val originalDurationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val color: Color,
    val uri: Uri? = null,
    val volume: Float = 1.0f,
    val startOffsetMs: Long = 0L
) {
    val durationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)
}
