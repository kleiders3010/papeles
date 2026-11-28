package com.jurisgames.papeles.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jurisgames.papeles.core.Picture
import com.jurisgames.papeles.core.loadEngine
import com.jurisgames.papeles.ui.AppInfo
import com.jurisgames.papeles.ui.AppTheme
import com.jurisgames.papeles.ui.Host
import com.jurisgames.papeles.ui.PapelesApp
import com.jurisgames.papeles.ui.Picked
import com.jurisgames.papeles.ui.darkMode
import com.jurisgames.papeles.ui.tr
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** This build's version code: the same number as the Android build's versionCode. */
const val VERSION_CODE = 2uL

/** A file on disk as the app sees it. */
fun picked(f: File) = Picked(f.name, f.absolutePath) { f.readBytes() }

/**
 * U-P21: files handed to the window (command line, drag and drop): the first PDF opens; with no PDF, the photos start
 * Pictures to PDF. Anything else is left alone.
 */
fun routeFiles(files: List<File>): Pair<Picked?, List<Picked>> {
    val pdf = files.firstOrNull { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
    val pics = if (pdf != null) emptyList() else files.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
    return pdf?.let(::picked) to pics.map(::picked)
}

/** Turns PDFium's RGBA rows into a Compose image. */
fun skiaImage(p: Picture): ImageBitmap {
    val info = ImageInfo(p.width.toInt(), p.height.toInt(), ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    return org.jetbrains.skia.Image.makeRaster(info, p.rgba, p.width.toInt() * 4).toComposeImageBitmap()
}

/**
 * The desktop's file dialogs (AWT: the system's own on Windows). Data lives in the platform's app-data folder;
 * `-Dpp.home=DIR` moves it (tests).
 */
class DesktopHost(private val owner: () -> Frame?) : Host {
    override val appFiles: File = System.getProperty("pp.home")?.let { File(it) } ?: run {
        val appData = System.getenv("APPDATA") ?: (System.getProperty("user.home") + "/.local/share")
        File(appData, "Papeles")
    }.apply { mkdirs() }

    private fun choose(title: String, multiple: Boolean, vararg ext: String): List<File> {
        val d = FileDialog(owner(), title, FileDialog.LOAD)
        d.isMultipleMode = multiple
        d.setFilenameFilter { _, name -> ext.any { name.lowercase().endsWith(it) } }
        if (System.getProperty("os.name").lowercase().contains("win")) d.file = ext.joinToString(";") { "*$it" }
        d.isVisible = true
        return d.files.toList()
    }

    override fun pickPdfs(multiple: Boolean, done: (List<Picked>) -> Unit) =
        done(choose(tr("Open a PDF"), multiple, ".pdf").map(::picked))

    override fun pickPictures(done: (List<Picked>) -> Unit) =
        done(choose(tr("Add pictures"), true, ".jpg", ".jpeg", ".png").map(::picked))

    override fun save(suggested: String, bytes: ByteArray, done: (Picked?) -> Unit) {
        val d = FileDialog(owner(), tr("Save as PDF"), FileDialog.SAVE)
        d.file = suggested
        d.isVisible = true
        val name = d.file ?: return done(null)
        val f = File(d.directory, if (name.lowercase().endsWith(".pdf")) name else "$name.pdf")
        // Written next to the target first, then moved over it: a failed write never leaves half a file.
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeBytes(bytes)
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        done(picked(f))
    }

    override fun reopen(name: String, key: String): Picked? = File(key).takeIf { it.isFile }?.let(::picked)

    override fun folderOf(file: Picked): String? = File(file.key).parentFile?.path

    // Windows has no share sheet a plain desktop app can call simply: the file goes on the clipboard (pastes as a file
    // into mail, chat or Explorer) and its folder opens with it selected
    override fun share(file: Picked) {
        runCatching {
            val f = File(file.key).takeIf { it.isFile } ?: File(java.nio.file.Files.createTempDirectory("papeles-share").toFile(), file.name).also { it.writeBytes(file.read()) }
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(FileOnClipboard(f), null)
            if (System.getProperty("os.name").startsWith("Windows")) ProcessBuilder("explorer.exe", "/select,", f.absolutePath).start()
            else java.awt.Desktop.getDesktop().open(f.parentFile)
        }
    }

    override val shareNote: String get() = com.jurisgames.papeles.ui.tr("Copied. Paste it with Ctrl+V into an email, a chat or a folder.")

    override fun openWith(file: Picked) {
        runCatching { java.awt.Desktop.getDesktop().open(File(file.key)) }
    }

    override val canPrint: Boolean
        get() = runCatching { java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.PRINT) }.getOrDefault(false)

    override fun print(file: Picked) {
        runCatching { java.awt.Desktop.getDesktop().print(File(file.key)) }
    }

    override fun image(p: Picture): ImageBitmap = skiaImage(p)
}

/** A file on the clipboard, as Explorer puts one there (pastes as the file, or as its path in a text box). */
class FileOnClipboard(private val f: File) : java.awt.datatransfer.Transferable {
    private val flavors = arrayOf(java.awt.datatransfer.DataFlavor.javaFileListFlavor, java.awt.datatransfer.DataFlavor.stringFlavor)
    override fun getTransferDataFlavors() = flavors.clone()
    override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor in flavors
    override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = when (flavor) {
        java.awt.datatransfer.DataFlavor.javaFileListFlavor -> listOf(f)
        java.awt.datatransfer.DataFlavor.stringFlavor -> f.absolutePath
        else -> throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
    }
}

/** Where PDFium is: next to the app when packaged, or `-Dpp.pdfium=DIR` (development, tests). */
fun pdfiumDir(): String = System.getProperty("pp.pdfium") ?: System.getProperty("compose.application.resources.dir") ?: "."

fun main(args: Array<String>) {
    System.getProperty("compose.application.resources.dir")?.let { res ->
        if (System.getProperty("jna.library.path") == null) System.setProperty("jna.library.path", res)
    }
    // A missing engine shows on first open as a plain message (PpException.NoEngine); page work still runs.
    AppInfo.version = "1.0.0 desktop"
    AppInfo.engine = runCatching { loadEngine(pdfiumDir()) }.fold({ "started" }, { "did not start" })
    // `Papeles --check`: the packaged app's launch test (scripts/package-desktop.sh): the bridge and PDFium load,
    // then exit 0, or 1 when they don't; no window opens
    if ("--check" in args) {
        val ok = AppInfo.engine == "started" && runCatching { com.jurisgames.papeles.core.textBoxSize("check", 12f) }.isSuccess
        System.err.println("papeles check: engine ${AppInfo.engine}, bridge ${if (ok) "ok" else "failed"}")
        kotlin.system.exitProcess(if (ok) 0 else 1)
    }
    application {
        var frame: Frame? = null
        val host = remember { DesktopHost { frame } }
        var mode by remember { mutableStateOf(darkMode(host.appFiles)) }
        // U-P21: "Open with" / double-click hands the file on the command line; it was ignored
        val given = remember { routeFiles(args.filter { !it.startsWith("--") }.map(::File)) }
        var incoming by remember { mutableStateOf(given.first) }
        var pictures by remember { mutableStateOf(given.second) }
        Window(onCloseRequest = ::exitApplication, title = tr("Papeles"), state = rememberWindowState(width = 900.dp, height = 1000.dp),
            onKeyEvent = { e ->
                // Ctrl+O opens a PDF, as in every desktop program
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@Window false
                val ctrl = e.isCtrlPressed || e.isMetaPressed
                val K = androidx.compose.ui.input.key.Key
                when {
                    ctrl && e.key == K.O -> { host.pickPdfs(false) { it.firstOrNull()?.let { p -> incoming = p } }; true }
                    // U-P21: the reader's keys
                    ctrl && e.key == K.F -> com.jurisgames.papeles.ui.Keys.commands.tryEmit(com.jurisgames.papeles.ui.KeyCommand.Find)
                    ctrl && (e.key == K.Equals || e.key == K.Plus || e.key == K.NumPadAdd) -> com.jurisgames.papeles.ui.Keys.commands.tryEmit(com.jurisgames.papeles.ui.KeyCommand.ZoomIn)
                    ctrl && (e.key == K.Minus || e.key == K.NumPadSubtract) -> com.jurisgames.papeles.ui.Keys.commands.tryEmit(com.jurisgames.papeles.ui.KeyCommand.ZoomOut)
                    e.key == K.PageDown -> com.jurisgames.papeles.ui.Keys.commands.tryEmit(com.jurisgames.papeles.ui.KeyCommand.NextPage)
                    e.key == K.PageUp -> com.jurisgames.papeles.ui.Keys.commands.tryEmit(com.jurisgames.papeles.ui.KeyCommand.PrevPage)
                    else -> false
                }
            }) {
            frame = window
            // U-P21: files dropped on the window
            androidx.compose.runtime.LaunchedEffect(Unit) {
                window.dropTarget = object : java.awt.dnd.DropTarget() {
                    @Synchronized
                    override fun drop(evt: java.awt.dnd.DropTargetDropEvent) {
                        evt.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                        val files = runCatching { evt.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*> }
                            .getOrDefault(emptyList<Any>()).filterIsInstance<File>()
                        val (pdf, pics) = routeFiles(files)
                        if (pdf != null) incoming = pdf else if (pics.isNotEmpty()) pictures = pics
                        evt.dropComplete(pdf != null || pics.isNotEmpty())
                    }
                }
            }
            AppTheme(mode) {
                PapelesApp(host, incoming = incoming, onIncomingDone = { incoming = null }, pictures = pictures,
                    onPicturesDone = { pictures = emptyList() }) { mode = it }
            }
        }
    }
}
