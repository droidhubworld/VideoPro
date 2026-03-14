package com.droidhubworld.videopro.ui.screen.rough

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhubworld.videopro.utils.FFmpegNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun RoughScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var originalDurationMs by remember { mutableLongStateOf(0L) }
    var trimStartMs by remember { mutableLongStateOf(0L) }
    var trimEndMs by remember { mutableLongStateOf(0L) }
    var isSelected by remember { mutableStateOf(false) }
    var thumbnails by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

    val msPerDp = 10f 
    val scrollState = rememberScrollState()
    var isTrimming by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            videoUri = it
            thumbnails = emptyList()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, it)
                val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = time?.toLong() ?: 0L
                originalDurationMs = duration
                trimStartMs = 0L
                trimEndMs = duration

                scope.launch(Dispatchers.IO) {
                    val path = getRealPathFromURI(context, it)
                    if (path != null) {
                        val newThumbnails = mutableListOf<Bitmap>()
                        val count = 10
                        val step = duration / count
                        for (i in 0 until count) {
                            val timeMs = i * step
                            val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
                            if (FFmpegNative.extractFrame(path, timeMs, bitmap)) {
                                newThumbnails.add(bitmap)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            thumbnails = newThumbnails
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { isSelected = false },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (videoUri == null) {
                Button(onClick = { launcher.launch("video/*") }) { Text("Select Video") }
            } else {
                Text(
                    text = "Rough Timeline (Ripple Trim)",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(Color.Black.copy(alpha = 0.3f))
                        .horizontalScroll(scrollState),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // Constant width container prevents scroll clamping during layout transitions
                    val containerWidthDp = (originalDurationMs / msPerDp).dp

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 40.dp)
                            .width(containerWidthDp)
                            .height(80.dp)
                    ) {
                        RoughVideoClipItem(
                            durationMs = originalDurationMs,
                            trimStartMs = trimStartMs,
                            trimEndMs = trimEndMs,
                            msPerDp = msPerDp,
                            isSelected = isSelected,
                            isTrimming = isTrimming,
                            thumbnails = thumbnails,
                            onTrimChange = { start, end ->
                                trimStartMs = start
                                trimEndMs = end
                            },
                            onTrimStart = { isLeftHandle ->
                                val startMs = trimStartMs
                                val currentScroll = scrollState.value
                                isTrimming = true
                                scope.launch {
                                    if (isLeftHandle) {
                                        scrollState.scrollTo((startMs / msPerDp * density.density).toInt())
                                    } else {
                                        // Align scroll to match the clip's new absolute position offset
                                        scrollState.scrollTo(currentScroll + (startMs / msPerDp * density.density).toInt())
                                    }
                                }
                            },
                            onTrimEnd = {
                                isTrimming = false
                                scope.launch { scrollState.scrollTo(0) } // Use scrollTo(0) to avoid animation overlap jumps
                            },
                            onSelect = { isSelected = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Duration: ${formatDuration(trimEndMs - trimStartMs)}", color = Color(0xFF66BB6A), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Start: ${formatDuration(trimStartMs)} | End: ${formatDuration(trimEndMs)}", color = Color.Gray, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { videoUri = null; isSelected = false; thumbnails = emptyList() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Reset Workspace") }
            }
        }
    }
}

@Composable
fun RoughVideoClipItem(
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    msPerDp: Float,
    isSelected: Boolean,
    isTrimming: Boolean,
    thumbnails: List<Bitmap>,
    onTrimChange: (Long, Long) -> Unit,
    onTrimStart: (Boolean) -> Unit,
    onTrimEnd: () -> Unit,
    onSelect: () -> Unit
) {
    val density = LocalDensity.current
    val effectiveDuration = (trimEndMs - trimStartMs).coerceAtLeast(100L)

    val clipWidthDp = (effectiveDuration / msPerDp).dp
    val clipOffsetDp = if (isTrimming) (trimStartMs / msPerDp).dp else 0.dp
    val originalWidthDp = (durationMs / msPerDp).dp

    var initialTrimStart by remember { mutableLongStateOf(0L) }
    var initialTrimEnd by remember { mutableLongStateOf(0L) }
    var dragAccumulatorPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .offset(x = clipOffsetDp)
            .width(clipWidthDp)
            .fillMaxHeight()
            .background(Color(0xFF2C2C2C), RoundedCornerShape(4.dp))
            .then(if (isSelected) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect() }
            .clip(RoundedCornerShape(4.dp))
    ) {
        Row(
            modifier = Modifier
                .width(originalWidthDp)
                .fillMaxHeight()
                .offset(x = -(trimStartMs / msPerDp).dp)
        ) {
            if (thumbnails.isEmpty()) {
                repeat(5) { Box(modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.White.copy(0.1f))) }
            } else {
                thumbnails.forEach { bitmap ->
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.1f)))

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(18.dp)
                    .fillMaxHeight()
                    .background(Color.White)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            dragAccumulatorPx += delta
                            val dragMs = (dragAccumulatorPx / density.density * msPerDp).toLong()
                            val newStart = (initialTrimStart + dragMs).coerceIn(0L, trimEndMs - 200L)
                            onTrimChange(newStart, trimEndMs)
                        },
                        onDragStarted = {
                            initialTrimStart = trimStartMs
                            dragAccumulatorPx = 0f
                            onTrimStart(true)
                        },
                        onDragStopped = { onTrimEnd() }
                    ),
                contentAlignment = Alignment.Center
            ) { Box(modifier = Modifier.width(2.dp).height(20.dp).background(Color.Red)) }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(18.dp)
                    .fillMaxHeight()
                    .background(Color.White)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            dragAccumulatorPx += delta
                            val dragMs = (dragAccumulatorPx / density.density * msPerDp).toLong()
                            val newEnd = (initialTrimEnd + dragMs).coerceIn(trimStartMs + 200L, durationMs)
                            onTrimChange(trimStartMs, newEnd)
                        },
                        onDragStarted = {
                            initialTrimEnd = trimEndMs
                            dragAccumulatorPx = 0f
                            onTrimStart(false)
                        },
                        onDragStopped = { onTrimEnd() }
                    ),
                contentAlignment = Alignment.Center
            ) { Box(modifier = Modifier.width(2.dp).height(20.dp).background(Color.Red)) }
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

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000f
    return String.format(Locale.getDefault(), "%.2fs", seconds)
}
