@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jurisgames.papeles.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jurisgames.papeles.core.Area
import com.jurisgames.papeles.core.InkLine
import com.jurisgames.papeles.core.InkPoint
import com.jurisgames.papeles.core.Mark
import com.jurisgames.papeles.core.PageBox
import com.jurisgames.papeles.core.PdfInput
import com.jurisgames.papeles.core.addMarks
import com.jurisgames.papeles.core.textBoxSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.key

/** Typed text sizes, in points; it starts at 12. */
val TEXT_SIZES = listOf(8f, 10f, 12f, 16f, 20f, 28f)
/** The pen's width in points. */
const val MARK_PEN = 2f
private val MARK_INK = Color(0xFF1A237E)
private val MARK_YELLOW = Color(0xFFFFEB3B)

enum class MarkTool { Text, Highlight, Pen, Select }

/** What is being typed: where it goes on which page (points as shown). */
private data class Typing(val page: Int, val x: Float, val y: Float)

/** U-P15: where a mark sits on its page, in points, to pick it with a tap. */
private fun Mark.bounds(): androidx.compose.ui.geometry.Rect = when (this) {
    is Mark.Text -> {
        val b = runCatching { textBoxSize(text, size) }.getOrNull()
        androidx.compose.ui.geometry.Rect(x, y, x + (b?.getOrNull(0) ?: (size * 0.6f * text.length)), y + (b?.getOrNull(1) ?: (size * 1.4f)))
    }
    is Mark.Highlight -> if (areas.isEmpty()) androidx.compose.ui.geometry.Rect.Zero else
        androidx.compose.ui.geometry.Rect(areas.minOf { it.x }, areas.minOf { it.y }, areas.maxOf { it.x + it.w }, areas.maxOf { it.y + it.h })
    is Mark.Pen -> lines.flatMap { it.points }.let { ps ->
        if (ps.isEmpty()) androidx.compose.ui.geometry.Rect.Zero else
            androidx.compose.ui.geometry.Rect(ps.minOf { it.x } - pen, ps.minOf { it.y } - pen, ps.maxOf { it.x } + pen, ps.maxOf { it.y } + pen)
    }
}

/** U-P15: the same mark moved by (dx, dy) points. */
private fun Mark.moved(dx: Float, dy: Float): Mark = when (this) {
    is Mark.Text -> Mark.Text(page, x + dx, y + dy, text, size)
    is Mark.Highlight -> Mark.Highlight(page, areas.map { com.jurisgames.papeles.core.Area(it.x + dx, it.y + dy, it.w, it.h) })
    is Mark.Pen -> Mark.Pen(page, lines.map { l -> com.jurisgames.papeles.core.InkLine(l.points.map { com.jurisgames.papeles.core.InkPoint(it.x + dx, it.y + dy) }) }, pen)
}

private fun Mark.page(): Int = when (this) {
    is Mark.Text -> page.toInt()
    is Mark.Highlight -> page.toInt()
    is Mark.Pen -> page.toInt()
}

/**
 * Mark (plan M4): type text, highlight, or write with a pen on the pages, one page at a time, then save a copy;
 * the open file never changes. The marks are standard PDF notes, so other readers show them and can still remove
 * them.
 */
@Composable
fun MarkScreen(doc: OpenDoc, host: Host, worker: Worker, back: () -> Unit) {
    var tool by remember { mutableStateOf(MarkTool.Text) }
    var size by remember { mutableStateOf(12f) }
    var page by remember(doc) { mutableStateOf(1) }
    val marks = remember(doc) { mutableStateListOf<Mark>() }
    var typing by remember { mutableStateOf<Typing?>(null) }
    // U-P15: the mark picked with Select (its place in marks), and a text taken out to be edited
    var picked by remember(doc) { mutableStateOf<Int?>(null) }
    // U-P15: zoom (buttons, or two fingers); two fingers also move around the zoomed page
    var zoom by remember(doc) { mutableStateOf(1f) }
    val across = androidx.compose.foundation.rememberScrollState()
    val down = androidx.compose.foundation.rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var editing by remember(doc) { mutableStateOf<Mark.Text?>(null) }
    var saved by remember { mutableStateOf<Picked?>(null) }
    val sizes = remember(doc) { HashMap<Int, PageBox>() }
    val box = sizes.getOrPut(page) { runCatching { doc.file.pageSize(page.toUInt()) }.getOrDefault(PageBox(595f, 842f)) }

    fun add(m: Mark) {
        marks += m
        saved = null
    }

    fun place(t: Typing, text: String) {
        val b = runCatching { textBoxSize(text, size) }.getOrNull()
        val bx = sizes[t.page] ?: box
        // kept on the page: the box moves in from the right and bottom edges
        val x = if (b != null) t.x.coerceIn(0f, maxOf(0f, bx.width - b[0])) else t.x
        val y = if (b != null) t.y.coerceIn(0f, maxOf(0f, bx.height - b[1])) else t.y
        add(Mark.Text(t.page.toUInt(), x, y, text, size))
        typing = null
        editing = null
    }

    /** U-P15: the topmost mark on this page under (x, y) points, a little slack around it */
    fun pickAt(x: Float, y: Float): Int? = marks.indices.lastOrNull { i ->
        marks[i].page() == page && marks[i].bounds().inflate(6f).contains(Offset(x, y))
    }

    fun save() {
        val all = marks.toList()
        worker.run({ addMarks(PdfInput(doc.bytes, doc.password), all) }, tr("Adding the marks…")) { bytes ->
            host.save(nameWith(doc.name, tr("marked")), bytes) { saved = it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val leave = guardBack(marks.isNotEmpty() && saved == null, back)
        TopRow(tr("Mark: {name}", "name" to doc.name), if (typing != null) ({ editing?.let { marks += it }; editing = null; typing = null }) else leave) // U-P22: Back closes the text bar first
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { // four tools + Undo wrap on a small phone
            for ((t, label) in listOf(MarkTool.Text to tr("Text"), MarkTool.Highlight to tr("Highlight"), MarkTool.Pen to tr("Pen"), MarkTool.Select to tr("Select"))) {
                FilterChip(selected = tool == t, onClick = { tool = t; picked = null }, label = { Text(label) },
                    modifier = Modifier.padding(horizontal = 4.dp).testTag("tool${t.name}"))
            }
            TextButton(onClick = { marks.removeLastOrNull(); picked = null; saved = null }, enabled = marks.isNotEmpty(), modifier = Modifier.testTag("undoMark")) {
                Text(tr("Undo"))
            }
        }
        Text(when (tool) {
            MarkTool.Text -> tr("Tap where the text goes.")
            MarkTool.Highlight -> tr("Drag over what to highlight.")
            MarkTool.Pen -> tr("Write or draw with your finger or the mouse.")
            MarkTool.Select -> tr("Tap a mark to pick it; drag it to move it.")
        }, Modifier.padding(horizontal = 16.dp).testTag("markHint"))
        // U-P15: what can be done with the picked mark
        picked?.let { i -> marks.getOrNull(i) }?.let { m ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m is Mark.Text) TextButton(onClick = {
                    marks.removeAt(picked!!); picked = null; editing = m; size = m.size; typing = Typing(m.page.toInt(), m.x, m.y); saved = null
                }, modifier = Modifier.testTag("editMark")) { Text(tr("Edit")) }
                TextButton(onClick = { marks.removeAt(picked!!); picked = null; saved = null }, modifier = Modifier.testTag("deleteMark")) { Text(tr("Delete")) }
            }
        }
        typing?.let { t ->
            key(t, editing) {
                TextBar(size, onSize = { size = it }, onCancel = { editing?.let { marks += it }; editing = null; typing = null }, start = editing?.text ?: "") { place(t, it) }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
            contentAlignment = Alignment.Center) {
            val ratio = (box.width / box.height).takeIf { it.isFinite() && it > 0.05f && it < 20f } ?: 0.707f
            // the whole page fits: as wide as there is room, or as tall
            val shown = minOf(maxWidth, maxHeight * ratio) * zoom
            val px = with(LocalDensity.current) { shown.roundToPx() }.coerceIn(256, 2048)
            Box(Modifier.fillMaxSize().then(if (zoom > 1.01f) Modifier.horizontalScroll(across).verticalScroll(down) else Modifier) // scrolls only when zoomed (APP-RULES 196)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.changes.count { it.pressed } >= 2) {
                                val z = e.calculateZoom()
                                val pan = e.calculatePan()
                                zoom = (zoom * z).coerceIn(ZOOMS.first(), ZOOMS.last())
                                scope.launch { across.scrollBy(-pan.x); down.scrollBy(-pan.y) }
                                e.changes.forEach { it.consume() }
                            }
                        } while (e.changes.any { it.pressed })
                    }
                }, contentAlignment = Alignment.Center) {
            MarkPage(doc, host, page, box, shown, ratio, px, tool, marks.filter { it.page() == page }, add = ::add,
                type = { x, y -> typing = Typing(page, x, y) },
                picked = picked?.let { marks.getOrNull(it) },
                pick = { x, y -> picked = pickAt(x, y) },
                move = { dx, dy -> picked?.let { i -> marks.getOrNull(i)?.let { marks[i] = it.moved(dx, dy); saved = null } } })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { page--; picked = null }, enabled = page > 1, modifier = Modifier.testTag("markPrev")) { Text(tr("Previous")) }
            Text(tr("Page {n} of {total}", "n" to page, "total" to doc.pages), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = { page++; picked = null }, enabled = page < doc.pages, modifier = Modifier.testTag("markNext")) { Text(tr("Next")) }
            androidx.compose.material3.IconButton(onClick = { zoom = ZOOMS.lastOrNull { it < zoom - 0.01f } ?: ZOOMS.first() }, enabled = zoom > ZOOMS.first() + 0.01f,
                modifier = Modifier.testTag("markZoomOut")) { androidx.compose.material3.Icon(Ic.ZoomOut, tr("Zoom out")) }
            androidx.compose.material3.IconButton(onClick = { zoom = ZOOMS.firstOrNull { it > zoom + 0.01f } ?: ZOOMS.last() }, enabled = zoom < ZOOMS.last() - 0.01f,
                modifier = Modifier.testTag("markZoomIn")) { androidx.compose.material3.Icon(Ic.ZoomIn, tr("Zoom in")) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { save() }, enabled = marks.isNotEmpty() && !worker.busy, modifier = Modifier.testTag("make")) {
                Text(tr("Save marked copy"))
            }
        }
        SavedRow(host, saved)
    }
}

/** Typing a text: the page stays in view; the text goes where it was tapped once added. */
@Composable
private fun TextBar(size: Float, onSize: (Float) -> Unit, onCancel: () -> Unit, start: String = "", onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(start) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(text, { text = it.take(2000) }, Modifier.fillMaxWidth().testTag("markText"), label = { Text(tr("Add text")) }, minLines = 2)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth()) { // U-P4: wraps instead of breaking words
            TextButton(onClick = { TEXT_SIZES.lastOrNull { it < size }?.let(onSize) }, enabled = size > TEXT_SIZES.first(),
                modifier = Modifier.testTag("textSmaller")) { Text(tr("Smaller")) }
            Text(tr("{n} points", "n" to size.toInt()), Modifier.align(Alignment.CenterVertically).testTag("textSize")) // n>=2: pinned: TEXT_SIZES starts at 8
            TextButton(onClick = { TEXT_SIZES.firstOrNull { it > size }?.let(onSize) }, enabled = size < TEXT_SIZES.last(),
                modifier = Modifier.testTag("textBigger")) { Text(tr("Bigger")) }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("cancelText")) { Text(tr("Cancel")) }
            Button(onClick = { onDone(text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("addText")) { Text(tr("Add")) }
        }
    }
}

@Composable
private fun MarkPage(doc: OpenDoc, host: Host, n: Int, box: PageBox, shown: androidx.compose.ui.unit.Dp, ratio: Float, px: Int, tool: MarkTool,
                     here: List<Mark>, add: (Mark) -> Unit, type: (Float, Float) -> Unit,
                     picked: Mark? = null, pick: (Float, Float) -> Unit = { _, _ -> }, move: (Float, Float) -> Unit = { _, _ -> }) {
    var img by remember(doc, n) { mutableStateOf<ImageBitmap?>(null) } // the old picture stays until the sharper one is ready (223)
    LaunchedEffect(doc, n, px) {
        img = withContext(Dispatchers.IO) { runCatching { host.image(doc.file.render(n.toUInt(), px)) }.getOrNull() }
    }
    // what the finger is doing right now, in page points
    var drag by remember(n, tool) { mutableStateOf<List<Offset>>(emptyList()) }
    // where the finger went down: a drag is only known once it has moved a little, and the mark starts here
    var downAt by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    Box(Modifier.width(shown).aspectRatio(ratio).background(Color.White)
        .pointerInput(Unit) {
            awaitEachGesture { downAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position }
        }
        .pointerInput(n, box, tool) {
            fun pt(o: Offset) = Offset(o.x / size.width * box.width, o.y / size.height * box.height)
            if (tool == MarkTool.Text) {
                detectTapGestures { o -> pt(o).let { type(it.x, it.y) } }
            } else if (tool == MarkTool.Select) {
                // U-P15: a tap picks; a drag picks what is under the finger, then moves it with the finger
                // one loop: a tap picks; past the touch slop it picks what is under the finger and moves it with the finger
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    val start = pt(d.position)
                    var last = d.position
                    var dragging = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == d.id } ?: break
                        if (!c.pressed) { if (!dragging) pick(start.x, start.y); break }
                        if (!dragging && (c.position - d.position).getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                            pick(start.x, start.y)
                        }
                        if (dragging) {
                            val a = pt(last); val b = pt(c.position)
                            move(b.x - a.x, b.y - a.y)
                            last = c.position
                            c.consume()
                        }
                    }
                }
            } else {
                detectDragGestures(
                    onDragStart = { o -> drag = listOf(pt(downAt), pt(o)) },
                    onDrag = { c, _ -> drag = if (tool == MarkTool.Pen) drag + pt(c.position) else listOf(drag.first(), pt(c.position)) },
                    onDragEnd = {
                        val d = drag
                        drag = emptyList()
                        if (tool == MarkTool.Pen && d.isNotEmpty()) {
                            add(Mark.Pen(n.toUInt(), listOf(InkLine(d.map { InkPoint(it.x, it.y) })), MARK_PEN))
                        } else if (d.size == 2) {
                            val (a, b) = d
                            val area = Area(minOf(a.x, b.x), minOf(a.y, b.y), kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                            // U-P33: the words under the box, line by line (a plain box cut words in half); no text there: the box
                            if (area.w > 1f && area.h > 1f) add(Mark.Highlight(n.toUInt(), runCatching { doc.file.linesIn(n.toUInt(), area) }.getOrDefault(emptyList()).ifEmpty { listOf(area) }))
                        }
                    },
                    onDragCancel = { drag = emptyList() },
                )
            }
        }
        .semantics { contentDescription = tr("Page {n} of {total}", "n" to n, "total" to doc.pages) }.testTag("markPage-$n")) {
        img?.let { Image(it, null, Modifier.fillMaxSize()) }
        Canvas(Modifier.fillMaxSize()) {
            val s = size.width / box.width
            fun line(points: List<Offset>, width: Float) {
                if (points.isEmpty()) return
                val path = Path()
                points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * s, p.y * s) else path.lineTo(p.x * s, p.y * s) }
                if (points.size == 1) path.lineTo(points[0].x * s + 0.01f, points[0].y * s)
                drawPath(path, MARK_INK, style = Stroke(width = width * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            for (m in here) when (m) {
                is Mark.Highlight -> for (a in m.areas) drawRect(MARK_YELLOW, Offset(a.x * s, a.y * s), Size(a.w * s, a.h * s), blendMode = BlendMode.Multiply)
                is Mark.Pen -> for (l in m.lines) line(l.points.map { Offset(it.x, it.y) }, m.pen)
                is Mark.Text -> drawText(measurer, m.text, Offset((m.x + m.size * 0.25f) * s, (m.y + m.size * 0.25f) * s),
                    TextStyle(color = Color.Black, fontFamily = FontFamily.SansSerif, fontSize = (m.size * s / density).sp, lineHeight = (m.size * 1.2f * s / density).sp))
            }
            // U-P15: the picked mark is outlined
            picked?.bounds()?.inflate(3f)?.let { r ->
                drawRect(Color(0xFF1565C0), Offset(r.left * s, r.top * s), Size(r.width * s, r.height * s), style = Stroke(width = 2.dp.toPx()))
            }
            if (drag.isNotEmpty()) when (tool) {
                MarkTool.Pen -> line(drag, MARK_PEN)
                MarkTool.Highlight -> if (drag.size == 2) {
                    val (a, b) = drag
                    drawRect(MARK_YELLOW.copy(alpha = 0.6f), Offset(minOf(a.x, b.x) * s, minOf(a.y, b.y) * s),
                        Size(kotlin.math.abs(a.x - b.x) * s, kotlin.math.abs(a.y - b.y) * s), blendMode = BlendMode.Multiply)
                }
                MarkTool.Text, MarkTool.Select -> {}
            }
        }
    }
}
