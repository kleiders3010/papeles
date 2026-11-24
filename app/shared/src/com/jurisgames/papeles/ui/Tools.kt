package com.jurisgames.papeles.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.jurisgames.papeles.core.openPdf
import com.jurisgames.papeles.core.PpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Report.pdf" -> "Report (smaller).pdf". */
fun nameWith(name: String, words: String): String {
    val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
    return "$base ($words).pdf"
}

/** Opens a file in the reader (PapelesApp's open), for the saved row's Open. */
val LocalOpen = androidx.compose.runtime.staticCompositionLocalOf<((Picked) -> Unit)?> { null }

/** U-P21: keyboard commands a computer's window passes to the screen on show (the reader listens). */
enum class KeyCommand { Find, NextPage, PrevPage, ZoomIn, ZoomOut }
object Keys { val commands = kotlinx.coroutines.flow.MutableSharedFlow<KeyCommand>(extraBufferCapacity = 8) }

/** U-P27: whether the screen on show holds work not saved yet (set by guardBack), so a file arriving asks first. */
class Unsaved { var now by mutableStateOf(false) }
val LocalUnsaved = androidx.compose.runtime.staticCompositionLocalOf<Unsaved?> { null }

/** After a save: what was saved and where (U-P6), Open in the reader, and a button to hand it on. */
@Composable
internal fun SavedRow(host: Host, saved: Picked?, extra: String = "") {
    var noted by remember(saved) { mutableStateOf<String?>(null) }
    saved ?: return
    val open = LocalOpen.current
    val folder = remember(saved) { host.folderOf(saved) }
    Card(Modifier.fillMaxWidth().padding(16.dp).testTag("saved")) {
        Column(Modifier.padding(14.dp)) {
            Text(if (folder != null) tr("Saved in {folder}: {name}", "folder" to folder, "name" to saved.name) else tr("Saved: {name}", "name" to saved.name),
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("savedName"))
            if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("savedExtra"))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (open != null) TextButton(onClick = { open(saved) }, modifier = Modifier.testTag("openSaved")) { Text(tr("Open")) }
                // APP-RULE 222: what it made can be printed too
                if (host.canPrint) TextButton(onClick = { host.print(saved) }, modifier = Modifier.testTag("printSaved")) { Text(tr("Print")) }
                OutlinedButton(onClick = { host.share(saved); noted = host.shareNote }, modifier = Modifier.padding(start = 8.dp).testTag("share")) { Text(tr("Share")) }
            }
            noted?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp).testTag("shareNote")) }
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
internal fun <T> Choice(options: List<Triple<T, String, String>>, chosen: T, tagPrefix: String, pick: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        for ((value, label, about) in options) {
            Row(Modifier.fillMaxWidth().selectable(chosen == value, role = Role.RadioButton) { pick(value) }.heightIn(min = 48.dp).padding(vertical = 4.dp)
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
    val passwords = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var asking by remember { mutableStateOf<Picked?>(null) }
    fun make() {
        val list = files.toList()
        val pws = passwords.toMap()
        worker.run({
            val read = list.map { it to it.read() }
            // the first locked file whose password isn't known yet (or was wrong) is asked for; nothing is joined
            read.firstOrNull { (p, b) -> lockedWith(b, pws[p.key]) }?.first to
                (if (read.any { (p, b) -> lockedWith(b, pws[p.key]) }) null else merge(read.map { (p, b) -> PdfInput(b, pws[p.key]) }))
        }, tr("Joining the PDFs…")) { (locked, bytes) ->
            if (locked != null) asking = locked
            else if (bytes != null) host.save(nameWith(list.first().name, tr("joined")), bytes) { saved = it }
        }
    }
    asking?.let { p ->
        PasswordDialog(p.name, passwords.containsKey(p.key), onOpen = { passwords[p.key] = it; asking = null; make() }, onClose = { asking = null })
    }
    Column(Modifier.fillMaxSize()) {
        TopRow(tr("Join PDFs"), back)
        if (files.isEmpty()) Hint(tr("Choose the PDFs to join, in order."))
        // why "Save as PDF" is still greyed with one file (APP-RULES 212)
        if (files.size == 1) Hint(tr("Add at least one more PDF to join."), "needMore")
        FileList(files, Modifier.weight(1f, fill = false))
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { host.pickPdfs(true) { files.addAll(it); saved = null } }, modifier = Modifier.testTag("add")) { Text(tr("Add PDFs")) }
            Button(onClick = { make() }, enabled = files.size >= 2, modifier = Modifier.testTag("make")) { Text(tr("Save as PDF")) }
        }
        SavedRow(host, saved)
    }
}

@Composable
fun PicturesScreen(host: Host, worker: Worker, back: () -> Unit, start: List<Picked> = emptyList()) {
    val files = remember(start) { mutableStateListOf<Picked>().apply { addAll(start) } }
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
                worker.run({ picturesToPdf(list.map { it.read() }, p) }, tr("Making the PDF…")) { bytes ->
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
    var password by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(ShrinkLevel.MEDIUM) }
    var saved by remember { mutableStateOf<Picked?>(null) }
    var sizes by remember { mutableStateOf("") }
    fun shrinkIt() {
                val src = file ?: return
                val l = level
                val pw = password
        worker.run({
            val b = src.read()
            // a locked file: its password is asked for, then the same job runs again
            if (lockedWith(b, pw)) null else b.size to shrink(PdfInput(b, pw), l)
        }, tr("Making it smaller…")) { got ->
            if (got == null) { asking = true; return@run }
            val (before, r) = got
                    if (r.bytes.size >= before) worker.say(tr("This PDF is already as small as it gets here."))
                    else host.save(nameWith(src.name, tr("smaller")), r.bytes) {
                        saved = it
                        sizes = tr("{before} → {after}", "before" to sizeLabel(before.toLong()), "after" to sizeLabel(r.bytes.size.toLong()))
                    }
                }
    }
    if (asking) PasswordDialog(file?.name ?: "", password != null, onOpen = { password = it; asking = false; shrinkIt() }, onClose = { asking = false })
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
            OutlinedButton(onClick = { host.pickPdfs(false) { it.firstOrNull()?.let { p -> file = p; password = null; saved = null } } }, modifier = Modifier.testTag("add")) { Text(tr("Choose a PDF")) }
            Button(onClick = { shrinkIt() }, enabled = f != null, modifier = Modifier.testTag("make")) { Text(tr("Save as PDF")) }
        }
        SavedRow(host, saved, sizes)
    }
}

/** One page on the page editor: which page of the file and its extra turn. */
private data class Card(val page: Int, val turn: Int, val id: Int)

/** The page editor: turn, delete and move pages, then save a new PDF (the open file is never changed). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PagesScreen(doc: OpenDoc, host: Host, worker: Worker, back: () -> Unit) {
    val cards = remember(doc) { mutableStateListOf<Card>().apply { addAll((1..doc.pages).map { Card(it, 0, it) }) } }
    val chosen = remember(doc) { mutableStateListOf<Int>() } // card ids
    var saved by remember { mutableStateOf<Picked?>(null) }
    // the pages the last Delete took, with where each was, for Undo
    var deleted by remember(doc) { mutableStateOf<List<Pair<Int, Card>>>(emptyList()) }
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
        TopRow(tr("Pages: {name}", "name" to doc.name), guardBack(cards.toList() != (1..doc.pages).map { Card(it, 0, it) } && saved == null, back))
        Text(if (chosen.isEmpty()) tr("Tap a page to choose it.") else plural(chosen.size, "1 chosen", "{n} chosen"),
            Modifier.padding(horizontal = 16.dp).testTag("chosenCount"))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val any = chosen.isNotEmpty()
            IconButton(onClick = { turn(270) }, enabled = any, modifier = Modifier.testTag("turnLeft")) { Icon(Ic.TurnLeft, tr("Turn left")) }
            IconButton(onClick = { turn(90) }, enabled = any, modifier = Modifier.testTag("turnRight")) {
                Icon(Ic.TurnLeft, tr("Turn right"), Modifier.graphicsLayer(scaleX = -1f))
            }
            IconButton(onClick = { move(-1) }, enabled = chosen.size == 1, modifier = Modifier.testTag("moveUp")) { Icon(Ic.Up, tr("Move up")) }
            IconButton(onClick = { move(1) }, enabled = chosen.size == 1, modifier = Modifier.testTag("moveDown")) { Icon(Ic.Down, tr("Move down")) }
            IconButton(onClick = {
                deleted = cards.withIndex().filter { it.value.id in chosen }.map { it.index to it.value }
                cards.removeAll { it.id in chosen }; chosen.clear(); saved = null
            }, enabled = any && chosen.size < cards.size, modifier = Modifier.testTag("deletePages")) { Icon(Ic.Delete, tr("Delete page")) }
            if (deleted.isNotEmpty()) TextButton(onClick = {
                for ((i, c) in deleted) cards.add(i.coerceAtMost(cards.size), c)
                deleted = emptyList(); saved = null
            }, modifier = Modifier.testTag("undoDelete")) { Text(tr("Undo")) }
        }
        LazyVerticalGrid(GridCells.Adaptive(120.dp), Modifier.weight(1f).testTag("grid"), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(cards, key = { _, c -> c.id }) { i, c ->
                val on = c.id in chosen
                // a check box to TalkBack: its page and whether it's chosen (APP-RULES 209)
                Column(Modifier.toggleable(on, role = Role.Checkbox) { if (on) chosen.remove(c.id) else chosen.add(c.id) }
                    .semantics { contentDescription = tr("Page {n} of {total}", "n" to i + 1, "total" to cards.size) }.testTag("card-${c.page}"),
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
                worker.run({ arrange(listOf(PdfInput(doc.bytes, doc.password)), picks) }, tr("Making the PDF…")) { bytes ->
                    host.save(nameWith(doc.name, tr("edited")), bytes) { saved = it }
                }
            }, modifier = Modifier.testTag("make")) { Text(tr("Save as PDF")) }
        }
        val order = cards.toList()
        val one = order.indexOfFirst { it.id == chosen.singleOrNull() }
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // only the chosen pages, in the order shown, as a new PDF
            OutlinedButton(onClick = {
                val picks = order.filter { it.id in chosen }.map { PagePick(0u, it.page.toUInt(), it.turn.toLong()) }
                worker.run({ arrange(listOf(PdfInput(doc.bytes, doc.password)), picks) }, tr("Making the PDF…")) { bytes ->
                    host.save(nameWith(doc.name, tr("chosen pages")), bytes) { saved = it }
                }
            }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("keepChosen")) { Text(tr("Save chosen pages as a new PDF")) }
            // two PDFs: up to the chosen page, and the rest
            OutlinedButton(onClick = {
                val picks = order.map { PagePick(0u, it.page.toUInt(), it.turn.toLong()) }
                worker.run({
                    val inputs = listOf(PdfInput(doc.bytes, doc.password))
                    arrange(inputs, picks.take(one + 1)) to arrange(inputs, picks.drop(one + 1))
                }) { (first, rest) ->
                    host.save(nameWith(doc.name, tr("part {n}", "n" to 1)), first) { a ->
                        if (a == null) return@save
                        host.save(nameWith(doc.name, tr("part {n}", "n" to 2)), rest) { saved = it ?: a }
                    }
                }
            }, enabled = one in 0 until order.size - 1, modifier = Modifier.testTag("splitAfter")) { Text(tr("Split after this page")) }
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

/** True when [bytes] is a PDF that [password] doesn't open (none given, or a wrong one). */
internal fun lockedWith(bytes: ByteArray, password: String?): Boolean =
    runCatching { openPdf(bytes, password).close() }.exceptionOrNull() is PpException.Password
