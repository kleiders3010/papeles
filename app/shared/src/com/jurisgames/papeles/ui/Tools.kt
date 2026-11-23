package com.jurisgames.papeles.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jurisgames.papeles.core.PagePick
import com.jurisgames.papeles.core.Paper
import com.jurisgames.papeles.core.PdfInput
import com.jurisgames.papeles.core.ShrinkLevel
import com.jurisgames.papeles.core.arrange
import com.jurisgames.papeles.core.merge
import com.jurisgames.papeles.core.picturesToPdf
import com.jurisgames.papeles.core.shrink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Report.pdf" -> "Report (smaller).pdf". */
fun nameWith(name: String, words: String): String {
    val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
    return "$base ($words).pdf"
}

/** After a save: what was saved and a button to hand it on. */
@Composable
private fun SavedRow(host: Host, saved: Picked?, extra: String = "") {
    saved ?: return
    Card(Modifier.fillMaxWidth().padding(16.dp).testTag("saved")) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Saved: {name}", "name" to saved.name), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("savedExtra"))
            }
            OutlinedButton(onClick = { host.share(saved) }, modifier = Modifier.testTag("share")) { Text(tr("Share")) }
        }
    }
}

/** A list of chosen files with move up / move down / remove on each row. */
@Composable
private fun FileList(files: MutableList<Picked>, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 16.dp)) {
        itemsIndexed(files, key = { i, f -> f.key + "#" + i }) { i, f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("file-$i"), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${f.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { files.add(i - 1, files.removeAt(i)) }, enabled = i > 0, modifier = Modifier.testTag("up-$i")) { Icon(Ic.Up, tr("Move up")) }
                IconButton(onClick = { files.add(i + 1, files.removeAt(i)) }, enabled = i < files.size - 1, modifier = Modifier.testTag("down-$i")) { Icon(Ic.Down, tr("Move down")) }
                IconButton(onClick = { files.removeAt(i) }, modifier = Modifier.testTag("remove-$i")) { Icon(Ic.Close, tr("Remove")) }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun <T> Choice(options: List<Triple<T, String, String>>, chosen: T, tagPrefix: String, pick: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        for ((value, label, about) in options) {
            Row(Modifier.fillMaxWidth().selectable(chosen == value, role = Role.RadioButton) { pick(value) }.padding(vertical = 4.dp)
                .testTag("$tagPrefix-$value"), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(chosen == value, null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(label)
                    if (about.isNotEmpty()) Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun JoinScreen(host: Host, worker: Worker, back: () -> Unit) {
    val files = remember { mutableStateListOf<Picked>() }
    var saved by remember { mutableStateOf<Picked?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopRow(tr("Join PDFs"), back)
        if (files.isEmpty()) Hint(tr("Choose the PDFs to join, in order."))
        FileList(files, Modifier.weight(1f, fill = false))
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { host.pickPdfs(true) { files.addAll(it); saved = null } }, modifier = Modifier.testTag("add")) { Text(tr("Add PDFs")) }
            Button(onClick = {
                val list = files.toList()
                worker.run({ merge(list.map { PdfInput(it.read(), null) }) }) { bytes ->
                    host.save(nameWith(list.first().name, tr("Join PDFs").lowercase()), bytes) { saved = it }
                }
            }, enabled = files.size >= 2, modifier = Modifier.testTag("make")) { Text(tr("Save as PDF")) }
        }
        SavedRow(host, saved)
    }
}

@Composable
fun PicturesScreen(host: Host, worker: Worker, back: () -> Unit) {
    val files = remember { mutableStateListOf<Picked>() }
    var paper by remember { mutableStateOf(Paper.A4) }
    var saved by remember { mutableStateOf<Picked?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopRow(tr("Pictures to PDF"), back)
        if (files.isEmpty()) Hint(tr("Choose the pictures, one per page."))
        FileList(files, Modifier.weight(1f, fill = false))
        Text(tr("Page size"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Choice(listOf(Triple(Paper.A4, tr("A4"), ""), Triple(Paper.LETTER, tr("Letter"), ""), Triple(Paper.FIT, tr("Same as the picture"), "")), paper, "paper") { paper = it }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { host.pickPictures { files.addAll(it); saved = null } }, modifier = Modifier.testTag("add")) { Text(tr("Add pictures")) }
            Button(onClick = {
                val list = files.toList()
                val p = paper
                worker.run({ picturesToPdf(list.map { it.read() }, p) }) { bytes ->
                    host.save(list.first().name.substringBeforeLast('.') + ".pdf", bytes) { saved = it }
                }
            }, enabled = files.isNotEmpty(), modifier = Modifier.testTag("make")) { Text(tr("Save as PDF")) }
        }
        SavedRow(host, saved)
    }
}

@Composable
fun ShrinkScreen(host: Host, worker: Worker, back: () -> Unit) {
    var file by remember { mutableStateOf<Picked?>(null) }
    var level by remember { mutableStateOf(ShrinkLevel.MEDIUM) }
    var saved by remember { mutableStateOf<Picked?>(null) }
    var sizes by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopRow(tr("Make smaller"), back)
        val f = file
        if (f == null) Hint(tr("Choose a PDF to make smaller.")) else Text(f.name, Modifier.padding(16.dp).testTag("chosen"), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Choice(listOf(
            Triple(ShrinkLevel.SMALL, tr("For screens"), tr("Smallest; pictures look fine on a phone or computer.")),
            Triple(ShrinkLevel.MEDIUM, tr("For printing"), tr("Pictures stay sharp enough to print at home.")),
            Triple(ShrinkLevel.LIGHT, tr("Light touch"), tr("Barely changes how pictures look.")),
        ), level, "level") { level = it }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { host.pickPdfs(false) { it.firstOrNull()?.let { p -> file = p; saved = null } } }, modifier = Modifier.testTag("add")) { Text(tr("Choose a PDF")) }
            Button(onClick = {
                val src = f ?: return@Button
                val l = level
                worker.run({ val b = src.read(); b.size to shrink(PdfInput(b, null), l) }) { (before, r) ->
                    if (r.bytes.size >= before) worker.say(tr("This PDF is already as small as it gets here."))
                    else host.save(nameWith(src.name, tr("Make smaller").lowercase()), r.bytes) {
                        saved = it
                        sizes = tr("{before} → {after}", "before" to sizeLabel(before.toLong()), "after" to sizeLabel(r.bytes.size.toLong()))
                    }
                }
            }, enabled = f != null, modifier = Modifier.testTag("make")) { Text(tr("Save as PDF")) }
        }
        SavedRow(host, saved, sizes)
    }
}

/** One page on the page editor: which page of the file and its extra turn. */
private data class Card(val page: Int, val turn: Int, val id: Int)

/** The page editor: turn, delete and move pages, then save a new PDF (the open file is never changed). */
@Composable
fun PagesScreen(doc: OpenDoc, host: Host, worker: Worker, back: () -> Unit) {
    val cards = remember(doc) { mutableStateListOf<Card>().apply { addAll((1..doc.pages).map { Card(it, 0, it) }) } }
    val chosen = remember(doc) { mutableStateListOf<Int>() } // card ids
    var saved by remember { mutableStateOf<Picked?>(null) }
    fun turn(by: Int) {
        for (i in cards.indices) if (cards[i].id in chosen) cards[i] = cards[i].copy(turn = (cards[i].turn + by + 360) % 360)
        saved = null
    }
    fun move(by: Int) {
        val i = cards.indexOfFirst { it.id == chosen.singleOrNull() }
        val j = i + by
        if (i < 0 || j !in cards.indices) return
        cards.add(j, cards.removeAt(i))
        saved = null
    }
    Column(Modifier.fillMaxSize()) {
        TopRow(tr("Pages: {name}", "name" to doc.name), back)
        Text(if (chosen.isEmpty()) tr("Tap a page to choose it.") else tr("{n} chosen", "n" to chosen.size),
            Modifier.padding(horizontal = 16.dp).testTag("chosenCount"))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val any = chosen.isNotEmpty()
            IconButton(onClick = { turn(270) }, enabled = any, modifier = Modifier.testTag("turnLeft")) { Icon(Ic.TurnLeft, tr("Turn left")) }
            IconButton(onClick = { turn(90) }, enabled = any, modifier = Modifier.testTag("turnRight")) {
                Icon(Ic.TurnLeft, tr("Turn right"), Modifier.graphicsLayer(scaleX = -1f))
            }
            IconButton(onClick = { move(-1) }, enabled = chosen.size == 1, modifier = Modifier.testTag("moveUp")) { Icon(Ic.Up, tr("Move up")) }
            IconButton(onClick = { move(1) }, enabled = chosen.size == 1, modifier = Modifier.testTag("moveDown")) { Icon(Ic.Down, tr("Move down")) }
            IconButton(onClick = { cards.removeAll { it.id in chosen }; chosen.clear(); saved = null },
                enabled = any && chosen.size < cards.size, modifier = Modifier.testTag("deletePages")) { Icon(Ic.Delete, tr("Delete page")) }
        }
        LazyVerticalGrid(GridCells.Adaptive(120.dp), Modifier.weight(1f).testTag("grid"), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(cards, key = { _, c -> c.id }) { i, c ->
                val on = c.id in chosen
                Column(Modifier.clickable { if (on) chosen.remove(c.id) else chosen.add(c.id) }.testTag("card-${c.page}"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.75f)
                        .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                        .padding(4.dp), contentAlignment = Alignment.Center) {
                        Thumb(doc, host, c.page, c.turn)
                    }
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(Modifier.padding(16.dp)) {
            Button(onClick = {
                val picks = cards.map { PagePick(0u, it.page.toUInt(), it.turn.toLong()) }
                worker.run({ arrange(listOf(PdfInput(doc.bytes, doc.password)), picks) }) { bytes ->
                    host.save(nameWith(doc.name, tr("Edit pages").lowercase()), bytes) { saved = it }
                }
            }, modifier = Modifier.testTag("make")) { Text(tr("Save as PDF")) }
        }
        SavedRow(host, saved)
    }
}

@Composable
private fun Thumb(doc: OpenDoc, host: Host, page: Int, turn: Int) {
    var img by remember(doc, page) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(doc, page) {
        img = withContext(Dispatchers.IO) { runCatching { host.image(doc.file.render(page.toUInt(), 240)) }.getOrNull() }
    }
    img?.let { Image(it, null, Modifier.rotate(turn.toFloat()).background(Color.White)) }
}
