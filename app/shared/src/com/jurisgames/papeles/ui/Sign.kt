package com.jurisgames.papeles.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jurisgames.papeles.core.Area
import com.jurisgames.papeles.core.InkLine
import com.jurisgames.papeles.core.InkPoint
import com.jurisgames.papeles.core.PageBox
import com.jurisgames.papeles.core.PdfInput
import com.jurisgames.papeles.core.StampAt
import com.jurisgames.papeles.core.inkPicture
import com.jurisgames.papeles.core.stampPictures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The pen of the signature pad, in pad units (dp); the ink picture uses the same width. */
const val PEN = 3f
/** A placed signature starts this wide on the page (points: about 5.6 cm) and can be made smaller or bigger. */
const val SIGN_WIDTH = 160f
val SIGN_WIDTHS = listOf(80f, 120f, 160f, 220f, 300f)
private val INK = Color(0xFF1A237E)

/** A drawn signature: lines of points (pad units, y down). Kept in the app's own files, on this device only. */
typealias Ink = List<List<Offset>>

object SignatureStore {
    private fun file(dir: File) = File(dir, "signature.txt")

    fun load(dir: File): Ink? = runCatching {
        file(dir).readLines().filter { it.isNotBlank() }.map { line ->
            line.split(';').mapNotNull { p -> p.split(',').takeIf { it.size == 2 }?.let { (x, y) -> Offset(x.toFloat(), y.toFloat()) } }
        }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun save(dir: File, ink: Ink) {
        dir.mkdirs()
        file(dir).writeText(ink.joinToString("\n") { l -> l.joinToString(";") { "${it.x},${it.y}" } })
    }

    fun forget(dir: File) {
        file(dir).delete()
    }
}

/** The drawing's box with the room the ink picture keeps around it (pen x 1.5), as width / height. */
private fun inkBox(ink: Ink): FloatArray {
    val pts = ink.flatten()
    val pad = PEN * 1.5f
    val x0 = pts.minOf { it.x } - pad
    val y0 = pts.minOf { it.y } - pad
    val x1 = pts.maxOf { it.x } + pad
    val y1 = pts.maxOf { it.y } + pad
    return floatArrayOf(x0, y0, maxOf(x1 - x0, 1f), maxOf(y1 - y0, 1f))
}

/** Where a signature is placed: page from 1, area in points from the page's top left as shown. */
private data class Placed(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Sign (plan M4): draw a signature once (kept on this device), then tap the pages where it goes; a copy with it is
 * saved, the open file never changes. Not a digital signature: a picture of one, as on paper.
 */
@Composable
fun SignScreen(doc: OpenDoc, host: Host, worker: Worker, back: () -> Unit) {
    var ink by remember { mutableStateOf(SignatureStore.load(host.appFiles)) }
    var drawing by remember { mutableStateOf(ink == null) }
    var width by remember { mutableStateOf(SIGN_WIDTH) }
    val placed = remember(doc) { mutableStateListOf<Placed>() }
    var saved by remember { mutableStateOf<Picked?>(null) }
    val sizes = remember(doc) { HashMap<Int, PageBox>() }

    fun place(page: Int, cx: Float, cy: Float) {
        val i = ink ?: return
        val b = inkBox(i)
        val w = width
        val h = w * b[3] / b[2]
        val box = sizes[page] ?: return
        placed += Placed(page, (cx - w / 2).coerceIn(0f, maxOf(0f, box.width - w)), (cy - h / 2).coerceIn(0f, maxOf(0f, box.height - h)), w, h)
        saved = null
    }

    fun resizeLast(up: Boolean) {
        val last = placed.lastOrNull() ?: return
        val next = if (up) SIGN_WIDTHS.firstOrNull { it > last.w + 0.5f } else SIGN_WIDTHS.lastOrNull { it < last.w - 0.5f }
        next ?: return
        width = next
        val k = next / last.w
        val cx = last.x + last.w / 2
        val cy = last.y + last.h / 2
        placed[placed.size - 1] = last.copy(x = cx - last.w * k / 2, y = cy - last.h * k / 2, w = last.w * k, h = last.h * k)
        saved = null
    }

    fun save() {
        val i = ink ?: return
        val lines = i.map { l -> InkLine(l.map { InkPoint(it.x, it.y) }) }
        val at = placed.map { StampAt(it.page.toUInt(), Area(it.x, it.y, it.w, it.h)) }
        worker.run({ stampPictures(PdfInput(doc.bytes, doc.password), at, inkPicture(lines, PEN)) }) { bytes ->
            host.save(nameWith(doc.name, tr("signed")), bytes) { saved = it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopRow(tr("Sign: {name}", "name" to doc.name), guardBack(placed.isNotEmpty() && saved == null, back))
        Text(if (placed.isEmpty()) tr("Tap the page where your signature goes.") else tr("{n} placed. Tap again to add another.", "n" to placed.size),
            Modifier.padding(horizontal = 16.dp).testTag("signHint"))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { drawing = true }, modifier = Modifier.testTag("redraw")) { Text(tr("Draw a new signature")) }
            TextButton(onClick = { placed.removeLastOrNull(); saved = null }, enabled = placed.isNotEmpty(), modifier = Modifier.testTag("undoPlace")) { Text(tr("Undo")) }
            TextButton(onClick = { resizeLast(false) }, enabled = placed.isNotEmpty(), modifier = Modifier.testTag("smaller")) { Text(tr("Smaller")) }
            TextButton(onClick = { resizeLast(true) }, enabled = placed.isNotEmpty(), modifier = Modifier.testTag("bigger")) { Text(tr("Bigger")) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
            val pageW = minOf(maxWidth - 16.dp, 1000.dp)
            val px = with(androidx.compose.ui.platform.LocalDensity.current) { pageW.roundToPx() }.coerceIn(256, 2048)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                items(doc.pages) { i ->
                    val n = i + 1
                    val box = sizes.getOrPut(n) { runCatching { doc.file.pageSize(n.toUInt()) }.getOrDefault(PageBox(595f, 842f)) }
                    SignPage(doc, host, n, box, pageW, px, ink, placed.filter { it.page == n }) { x, y -> place(n, x, y) }
                }
            }
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { save() }, enabled = placed.isNotEmpty() && ink != null && !worker.busy, modifier = Modifier.testTag("make")) {
                Text(tr("Save signed copy"))
            }
        }
        Text(tr("This is a picture of your signature, as on paper; it is not a digital signature."),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        SavedRow(host, saved)
    }
    if (drawing) SignaturePad(
        onCancel = { drawing = false; if (ink == null) back() },
        onDone = { new -> SignatureStore.save(host.appFiles, new); ink = new; drawing = false },
    )
}

@Composable
private fun SignPage(doc: OpenDoc, host: Host, n: Int, box: PageBox, shown: androidx.compose.ui.unit.Dp, px: Int, ink: Ink?,
                     here: List<Placed>, tap: (Float, Float) -> Unit) {
    var img by remember(doc, n, px) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(doc, n, px) {
        img = withContext(Dispatchers.IO) { runCatching { host.image(doc.file.render(n.toUInt(), px)) }.getOrNull() }
    }
    val ratio = (box.width / box.height).takeIf { it.isFinite() && it > 0.05f && it < 20f } ?: 0.707f
    Box(Modifier.width(shown).aspectRatio(ratio).background(Color.White)
        .pointerInput(n, box) { detectTapGestures { o -> tap(o.x / size.width * box.width, o.y / size.height * box.height) } }
        .semantics { contentDescription = tr("Page {n} of {total}", "n" to n, "total" to doc.pages) }.testTag("signPage-$n")) {
        img?.let { Image(it, null, Modifier.fillMaxSize()) }
        if (ink != null && here.isNotEmpty()) {
            val b = remember(ink) { inkBox(ink) }
            Canvas(Modifier.fillMaxSize()) {
                val s = size.width / box.width
                for (p in here) {
                    val dst = androidx.compose.ui.geometry.Size(p.w * s, p.h * s)
                    translate(p.x * s, p.y * s) {
                        val ds = this
                        drawRect(Color(0x332F5FC4), size = dst)
                        clipRect(0f, 0f, dst.width, dst.height) {
                            val k = minOf(dst.width / b[2], dst.height / b[3])
                            for (line in ink) {
                                val path = Path()
                                line.forEachIndexed { i, q ->
                                    val x = (q.x - b[0]) * k
                                    val y = (q.y - b[1]) * k
                                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                ds.drawPath(path, INK, style = Stroke(width = PEN * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Draw a signature with a finger or the mouse; Clear starts again. */
@Composable
fun SignaturePad(onCancel: () -> Unit, onDone: (Ink) -> Unit) {
    val lines = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // where the pen went down: a stroke is only known once it has moved a little, and it starts here
    var downAt by remember { mutableStateOf(Offset.Zero) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(tr("Draw your signature")) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(180.dp).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outline)
                    .pointerInput(Unit) {
                        awaitEachGesture { downAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { o -> current = listOf(Offset(downAt.x / density, downAt.y / density), Offset(o.x / density, o.y / density)) },
                            onDrag = { change, _ -> current = current + Offset(change.position.x / density, change.position.y / density) },
                            onDragEnd = { if (current.isNotEmpty()) lines += current; current = emptyList() },
                            onDragCancel = { current = emptyList() },
                        )
                    }.testTag("pad")) {
                    Canvas(Modifier.fillMaxSize()) {
                        for (line in lines + listOf(current)) {
                            if (line.isEmpty()) continue
                            val path = Path()
                            line.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * density, p.y * density) else path.lineTo(p.x * density, p.y * density) }
                            drawPath(path, INK, style = Stroke(width = PEN * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
                Text(tr("It stays on this device."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onDone(lines.toList()) }, enabled = lines.isNotEmpty(), modifier = Modifier.testTag("useSignature")) { Text(tr("Use this signature")) } },
        dismissButton = {
            Row {
                TextButton(onClick = { lines.clear() }, enabled = lines.isNotEmpty(), modifier = Modifier.testTag("clearPad")) { Text(tr("Clear")) }
                TextButton(onClick = onCancel) { Text(tr("Cancel")) }
            }
        },
    )
}
