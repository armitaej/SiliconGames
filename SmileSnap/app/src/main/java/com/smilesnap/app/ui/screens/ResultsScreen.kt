package com.smilesnap.app.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smilesnap.app.analyzer.FrameScore
import java.text.SimpleDateFormat
import java.util.*

/** Results for a single video in a batch. */
data class VideoResult(
    val videoLabel: String,
    val candidates: List<FrameScore>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    videoResults: List<VideoResult>,
    onStartOver: () -> Unit
) {
    val context = LocalContext.current
    // Track selected candidate index per video
    val selectedIndices = remember {
        mutableStateMapOf<Int, Int>().apply {
            videoResults.indices.forEach { put(it, 0) }
        }
    }
    var showSaveSuccess by remember { mutableStateOf(false) }
    var saveCount by remember { mutableIntStateOf(0) }

    val isBatch = videoResults.size > 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isBatch) "Results \u2014 ${videoResults.size} videos"
                        else "Results"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onStartOver) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onStartOver,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Over")
                    }

                    if (isBatch) {
                        // Save all selected (one per video)
                        Button(
                            onClick = {
                                var count = 0
                                videoResults.forEachIndexed { videoIdx, result ->
                                    val selIdx = selectedIndices[videoIdx] ?: 0
                                    result.candidates.getOrNull(selIdx)?.bitmap?.let { bitmap ->
                                        saveBitmapToGallery(context, bitmap, "batch_${videoIdx + 1}")
                                        count++
                                    }
                                }
                                saveCount = count
                                showSaveSuccess = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SaveAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save All")
                        }
                    } else {
                        Button(
                            onClick = {
                                val selIdx = selectedIndices[0] ?: 0
                                videoResults.firstOrNull()?.candidates?.getOrNull(selIdx)
                                    ?.bitmap?.let { bitmap ->
                                        saveBitmapToGallery(context, bitmap)
                                        saveCount = 1
                                        showSaveSuccess = true
                                    }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Photo")
                        }
                    }
                }
            }
        }
    ) { padding ->
        val allEmpty = videoResults.all { it.candidates.isEmpty() }

        if (allEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("\uD83D\uDE15", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No smiles detected",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Try different videos with visible faces.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (!isBatch) {
            // Single video layout — same as before
            SingleVideoResults(
                candidates = videoResults.first().candidates,
                selectedIndex = selectedIndices[0] ?: 0,
                onSelectIndex = { selectedIndices[0] = it },
                modifier = Modifier.padding(padding)
            )
        } else {
            // Batch layout — scrollable list of video results
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                videoResults.forEachIndexed { videoIdx, result ->
                    item(key = "header_$videoIdx") {
                        Text(
                            text = result.videoLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = if (videoIdx == 0) 8.dp else 24.dp,
                                bottom = 8.dp
                            )
                        )
                    }

                    if (result.candidates.isEmpty()) {
                        item(key = "empty_$videoIdx") {
                            Text(
                                text = "No smiles found in this video.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                        val selIdx = selectedIndices[videoIdx] ?: 0
                        val selected = result.candidates.getOrNull(selIdx)

                        // Large preview of selected candidate
                        item(key = "preview_$videoIdx") {
                            selected?.bitmap?.let { bitmap ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(240.dp)
                                        .padding(horizontal = 16.dp)
                                ) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Selected frame",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Fit
                                    )

                                    // Score badge
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = "Score: ${"%.1f".format(selected.compositeScore)}",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }

                                    // Face count + timestamp
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(8.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                                    ) {
                                        val ts = formatTimestamp(selected.timestampMs)
                                        Text(
                                            text = "${selected.numFaces} face${if (selected.numFaces != 1) "s" else ""} \u00b7 $ts",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    // Per-video save button
                                    IconButton(
                                        onClick = {
                                            saveBitmapToGallery(context, bitmap, "video_${videoIdx + 1}")
                                            saveCount = 1
                                            showSaveSuccess = true
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(4.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                        ) {
                                            Icon(
                                                Icons.Default.Save,
                                                contentDescription = "Save this photo",
                                                modifier = Modifier.padding(6.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Horizontal scrolling thumbnail strip
                        item(key = "thumbs_$videoIdx") {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(result.candidates) { index, candidate ->
                                    candidate.bitmap?.let { bitmap ->
                                        val isSelected = index == selIdx
                                        Card(
                                            modifier = Modifier
                                                .size(72.dp)
                                                .clickable { selectedIndices[videoIdx] = index },
                                            shape = RoundedCornerShape(8.dp),
                                            border = if (isSelected) BorderStroke(
                                                3.dp,
                                                MaterialTheme.colorScheme.primary
                                            ) else null,
                                            elevation = CardDefaults.cardElevation(
                                                defaultElevation = if (isSelected) 4.dp else 1.dp
                                            )
                                        ) {
                                            Box {
                                                Image(
                                                    bitmap = bitmap.asImageBitmap(),
                                                    contentDescription = "Candidate ${index + 1}",
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )
                                                if (index == 0) {
                                                    Surface(
                                                        modifier = Modifier
                                                            .align(Alignment.TopStart)
                                                            .padding(2.dp),
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.secondary
                                                    ) {
                                                        Text(
                                                            text = "#1",
                                                            modifier = Modifier.padding(
                                                                horizontal = 4.dp,
                                                                vertical = 1.dp
                                                            ),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSecondary
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveSuccess) {
        LaunchedEffect(saveCount) {
            val msg = if (saveCount > 1) "$saveCount photos saved to gallery!"
                      else "Photo saved to gallery!"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            showSaveSuccess = false
        }
    }
}

/**
 * Single-video result layout: large preview + grid of candidates.
 */
@Composable
private fun SingleVideoResults(
    candidates: List<FrameScore>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        val selected = candidates.getOrNull(selectedIndex)
        selected?.bitmap?.let { bitmap ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Selected frame",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "Score: ${"%.1f".format(selected.compositeScore)}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                ) {
                    val ts = formatTimestamp(selected.timestampMs)
                    Text(
                        text = "${selected.numFaces} face${if (selected.numFaces != 1) "s" else ""} \u00b7 $ts",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = "Top ${candidates.size} candidates",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(candidates) { index, candidate ->
                candidate.bitmap?.let { bitmap ->
                    val isSelected = index == selectedIndex
                    Card(
                        modifier = Modifier
                            .size(80.dp)
                            .clickable { onSelectIndex(index) },
                        shape = RoundedCornerShape(8.dp),
                        border = if (isSelected) BorderStroke(
                            3.dp,
                            MaterialTheme.colorScheme.primary
                        ) else null,
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = if (isSelected) 4.dp else 1.dp
                        )
                    ) {
                        Box {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Candidate ${index + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            if (index == 0) {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(2.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondary
                                ) {
                                    Text(
                                        text = "#1",
                                        modifier = Modifier.padding(
                                            horizontal = 6.dp,
                                            vertical = 2.dp
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

private fun saveBitmapToGallery(
    context: android.content.Context,
    bitmap: Bitmap,
    suffix: String = ""
) {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val extra = if (suffix.isNotEmpty()) "_$suffix" else ""
    val filename = "SmileSnap_${timestamp}${extra}.png"

    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SmileSnap")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    uri?.let {
        resolver.openOutputStream(it)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(it, contentValues, null, null)
        }
    }
}
