package com.droidhubworld.videopro.ui.screen.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.droidhubworld.videopro.ui.theme.VideoProTheme
import com.droidhubworld.videopro.utils.FFmpegNative
import kotlinx.coroutines.delay

@Composable
fun Home(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val version = remember { FFmpegNative.getFFmpegVersion() }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

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

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    if (uiState.clips.isNotEmpty()) {
                        uiState.selectedVideoUri?.let { uri ->
                            val currentClip = uiState.clips.find { it.uri == uri }
                            if (currentClip?.durationMs == 0L) {
                                viewModel.updateClipDuration(uri, exoPlayer.duration, context)
                            }
                        }
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val startMs = viewModel.getClipStartMs(uiState.selectedVideoUri)
                viewModel.updateProgress(startMs + exoPlayer.currentPosition)
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(uiState.isPlaying) {
        while (uiState.isPlaying) {
            val startMs = viewModel.getClipStartMs(uiState.selectedVideoUri)
            viewModel.updateProgress(startMs + exoPlayer.currentPosition)
            delay(30)
        }
    }

    LaunchedEffect(uiState.selectedVideoUri) {
        uiState.selectedVideoUri?.let { uri ->
            val currentMediaItem = exoPlayer.currentMediaItem
            if (currentMediaItem?.localConfiguration?.uri != uri) {
                val startMs = viewModel.getClipStartMs(uri)
                val relativePosition = uiState.currentPositionMs - startMs

                exoPlayer.setMediaItem(MediaItem.fromUri(uri))
                exoPlayer.prepare()
                if (relativePosition > 0) {
                    exoPlayer.seekTo(relativePosition)
                }
                exoPlayer.playWhenReady = uiState.isPlaying
            }
        }
    }

    LaunchedEffect(uiState.isPlaying) {
        exoPlayer.playWhenReady = uiState.isPlaying
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    EditorScreenContent(
        uiState = uiState,
        exoPlayer = exoPlayer,
        version = version,
        onAddVideoClick = { videoPickerLauncher.launch("video/*") },
        onPlayPauseClick = { viewModel.onPlayPauseClicked() },
        onSeek = { position ->
            viewModel.updateProgress(position)
            // The updateProgress above might change selectedVideoUri, which triggers the LaunchedEffect
            // But we also need to seek if it's the SAME uri or to ensure it's exact
            val startMs = viewModel.getClipStartMs(uiState.selectedVideoUri)
            exoPlayer.seekTo(position - startMs)
        },
        onZoom = { factor -> viewModel.updateZoom(factor) },
        onZoomIn = { viewModel.zoomIn() },
        onZoomOut = { viewModel.zoomOut() },
        modifier = modifier
    )
}

@Composable
fun EditorScreenContent(
    uiState: HomeUiState,
    exoPlayer: ExoPlayer,
    version: String,
    onAddVideoClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onZoom: (Float) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { EditorTopBar() },
        bottomBar = { EditorBottomBar() },
        containerColor = Color.Black
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Text(
                text = "FFmpeg Version: $version",
                color = Color.Green,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            VideoPreviewSection(
                exoPlayer = exoPlayer,
                isPlaying = uiState.isPlaying,
                onPlayPauseClick = onPlayPauseClick,
                modifier = Modifier.weight(1.2f)
            )
            EditorTimeline(
                uiState = uiState,
                onAddVideoClick = onAddVideoClick,
                onSeek = onSeek,
                onZoom = onZoom,
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                modifier = Modifier.weight(0.8f)
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPreviewSection(
    exoPlayer: ExoPlayer,
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditorIconButton(Icons.Default.Refresh, "Undo")
            EditorIconButton(
                if (isPlaying) Icons.Default.Menu else Icons.Default.PlayArrow,
                "Play/Pause",
                size = 32.dp,
                onClick = onPlayPauseClick
            )
            EditorIconButton(Icons.Default.CheckCircle, "Redo")
        }
    }
}

@Composable
fun EditorIconButton(
    icon: ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp = 20.dp,
    onClick: () -> Unit = {}
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
fun EditorTopBar() {
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
fun EditorBottomBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(Color(0xFF1A1A1A))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        BottomBarItem(Icons.Default.Edit, "Edit")
        BottomBarItem(Icons.Default.Menu, "Audio")
        BottomBarItem(Icons.Default.Add, "Text")
        BottomBarItem(Icons.Default.Face, "Stickers")
        BottomBarItem(Icons.Default.Build, "Effects")
    }
}

@Composable
fun BottomBarItem(icon: ImageVector, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(8.dp)
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
    onSeek: (Long) -> Unit,
    onZoom: (Float) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val halfScreenWidth = screenWidth / 2
    val density = LocalDensity.current

    val isDragged by listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(isDragged, uiState.msPerDp) {
        if (isDragged) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    var timeMs = 0L
                    for (i in 0 until index) {
                        timeMs += uiState.clips.getOrNull(i)?.durationMs ?: 0L
                    }
                    val offsetInDp = with(density) { offset.toDp() }
                    val offsetMs = (offsetInDp.value * uiState.msPerDp).toLong()
                    onSeek(timeMs + offsetMs)
                }
        }
    }

    LaunchedEffect(uiState.currentPositionMs, uiState.msPerDp) {
        if (!isDragged) {
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
                val offsetInDp = (offsetMs / uiState.msPerDp).toFloat().dp
                val offsetPx = with(density) { offsetInDp.toPx() }.toInt()
                listState.scrollToItem(itemIndex, offsetPx)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F0F))
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) onZoom(zoom)
                }
            }
    ) {
        TimelineHeader(uiState.currentTime, uiState.currentPositionMs, uiState.msPerDp)

        Box(modifier = Modifier.fillMaxSize()) {
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
                items(uiState.clips) { clip ->
                    VideoClipItem(clip, uiState.msPerDp)
                }

                item {
                    AddClipButton(onAddVideoClick)
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
fun VideoClipItem(clip: VideoClip, msPerDp: Float) {
    val width = if (clip.durationMs > 0L) (clip.durationMs / msPerDp).toFloat().dp else 150.dp

    Box(
        modifier = Modifier
            .width(width)
            .height(64.dp)
            .padding(horizontal = 0.5.dp)
            .background(clip.color, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (clip.thumbnails.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxSize()) {
                clip.thumbnails.forEach { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        Text(
            clip.name,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Color.Black.copy(0.4f), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun AddClipButton(onAddVideoClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(Color(0xFF222222), RoundedCornerShape(4.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onAddVideoClick) {
            Icon(Icons.Default.Add, contentDescription = "Add Clip", tint = Color.White)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomePreview() {
    VideoProTheme {
        Home()
    }
}
