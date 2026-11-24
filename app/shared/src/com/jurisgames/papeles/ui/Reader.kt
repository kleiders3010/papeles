package com.jurisgames.papeles.ui

import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jurisgames.papeles.core.Area
import com.jurisgames.papeles.core.Hit
import com.jurisgames.papeles.core.PageBox
import com.jurisgames.papeles.core.PdfInput
import com.jurisgames.papeles.core.formFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Page pictures are drawn in steps of this many pixels wide, so a small resize doesn't redraw every page. */
private const val STEP = 256

/** Widest page picture the reader asks for: sharp on a big screen, small enough for a phone's memory. */
private const val MAX_WIDTH = 2048

/** Zoomed in, pages are drawn up to this wide: sharper text, still one or two pages' worth of a phone's memory. */
private const val MAX_ZOOMED_WIDTH = 2560

/** The zoom steps of the buttons (a pinch goes anywhere between the ends). */
val ZOOMS = listOf(1f, 1.5f, 2f, 3f, 4f)

/** Pages are at most this wide at 1x, so a big screen doesn't show one huge page. */
private val FIT_MAX = 1000.dp

/** Most search hits gathered at once. */
private const val MAX_HITS = 500u

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Reader(doc: OpenDoc, host: Host, worker: Worker, back: () -> Unit, editPages: () -> Unit, fill: () -> Unit = {}, sign: () -> Unit = {}, mark: () -> Unit = {}) {
    // Fill shows only for a PDF with fields to fill (read once, off the main thread)
    var hasForm by remember(doc) { mutableStateOf(false) }
    LaunchedEffect(doc) {
        hasForm = withContext(Dispatchers.IO) { runCatching { fillable(formFields(PdfInput(doc.bytes, doc.password))) }.getOrDefault(false) }
    }
    // opens where it was last read (U-P11), and remembers the page as it changes
    val list = rememberLazyListState(initialFirstVisibleItemIndex = remember(doc) { (LastPage.get(host.appFiles, doc.key) - 1).coerceIn(0, maxOf(0, doc.pages - 1)) })
    LaunchedEffect(doc) {
        snapshotFlow { list.firstVisibleItemIndex + 1 }.collect { withContext(Dispatchers.IO) { LastPage.set(host.appFiles, doc.key, it) } }
    }
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>?>(null) }
    var dark by remember { mutableStateOf(DarkPages.get(host.appFiles)) } // U-P30
    var at by remember { mutableStateOf(0) }
    val sizes = remember(doc) { HashMap<Int, PageBox>() }
    var zoom by remember(doc) { mutableStateOf(1f) }
    fun zoomBy(up: Boolean) {
        zoom = if (up) ZOOMS.firstOrNull { it > zoom + 0.01f } ?: ZOOMS.last() else ZOOMS.lastOrNull { it < zoom - 0.01f } ?: ZOOMS.first()
    }

    // U-P21: Ctrl+F, Page Up / Down and Ctrl+plus / minus from a computer's keyboard
    LaunchedEffect(doc) {
        Keys.commands.collect { c ->
            when (c) {
                KeyCommand.Find -> searching = true
                KeyCommand.NextPage -> list.animateScrollToItem((list.firstVisibleItemIndex + 1).coerceAtMost(maxOf(0, doc.pages - 1)))
                KeyCommand.PrevPage -> list.animateScrollToItem((list.firstVisibleItemIndex - 1).coerceAtLeast(0))
                KeyCommand.ZoomIn -> zoomBy(true)
                KeyCommand.ZoomOut -> zoomBy(false)
            }
        }
    }

    fun show(i: Int) {
        val h = hits ?: return
        if (h.isEmpty()) return
        at = (i + h.size) % h.size
        scope.launch { list.animateScrollToItem(h[at].page.toInt() - 1) }
    }

    fun find() {
        val q = query.trim()
        if (q.isEmpty()) return
        worker.run({ doc.file.search(q, MAX_HITS) }, tr("Searching…")) { hits = it; show(0) }
    }

    Column(Modifier.fillMaxSize()) {
        TopRow(doc.name, if (searching) ({ searching = false; hits = null; query = "" }) else back) { // U-P22: Back closes the search first
            IconButton(onClick = { searching = !searching; if (!searching) { hits = null; query = "" } }, modifier = Modifier.testTag("search")) {
                if (searching) Icon(Ic.Close, tr("Close search")) else Icon(Ic.Search, tr("Search in this PDF"))
            }
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.testTag("more")) { Icon(Ic.More, tr("More")) }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text(tr("Share")) }, onClick = { more = false; host.share(Picked(doc.name, doc.key) { doc.bytes }); host.shareNote?.let { worker.say(it) } },
                        modifier = Modifier.testTag("shareDoc"))
                    DropdownMenuItem(text = { Text(if (dark) tr("Light pages") else tr("Dark pages")) },
                        onClick = { more = false; dark = !dark; DarkPages.set(host.appFiles, dark) }, modifier = Modifier.testTag("darkPages"))
                    // U-P8 / APP-RULE 222: Print and Open in another app next to Share
                    if (host.canPrint) DropdownMenuItem(text = { Text(tr("Print")) }, onClick = { more = false; host.print(Picked(doc.name, doc.key) { doc.bytes }) },
                        modifier = Modifier.testTag("printDoc"))
                    DropdownMenuItem(text = { Text(tr("Open in another app")) }, onClick = { more = false; host.openWith(Picked(doc.name, doc.key) { doc.bytes }) },
                        modifier = Modifier.testTag("openWith"))
                }
            }
        }
        // U-P2: what can be done with the file, in words (four word-less icons before; Fill only on forms)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("tools"),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (hasForm) Tool(Ic.Fill, tr("Fill in the form"), "fill", fill)
            Tool(Ic.Sign, tr("Sign"), "sign", sign)
            Tool(Ic.Mark, tr("Mark up"), "mark", mark)
            Tool(Ic.Pages, tr("Edit pages"), "editPages", editPages)
        }
        if (searching) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it; hits = null }, Modifier.weight(1f).testTag("searchField"), singleLine = true,
                    placeholder = { Text(tr("Search in this PDF")) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { find() }))
                IconButton(onClick = { find() }, modifier = Modifier.testTag("searchGo")) { Icon(Ic.Search, tr("Search")) }
            }
            hits?.let { h ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (h.isEmpty()) tr("Not found") else (if (h.size >= MAX_HITS.toInt()) tr("{n} found", "n" to "${h.size}+") else plural(h.size, "1 found", "{n} found")) +
                        (if (h.isEmpty()) "" else " · ${at + 1}/${h.size}"), Modifier.weight(1f).testTag("hitCount"))
                    IconButton(onClick = { show(at - 1) }, enabled = h.isNotEmpty(), modifier = Modifier.testTag("hitPrev")) { Icon(Ic.Up, tr("Previous")) }
                    IconButton(onClick = { show(at + 1) }, enabled = h.isNotEmpty(), modifier = Modifier.testTag("hitNext")) { Icon(Ic.Down, tr("Next")) }
                }
            }
        }
        // a pinch zooms (one finger still scrolls); wider than the screen, the pages scroll sideways too
        val pinch = rememberTransformableState { change, _, _ -> zoom = (zoom * change).coerceIn(ZOOMS.first(), ZOOMS.last()) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .transformable(pinch, canPan = { false })) {
            val density = LocalDensity.current
            val fit = minOf(maxWidth - 16.dp, FIT_MAX)
            val pageW = fit * zoom
            val px = with(density) { pageW.roundToPx() }
            val bucket = (((px + STEP - 1) / STEP) * STEP).coerceIn(STEP, if (zoom > 1.01f) MAX_ZOOMED_WIDTH else MAX_WIDTH)
            val current = hits?.getOrNull(at)
            val sideways = rememberScrollState()
            val listW = maxOf(maxWidth, pageW + 16.dp)
            Box(Modifier.fillMaxSize().horizontalScroll(sideways, enabled = pageW + 16.dp > maxWidth)) {
                LazyColumn(Modifier.width(listW).fillMaxHeight().testTag("pages"), state = list, contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    items(doc.pages) { i ->
                        val n = i + 1
                        val box = sizes.getOrPut(n) { runCatching { doc.file.pageSize(n.toUInt()) }.getOrDefault(PageBox(595f, 842f)) }
                        val marks = hits?.filter { it.page.toInt() == n }.orEmpty()
                        PageView(doc, host, n, box, pageW, bucket, marks, current?.takeIf { it.page.toInt() == n }, dark)
                    }
                }
            }
        }
        val first by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
        // every page small along the bottom (plan M1): a tap goes to it; the one being read is outlined
        if (doc.pages > 1) {
            val strip = rememberLazyListState()
            LaunchedEffect(first) {
                val seen = strip.layoutInfo.visibleItemsInfo.map { it.index }
                if (first - 1 !in seen.drop(1).dropLast(1)) strip.animateScrollToItem(maxOf(0, first - 2))
            }
            LazyRow(Modifier.fillMaxWidth().height(THUMB_H + 12.dp).testTag("thumbs"), state = strip, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                items(doc.pages) { i ->
                    val n = i + 1
                    val box = sizes.getOrPut(n) { runCatching { doc.file.pageSize(n.toUInt()) }.getOrDefault(PageBox(595f, 842f)) }
                    PageThumb(doc, host, n, box, n == first) { scope.launch { list.animateScrollToItem(i) } }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                androidx.compose.material3.TextButton(onClick = { goTo = true }, enabled = doc.pages > 1, modifier = Modifier.testTag("pageOf")) {
                    Text(tr("Page {n} of {total}", "n" to first, "total" to doc.pages), style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(onClick = { zoomBy(false) }, enabled = zoom > ZOOMS.first() + 0.01f, modifier = Modifier.testTag("zoomOut")) { Icon(Ic.ZoomOut, tr("Zoom out")) }
            Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("zoomLevel"))
            IconButton(onClick = { zoomBy(true) }, enabled = zoom < ZOOMS.last() - 0.01f, modifier = Modifier.testTag("zoomIn")) { Icon(Ic.ZoomIn, tr("Zoom in")) }
        }
    }
    if (goTo) GoToPage(doc.pages, onClose = { goTo = false }) { n -> goTo = false; scope.launch { list.scrollToItem(n - 1) } }
}

/** "Go to page": a number from 1 to [pages]; Go is greyed until it is one (U-P12). */
@Composable
private fun GoToPage(pages: Int, onClose: () -> Unit, go: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val n = text.trim().toIntOrNull()?.takeIf { it in 1..pages }
    androidx.compose.material3.AlertDialog(onDismissRequest = onClose, title = { Text(tr("Go to page")) },
        text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit).take(6) }, Modifier.testTag("pageNumber"), singleLine = true,
                label = { Text(tr("Page (1 to {total})", "total" to pages)) },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { n?.let(go) }))
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { n?.let(go) }, enabled = n != null, modifier = Modifier.testTag("goPage")) { Text(tr("Go")) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text(tr("Cancel")) } })
}

/** A tool in the reader's row: its icon and its name (U-P2). */
@Composable
private fun Tool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Icon(icon, null, Modifier.padding(end = 6.dp))
        Text(label, maxLines = 1)
    }
}

/** How tall a page is in the strip of small pages. */
private val THUMB_H = 64.dp

@Composable
private fun PageThumb(doc: OpenDoc, host: Host, n: Int, box: PageBox, current: Boolean, go: () -> Unit) {
    var img by remember(doc, n) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(doc, n) {
        img = withContext(Dispatchers.IO) { runCatching { host.image(doc.file.render(n.toUInt(), 96)) }.getOrNull() }
    }
    val ratio = (box.width / box.height).takeIf { it.isFinite() && it > 0.2f && it < 5f } ?: 0.707f
    val edge = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    // the whole small page is the button: at least 48 dp wide even for a tall narrow page
    Box(Modifier.height(THUMB_H).widthIn(min = 48.dp).clickable(onClick = go)
        .semantics { contentDescription = tr("Go to page {n}", "n" to n) }.testTag("thumb-$n"), contentAlignment = Alignment.Center) {
        Box(Modifier.height(THUMB_H).aspectRatio(ratio).background(Color.White).border(if (current) 2.dp else 1.dp, edge)) {
            img?.let { Image(it, null, Modifier.fillMaxSize()) }
        }
    }
}

/** U-P30: white paper becomes black, black ink white (offsets are 0-255) */
private val INVERT = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f)))

@Composable
private fun PageView(doc: OpenDoc, host: Host, n: Int, box: PageBox, shown: Dp, width: Int, marks: List<Hit>, current: Hit?, dark: Boolean = false) {
    // U-P10: the picture is kept across a zoom step (not keyed on width) and stretched until the sharper one is ready;
    // keyed on width, each 256 px step dropped it and the page flashed white many times during one pinch
    var img by remember(doc, n) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(doc, n, width) {
        img = withContext(Dispatchers.IO) { runCatching { host.image(doc.file.render(n.toUInt(), width)) }.getOrNull() }
    }
    val ratio = (box.width / box.height).takeIf { it.isFinite() && it > 0.05f && it < 20f } ?: 0.707f
    Box(Modifier.width(shown).aspectRatio(ratio).background(if (dark) Color.Black else Color.White)
        .semantics { contentDescription = tr("Page {n} of {total}", "n" to n, "total" to doc.pages) }.testTag("page-$n")) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), colorFilter = if (dark) INVERT else null) }
        if (marks.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
            val s = size.width / box.width
            for (h in marks) for (a: Area in h.areas) {
                val color = if (h === current) Color(0x99FF9800) else Color(0x66FFEB3B)
                drawRect(color, Offset(a.x * s, a.y * s), Size(a.w * s, a.h * s))
                // U-P32: the current hit is outlined too, not told apart by colour alone
                if (h === current) drawRect(Color(0xFFD84315), Offset(a.x * s - 2f, a.y * s - 2f), Size(a.w * s + 4f, a.h * s + 4f), style = Stroke(3f))
            }
        }
    }
}
