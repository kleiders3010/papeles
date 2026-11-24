package com.jurisgames.papeles.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.jurisgames.papeles.core.Picture

/** A file the person chose: its name to show, a key to find it again (a path or a content URI) and its bytes. */
class Picked(val name: String, val key: String, val read: () -> ByteArray)

/**
 * What differs between Android and the desktop: choosing and saving files, turning page pictures into images, where
 * small app data lives. Every callback runs on the main thread. Picking nothing calls back with an empty list / null.
 */
interface Host {
    /** Folder for the app's own small files (recent list, settings). */
    val appFiles: java.io.File

    fun pickPdfs(multiple: Boolean, done: (List<Picked>) -> Unit)

    fun pickPictures(done: (List<Picked>) -> Unit)

    /** A new photo from the camera (scanner); where there is none, choosing photos. */
    fun takePhoto(done: (List<Picked>) -> Unit) = pickPictures(done)

    /** Photos the camera brought back after Android closed the app meanwhile (taken once; Android only). */
    fun waitingPhotos(): List<Picked> = emptyList()

    /** Saves `bytes` where the person chooses, `suggested` as the starting name; calls back with what was saved. */
    fun save(suggested: String, bytes: ByteArray, done: (Picked?) -> Unit)

    /** The folder a saved file went to, in the person's words ("Download"), when it can be told; else null. */
    fun folderOf(file: Picked): String? = null

    /** A file from the recent list, if it can still be read. */
    fun reopen(name: String, key: String): Picked?

    /** Hands a saved file to another app (share sheet / the system's default app). */
    fun share(file: Picked)

    fun image(p: Picture): ImageBitmap
}
