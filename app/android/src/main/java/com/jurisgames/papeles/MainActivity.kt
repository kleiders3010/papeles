package com.jurisgames.papeles

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.jurisgames.papeles.core.Picture
import com.jurisgames.papeles.core.loadEngine
import com.jurisgames.papeles.ui.AppInfo
import com.jurisgames.papeles.ui.AppTheme
import com.jurisgames.papeles.ui.Host
import com.jurisgames.papeles.ui.PapelesApp
import com.jurisgames.papeles.ui.Picked
import com.jurisgames.papeles.ui.darkMode
import com.jurisgames.papeles.ui.tr
import java.io.File
import java.nio.ByteBuffer

/**
 * Android: files come and go through the system's file picker (no storage permission). A picked file's read access
 * is kept (persistable permission) so the recent list can open it again.
 */
class MainActivity : ComponentActivity(), Host {
    override val appFiles: File get() = filesDir

    private var onPdfs: ((List<Picked>) -> Unit)? = null
    private var onPictures: ((List<Picked>) -> Unit)? = null
    private var onSaved: ((Picked?) -> Unit)? = null
    private var pendingSave: ByteArray? = null

    /** A PDF handed to the app ("Open with"), opened once the screen is up. */
    private var incoming by mutableStateOf<Picked?>(null)

    private val pickOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        deliver(onPdfs, listOfNotNull(uri)); onPdfs = null
    }
    private val pickMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        deliver(onPdfs, uris); onPdfs = null
    }
    private val pickPics = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        deliver(onPictures, uris); onPictures = null
    }
    // the scanner's camera: the system camera app writes the photo to a file of ours, read once and deleted
    private var onPhoto: ((List<Picked>) -> Unit)? = null
    private var photoFile: java.io.File? = null
    private val shoot = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photoFile
        val done = onPhoto
        photoFile = null
        onPhoto = null
        val bytes = if (ok && f != null && f.length() > 0) runCatching { f.readBytes() }.getOrNull() else null
        f?.delete()
        done?.invoke(if (bytes == null) emptyList() else listOf(Picked("photo.jpg", "photo:" + System.currentTimeMillis()) { bytes }))
    }

    private val create = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val bytes = pendingSave
        pendingSave = null
        val done = onSaved
        onSaved = null
        if (uri == null || bytes == null) { done?.invoke(null); return@registerForActivityResult }
        val ok = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.isSuccess
        done?.invoke(if (ok) picked(uri) else null)
    }

    private fun deliver(to: ((List<Picked>) -> Unit)?, uris: List<Uri>) {
        for (u in uris) runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        to?.invoke(uris.map(::picked))
    }

    private fun nameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"

    private fun picked(uri: Uri) = Picked(nameOf(uri), uri.toString()) {
        contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read $uri")
    }

    override fun pickPdfs(multiple: Boolean, done: (List<Picked>) -> Unit) {
        onPdfs = done
        if (multiple) pickMany.launch(arrayOf("application/pdf")) else pickOne.launch(arrayOf("application/pdf"))
    }

    override fun pickPictures(done: (List<Picked>) -> Unit) {
        onPictures = done
        pickPics.launch(arrayOf("image/jpeg", "image/png"))
    }

    override fun takePhoto(done: (List<Picked>) -> Unit) {
        val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // a photo left by an interrupted shot
        val f = java.io.File(dir, "page-${System.currentTimeMillis()}.jpg")
        photoFile = f
        onPhoto = done
        runCatching { shoot.launch(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)) }.onFailure {
            // no camera app on this device: choose photos instead
            photoFile = null
            onPhoto = null
            pickPictures(done)
        }
    }

    override fun save(suggested: String, bytes: ByteArray, done: (Picked?) -> Unit) {
        pendingSave = bytes
        onSaved = done
        create.launch(suggested)
    }

    override fun reopen(name: String, key: String): Picked? {
        val uri = Uri.parse(key)
        // still readable? (the file may be gone, or its permission dropped)
        val ok = runCatching { contentResolver.openInputStream(uri)?.close() != null }.getOrDefault(false)
        return if (ok) Picked(name, key) { contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read") } else null
    }

    override fun share(file: Picked) {
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, Uri.parse(file.key))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, tr("Share"))) }
    }

    override fun image(p: Picture): ImageBitmap {
        val bmp = Bitmap.createBitmap(p.width.toInt(), p.height.toInt(), Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(p.rgba)) // ARGB_8888 is stored as R, G, B, A bytes: PDFium's order
        return bmp.asImageBitmap()
    }

    private fun take(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        incoming = picked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A missing engine shows on first open as a plain message (PpException.NoEngine); page work still runs.
        AppInfo.version = "1.0.0 android"
        AppInfo.engine = runCatching { loadEngine(applicationInfo.nativeLibraryDir) }.fold({ "started" }, { "did not start" })
        if (savedInstanceState == null) take(intent)
        setContent {
            var mode by androidx.compose.runtime.remember { mutableStateOf(darkMode(appFiles)) }
            // The family's own blue, not wallpaper colors: the app looks the same on every phone and in the store pictures.
            AppTheme(mode) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    PapelesApp(this@MainActivity, incoming = incoming, onIncomingDone = { incoming = null }) { mode = it }
                }
            }
        }
        // Back does what the screen's back arrow does (asking first when work isn't saved); on the home screen it leaves
        // as usual (APP-RULES 170/203: before, Back on any screen closed the app and dropped what was being done)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                com.jurisgames.papeles.ui.SystemBack.current?.let { it(); return }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }
}
