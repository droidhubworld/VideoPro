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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

    // Add initial video if provided
    LaunchedEffect(initialUri) {
        initialUri?.let {
            if (uiState.clips.none { clip -> clip.uri == it }) {
                viewModel.addVideo(it, context)
            }
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.addVideo(it, context)
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    // Handle Playback State Changes
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    viewModel.onPlaybackEnded()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Sync ExoPlayer Playlist with clips
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

        if (mediaItems.isNotEmpty()) {
            val currentPos = exoPlayer.currentPosition
            val currentIndex = exoPlayer.currentMediaItemIndex
            exoPlayer.setMediaItems(mediaItems)
            exoPlayer.prepare()
            if (currentIndex < mediaItems.size) {
                exoPlayer.seekTo(currentIndex, currentPos)
            }
        } else {
            exoPlayer.clearMediaItems()
        }
    }

    // Continuous Progress Loop
    LaunchedEffect(uiState.isPlaying) {
        while (uiState.isPlaying) {
            val currentWindow = exoPlayer.currentMediaItemIndex
            var accumulatedMs = 0L
            for (i in 0 until currentWindow) {
                accumulatedMs += uiState.clips.getOrNull(i)?.durationMs ?: 0L
            }
            val globalPosition = accumulatedMs + exoPlayer.currentPosition
            viewModel.updateProgress(globalPosition)
            delay(30)
        }
    }

    LaunchedEffect(uiState.isPlaying) {
        if (uiState.isPlaying && exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekTo(0, 0)
        }
        exoPlayer.playWhenReady = uiState.isPlaying
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.5f)).zIndex(100f), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.Green)
        }
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
        onPlayPauseClick = { viewModel.onPlayPauseClicked() },
        onUndoClick = { viewModel.undo() },
        onRedoClick = { viewModel.redo() },
        onSplitClick = { viewModel.splitSelectedClip() },
        onDeleteClick = { viewModel.deleteSelectedClip() },
        onClipSelected = { id -> viewModel.selectClip(id) },
        onMoveClip = { from, to -> viewModel.moveClip(from, to) },
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
            }
        },
        onPause = { viewModel.pause() },
        onZoom = { factor -> viewModel.updateZoom(factor) },
        onZoomIn = { viewModel.zoomIn() },
        onZoomOut = { viewModel.zoomOut() },
        onTrimClip = { _, startMs, endMs ->
            viewModel.trimSelectedClip(startMs, endMs, context)
        },
        onTransitionClick = { clipId -> showTransitionDialog = clipId },
        modifier = modifier
    )
}

@Composable
fun TransitionSelectorDialog(
    onDismiss: () -> Unit,
    onTransitionSelected: (TransitionType) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1A1A1A)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Select Transition", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                TransitionType.entries.forEach { type ->
                    TextButton(
                        onClick = { onTransitionSelected(type) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(type.name.replace("_", " "), color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun EditorScreenContent(
    uiState: HomeUiState,
    exoPlayer: ExoPlayer,
    version: String,
    onAddVideoClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    onSplitClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onClipSelected: (String?) -> Unit,
    onMoveClip: (Int, Int) -> Unit,
    onSeek: (Long) -> Unit,
    onPause: () -> Unit,
    onZoom: (Float) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onTrimClip: (String, Long, Long) -> Unit,
    onTransitionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { EditorTopBar(onAddVideoClick = onAddVideoClick) },
        bottomBar = {
            EditorBottomBar(
                isClipSelected = uiState.selectedClipId != null,
                onSplitClick = onSplitClick,
                onDeleteClick = onDeleteClick
            )
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClipSelected(null) }
        ) {
            Text(
                text = "FFmpeg Version: $version",
                color = Color.Green,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            VideoPreviewSection(
                uiState = uiState,
                exoPlayer = exoPlayer,
                onPlayPauseClick = onPlayPauseClick,
                onUndoClick = onUndoClick,
                onRedoClick = onRedoClick,
                modifier = Modifier.weight(1.2f)
            )
            EditorTimeline(
                uiState = uiState,
                onAddVideoClick = onAddVideoClick,
                onClipSelected = onClipSelected,
                onMoveClip = onMoveClip,
                onSeek = onSeek,
                onPause = onPause,
                onZoom = onZoom,
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                onTrimClip = onTrimClip,
                onTransitionClick = onTransitionClick,
                modifier = Modifier.weight(0.8f)
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPreviewSection(
    uiState: HomeUiState,
    exoPlayer: ExoPlayer,
    onPlayPauseClick: () -> Unit,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Transition Preview State
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
                            TransitionType.FADE_BLACK -> {
                                overlayAlpha = if (progress < 0.5f) progress * 2 else (1f - progress) * 2
                                playerAlpha = 1f
                            }
                            TransitionType.CROSS_DISSOLVE -> {
                                // Simulate cross dissolve by fading the only player out and back in
                                playerAlpha = if (progress < 0.5f) 1f - progress * 2 else (progress - 0.5f) * 2
                                overlayAlpha = 0f
                            }
                            TransitionType.BLUR -> {
                                blurRadius = if (progress < 0.5f) (progress * 40).dp else ((1f - progress) * 40).dp
                                playerAlpha = 1f
                            }
                            else -> {}
                        }
                        break
                    }
                }
                accumulatedMs += clip.durationMs
            }
            if (!found) {
                activeTransition = null
                overlayAlpha = 0f
                playerAlpha = 1f
                blurRadius = 0.dp
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = playerAlpha
                }
                .blur(blurRadius)
        ) {
            AndroidView(
                factory = { context ->
                    LayoutInflater.from(context).inflate(R.layout.player_view_texture, null).apply {
                        this as PlayerView
                        player = exoPlayer
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Fade Overlay
        if (activeTransition == TransitionType.FADE_BLACK) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = overlayAlpha)))
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditorIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                description = "Undo",
                tint = if (uiState.canUndo) Color.White else Color.Gray,
                onClick = { if (uiState.canUndo) onUndoClick() }
            )
            EditorIconButton(
                icon = if (uiState.isPlaying) Icons.Default.Menu else Icons.Default.PlayArrow,
                description = "Play/Pause",
                size = 32.dp,
                onClick = onPlayPauseClick
            )
            EditorIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                description = "Redo",
                tint = if (uiState.canRedo) Color.White else Color.Gray,
                onClick = { if (uiState.canRedo) onRedoClick() }
            )
        }
    }
}

@Composable
fun EditorIconButton(
    icon: ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp = 20.dp,
    tint: Color = Color.White,
    onClick: () -> Unit = {}
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size))
    }
}

@Composable
fun EditorTopBar(onAddVideoClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { }) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAddVideoClick, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Add Video", tint = Color.White)
            }
            Surface(
                color = Color(0xFF333333),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Text(
                    text = "1080P",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Button(
                onClick = { },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Export", fontSize = 13.sp, color = Color.White)
            }
        }
    }
}

@Composable
fun EditorBottomBar(
    isClipSelected: Boolean,
    onSplitClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(Color(0xFF1A1A1A))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        BottomBarItem(Icons.Default.Build, "Split", onClick = onSplitClick)
        
        if (isClipSelected) {
            BottomBarItem(Icons.Default.KeyboardArrowUp, "Speed")
            BottomBarItem(Icons.Default.PlayArrow, "Animation")
            BottomBarItem(Icons.Default.Delete, "Delete", onClick = onDeleteClick)
        } else {
            BottomBarItem(Icons.Default.Edit, "Edit")
            BottomBarItem(Icons.Default.Menu, "Audio")
            BottomBarItem(Icons.Default.Add, "Text")
            BottomBarItem(Icons.Default.Delete, "Delete", onClick = onDeleteClick)
        }
    }
}

@Composable
fun BottomBarItem(icon: ImageVector, label: String, onClick: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(8.dp)
            .clickable { onClick() }
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 10.sp)
    }
}

@Composable
fun EditorTimeline(
    uiState: HomeUiState,
    onAddVideoClick: () -> Unit,
    onClipSelected: (String?) -> Unit,
    onMoveClip: (Int, Int) -> Unit,
    onSeek: (Long) -> Unit,
    onPause: () -> Unit,
    onZoom: (Float) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onTrimClip: (String, Long, Long) -> Unit,
    onTransitionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val halfScreenWidth = screenWidth / 2
    val density = LocalDensity.current

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var isTrimming by remember { mutableStateOf(false) }

    // Drag and Drop State
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    // Animated Zoom Out Effect when dragging - Significantly wider view
    val displayMsPerDp by animateFloatAsState(
        targetValue = if (draggedIndex != null) 150f else uiState.msPerDp,
        animationSpec = tween(durationMillis = 300),
        label = "dragZoom"
    )

    LaunchedEffect(isDragged) {
        if (isDragged) {
            onPause()
        }
    }

    LaunchedEffect(isDragged, displayMsPerDp) {
        if (isDragged && draggedIndex == null) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    val clipIndex = index / 2
                    val isTransition = index % 2 != 0
                    
                    var timeMs = 0L
                    for (i in 0 until clipIndex) {
                        timeMs += uiState.clips.getOrNull(i)?.durationMs ?: 0L
                    }
                    
                    if (!isTransition) {
                        val offsetInDp = with(density) { offset.toDp() }
                        val offsetMs = (offsetInDp.value * displayMsPerDp).toLong()
                        onSeek(timeMs + offsetMs)
                    } else {
                        onSeek(timeMs + (uiState.clips.getOrNull(clipIndex)?.durationMs ?: 0L))
                    }
                }
        }
    }

    LaunchedEffect(uiState.currentPositionMs, displayMsPerDp, isTrimming, draggedIndex) {
        if (!isDragged && !isTrimming && draggedIndex == null) {
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
                val offsetInDp = (offsetMs / displayMsPerDp).toFloat().dp
                val offsetPx = with(density) { offsetInDp.toPx() }.toInt()
                listState.scrollToItem(itemIndex * 2, offsetPx)
            } else if (uiState.clips.isNotEmpty()) {
                // Scroll to end of last clip
                listState.scrollToItem(uiState.clips.size * 2 - 2, Int.MAX_VALUE)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F0F))
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.size > 1) {
                            val zoomFactor = event.calculateZoom()
                            if (zoomFactor != 1f) {
                                onZoom(zoomFactor)
                                event.changes.forEach { it.consume() }
                            }
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
                    IconButton(
                        onClick = onAddVideoClick,
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color(0xFF222222), RoundedCornerShape(12.dp))
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Video", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Add Video", color = Color.White, fontSize = 14.sp)
                }
            } else {
                // Playhead Line - MASTER REFERENCE
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(Color.White)
                        .align(Alignment.Center)
                        .zIndex(10f)
                )

                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = halfScreenWidth),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    uiState.clips.forEachIndexed { index, clip ->
                        val isBeingDragged = draggedIndex == index

                        item(key = clip.id) {
                            VideoClipItem(
                                clip = clip,
                                msPerDp = displayMsPerDp,
                                isSelected = uiState.selectedClipId == clip.id,
                                onClick = { onClipSelected(clip.id) },
                                onTrim = { startMs, endMs ->
                                    isTrimming = false
                                    onTrimClip(clip.id, startMs, endMs)
                                },
                                onDragStart = { isTrimming = true },
                                onDragHandle = { previewTrimMs ->
                                    var timeMs = 0L
                                    for (c in uiState.clips) {
                                        if (c.id == clip.id) break
                                        timeMs += c.durationMs
                                    }
                                    val offsetFromStartOfClip = (previewTrimMs - clip.trimStartMs)
                                    onSeek(timeMs + offsetFromStartOfClip)
                                },
                                modifier = Modifier
                                    .zIndex(if (isBeingDragged) 100f else 1f)
                                    .offset {
                                        if (isBeingDragged) IntOffset(dragOffset.roundToInt(), 0)
                                        else IntOffset.Zero
                                    }
                                    .scale(if (isBeingDragged) 0.85f else 1f)
                                    .alpha(if (draggedIndex != null && !isBeingDragged) 0.6f else 1f)
                                    .pointerInput(uiState.clips) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { _ ->
                                                draggedIndex = index
                                                dragOffset = 0f
                                            },
                                            onDragEnd = {
                                                val finalDragOffsetDp = with(density) { dragOffset.toDp() }.value
                                                var targetIndex = index
                                                var accumulatedOffset = 0f

                                                if (finalDragOffsetDp > 0) {
                                                    for (i in index + 1 until uiState.clips.size) {
                                                        val nextClipWidth = (uiState.clips[i].durationMs / displayMsPerDp)
                                                        if (finalDragOffsetDp > accumulatedOffset + (nextClipWidth / 2)) {
                                                            targetIndex = i
                                                            accumulatedOffset += nextClipWidth
                                                        } else break
                                                    }
                                                } else if (finalDragOffsetDp < 0) {
                                                    val absOffset = -finalDragOffsetDp
                                                    for (i in index - 1 downTo 0) {
                                                        val prevClipWidth = (uiState.clips[i].durationMs / displayMsPerDp)
                                                        if (absOffset > accumulatedOffset + (prevClipWidth / 2)) {
                                                            targetIndex = i
                                                            accumulatedOffset += prevClipWidth
                                                        } else break
                                                    }
                                                }

                                                if (targetIndex != index) {
                                                    onMoveClip(index, targetIndex)
                                                }
                                                draggedIndex = null
                                                dragOffset = 0f
                                            },
                                            onDragCancel = {
                                                draggedIndex = null
                                                dragOffset = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffset += dragAmount.x
                                            }
                                        )
                                    }
                            )
                        }

                        if (index < uiState.clips.size - 1) {
                            item(key = "trans_${clip.id}") {
                                val isLeftSelected = uiState.selectedClipId == clip.id
                                val isRightSelected = uiState.selectedClipId == uiState.clips.getOrNull(index + 1)?.id
                                
                                val transitionOffset by animateDpAsState(
                                    targetValue = when {
                                        isLeftSelected -> 28.dp
                                        isRightSelected -> (-28).dp
                                        else -> 0.dp
                                    },
                                    label = "transitionOffset"
                                )

                                TransitionButton(
                                    type = clip.transitionAfter?.type ?: TransitionType.NONE,
                                    onClick = { onTransitionClick(clip.id) },
                                    modifier = Modifier.offset(x = transitionOffset)
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    FloatingActionButton(
                        onClick = onZoomIn,
                        containerColor = Color.DarkGray,
                        contentColor = Color.White,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Zoom In")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FloatingActionButton(
                        onClick = onZoomOut,
                        containerColor = Color.DarkGray,
                        contentColor = Color.White,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Zoom Out")
                    }
                }
            }
        }
    }
}

@Composable
fun TransitionButton(
    type: TransitionType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = type != TransitionType.NONE
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                // Report 0 width to parent but center the actual content
                layout(0, placeable.height) {
                    placeable.placeRelative(-placeable.width / 2, 0)
                }
            }
            .fillMaxHeight()
            .zIndex(20f),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isActive) Color(0xFF80DEEA) else Color(0xFFFFFFFF))
                .clickable { onClick() }
                .then(if (!isActive) Modifier.border(1.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(14.dp, 10.dp)) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(w / 2f, h / 2f)
                    lineTo(0f, h)
                    close()
                    moveTo(w, 0f)
                    lineTo(w / 2f, h / 2f)
                    lineTo(w, h)
                    close()
                }
                drawPath(path, Color.Black)
            }
        }
    }
}

@Composable
fun TimelineHeader(currentTime: String, currentPositionMs: Long, msPerDp: Float) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = Color.Gray,
        fontSize = 10.sp,
        fontWeight = FontWeight.Normal
    )

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(currentTime, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(32.dp)) {
            val stepInMs = when {
                msPerDp <= 2f -> 100L
                msPerDp <= 5f -> 200L
                msPerDp <= 10f -> 500L
                msPerDp <= 20f -> 1000L
                msPerDp <= 50f -> 2000L
                else -> 5000L
            }

            val stepInPx = (stepInMs / msPerDp).toFloat().dp.toPx()
            val center = size.width / 2
            val offsetInPx = with(density) { (currentPositionMs / msPerDp).toFloat().dp.toPx() }

            val minI = ((offsetInPx - center) / stepInPx).toInt() - 1
            val maxI = ((size.width + offsetInPx - center) / stepInPx).toInt() + 1

            for (i in minI..maxI) {
                val x = center + (i * stepInPx) - offsetInPx
                if (x < 0 || x > size.width) continue

                val currentTickMs = i * stepInMs
                val isMajor = currentTickMs % 1000L == 0L
                val height = if (isMajor) 12.dp.toPx() else 6.dp.toPx()

                drawLine(
                    color = if (isMajor) Color.Gray else Color.DarkGray,
                    start = Offset(x, size.height),
                    end = Offset(x, size.height - height),
                    strokeWidth = 1.dp.toPx()
                )

                if (isMajor && i >= 0) {
                    val timeLabel = formatTimelineLabel((currentTickMs / 1000).toInt())
                    val textLayoutResult = textMeasurer.measure(timeLabel, labelStyle)
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(
                            x - textLayoutResult.size.width / 2,
                            size.height - height - textLayoutResult.size.height - 2.dp.toPx()
                        )
                    )
                }
            }
        }
    }
}

private fun formatTimelineLabel(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "%02d:%02d".format(m, s) else "%02d".format(s)
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

    // REAL-TIME RIPPLE
    var tempTrimStartMs by remember(clip.id, clip.trimStartMs) { mutableLongStateOf(clip.trimStartMs) }
    var tempTrimEndMs by remember(clip.id, clip.trimEndMs) { mutableLongStateOf(clip.trimEndMs) }

    var initialTrimStart by remember { mutableLongStateOf(0L) }
    var initialTrimEnd by remember { mutableLongStateOf(0L) }
    var dragAccumulatorPx by remember { mutableFloatStateOf(0f) }

    var isHandlePressed by remember { mutableStateOf(false) }

    val effectiveDuration = (tempTrimEndMs - tempTrimStartMs).coerceAtLeast(100L)
    val baseWidthMs = if (effectiveDuration <= 100L && clip.originalDurationMs == 0L) 3000L else effectiveDuration
    val layoutWidth = (baseWidthMs.toFloat() / msPerDp).dp
    val originalWidthDp = (clip.originalDurationMs.coerceAtLeast(baseWidthMs) / msPerDp).dp

    // Calculate the visual offset to keep the right edge stable during start-trimming
    val visualOffsetMs = tempTrimStartMs - clip.trimStartMs
    val visualOffsetPx = with(density) { (visualOffsetMs.toFloat() / msPerDp).dp.roundToPx() }

    Box(
        modifier = modifier
            .offset { IntOffset(visualOffsetPx, 0) }
            .width(layoutWidth)
            .height(64.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(clip.color, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
        ) {
            Row(
                modifier = Modifier
                    .requiredWidth(originalWidthDp)
                    .fillMaxHeight()
                    .offset(x = -(tempTrimStartMs / msPerDp).dp)
            ) {
                if (clip.thumbnails.isEmpty()) {
                    repeat(5) { Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(0.1f))) }
                } else {
                    clip.thumbnails.forEach { bitmap ->
                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))

            if (isSelected) {
                // The selection border (2dp white) serves as the "line" when dragging
                Box(modifier = Modifier.fillMaxSize().border(2.dp, Color.White, RoundedCornerShape(4.dp)))

                // Left Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(if (isHandlePressed) 40.dp else 16.dp)
                        .fillMaxHeight()
                        .background(if (isHandlePressed) Color.Transparent else Color.White)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitFirstDown()
                                    isHandlePressed = true
                                    waitForUpOrCancellation()
                                    isHandlePressed = false
                                }
                            }
                        }
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                dragAccumulatorPx += delta
                                val dragMs = (dragAccumulatorPx / density.density * msPerDp).toLong()
                                tempTrimStartMs = (initialTrimStart + dragMs).coerceIn(0L, tempTrimEndMs - 200L)
                                onDragHandle(tempTrimStartMs)
                            },
                            onDragStarted = {
                                isHandlePressed = true
                                initialTrimStart = tempTrimStartMs
                                dragAccumulatorPx = 0f
                                onDragStart()
                            },
                            onDragStopped = {
                                isHandlePressed = false
                                onTrim(tempTrimStartMs, tempTrimEndMs)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) { 
                    if (!isHandlePressed) {
                        Box(modifier = Modifier.width(3.dp).height(24.dp).background(Color(0xFFE53935))) 
                    }
                }

                // Right Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(if (isHandlePressed) 40.dp else 16.dp)
                        .fillMaxHeight()
                        .background(if (isHandlePressed) Color.Transparent else Color.White)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitFirstDown()
                                    isHandlePressed = true
                                    waitForUpOrCancellation()
                                    isHandlePressed = false
                                }
                            }
                        }
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                dragAccumulatorPx += delta
                                val dragMs = (dragAccumulatorPx / density.density * msPerDp).toLong()
                                tempTrimEndMs = (initialTrimEnd + dragMs).coerceIn(tempTrimStartMs + 200L, clip.originalDurationMs)
                                onDragHandle(tempTrimEndMs)
                            },
                            onDragStarted = {
                                isHandlePressed = true
                                initialTrimEnd = tempTrimEndMs
                                dragAccumulatorPx = 0f
                                onDragStart()
                            },
                            onDragStopped = {
                                isHandlePressed = false
                                onTrim(tempTrimStartMs, tempTrimEndMs)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) { 
                    if (!isHandlePressed) {
                        Box(modifier = Modifier.width(3.dp).height(24.dp).background(Color(0xFFE53935))) 
                    }
                }
            }
        }
    }
}

private fun formatDurationLabel(ms: Long): String {
    val totalSeconds = ms / 1000f
    return String.format(Locale.getDefault(), "%.1fs", totalSeconds)
}

@Preview(showBackground = true)
@Composable
fun HomePreview() {
    VideoProTheme {
        Home()
    }
}
