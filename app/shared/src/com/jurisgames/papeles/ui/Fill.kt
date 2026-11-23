package com.jurisgames.papeles.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jurisgames.papeles.core.FieldKind
import com.jurisgames.papeles.core.FormField
import com.jurisgames.papeles.core.PdfInput
import com.jurisgames.papeles.core.fillForm
import com.jurisgames.papeles.core.formFields
import com.jurisgames.papeles.core.isSigned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Page pictures for the field crops are drawn this wide (a crop is a small part of it). */
private const val PEEK_WIDTH = 1200

/** One thing to fill: a field name and its widgets (a radio group has one widget per choice). */
private class Group(val name: String, val widgets: List<FormField>) {
    val first get() = widgets.first()
    val kind get() = first.kind
}

/** Whether a PDF has anything this screen can fill (the reader shows Fill only then). */
fun fillable(fields: List<FormField>) = fields.any { !it.readOnly && it.kind in setOf(FieldKind.TEXT, FieldKind.CHECK, FieldKind.RADIO, FieldKind.CHOICE) }

/**
 * Fill (plan M4): every field of the PDF as a list, each with the part of the page around it (so the printed label
 * next to it shows), then a filled copy is saved; the open file is never changed. A digitally signed PDF says first
 * that a filled copy no longer proves the signature.
 */
@Composable
fun FillScreen(doc: OpenDoc, host: Host, worker: Worker, back: () -> Unit) {
    val input = remember(doc) { PdfInput(doc.bytes, doc.password) }
    var groups by remember(doc) { mutableStateOf<List<Group>?>(null) }
    var signed by remember(doc) { mutableStateOf(false) }
    val values = remember(doc) { mutableStateMapOf<String, String>() }
    var saved by remember { mutableStateOf<Picked?>(null) }
    var warn by remember { mutableStateOf(false) }
    LaunchedEffect(doc) {
        val r = withContext(Dispatchers.IO) { runCatching { formFields(input) to isSigned(input) } }
        r.onSuccess { (f, s) ->
            groups = f.groupBy { it.name }.map { (n, w) -> Group(n, w) }.filter { it.kind != FieldKind.OTHER }
            signed = s
        }.onFailure { worker.say(userError(it)); groups = emptyList() }
    }
    val pictures = remember(doc) { HashMap<UInt, ImageBitmap?>() }
    fun changed(): Map<String, String> = groups.orEmpty().mapNotNull { g ->
        values[g.name]?.takeIf { it != g.first.value }?.let { g.name to it }
    }.toMap()
    fun save() {
        val c = changed()
        worker.run({ fillForm(input, c) }) { bytes ->
            host.save(nameWith(doc.name, tr("filled")), bytes) { saved = it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopRow(tr("Fill: {name}", "name" to doc.name), back)
        val g = groups
        if (g == null) {
            Text(tr("Reading the form…"), Modifier.padding(16.dp))
            return@Column
        }
        if (g.isEmpty()) {
            Text(tr("This PDF has no fields to fill."), Modifier.padding(16.dp).testTag("noFields"))
            return@Column
        }
        if (signed) Text(tr("This PDF is digitally signed. A filled copy no longer proves who signed it."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp).testTag("signedNote"))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(g, key = { it.name }) { group ->
                Column(Modifier.fillMaxWidth().testTag("field-${group.name}")) {
                    Peek(doc, host, group, pictures)
                    FieldControl(group, values[group.name] ?: group.first.value) { values[group.name] = it; saved = null }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val n = changed().size
            Button(onClick = { if (signed) warn = true else save() }, enabled = n > 0 && !worker.busy, modifier = Modifier.testTag("make")) {
                Text(tr("Save filled copy"))
            }
            Text(if (n == 0) tr("Nothing changed yet") else tr("{n} changed", "n" to n), Modifier.padding(start = 12.dp).testTag("changedCount"))
        }
        SavedRow(host, saved)
    }
    if (warn) AlertDialog(
        onDismissRequest = { warn = false },
        title = { Text(tr("Save a filled copy?")) },
        text = { Text(tr("This PDF is digitally signed. A filled copy no longer proves who signed it. The open file stays as it is.")) },
        confirmButton = { TextButton(onClick = { warn = false; save() }, modifier = Modifier.testTag("saveAnyway")) { Text(tr("Save the copy")) } },
        dismissButton = { TextButton(onClick = { warn = false }) { Text(tr("Cancel")) } },
    )
}

/** The part of the page around the field, a little to its left and above it, where its label usually is. */
@Composable
private fun Peek(doc: OpenDoc, host: Host, group: Group, cache: HashMap<UInt, ImageBitmap?>) {
    val f = group.first
    var img by remember(doc, f.page) { mutableStateOf(cache[f.page]) }
    LaunchedEffect(doc, f.page) {
        if (img == null) img = withContext(Dispatchers.IO) { runCatching { host.image(doc.file.render(f.page, PEEK_WIDTH)) }.getOrNull() }
        cache[f.page] = img
    }
    val bm = img ?: return
    val box = remember(doc, f.page) { runCatching { doc.file.pageSize(f.page) }.getOrNull() } ?: return
    val s = bm.width / box.width
    // every widget of the group, plus room for a label: 180 points to the left, 24 above
    val x0 = (group.widgets.minOf { it.area.x } - 180f).coerceAtLeast(0f)
    val y0 = (group.widgets.minOf { it.area.y } - 24f).coerceAtLeast(0f)
    val x1 = (group.widgets.maxOf { it.area.x + it.area.w } + 12f).coerceAtMost(box.width)
    val y1 = (group.widgets.maxOf { it.area.y + it.area.h } + 6f).coerceAtMost(box.height)
    if (x1 <= x0 || y1 <= y0) return
    val off = IntOffset((x0 * s).toInt(), (y0 * s).toInt())
    val size = IntSize(((x1 - x0) * s).toInt().coerceIn(1, bm.width - off.x), ((y1 - y0) * s).toInt().coerceIn(1, bm.height - off.y))
    Image(BitmapPainter(bm, off, size), null, Modifier.fillMaxWidth().aspectRatio(size.width.toFloat() / size.height).heightIn(max = 160.dp)
        .background(Color.White).testTag("peek"))
}

@Composable
private fun FieldControl(group: Group, value: String, set: (String) -> Unit) {
    val f = group.first
    val label = group.name.substringAfterLast('.').ifEmpty { group.name }
    when (f.kind) {
        FieldKind.TEXT -> OutlinedTextField(value, { set(it) }, label = { Text(label) }, singleLine = !f.multiline, enabled = !f.readOnly,
            minLines = if (f.multiline) 3 else 1, modifier = Modifier.fillMaxWidth().testTag("input-${group.name}"))
        FieldKind.CHECK -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value != "Off", enabled = !f.readOnly, role = Role.Checkbox) { set(if (it) f.on else "Off") }
            .testTag("input-${group.name}"), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(value != "Off", null, enabled = !f.readOnly)
            Text(label, Modifier.padding(start = 8.dp))
        }
        FieldKind.RADIO, FieldKind.CHOICE -> Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            val choices = if (f.kind == FieldKind.RADIO) group.widgets.map { it.on }.filter { it.isNotEmpty() }.distinct() else f.options
            for (c in choices) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(value == c, enabled = !f.readOnly, role = Role.RadioButton) { set(c) }
                .testTag("choice-${group.name}-$c"), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(value == c, null, enabled = !f.readOnly)
                Text(c, Modifier.padding(start = 8.dp))
            }
            if (f.kind == FieldKind.CHOICE && f.editable) OutlinedTextField(if (value in choices) "" else value, { set(it) },
                label = { Text(tr("Or type it")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("input-${group.name}"))
        }
        FieldKind.SIGNATURE -> Text(tr("A digital signature: it can't be filled here."), style = MaterialTheme.typography.bodySmall)
        FieldKind.OTHER -> {}
    }
    if (f.readOnly && f.kind != FieldKind.SIGNATURE) Text(tr("This field can't be changed."), style = MaterialTheme.typography.bodySmall)
}
