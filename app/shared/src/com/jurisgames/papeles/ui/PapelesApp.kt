package com.jurisgames.papeles.ui

import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jurisgames.papeles.core.PdfFile
import com.jurisgames.papeles.core.PpException
import com.jurisgames.papeles.core.openPdf
import com.jurisgames.papeles.core.formFields
import com.jurisgames.papeles.core.PdfInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A PDF open in the reader: its bytes stay in memory for page work (the reader holds its own copy in PDFium). */
class OpenDoc(val name: String, val key: String, val bytes: ByteArray, val password: String?, val file: PdfFile) {
    val pages: Int = file.pageCount().toInt()
}

sealed class Screen {
    data object Home : Screen()
    class Reader(val doc: OpenDoc) : Screen()
    class Pages(val doc: OpenDoc) : Screen()
    class Fill(val doc: OpenDoc) : Screen()
    class Sign(val doc: OpenDoc) : Screen()
    class Mark(val doc: OpenDoc) : Screen()
    data object Join : Screen()
    data object Pictures : Screen()
    data object Scan : Screen()
    data object Shrink : Screen()
}

/** Runs PDF work off the main thread; `busy` shows while it runs, errors become one plain sentence. */
class Worker(private val scope: CoroutineScope, private val snack: SnackbarHostState) {
    var busy by mutableStateOf(false)
        private set
    /** each job's number: Cancel moves it on, so a job that ends after Cancel is dropped (nothing saved, nothing shown) */
    private var job = 0

    /** What the running job is doing, in words ("Joining the PDFs…"); "Working…" when the caller doesn't say. */
    var doing by mutableStateOf("")
        private set
    /** one engine job at a time: a job started after a Cancel waits (spinner showing) for the cancelled one to finish,
     * so two big PDFs are never in memory together (U-P18), and its own result still arrives (WorkerCancelTest) */
    private val one = kotlinx.coroutines.sync.Mutex()

    fun <T> run(work: () -> T, what: String = "", done: (T) -> Unit) {
        if (busy) return
        busy = true
        doing = what
        val mine = ++job
        scope.launch {
            val r = one.withLock { withContext(Dispatchers.IO) { runCatching(work) } }
            if (mine != job) return@launch
            busy = false
            r.onSuccess(done).onFailure { lastError = it; say(userError(it)) }
        }
    }

    /**
     * APP-RULES 205: a long job (a big PDF made smaller, many pages scanned) has a way out. The engine call can't be
     * stopped halfway, so it finishes in the background and its result is thrown away: saves happen only in `done`.
     */
    fun cancel() {
        if (!busy) return
        job++
        busy = false
    }

    companion object {
        /** The last failure, raw (the person only sees [userError]); tests read it to say why. */
        @Volatile var lastError: Throwable? = null
    }

    fun say(text: String) {
        scope.launch { snack.showSnackbar(text, duration = androidx.compose.material3.SnackbarDuration.Long) }
    }
}

/** `incoming`: a PDF handed to the app from outside ("Open with"); opened once, then `onIncomingDone`. */
@Composable
fun PapelesApp(host: Host, incoming: Picked? = null, onIncomingDone: () -> Unit = {}, pictures: List<Picked> = emptyList(),
               onPicturesDone: () -> Unit = {}, onTheme: (DarkMode) -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val worker = remember { Worker(scope, snack) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var recent by remember { mutableStateOf(Recent.load(host.appFiles)) }
    var locked by remember { mutableStateOf<Picked?>(null) }
    var wrongPassword by remember { mutableStateOf(false) }
    // the tool a home entry goes into once its file opens ("sign", "fill", "mark", "pages"; "" = the reader)
    var into by remember { mutableStateOf("") }
    // photos shared to the app (U-P7): Pictures to PDF starts with them
    var sharedPictures by remember { mutableStateOf<List<Picked>>(emptyList()) }

    fun close(s: Screen) {
        // every screen holding a file: Open on a tool's saved row leaves it for the new one
        when (s) {
            is Screen.Reader -> s.doc; is Screen.Pages -> s.doc; is Screen.Fill -> s.doc; is Screen.Sign -> s.doc; is Screen.Mark -> s.doc
            else -> null
        }?.file?.close()
    }

    fun open(p: Picked, password: String? = null) {
        val tool = into
        worker.run({
            val bytes = p.read()
            runCatching { openPdf(bytes, password) }.map { OpenDoc(p.name, p.key, bytes, password, it) }.map { doc ->
                // "Fill in a form" on a paper with no fields to fill: typing on it (Mark's text) instead
                doc to (tool == "fill" && runCatching { fillable(formFields(PdfInput(bytes, password))) }.getOrDefault(false))
            }
        }, tr("Opening {name}…", "name" to p.name)) { r ->
            r.onSuccess { (doc, form) ->
                locked = null
                into = ""
                recent = Recent.add(host.appFiles, p.name, p.key)
                close(screen)
                screen = when (tool) {
                    "sign" -> Screen.Sign(doc)
                    "mark" -> Screen.Mark(doc)
                    "pages" -> Screen.Pages(doc)
                    "fill" -> if (form) Screen.Fill(doc) else Screen.Mark(doc).also { worker.say(tr("This PDF has no fields to fill: tap where your text goes.")) }
                    else -> Screen.Reader(doc)
                }
            }.onFailure { e ->
                if (e is PpException.Password) { wrongPassword = password != null; locked = p } else { into = ""; worker.say(userError(e)) }
            }
        }
    }

    LaunchedEffect(pictures) {
        if (pictures.isEmpty()) return@LaunchedEffect
        sharedPictures = pictures
        close(screen)
        screen = Screen.Pictures
        onPicturesDone()
    }

    LaunchedEffect(incoming) {
        incoming?.let { open(it); onIncomingDone() }
    }

    // Rule 171 (U-P1): which screen was open, and the open file's name and key (never its password), survive Android
    // closing the app while the camera or a file picker is open. A file's screen comes back as its reader.
    var savedScreen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val restore = remember { savedScreen }
    LaunchedEffect(Unit) {
        when {
            incoming != null || restore.isEmpty() -> {}
            restore == "scan" -> screen = Screen.Scan
            restore == "join" -> screen = Screen.Join
            restore == "pictures" -> screen = Screen.Pictures
            restore == "shrink" -> screen = Screen.Shrink
            restore.startsWith("doc\n") -> restore.split("\n", limit = 3).takeIf { it.size == 3 }?.let { (_, name, key) -> host.reopen(name, key)?.let { open(it) } }
        }
    }
    LaunchedEffect(screen) {
        savedScreen = when (val s = screen) {
            Screen.Home -> ""
            Screen.Scan -> "scan"
            Screen.Join -> "join"
            Screen.Pictures -> "pictures"
            Screen.Shrink -> "shrink"
            is Screen.Reader -> "doc\n${s.doc.name}\n${s.doc.key}"
            is Screen.Pages -> "doc\n${s.doc.name}\n${s.doc.key}"
            is Screen.Fill -> "doc\n${s.doc.name}\n${s.doc.key}"
            is Screen.Sign -> "doc\n${s.doc.name}\n${s.doc.key}"
            is Screen.Mark -> "doc\n${s.doc.name}\n${s.doc.key}"
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalOpen provides { p: Picked -> open(p) }) {
    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = screen) {
                Screen.Home -> Home(host, recent, worker, onTheme,
                    open = { open(it) },
                    openInto = { tool -> host.pickPdfs(false) { it.firstOrNull()?.let { p -> into = tool; open(p) } } },
                    reopen = { r ->
                        val p = host.reopen(r.name, r.key)
                        if (p == null) { recent = Recent.remove(host.appFiles, r.key); worker.say(tr("This file is no longer there.")) } else open(p)
                    },
                    forget = { recent = Recent.remove(host.appFiles, it.key) },
                    go = { screen = it })
                is Screen.Reader -> Reader(s.doc, host, worker, back = { close(s); screen = Screen.Home }, editPages = { screen = Screen.Pages(s.doc) }, fill = { screen = Screen.Fill(s.doc) }, sign = { screen = Screen.Sign(s.doc) }, mark = { screen = Screen.Mark(s.doc) })
                is Screen.Sign -> SignScreen(s.doc, host, worker, back = { screen = Screen.Reader(s.doc) })
                is Screen.Mark -> MarkScreen(s.doc, host, worker, back = { screen = Screen.Reader(s.doc) })
                is Screen.Fill -> FillScreen(s.doc, host, worker, back = { screen = Screen.Reader(s.doc) })
                is Screen.Pages -> PagesScreen(s.doc, host, worker, back = { screen = Screen.Reader(s.doc) })
                Screen.Join -> JoinScreen(host, worker, back = { screen = Screen.Home })
                Screen.Pictures -> PicturesScreen(host, worker, back = { sharedPictures = emptyList(); screen = Screen.Home }, start = sharedPictures)
                Screen.Scan -> ScanScreen(host, worker, back = { screen = Screen.Home })
                Screen.Shrink -> ShrinkScreen(host, worker, back = { screen = Screen.Home })
            }
            if (worker.busy) Busy(worker.doing, worker::cancel)
        }
    }
    } // LocalOpen
    locked?.let { p -> PasswordDialog(p.name, wrongPassword, onOpen = { open(p, it) }, onClose = { locked = null; wrongPassword = false; into = "" }) }
}

@Composable
fun Busy(what: String = "", onCancel: () -> Unit = {}) {
    Surface(Modifier.fillMaxSize().testTag("busy"), color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(what.ifEmpty { tr("Working…") }, modifier = Modifier.testTag("busyWhat"))
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("busyCancel")) { Text(tr("Cancel")) }
        }
    }
}

/** What the system's Back does now (Android): the open screen's own back, set by its TopRow; null on the home screen. */
object SystemBack {
    @Volatile var current: (() -> Unit)? = null
}

/**
 * Back from a screen that holds work not saved yet asks first, never drops it in one tap (APP-RULES 203). The
 * returned back is the one to give TopRow (so the system's Back asks too).
 */
@Composable
fun guardBack(unsaved: Boolean, back: () -> Unit): () -> Unit {
    var ask by remember { mutableStateOf(false) }
    if (ask) AlertDialog(onDismissRequest = { ask = false }, title = { Text(tr("Leave without saving?")) },
        text = { Text(tr("What you did here hasn't been saved yet, and it will be lost.")) },
        confirmButton = { TextButton(onClick = { ask = false; back() }, modifier = Modifier.testTag("leaveAnyway")) { Text(tr("Leave")) } },
        dismissButton = { TextButton(onClick = { ask = false }, modifier = Modifier.testTag("keepWorking")) { Text(tr("Keep working")) } })
    return { if (unsaved) ask = true else back() }
}

/** The top row of every screen: back (except on the home screen), the title, then the screen's own buttons. */
@Composable
fun TopRow(title: String, back: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    // the system's Back does what this row's back does (APP-RULES 170); the home screen leaves the app as usual
    DisposableEffect(back) {
        SystemBack.current = back
        onDispose { if (SystemBack.current === back) SystemBack.current = null }
    }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconButton(onClick = back, modifier = Modifier.testTag("back")) { Icon(Ic.Back, tr("Back")) } else Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("title"))
        actions()
    }
}

@Composable
private fun Home(host: Host, recent: List<RecentFile>, worker: Worker, onTheme: (DarkMode) -> Unit,
                 open: (Picked) -> Unit, reopen: (RecentFile) -> Unit, forget: (RecentFile) -> Unit, go: (Screen) -> Unit,
                 openInto: (String) -> Unit = {}) {
    var settings by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopRow(tr("Papeles"), null) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu")) { Icon(Ic.More, tr("Menu")) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("Settings")) }, onClick = { menu = false; settings = true }, modifier = Modifier.testTag("settings"))
                    DropdownMenuItem(text = { Text(tr("Send feedback")) }, onClick = { menu = false; feedback = true }, modifier = Modifier.testTag("feedbackMenu"))
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("home"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Action(Ic.Open, tr("Open a PDF"), "open") { host.pickPdfs(false) { it.firstOrNull()?.let(open) } }
            }
            item { Action(Ic.Sign, tr("Sign a PDF"), "homeSign") { openInto("sign") } }
            item { Action(Ic.Fill, tr("Fill in a form"), "homeFill") { openInto("fill") } }
            item { Action(Ic.Mark, tr("Mark up a PDF"), "homeMark") { openInto("mark") } }
            item { Action(Ic.Pages, tr("Edit pages"), "homePages") { openInto("pages") } }
            item { Action(Ic.Add, tr("Join PDFs"), "join") { go(Screen.Join) } }
            item { Action(Ic.Scan, tr("Scan"), "scan") { go(Screen.Scan) } }
            item { Action(Ic.Image, tr("Pictures to PDF"), "pictures") { go(Screen.Pictures) } }
            item { Action(Ic.Shrink, tr("Make smaller"), "shrink") { go(Screen.Shrink) } }
            item {
                Spacer(Modifier.height(8.dp))
                Text(tr("Recent"), style = MaterialTheme.typography.titleMedium)
                if (recent.isEmpty()) Text(tr("Files you open show up here."), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp).testTag("noRecent"))
            }
            items(recent, key = { it.key }) { r ->
                Row(Modifier.fillMaxWidth().clickable { reopen(r) }.padding(vertical = 6.dp).testTag("recent-" + r.name), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { forget(r) }, modifier = Modifier.testTag("forget-" + r.name)) { Icon(Ic.Close, tr("Remove from the list")) }
                }
                HorizontalDivider()
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text(tr("Your files never leave this device."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (settings) SettingsDialog(host, onTheme) { settings = false }
    if (feedback) FeedbackDialog(onSent = { feedback = false; worker.say(tr("Thanks! It was sent.")) }, onClose = { feedback = false })
}

@Composable
private fun Action(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag(tag)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SettingsDialog(host: Host, onTheme: (DarkMode) -> Unit, onClose: () -> Unit) {
    var mode by remember { mutableStateOf(darkMode(host.appFiles)) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("settingsClose")) { Text(tr("Close")) } },
        title = { Text(tr("Settings")) },
        text = {
            Column {
                Text(tr("Appearance"), style = MaterialTheme.typography.titleSmall)
                for (m in DarkMode.entries) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(mode == m, role = androidx.compose.ui.semantics.Role.RadioButton) { mode = m; setDarkMode(host.appFiles, m); onTheme(m) }
                        .testTag("dark-" + m.key), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == m, null)
                        Text(tr(m.label))
                    }
                }
            }
        },
    )
}

@Composable
fun PasswordDialog(name: String, wrong: Boolean, onOpen: (String) -> Unit, onClose: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = { onOpen(pw) }, enabled = pw.isNotEmpty(), modifier = Modifier.testTag("pwOpen")) { Text(tr("Open")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
        title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (wrong) tr("That password didn't open it. Check it and try again.") else tr("This PDF is locked. Type its password to open it."),
                    color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("pwHint"))
                OutlinedTextField(pw, { pw = it }, label = { Text(tr("Password")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (pw.isNotEmpty()) onOpen(pw) }),
                    modifier = Modifier.testTag("pwField"))
            }
        },
    )
}

/** "1 page" / "{n} pages". */
fun pagesLabel(n: Int): String = if (n == 1) tr("1 page") else tr("{n} pages", "n" to n)

@Composable
fun Hint(text: String, tag: String = "hint") {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp).testTag(tag))
}

