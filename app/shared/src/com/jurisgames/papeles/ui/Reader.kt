package com.jurisgames.papeles.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jurisgames.papeles.core.Area
import com.jurisgames.papeles.core.Hit
import com.jurisgames.papeles.core.PageBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Page pictures are drawn in steps of this many pixels wide, so a small resize doesn't redraw every page. */
private const val STEP = 256

/** Widest page picture the reader asks for: sharp on a big screen, small enough for a phone's memory. */
private const val MAX_WIDTH = 2048

/** Most search hits gathered at once. */
private const val MAX_HITS = 500u

@Composable
fun Reader(doc: OpenDoc, host: Host, worker: Worker, back: () -> Unit, editPages: () -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>?>(null) }
    var at by remember { mutableStateOf(0) }
    val sizes = remember(doc) { HashMap<Int, PageBox>() }

    fun show(i: Int) {
        val h = hits ?: return
        if (h.isEmpty()) return
        at = (i + h.size) % h.size
        scope.launch { list.animateScrollToItem(h[at].page.toInt() - 1) }
    }

    fun find() {
        val q = query.trim()
        if (q.isEmpty()) return
        worker.run({ doc.file.search(q, MAX_HITS) }) { hits = it; show(0) }
    }

    Column(Modifier.fillMaxSize()) {
        TopRow(doc.name, back) {
            IconButton(onClick = { searching = !searching; if (!searching) { hits = null; query = "" } }, modifier = Modifier.testTag("search")) {
                Icon(Ic.Search, tr("Search in this PDF"))
            }
            IconButton(onClick = editPages, modifier = Modifier.testTag("editPages")) { Icon(Ic.Pages, tr("Edit pages")) }
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
                    Text(if (h.isEmpty()) tr("Not found") else tr("{n} found", "n" to (if (h.size >= MAX_HITS.toInt()) "${h.size}+" else h.size)) +
                        (if (h.isEmpty()) "" else " · ${at + 1}/${h.size}"), Modifier.weight(1f).testTag("hitCount"))
                    IconButton(onClick = { show(at - 1) }, enabled = h.isNotEmpty(), modifier = Modifier.testTag("hitPrev")) { Icon(Ic.Up, tr("Previous")) }
                    IconButton(onClick = { show(at + 1) }, enabled = h.isNotEmpty(), modifier = Modifier.testTag("hitNext")) { Icon(Ic.Down, tr("Next")) }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
            val px = constraints.maxWidth
            val bucket = (((px + STEP - 1) / STEP) * STEP).coerceIn(STEP, MAX_WIDTH)
            val current = hits?.getOrNull(at)
            LazyColumn(Modifier.fillMaxSize().testTag("pages"), state = list, contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                items(doc.pages) { i ->
                    val n = i + 1
                    val box = sizes.getOrPut(n) { runCatching { doc.file.pageSize(n.toUInt()) }.getOrDefault(PageBox(595f, 842f)) }
                    val marks = hits?.filter { it.page.toInt() == n }.orEmpty()
                    PageView(doc, host, n, box, bucket, marks, current?.takeIf { it.page.toInt() == n })
                }
            }
        }
        val first by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
        Text(tr("Page {n} of {total}", "n" to first, "total" to doc.pages), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(6.dp).testTag("pageOf"))
    }
}

@Composable
private fun PageView(doc: OpenDoc, host: Host, n: Int, box: PageBox, width: Int, marks: List<Hit>, current: Hit?) {
    var img by remember(doc, n, width) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(doc, n, width) {
        img = withContext(Dispatchers.IO) { runCatching { host.image(doc.file.render(n.toUInt(), width)) }.getOrNull() }
    }
    val ratio = (box.width / box.height).takeIf { it.isFinite() && it > 0.05f && it < 20f } ?: 0.707f
    Box(Modifier.widthIn(max = 1000.dp).fillMaxWidth().aspectRatio(ratio).background(Color.White)
        .semantics { contentDescription = tr("Page {n} of {total}", "n" to n, "total" to doc.pages) }.testTag("page-$n")) {
        img?.let { Image(it, null, Modifier.fillMaxSize()) }
        if (marks.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
            val s = size.width / box.width
            for (h in marks) for (a: Area in h.areas) {
                val color = if (h === current) Color(0x99FF9800) else Color(0x66FFEB3B)
                drawRect(color, Offset(a.x * s, a.y * s), Size(a.w * s, a.h * s))
            }
        }
    }
}
