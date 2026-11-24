package com.jurisgames.papeles.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jurisgames.papeles.core.Paper
import com.jurisgames.papeles.core.PhotoPoint
import com.jurisgames.papeles.core.ScanLook
import com.jurisgames.papeles.core.photoCorners
import com.jurisgames.papeles.core.photoPreview
import com.jurisgames.papeles.core.picturesToPdf
import com.jurisgames.papeles.core.scanPage

/** The preview of a photo is drawn at most this many pixels on its longest side. */
private const val PREVIEW_SIDE = 1200u
private val EDGE = Color(0xFF2F80ED)

/** One scanned page: the photo, its size upright, where its paper's corners are (photo pixels), and its look. */
private class ScanItem(val photo: ByteArray, val w: Float, val h: Float, corners: List<Offset>, val preview: ImageBitmap) {
    val corners = mutableStateListOf<Offset>().apply { addAll(corners) }
    var look by mutableStateOf(ScanLook.DOCUMENT)
    fun whole() {
        corners.clear()
        corners.addAll(listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h)))
    }
}

/**
 * Scan (plan M5): take or choose a photo of each page; the paper's corners are found (drag them if needed), the page
 * is straightened and cleaned (document, grey or colour), pages can be reordered, and a PDF is saved. Everything
 * stays on the device.
 */
@Composable
fun ScanScreen(host: Host, worker: Worker, back: () -> Unit) {
    val items = remember { mutableStateListOf<ScanItem>() }
    var at by remember { mutableStateOf(0) }
    var paper by remember { mutableStateOf(Paper.A4) }
    var saved by remember { mutableStateOf<Picked?>(null) }

    fun add(picked: List<Picked>) {
        if (picked.isEmpty()) return
        worker.run({
            picked.map { p ->
                val bytes = p.read()
                val c = photoCorners(bytes)
                ScanItem(bytes, c[0], c[1], (0 until 4).map { Offset(c[2 + 2 * it], c[3 + 2 * it]) }, host.image(photoPreview(bytes, PREVIEW_SIDE)))
            }
        }) { made ->
            items.addAll(made)
            at = items.size - made.size
            saved = null
        }
    }

    fun save() {
        val list = items.map { Triple(it.photo, it.corners.map { c -> PhotoPoint(c.x, c.y) }, it.look) }
        val p = paper
        worker.run({ picturesToPdf(list.map { (b, c, l) -> scanPage(b, c, l) }, p) }) { bytes ->
            host.save(tr("Scan") + ".pdf", bytes) { saved = it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopRow(tr("Scan"), guardBack(items.isNotEmpty() && saved == null, back))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { host.takePhoto { add(it) } }, enabled = !worker.busy, modifier = Modifier.testTag("takePhoto")) { Text(tr("Take a photo")) }
            OutlinedButton(onClick = { host.pickPictures { add(it) } }, enabled = !worker.busy, modifier = Modifier.testTag("add")) { Text(tr("Choose photos")) }
        }
        val cur = items.getOrNull(at)
        if (cur == null) {
            Hint(tr("Take a photo of each page, or choose photos. The pages are straightened and cleaned on this device."))
            Box(Modifier.weight(1f))
        } else {
            Text(tr("Drag the corners to the paper's edges."), Modifier.padding(horizontal = 16.dp).testTag("scanHint"))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                contentAlignment = Alignment.Center) {
                val ratio = (cur.w / cur.h).takeIf { it.isFinite() && it > 0.05f } ?: 0.75f
                Corners(cur, minOf(maxWidth, maxHeight * ratio), ratio)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((l, label) in listOf(ScanLook.DOCUMENT to tr("Document"), ScanLook.GREY to tr("Grey"), ScanLook.COLOUR to tr("Colour"))) {
                    FilterChip(selected = cur.look == l, onClick = { cur.look = l }, label = { Text(label) },
                        modifier = Modifier.padding(horizontal = 4.dp).testTag("look${l.name}"))
                }
                TextButton(onClick = { cur.whole() }, modifier = Modifier.testTag("wholePhoto")) { Text(tr("Whole photo")) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { items.add(at - 1, items.removeAt(at)); at--; saved = null }, enabled = at > 0, modifier = Modifier.testTag("earlier")) { Text(tr("Move up")) }
                TextButton(onClick = { items.add(at + 1, items.removeAt(at)); at++; saved = null }, enabled = at < items.size - 1, modifier = Modifier.testTag("later")) { Text(tr("Move down")) }
                TextButton(onClick = { items.removeAt(at); at = at.coerceAtMost(items.size - 1).coerceAtLeast(0); saved = null }, modifier = Modifier.testTag("removeScan")) { Text(tr("Remove")) }
            }
            LazyRow(Modifier.fillMaxWidth().height(72.dp).testTag("scanPages"), horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                itemsIndexed(items) { i, it ->
                    Box(Modifier.height(64.dp).aspectRatio((it.w / it.h).coerceIn(0.3f, 3f)).border(if (i == at) 2.dp else 1.dp,
                        if (i == at) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                        .clickable { at = i }.semantics { contentDescription = tr("Page {n} of {total}", "n" to i + 1, "total" to items.size) }.testTag("scanPage-${i + 1}")) {
                        Image(it.preview, null, Modifier.fillMaxSize())
                    }
                }
            }
        }
        Text(tr("Page size"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        Choice(listOf(Triple(Paper.A4, tr("A4"), ""), Triple(Paper.LETTER, tr("Letter"), ""), Triple(Paper.FIT, tr("Same as the picture"), "")), paper, "paper") { paper = it }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(onClick = { save() }, enabled = items.isNotEmpty() && !worker.busy, modifier = Modifier.testTag("make")) { Text(tr("Save as PDF")) }
        }
        SavedRow(host, saved)
    }
}

/** The photo with the paper's outline and four round handles to drag (photo pixels kept in the item). */
@Composable
private fun Corners(item: ScanItem, shown: androidx.compose.ui.unit.Dp, ratio: Float) {
    var held by remember(item) { mutableStateOf(-1) }
    // where the finger went down: the corner nearest to it is the one picked up
    var downAt by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.width(shown).aspectRatio(ratio).testTag("scanPhoto")
        .pointerInput(Unit) {
            awaitEachGesture { downAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position }
        }
        .pointerInput(item) {
            val k = { size.width / item.w }
            detectDragGestures(
                onDragStart = { _ ->
                    val o = downAt
                    // the nearest corner within a finger's reach (48 dp)
                    val near = item.corners.withIndex().minByOrNull { (_, c) -> (c * k() - o).getDistance() }
                    held = if (near != null && (near.value * k() - o).getDistance() < 48.dp.toPx()) near.index else -1
                },
                onDrag = { change, amount ->
                    val i = held
                    if (i >= 0) {
                        change.consume()
                        val c = item.corners[i] + amount / k()
                        item.corners[i] = Offset(c.x.coerceIn(0f, item.w), c.y.coerceIn(0f, item.h))
                    }
                },
                onDragEnd = { held = -1 },
                onDragCancel = { held = -1 },
            )
        }) {
        Image(item.preview, tr("The photo"), Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / item.w
            val pts = item.corners.map { it * k }
            if (pts.size == 4) {
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (p in pts.drop(1)) lineTo(p.x, p.y); close() }
                drawPath(path, EDGE.copy(alpha = 0.18f))
                drawPath(path, EDGE, style = Stroke(width = 2.dp.toPx()))
                for (p in pts) {
                    drawCircle(Color.White, 12.dp.toPx(), p)
                    drawCircle(EDGE, 12.dp.toPx(), p, style = Stroke(width = 3.dp.toPx()))
                }
            }
        }
    }
}
