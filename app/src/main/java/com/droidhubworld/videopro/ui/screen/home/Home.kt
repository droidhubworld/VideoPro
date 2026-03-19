package com.droidhubworld.videopro.ui.screen.home

import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.droidhubworld.videopro.R
import com.droidhubworld.videopro.ui.theme.VideoProTheme
import com.droidhubworld.videopro.utils.FFmpegNative
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun Home(
    initialUri: Uri? = null,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val version = remember { FFmpegNative.getFFmpegVersion() }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showTransitionDialog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialUri) {
        initialUri?.let {
            if (uiState.clips.none { clip -> clip.uri == it }) {
                viewModel.addVideo(it, context)
            }
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.addVideo(it, context) } }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.addAudio(it, context) } }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    val audioPlayers = remember { mutableMapOf<String, ExoPlayer>() }
    val audioClipTrims = remember { mutableMapOf<String, Pair<Long, Long>>() }

    LaunchedEffect(uiState.audioClips) {
        val currentIds = uiState.audioClips.map { it.id }.toSet()
        val toRemove = audioPlayers.keys.toSet() - currentIds
        toRemove.forEach { id ->
            audioPlayers[id]?.release()
            audioPlayers.remove(id)
            audioClipTrims.remove(id)
        }
        uiState.audioClips.forEach { clip ->
            var player = audioPlayers[clip.id]
            if (player == null) {
                player = ExoPlayer.Builder(context).build()
                audioPlayers[clip.id] = player
            }
            
            val currentTrim = Pair(clip.trimStartMs, clip.trimEndMs)
            if (audioClipTrims[clip.id] != currentTrim) {
                audioClipTrims[clip.id] = currentTrim
                player.setMediaItem(
                    MediaItem.Builder()
                        .setUri(clip.uri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.trimStartMs)
                                .setEndPositionMs(clip.trimEndMs)
                                .build()
                        )
                        .build()
                )
                player.prepare()
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) { viewModel.onPlaybackEnded() }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = exoPlayer.currentMediaItemIndex
                val clip = uiState.clips.getOrNull(index)
                if (clip != null) {
                    val targetVolume = if (clip.isMuted) 0f else 1f
                    if (exoPlayer.volume != targetVolume) exoPlayer.volume = targetVolume
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(uiState.clips) {
        val mediaItems = uiState.clips.mapNotNull { clip ->
            clip.uri?.let { uri ->
                MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.trimStartMs)
                            .setEndPositionMs(clip.trimEndMs)
                            .build()
                    )
                    .build()
            }
        }
        
        val currentWindow = exoPlayer.currentMediaItemIndex
        val currentClip = uiState.clips.getOrNull(currentWindow)
        if (currentClip != null) {
            val targetVolume = if (currentClip.isMuted) 0f else 1f
            if (exoPlayer.volume != targetVolume) {
                exoPlayer.volume = targetVolume
            }
        }

        if (mediaItems.isNotEmpty()) {
            val currentPos = exoPlayer.currentPosition
            val currentIndex = exoPlayer.currentMediaItemIndex
            exoPlayer.setMediaItems(mediaItems)
            exoPlayer.prepare()
            if (currentIndex < mediaItems.size) {
                exoPlayer.seekTo(currentIndex, currentPos)
            }
        } else { exoPlayer.clearMediaItems() }
    }

    LaunchedEffect(uiState.isPlaying) {
        if (uiState.isPlaying && exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekTo(0, 0)
        }
        exoPlayer.playWhenReady = uiState.isPlaying
        
        if (!uiState.isPlaying) {
            audioPlayers.values.forEach { it.pause() }
        } else {
            while (uiState.isPlaying) {
                val currentWindow = exoPlayer.currentMediaItemIndex
                var accumulatedMs = 0L
                for (i in 0 until currentWindow) {
                    accumulatedMs += uiState.clips.getOrNull(i)?.durationMs ?: 0L
                }
                val globalPosition = accumulatedMs + exoPlayer.currentPosition
                viewModel.updateProgress(globalPosition)
                
                uiState.audioClips.forEach { clip ->
                    val player = audioPlayers[clip.id]
                    if (player != null) {
                        val clipEndMs = clip.startOffsetMs + clip.durationMs
                        if (globalPosition in clip.startOffsetMs..clipEndMs) {
                            val expectedAudioPos = globalPosition - clip.startOffsetMs
                            if (!player.playWhenReady) {
                                player.seekTo(expectedAudioPos)
                                player.play()
                            } else if (abs(player.currentPosition - expectedAudioPos) > 200) {
                                player.seekTo(expectedAudioPos)
                            }
                        } else {
                            if (player.playWhenReady) {
                                player.pause()
                            }
                        }
                    }
                }
                
                val currentClip = uiState.clips.getOrNull(currentWindow)
                if (currentClip != null) {
                    val targetVolume = if (currentClip.isMuted) 0f else 1f
                    if (exoPlayer.volume != targetVolume) {
                        exoPlayer.volume = targetVolume
                    }
                }
                
                delay(30)
            }
        }
    }

    DisposableEffect(Unit) { 
        onDispose { 
            exoPlayer.release()
            audioPlayers.values.forEach { it.release() }
            audioPlayers.clear()
        } 
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.5f)).zIndex(100f), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.Green)
        }
    }
    if (uiState.isExporting) { ExportProgressDialog(progress = uiState.exportProgress) }
    if (uiState.showExportDialog) {
        ExportQualityDialog(
            onDismiss = { viewModel.showExportDialog(false) },
            onQualitySelected = { quality -> viewModel.exportVideo(context, quality) }
        )
    }
    if (showTransitionDialog != null) {
        TransitionSelectorDialog(
            onDismiss = { showTransitionDialog = null },
            onTransitionSelected = { type ->
                viewModel.setTransition(showTransitionDialog!!, type)
                showTransitionDialog = null
            }
        )
    }

    EditorScreenContent(
        uiState = uiState,
        exoPlayer = exoPlayer,
        version = version,
        onAddVideoClick = { videoPickerLauncher.launch("video/*") },
        onAddAudioClick = { audioPickerLauncher.launch("audio/*") },
        onExportClick = { viewModel.showExportDialog(true) },
        onPlayPauseClick = { viewModel.onPlayPauseClicked() },
        onUndoClick = { viewModel.undo() },
        onRedoClick = { viewModel.redo() },
        onSplitClick = { viewModel.splitSelectedClip() },
        onDeleteClick = { viewModel.deleteSelectedClip() },
        onMuteToggleClick = { viewModel.toggleMuteSelectedClip() },
        onClipSelected = { id -> viewModel.selectClip(id) },
        onAudioSelected = { id -> viewModel.selectAudioClip(id) },
        onMoveClip = { from, to -> viewModel.moveClip(from, to) },
        onMoveAudio = { id, offset -> viewModel.moveAudioClip(id, offset) },
        onSeek = { position ->
            viewModel.updateProgress(position)
            var accumulatedMs = 0L
            var targetIndex = 0
            var targetPosition = 0L
            for (i in uiState.clips.indices) {
                val clipDur = uiState.clips[i].durationMs
                if (position < accumulatedMs + clipDur || i == uiState.clips.lastIndex) {
                    targetIndex = i
                    targetPosition = position - accumulatedMs
                    break
                }
                accumulatedMs += clipDur
            }
            if (exoPlayer.mediaItemCount > targetIndex) {
                exoPlayer.seekTo(targetIndex, targetPosition.coerceAtLeast(0))
                val clip = uiState.clips.getOrNull(targetIndex)
                if (clip != null) {
                    val targetVolume = if (clip.isMuted) 0f else 1f
                    if (exoPlayer.volume != targetVolume) exoPlayer.volume = targetVolume
                }
            }
            
            uiState.audioClips.forEach { clip ->
                val player = audioPlayers[clip.id]
                if (player != null) {
                    val clipEndMs = clip.startOffsetMs + clip.durationMs
                    if (position in clip.startOffsetMs..clipEndMs) {
                        player.seekTo(position - clip.startOffsetMs)
                    } else {
                        player.seekTo(0)
                        player.pause()
                    }
                }
            }
        },
        onPause = { viewModel.pause() },
        onZoom = { factor -> viewModel.updateZoom(factor) },
        onZoomIn = { viewModel.zoomIn() },
        onZoomOut = { viewModel.zoomOut() },
        onTrimClip = { _, startMs, endMs -> viewModel.trimSelectedClip(startMs, endMs) },
        onTrimAudio = { _, startMs, endMs -> viewModel.trimSelectedAudioClip(startMs, endMs) },
        onTransitionClick = { clipId -> showTransitionDialog = clipId },
        modifier = modifier
    )
}

@Composable
fun EditorScreenContent(
    uiState: HomeUiState,
    exoPlayer: ExoPlayer,
    version: String,
    onAddVideoClick: () -> Unit,
    onAddAudioClick: () -> Unit,
    onExportClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    onSplitClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onMuteToggleClick: () -> Unit,
    onClipSelected: (String?) -> Unit,
    onAudioSelected: (String?) -> Unit,
    onMoveClip: (Int, Int) -> Unit,
    onMoveAudio: (String, Long) -> Unit,
    onSeek: (Long) -> Unit,
    onPause: () -> Unit,
    onZoom: (Float) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onTrimClip: (String, Long, Long) -> Unit,
    onTrimAudio: (String, Long, Long) -> Unit,
    onTransitionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { EditorTopBar(onAddVideoClick = onAddVideoClick, onExportClick = onExportClick) },
        bottomBar = {
            val selectedClip = uiState.clips.find { it.id == uiState.selectedClipId }
            EditorBottomBar(
                isVideoClipSelected = uiState.selectedClipId != null,
                isAudioClipSelected = uiState.selectedAudioClipId != null,
                selectedClipIsMuted = selectedClip?.isMuted ?: false,
                onSplitClick = onSplitClick,
                onDeleteClick = onDeleteClick,
                onAddAudioClick = onAddAudioClick,
                onMuteToggleClick = onMuteToggleClick
            )
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onClipSelected(null)
                    onAudioSelected(null)
                }
        ) {
            Text(text = "FFmpeg Version: $version", color = Color.Green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(modifier = Modifier.height(8.dp))
            VideoPreviewSection(uiState = uiState, exoPlayer = exoPlayer, onPlayPauseClick = onPlayPauseClick, onUndoClick = onUndoClick, onRedoClick = onRedoClick, modifier = Modifier.weight(1.2f))
            EditorTimeline(
                uiState = uiState,
                onAddVideoClick = onAddVideoClick,
                onClipSelected = onClipSelected,
                onAudioSelected = onAudioSelected,
                onMoveClip = onMoveClip,
                onMoveAudio = onMoveAudio,
                onSeek = onSeek,
                onPause = onPause,
                onZoom = onZoom,
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                onTrimClip = onTrimClip,
                onTrimAudio = onTrimAudio,
                onTransitionClick = onTransitionClick,
                modifier = Modifier.weight(0.8f)
            )
        }
    }
}

@Composable
fun EditorTimeline(
    uiState: HomeUiState,
    onAddVideoClick: () -> Unit,
    onClipSelected: (String?) -> Unit,
    onAudioSelected: (String?) -> Unit,
    onMoveClip: (Int, Int) -> Unit,
    onMoveAudio: (String, Long) -> Unit,
    onSeek: (Long) -> Unit,
    onPause: () -> Unit,
    onZoom: (Float) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onTrimClip: (String, Long, Long) -> Unit,
    onTrimAudio: (String, Long, Long) -> Unit,
    onTransitionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val halfScreenWidth = screenWidth / 2
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var isTrimming by remember { mutableStateOf(false) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var draggedAudioId by remember { mutableStateOf<String?>(null) }
    var audioDragOffset by remember { mutableFloatStateOf(0f) }
    var lastSnappedMs by remember { mutableLongStateOf(-1L) }
    var dragStartOffsetMs by remember { mutableLongStateOf(0L) }
    
    // Zoom out (120f) only when dragging video clips, not audio clips
    val displayMsPerDp by animateFloatAsState(targetValue = if (draggedIndex != null) 120f else uiState.msPerDp, animationSpec = tween(durationMillis = 300), label = "dragZoom")

    LaunchedEffect(isDragged) { if (isDragged) onPause() }

    LaunchedEffect(isDragged, displayMsPerDp) {
        if (isDragged && draggedIndex == null) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
                val clipIndex = index / 2
                var timeMs = 0L
                for (i in 0 until clipIndex) { timeMs += uiState.clips.getOrNull(i)?.durationMs ?: 0L }
                val offsetInDp = with(density) { offset.toDp() }
                val offsetMs = (offsetInDp.value * displayMsPerDp).toLong()
                onSeek(timeMs + offsetMs)
            }
        }
    }

    LaunchedEffect(uiState.currentPositionMs, displayMsPerDp, isTrimming, draggedIndex, draggedAudioId) {
        // Keep timeline playhead centered during zoom changes, even while dragging
        if (!isDragged && !isTrimming) {
            var accumulatedMs = 0L
            var itemIndex = -1
            var offsetMs = 0L
            for (i in uiState.clips.indices) {
                val clipDuration = uiState.clips[i].durationMs
                if (uiState.currentPositionMs < accumulatedMs + clipDuration) {
                    itemIndex = i
                    offsetMs = uiState.currentPositionMs - accumulatedMs
                    break
                }
                accumulatedMs += clipDuration
            }
            if (itemIndex != -1) {
                val offsetPx = with(density) { (offsetMs / displayMsPerDp).toFloat().dp.toPx() }.toInt()
                listState.scrollToItem(itemIndex * 2, offsetPx)
            } else if (uiState.clips.isNotEmpty()) {
                listState.scrollToItem(uiState.clips.size * 2 - 2, Int.MAX_VALUE)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xFF0F0F0F)).pointerInput(Unit) {
            awaitEachGesture {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size > 1) {
                        val zoomFactor = event.calculateZoom()
                        if (zoomFactor != 1f) { onZoom(zoomFactor); event.changes.forEach { it.consume() } }
                    }
                    if (event.changes.all { !it.pressed }) break
                }
            }
        }
    ) {
        TimelineHeader(uiState.currentTime, uiState.currentPositionMs, displayMsPerDp)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (uiState.clips.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onAddVideoClick, modifier = Modifier.size(64.dp).background(Color(0xFF222222), RoundedCornerShape(12.dp))) {
                        Icon(Icons.Default.Add, contentDescription = "Add Video", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Add Video", color = Color.White, fontSize = 14.sp)
                }
            } else {
                Box(modifier = Modifier.fillMaxHeight().width(2.dp).background(Color.White).align(Alignment.Center).zIndex(10f))
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = halfScreenWidth, end = halfScreenWidth, top = 24.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    uiState.clips.forEachIndexed { index, clip ->
                        val isBeingDragged = draggedIndex == index
                        item(key = clip.id) {
                            Column {
                                VideoClipItem(
                                    clip = clip,
                                    msPerDp = displayMsPerDp,
                                    isSelected = uiState.selectedClipId == clip.id,
                                    onClick = { onClipSelected(clip.id) },
                                    onTrim = { startMs, endMs -> isTrimming = false; onTrimClip(clip.id, startMs, endMs) },
                                    onDragStart = { isTrimming = true },
                                    onDragHandle = { previewTrimMs ->
                                        var timeMs = 0L
                                        for (c in uiState.clips) { if (c.id == clip.id) break; timeMs += c.durationMs }
                                        onSeek(timeMs + (previewTrimMs - clip.trimStartMs))
                                    },
                                    modifier = Modifier
                                        .zIndex(if (isBeingDragged) 100f else 1f)
                                        .offset { if (isBeingDragged) IntOffset(dragOffset.roundToInt(), 0) else IntOffset.Zero }
                                        .scale(if (isBeingDragged) 0.85f else 1f)
                                        .alpha(if (draggedIndex != null && !isBeingDragged) 0.6f else 1f)
                                        .pointerInput(uiState.clips) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { draggedIndex = index; dragOffset = 0f; onPause() },
                                                onDragEnd = {
                                                    val finalDragOffsetDp = with(density) { dragOffset.toDp() }.value
                                                    var targetIndex = index
                                                    var accOffset = 0f
                                                    if (finalDragOffsetDp > 0) {
                                                        for (i in index + 1 until uiState.clips.size) {
                                                            val w = (uiState.clips[i].durationMs / displayMsPerDp)
                                                            if (finalDragOffsetDp > accOffset + (w / 2)) { targetIndex = i; accOffset += w } else break
                                                        }
                                                    } else if (finalDragOffsetDp < 0) {
                                                        val absOffset = -finalDragOffsetDp
                                                        for (i in index - 1 downTo 0) {
                                                            val w = (uiState.clips[i].durationMs / displayMsPerDp)
                                                            if (absOffset > accOffset + (w / 2)) { targetIndex = i; accOffset += w } else break
                                                        }
                                                    }
                                                    if (targetIndex != index) onMoveClip(index, targetIndex)
                                                    draggedIndex = null; dragOffset = 0f
                                                },
                                                onDragCancel = { draggedIndex = null; dragOffset = 0f },
                                                onDrag = { change, dragAmount -> change.consume(); dragOffset += dragAmount.x }
                                            )
                                        }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                // Render Audio Clips that intersect with this video clip's timeframe
                                Box(modifier = Modifier.layout { measurable, constraints -> 
                                    val placeable = measurable.measure(constraints)
                                    layout(0, placeable.height) { placeable.placeRelative(0, 0) }
                                }) {
                                    var accMs = 0L
                                    for (i in 0 until index) { accMs += uiState.clips[i].durationMs }
                                    val clipEndMs = accMs + clip.durationMs
                                    uiState.audioClips.forEach { audio ->
                                        val audioEnd = audio.startOffsetMs + audio.durationMs
                                        if (audio.startOffsetMs < clipEndMs && audioEnd > accMs) {
                                            val audioRelOffsetMs = audio.startOffsetMs - accMs
                                            val isAudioBeingDragged = draggedAudioId == audio.id
                                            
                                            // Handle snapping logic for visual offset
                                            val currentDragOffsetMs = if (isAudioBeingDragged) (audioDragOffset / density.density * displayMsPerDp).toLong() else 0L
                                            val tentativePosMs = (if (isAudioBeingDragged) dragStartOffsetMs else audio.startOffsetMs) + currentDragOffsetMs
                                            val tentativeEndMs = tentativePosMs + audio.durationMs
                                            
                                            var finalVisualOffsetMs = currentDragOffsetMs
                                            if (isAudioBeingDragged) {
                                                val snapThresholdMs = (10 * displayMsPerDp).toLong()
                                                val snapPoints = mutableListOf<Long>(0) // Snap to start of timeline
                                                
                                                // Snap to other audio clips
                                                uiState.audioClips.filter { it.id != audio.id }.forEach { other ->
                                                    snapPoints.add(other.startOffsetMs)
                                                    snapPoints.add(other.startOffsetMs + other.durationMs)
                                                }
                                                // Snap to video clip junctions
                                                var vAcc = 0L
                                                uiState.clips.forEach { vClip ->
                                                    vAcc += vClip.durationMs
                                                    snapPoints.add(vAcc)
                                                }
                                                
                                                var bestSnapMs: Long? = null
                                                for (point in snapPoints) {
                                                    if (abs(tentativePosMs - point) < snapThresholdMs) {
                                                        bestSnapMs = point
                                                        break
                                                    } else if (abs(tentativeEndMs - point) < snapThresholdMs) {
                                                        bestSnapMs = point - audio.durationMs
                                                        break
                                                    }
                                                }
                                                
                                                if (bestSnapMs != null) {
                                                    finalVisualOffsetMs = bestSnapMs - audio.startOffsetMs
                                                    if (lastSnappedMs != bestSnapMs) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        lastSnappedMs = bestSnapMs
                                                    }
                                                } else {
                                                    lastSnappedMs = -1L
                                                }
                                            }

                                            AudioClipItem(
                                                audio = audio,
                                                msPerDp = displayMsPerDp,
                                                isSelected = uiState.selectedAudioClipId == audio.id,
                                                isDragging = isAudioBeingDragged,
                                                onClick = { onAudioSelected(audio.id) },
                                                onTrim = { start, end -> onTrimAudio(audio.id, start, end) },
                                                onDragStart = { isTrimming = true },
                                                onDragEnd = { isTrimming = false },
                                                modifier = Modifier
                                                    .offset(x = ((audioRelOffsetMs + finalVisualOffsetMs).toFloat() / displayMsPerDp).dp)
                                                    .zIndex(if (isAudioBeingDragged) 100f else 1f)
                                                    .offset { if (isAudioBeingDragged) IntOffset(0, 0) else IntOffset.Zero }
                                                    .alpha(if (draggedAudioId != null && !isAudioBeingDragged) 0.6f else 1f)
                                                    .pointerInput(audio, displayMsPerDp, density.density) {
                                                        detectDragGesturesAfterLongPress(
                                                            onDragStart = { 
                                                                draggedAudioId = audio.id
                                                                dragStartOffsetMs = audio.startOffsetMs
                                                                audioDragOffset = 0f
                                                                lastSnappedMs = -1L
                                                                onPause()
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                audioDragOffset += dragAmount.x
                                                            },
                                                            onDragEnd = {
                                                                val dragMs = (audioDragOffset / density.density * displayMsPerDp).toLong()
                                                                // Use the snapped position for final move if it exists
                                                                val finalPosMs = if (lastSnappedMs != -1L) lastSnappedMs else (dragStartOffsetMs + dragMs)
                                                                onMoveAudio(audio.id, finalPosMs.coerceAtLeast(0L))
                                                                draggedAudioId = null
                                                                audioDragOffset = 0f
                                                                lastSnappedMs = -1L
                                                            },
                                                            onDragCancel = { 
                                                                draggedAudioId = null
                                                                audioDragOffset = 0f
                                                                lastSnappedMs = -1L
                                                            }
                                                        )
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (index < uiState.clips.size - 1) {
                            item(key = "trans_${clip.id}") {
                                val transOffset by animateDpAsState(targetValue = when { uiState.selectedClipId == clip.id -> 28.dp; uiState.selectedClipId == uiState.clips.getOrNull(index + 1)?.id -> (-28).dp; else -> 0.dp }, label = "transitionOffset")
                                TransitionButton(type = clip.transitionAfter?.type ?: TransitionType.NONE, onClick = { onTransitionClick(clip.id) }, modifier = Modifier.offset(x = transOffset))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioClipItem(
    audio: AudioClip,
    msPerDp: Float,
    isSelected: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onTrim: (Long, Long) -> Unit,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var tempTrimStartMs by remember(audio.id, audio.trimStartMs) { mutableLongStateOf(audio.trimStartMs) }
    var tempTrimEndMs by remember(audio.id, audio.trimEndMs) { mutableLongStateOf(audio.trimEndMs) }
    
    var dragAccPx by remember { mutableFloatStateOf(0f) }
    var initialTrimStart by remember { mutableLongStateOf(0L) }
    var initialTrimEnd by remember { mutableLongStateOf(0L) }

    val effectiveDur = (tempTrimEndMs - tempTrimStartMs).coerceAtLeast(100L)
    val layoutWidth = (effectiveDur.toFloat() / msPerDp).dp
    val visualOffsetPx = with(density) { ((tempTrimStartMs - audio.trimStartMs).toFloat() / msPerDp).dp.roundToPx() }

    Box(
        modifier = modifier
            .offset { IntOffset(visualOffsetPx, 0) }
            .width(layoutWidth)
            .height(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(audio.color.copy(alpha = 0.6f))
            .border(
                if (isSelected) 2.dp else 1.dp, 
                if (isSelected) Color.White else Color.White.copy(0.3f), 
                RoundedCornerShape(4.dp)
            )
            .clickable { onClick() }
    ) {
        Text(
            text = audio.name, 
            color = Color.White, 
            fontSize = 9.sp, 
            fontWeight = FontWeight.Bold, 
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
        // Only show handles if selected AND NOT currently being dragged
        if (isSelected && !isDragging) {
            // Left Trim Handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(10.dp)
                    .fillMaxHeight()
                    .background(Color.White)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            dragAccPx += delta
                            val dragMs = (dragAccPx / density.density * msPerDp).toLong()
                            tempTrimStartMs = (initialTrimStart + dragMs).coerceIn(0L, tempTrimEndMs - 200L)
                        },
                        onDragStarted = { 
                            initialTrimStart = tempTrimStartMs
                            dragAccPx = 0f
                            onDragStart() 
                        },
                        onDragStopped = { 
                            onTrim(tempTrimStartMs, tempTrimEndMs)
                            onDragEnd() 
                        }
                    )
            )
            // Right Trim Handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(10.dp)
                    .fillMaxHeight()
                    .background(Color.White)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            dragAccPx += delta
                            val dragMs = (dragAccPx / density.density * msPerDp).toLong()
                            tempTrimEndMs = (initialTrimEnd + dragMs).coerceIn(tempTrimStartMs + 200L, audio.originalDurationMs)
                        },
                        onDragStarted = { 
                            initialTrimEnd = tempTrimEndMs
                            dragAccPx = 0f
                            onDragStart() 
                        },
                        onDragStopped = { 
                            onTrim(tempTrimStartMs, tempTrimEndMs)
                            onDragEnd() 
                        }
                    )
            )
        }
    }
}

@Composable
fun VideoClipItem(
    clip: VideoClip,
    msPerDp: Float,
    isSelected: Boolean,
    onClick: () -> Unit,
    onTrim: (Long, Long) -> Unit = { _, _ -> },
    onDragStart: () -> Unit = {},
    onDragHandle: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var tempTrimStartMs by remember(clip.id, clip.trimStartMs) { mutableLongStateOf(clip.trimStartMs) }
    var tempTrimEndMs by remember(clip.id, clip.trimEndMs) { mutableLongStateOf(clip.trimEndMs) }
    var initialTrimStart by remember { mutableLongStateOf(0L) }
    var initialTrimEnd by remember { mutableLongStateOf(0L) }
    var dragAccPx by remember { mutableFloatStateOf(0f) }
    var isHandlePressed by remember { mutableStateOf(false) }

    val effectiveDur = (tempTrimEndMs - tempTrimStartMs).coerceAtLeast(100L)
    val layoutWidth = (effectiveDur.toFloat() / msPerDp).dp
    val originalWidthDp = (clip.originalDurationMs.coerceAtLeast(effectiveDur) / msPerDp).dp
    val visualOffsetPx = with(density) { ((tempTrimStartMs - clip.trimStartMs).toFloat() / msPerDp).dp.roundToPx() }

    Box(modifier = modifier.offset { IntOffset(visualOffsetPx, 0) }.width(layoutWidth).height(64.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {
        Box(modifier = Modifier.fillMaxSize().background(clip.color, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))) {
            Row(modifier = Modifier.requiredWidth(originalWidthDp).fillMaxHeight().offset(x = -(tempTrimStartMs / msPerDp).dp)) {
                if (clip.thumbnails.isEmpty()) { repeat(5) { Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(0.1f))) } } else { clip.thumbnails.forEach { Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop) } }
            }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
            if (clip.isMuted) {
                Icon(painter = painterResource(id = R.drawable.ic_audio_muted), contentDescription = "Muted", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(16.dp))
            }
            if (isSelected) {
                Box(modifier = Modifier.fillMaxSize().border(2.dp, Color.White, RoundedCornerShape(4.dp)))
                Box(modifier = Modifier.align(Alignment.CenterStart).width(if (isHandlePressed) 40.dp else 16.dp).fillMaxHeight().background(if (isHandlePressed) Color.Transparent else Color.White).pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitFirstDown(); isHandlePressed = true; waitForUpOrCancellation(); isHandlePressed = false } } }.draggable(orientation = Orientation.Horizontal, state = rememberDraggableState { delta -> dragAccPx += delta; val dragMs = (dragAccPx / density.density * msPerDp).toLong(); tempTrimStartMs = (initialTrimStart + dragMs).coerceIn(0L, tempTrimEndMs - 200L); onDragHandle(tempTrimStartMs) }, onDragStarted = { isHandlePressed = true; initialTrimStart = tempTrimStartMs; dragAccPx = 0f; onDragStart() }, onDragStopped = { isHandlePressed = false; onTrim(tempTrimStartMs, tempTrimEndMs) }), contentAlignment = Alignment.Center) { if (!isHandlePressed) Box(modifier = Modifier.width(3.dp).height(24.dp).background(Color(0xFFE53935))) }
                Box(modifier = Modifier.align(Alignment.CenterEnd).width(if (isHandlePressed) 40.dp else 16.dp).fillMaxHeight().background(if (isHandlePressed) Color.Transparent else Color.White).pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitFirstDown(); isHandlePressed = true; waitForUpOrCancellation(); isHandlePressed = false } } }.draggable(orientation = Orientation.Horizontal, state = rememberDraggableState { delta -> dragAccPx += delta; val dragMs = (dragAccPx / density.density * msPerDp).toLong(); tempTrimEndMs = (initialTrimEnd + dragMs).coerceIn(tempTrimStartMs + 200L, clip.originalDurationMs); onDragHandle(tempTrimEndMs) }, onDragStarted = { isHandlePressed = true; initialTrimEnd = tempTrimEndMs; dragAccPx = 0f; onDragStart() }, onDragStopped = { isHandlePressed = false; onTrim(tempTrimStartMs, tempTrimEndMs) }), contentAlignment = Alignment.Center) { if (!isHandlePressed) Box(modifier = Modifier.width(3.dp).height(24.dp).background(Color(0xFFE53935))) }
            }
        }
    }
}

@Composable
fun TimelineHeader(currentTime: String, currentPositionMs: Long, msPerDp: Float) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Normal)
    Column {
        Box(modifier = Modifier.fillMaxWidth().height(30.dp).background(Color.Black), contentAlignment = Alignment.Center) { Text(currentTime, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        Canvas(modifier = Modifier.fillMaxWidth().height(32.dp)) {
            val stepInMs = when { msPerDp <= 2f -> 100L; msPerDp <= 5f -> 200L; msPerDp <= 10f -> 500L; msPerDp <= 20f -> 1000L; msPerDp <= 50f -> 2000L; else -> 5000L }
            val stepInPx = (stepInMs / msPerDp).toFloat().dp.toPx()
            val center = size.width / 2
            val offsetInPx = with(density) { (currentPositionMs / msPerDp).toFloat().dp.toPx() }
            val minI = ((offsetInPx - center) / stepInPx).toInt() - 1
            val maxI = ((size.width + offsetInPx - center) / stepInPx).toInt() + 1
            for (i in minI..maxI) {
                val x = center + (i * stepInPx) - offsetInPx
                if (x < 0 || x > size.width) continue
                val curTickMs = i * stepInMs
                val isMajor = curTickMs % 1000L == 0L
                val h = if (isMajor) 12.dp.toPx() else 6.dp.toPx()
                drawLine(color = if (isMajor) Color.Gray else Color.DarkGray, start = Offset(x, size.height), end = Offset(x, size.height - h), strokeWidth = 1.dp.toPx())
                if (isMajor && i >= 0) {
                    val label = formatTimelineLabel((curTickMs / 1000).toInt())
                    val layoutResult = textMeasurer.measure(label, labelStyle)
                    drawText(textLayoutResult = layoutResult, topLeft = Offset(x - layoutResult.size.width / 2, size.height - h - layoutResult.size.height - 2.dp.toPx()))
                }
            }
        }
    }
}

private fun formatTimelineLabel(seconds: Int): String { val m = seconds / 60; val s = seconds % 60; return if (m > 0) "%02d:%02d".format(m, s) else "%02d".format(s) }

@Composable
fun TransitionButton(type: TransitionType, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isActive = type != TransitionType.NONE
    Box(modifier = modifier.layout { measurable, constraints -> val placeable = measurable.measure(constraints); layout(0, placeable.height) { placeable.placeRelative(-placeable.width / 2, 0) } }.height(64.dp).zIndex(20f), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(width = 28.dp, height = 22.dp).clip(RoundedCornerShape(6.dp)).background(if (isActive) Color(0xFF80DEEA) else Color(0xFFFFFFFF)).clickable { onClick() }.then(if (!isActive) Modifier.border(1.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(14.dp, 10.dp)) { val w = size.width; val h = size.height; val path = Path().apply { moveTo(0f, 0f); lineTo(w / 2f, h / 2f); lineTo(0f, h); close(); moveTo(w, 0f); lineTo(w / 2f, h / 2f); lineTo(w, h); close() }; drawPath(path, Color.Black) }
        }
    }
}

@Composable
fun EditorBottomBar(
    isVideoClipSelected: Boolean,
    isAudioClipSelected: Boolean,
    selectedClipIsMuted: Boolean,
    onSplitClick: () -> Unit, 
    onDeleteClick: () -> Unit, 
    onAddAudioClick: () -> Unit,
    onMuteToggleClick: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().navigationBarsPadding().background(Color(0xFF1A1A1A)).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        BottomBarItem(rememberVectorPainter(Icons.Default.Build), "Split", onClick = onSplitClick)
        BottomBarItem(rememberVectorPainter(Icons.Default.Menu), "Audio", onClick = onAddAudioClick)
        if (isVideoClipSelected || isAudioClipSelected) {
            BottomBarItem(rememberVectorPainter(Icons.Default.KeyboardArrowUp), "Speed")
            if (isVideoClipSelected) {
                BottomBarItem(
                    painterResource(if (selectedClipIsMuted) R.drawable.ic_audio else R.drawable.ic_audio_muted),
                    if (selectedClipIsMuted) "Unmute" else "Mute", 
                    onClick = onMuteToggleClick
                )
            }
            BottomBarItem(rememberVectorPainter(Icons.Default.Delete), "Delete", onClick = onDeleteClick)
        } else {
            BottomBarItem(rememberVectorPainter(Icons.Default.Edit), "Edit")
            BottomBarItem(rememberVectorPainter(Icons.Default.Add), "Text")
            BottomBarItem(rememberVectorPainter(Icons.Default.Delete), "Delete", onClick = onDeleteClick)
        }
    }
}

@Composable
fun BottomBarItem(painter: Painter, label: String, onClick: () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp).clickable { onClick() }) { Icon(painter, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp)); Spacer(modifier = Modifier.height(4.dp)); Text(label, color = Color.White, fontSize = 10.sp) }
}

@Composable
fun EditorTopBar(onAddVideoClick: () -> Unit, onExportClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { }) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAddVideoClick, modifier = Modifier.padding(end = 8.dp)) { Icon(Icons.Default.Add, contentDescription = "Add Video", tint = Color.White) }
            Surface(color = Color(0xFF333333), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(end = 12.dp)) { Text(text = "1080P", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
            Button(onClick = onExportClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp), shape = RoundedCornerShape(4.dp), modifier = Modifier.height(32.dp)) { Text("Export", fontSize = 13.sp, color = Color.White) }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPreviewSection(uiState: HomeUiState, exoPlayer: ExoPlayer, onPlayPauseClick: () -> Unit, onUndoClick: () -> Unit, onRedoClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        val currentMs = uiState.currentPositionMs
        var activeTransition by remember { mutableStateOf<TransitionType?>(null) }
        var overlayAlpha by remember { mutableFloatStateOf(0f) }
        var playerAlpha by remember { mutableFloatStateOf(1f) }
        var blurRadius by remember { mutableStateOf(0.dp) }
        LaunchedEffect(currentMs, uiState.clips) {
            var accumulatedMs = 0L
            var found = false
            for (clip in uiState.clips) {
                val cutPoint = accumulatedMs + clip.durationMs
                val trans = clip.transitionAfter
                if (trans != null && trans.type != TransitionType.NONE) {
                    val halfDur = trans.durationMs / 2
                    if (currentMs in (cutPoint - halfDur)..(cutPoint + halfDur)) {
                        found = true
                        activeTransition = trans.type
                        val progress = (currentMs - (cutPoint - halfDur)).toFloat() / trans.durationMs
                        when (trans.type) {
                            TransitionType.FADE_BLACK -> { overlayAlpha = if (progress < 0.5f) progress * 2 else (1f - progress) * 2; playerAlpha = 1f }
                            TransitionType.CROSS_DISSOLVE -> { playerAlpha = if (progress < 0.5f) 1f - progress * 2 else (progress - 0.5f) * 2; overlayAlpha = 0f }
                            TransitionType.BLUR -> { blurRadius = if (progress < 0.5f) (progress * 40).dp else ((1f - progress) * 40).dp; playerAlpha = 1f }
                            else -> {}
                        }
                        break
                    }
                }
                accumulatedMs += clip.durationMs
            }
            if (!found) { activeTransition = null; overlayAlpha = 0f; playerAlpha = 1f; blurRadius = 0.dp }
        }
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = playerAlpha }.blur(blurRadius)) { AndroidView(factory = { context -> LayoutInflater.from(context).inflate(R.layout.player_view_texture, null).apply { this as PlayerView; player = exoPlayer; setBackgroundColor(android.graphics.Color.BLACK) } }, modifier = Modifier.fillMaxSize()) }
        if (activeTransition == TransitionType.FADE_BLACK) { Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = overlayAlpha))) }
        Row(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            EditorIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, description = "Undo", tint = if (uiState.canUndo) Color.White else Color.Gray, onClick = { if (uiState.canUndo) onUndoClick() })
            EditorIconButton(icon = if (uiState.isPlaying) Icons.Default.Menu else Icons.Default.PlayArrow, description = "Play/Pause", size = 32.dp, onClick = onPlayPauseClick)
            EditorIconButton(icon = Icons.AutoMirrored.Filled.ArrowForward, description = "Redo", tint = if (uiState.canRedo) Color.White else Color.Gray, onClick = { if (uiState.canRedo) onRedoClick() })
        }
    }
}

@Composable
fun EditorIconButton(icon: ImageVector, description: String, size: androidx.compose.ui.unit.Dp = 20.dp, tint: Color = Color.White, onClick: () -> Unit = {}) { IconButton(onClick = onClick) { Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size)) } }

@Composable
fun TransitionSelectorDialog(onDismiss: () -> Unit, onTransitionSelected: (TransitionType) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1A1A1A)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Select Transition", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(16.dp))
                TransitionType.entries.forEach { type -> TextButton(onClick = { onTransitionSelected(type) }, modifier = Modifier.fillMaxWidth()) { Text(type.name.replace("_", " "), color = Color.White) } }
            }
        }
    }
}

@Composable
fun ExportQualityDialog(onDismiss: () -> Unit, onQualitySelected: (ExportQuality) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1A1A1A)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Export Quality", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(16.dp))
                ExportQuality.entries.forEach { quality -> Button(onClick = { onQualitySelected(quality) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)), shape = RoundedCornerShape(8.dp)) { val label = when(quality) { ExportQuality.P480 -> "480P (SD)"; ExportQuality.P720 -> "720P (HD)"; ExportQuality.P1080 -> "1080P (Full HD)" }; Text(label, color = Color.White) } }
                Spacer(modifier = Modifier.height(8.dp)); TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
            }
        }
    }
}

@Composable
fun ExportProgressDialog(progress: Float) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1A1A1A)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(progress = { progress }, color = Color.Green, modifier = Modifier.size(64.dp)); Spacer(modifier = Modifier.height(16.dp))
                Text("Exporting... ${(progress * 100).toInt()}%", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomePreview() { VideoProTheme { Home() } }
