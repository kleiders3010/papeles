package com.jurisgames.papeles.ui

import java.io.File

/** One file on the recent list: what it is called and how to find it again (a path or a content URI). */
data class RecentFile(val name: String, val key: String, val openedMs: Long)

/**
 * The recent list, newest first, at most [MAX] entries, in `recent.tsv` in the app's data folder. Names and keys are
 * stored with tabs and line breaks removed, so one bad name cannot break the file.
 */
object Recent {
    const val MAX = 30

    private fun file(dir: File) = File(dir, "recent.tsv")

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun load(dir: File): List<RecentFile> = runCatching {
        file(dir).readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size == 3) p[2].toLongOrNull()?.let { RecentFile(p[0], p[1], it) } else null
        }
    }.getOrDefault(emptyList()).take(MAX)

    private fun write(dir: File, list: List<RecentFile>) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "recent.tsv.tmp")
            tmp.writeText(list.take(MAX).joinToString("") { "${clean(it.name)}\t${clean(it.key)}\t${it.openedMs}\n" })
            if (!tmp.renameTo(file(dir))) { file(dir).delete(); tmp.renameTo(file(dir)) }
        }
    }

    fun add(dir: File, name: String, key: String, now: Long = System.currentTimeMillis()): List<RecentFile> {
        val list = listOf(RecentFile(clean(name), clean(key), now)) + load(dir).filter { it.key != clean(key) }
        write(dir, list)
        return load(dir)
    }

    /** U-P31: Undo of a remove puts the entry back where its time puts it. */
    fun restore(dir: File, f: RecentFile): List<RecentFile> {
        write(dir, (load(dir).filter { it.key != f.key } + f).sortedByDescending { it.openedMs })
        return load(dir)
    }

    fun remove(dir: File, key: String): List<RecentFile> {
        write(dir, load(dir).filter { it.key != key })
        return load(dir)
    }
}

/** The page each file was last read on (U-P11), kept for the files in Recent only. */
/** U-P30: pages drawn dark (inverted) for reading at night; one setting for every PDF. */
object DarkPages {
    private fun file(dir: File) = File(dir, "dark-pages")
    fun get(dir: File): Boolean = file(dir).exists()
    fun set(dir: File, on: Boolean) {
        runCatching { if (on) file(dir).writeText("1") else file(dir).delete() }
    }
}

object LastPage {
    private fun file(dir: File) = File(dir, "pages.tsv")

    fun load(dir: File): Map<String, Int> = runCatching {
        file(dir).readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { (k, n) -> n.toIntOrNull()?.let { k to it } } }.toMap()
    }.getOrDefault(emptyMap())

    fun get(dir: File, key: String): Int = load(dir)[key] ?: 1

    fun set(dir: File, key: String, page: Int) {
        val keep = Recent.load(dir).map { it.key }.toSet()
        val all = (load(dir) + (key to page)).filterKeys { it in keep || it == key }
        runCatching {
            val tmp = File(dir, "pages.tsv.tmp")
            tmp.writeText(all.entries.joinToString("") { "${it.key.replace('\t', ' ').replace('\n', ' ')}\t${it.value}\n" })
            if (!tmp.renameTo(file(dir))) { file(dir).delete(); tmp.renameTo(file(dir)) }
        }
    }
}
