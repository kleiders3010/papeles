package com.jurisgames.papeles.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.jurisgames.papeles.core.PdfInput
import com.jurisgames.papeles.core.Picture
import com.jurisgames.papeles.core.countPages
import com.jurisgames.papeles.core.loadEngine
import com.jurisgames.papeles.core.openPdf
import com.jurisgames.papeles.ui.AppTheme
import com.jurisgames.papeles.ui.DarkMode
import com.jurisgames.papeles.ui.Host
import com.jurisgames.papeles.ui.PapelesApp
import com.jurisgames.papeles.ui.Picked
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A PDF with one line of Helvetica text per page, xref offsets counted, so every reader accepts it. */
fun textPdf(vararg pages: String): ByteArray {
    val objs = mutableListOf<String>()
    val n = pages.size
    // 1 catalog, 2 pages, 3 font, then per page: page, contents
    objs += "<< /Type /Catalog /Pages 2 0 R >>"
    objs += "<< /Type /Pages /Kids [${(0 until n).joinToString(" ") { "${4 + it * 2} 0 R" }}] /Count $n >>"
    objs += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    for ((i, text) in pages.withIndex()) {
        val stream = "BT /F1 20 Tf 72 760 Td ($text) Tj ET"
        objs += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents ${5 + i * 2} 0 R >>"
        objs += "<< /Length ${stream.length} >>\nstream\n$stream\nendstream"
    }
    val out = StringBuilder("%PDF-1.7\n")
    val offsets = objs.mapIndexed { i, o -> out.length.also { out.append("${i + 1} 0 obj\n$o\nendobj\n") } }
    val xref = out.length
    out.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
    for (o in offsets) out.append(String.format("%010d 00000 n \n", o))
    out.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return out.toString().toByteArray(Charsets.ISO_8859_1)
}

fun png(w: Int, h: Int): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, (x * 255 / w shl 16) or (y * 255 / h shl 8))
    return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
}

/** Picks come from queues the test fills; saves are kept in memory. */
class FakeHost : Host {
    override val dataDir: File = Files.createTempDirectory("papeles-test").toFile()
    val pdfs = ArrayDeque<List<Picked>>()
    val pictures = ArrayDeque<List<Picked>>()
    val saved = mutableListOf<Pair<String, ByteArray>>()
    val shared = mutableListOf<String>()
    val files = mutableMapOf<String, ByteArray>()

    fun file(name: String, bytes: ByteArray): Picked {
        files[name] = bytes
        return Picked(name, "mem:$name") { files[name] ?: error("gone") }
    }

    override fun pickPdfs(multiple: Boolean, done: (List<Picked>) -> Unit) = done(pdfs.removeFirstOrNull() ?: emptyList())
    override fun pickPictures(done: (List<Picked>) -> Unit) = done(pictures.removeFirstOrNull() ?: emptyList())
    override fun save(suggested: String, bytes: ByteArray, done: (Picked?) -> Unit) {
        saved += suggested to bytes
        done(file(suggested, bytes))
    }
    override fun reopen(name: String, key: String): Picked? = files[name]?.let { Picked(name, key) { it } }
    override fun share(file: Picked) { shared += file.name }
    override fun image(p: Picture): ImageBitmap = skiaImage(p)
}

@OptIn(ExperimentalTestApi::class)
class AppFlowTest {
    @BeforeTest
    fun engine() {
        loadEngine(System.getProperty("pp.pdfium")!!)
    }

    private fun pages(b: ByteArray) = countPages(PdfInput(b, null)).toInt()

    @Test
    fun open_read_search_and_recent() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        onNodeWithTag("noRecent").assertExists()
        host.pdfs += listOf(host.file("cats.pdf", textPdf("The cat sat on the mat", "No animals here", "One more cat")))
        onNodeWithTag("open").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("page-1")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("title").assertTextContains("cats.pdf")
        onNodeWithTag("pageOf").assertTextContains("Page 1 of 3")
        onNodeWithTag("search").performClick()
        onNodeWithTag("searchField").performTextInput("CAT")
        onNodeWithTag("searchGo").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("hitCount")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("hitCount").assertTextContains("2 found", substring = true)
        onNodeWithTag("hitNext").performClick()
        onNodeWithTag("hitCount").assertTextContains("2/2", substring = true)
        onNodeWithTag("back").performClick()
        onNodeWithTag("recent-cats.pdf").assertExists()
        // the recent list survives a restart (it lives in a file)
        assertTrue(File(host.dataDir, "recent.tsv").readText().contains("cats.pdf"))
        onNodeWithTag("recent-cats.pdf").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("page-1")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("back").performClick()
        onNodeWithTag("forget-cats.pdf").performClick()
        onNodeWithTag("noRecent").assertExists()
    }

    @Test
    fun a_file_that_is_not_a_pdf_says_so() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        host.pdfs += listOf(host.file("notes.pdf", "just some text".toByteArray()))
        onNodeWithTag("open").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("This file isn't a PDF, or it is damaged.")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("noRecent").assertExists()
    }

    @Test
    fun join_two_pdfs_in_the_chosen_order() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        onNodeWithTag("join").performClick()
        onNodeWithTag("make").assertIsNotEnabled()
        host.pdfs += listOf(host.file("a.pdf", textPdf("first A", "second A")), host.file("b.pdf", textPdf("only B")))
        onNodeWithTag("add").performClick()
        onNodeWithTag("down-0").performClick() // b first, then a
        // the row's name is a child node (the row itself merges nothing)
        onNodeWithText("1. b.pdf").assertExists()
        onNodeWithText("2. a.pdf").assertExists()
        onNodeWithTag("make").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 10_000) { host.saved.isNotEmpty() }
        val out = host.saved.single().second
        assertEquals(3, pages(out)) // pinned: 1 + 2 pages
        openPdf(out, null).use { assertTrue(it.text(1u).contains("only B")) }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("saved")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("share").performClick()
        assertEquals(1, host.shared.size) // pinned: one share
    }

    @Test
    fun pictures_become_pages() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        onNodeWithTag("pictures").performClick()
        host.pictures += listOf(host.file("one.png", png(300, 200)), host.file("two.png", png(200, 300)))
        onNodeWithTag("add").performClick()
        onNodeWithTag("paper-FIT").performClick()
        onNodeWithTag("make").performClick()
        waitUntil(timeoutMillis = 10_000) { host.saved.isNotEmpty() }
        assertEquals("one.pdf", host.saved.single().first) // pinned: named after the first picture
        assertEquals(2, pages(host.saved.single().second)) // pinned: two pictures
    }

    @Test
    fun shrink_says_when_nothing_gets_smaller() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        onNodeWithTag("shrink").performClick()
        onNodeWithTag("make").assertIsNotEnabled()
        host.pdfs += listOf(host.file("plain.pdf", textPdf("no pictures at all")))
        onNodeWithTag("add").performClick()
        onNodeWithTag("chosen").assertTextContains("plain.pdf")
        onNodeWithTag("level-SMALL").performClick()
        onNodeWithTag("make").performClick()
        // a text-only PDF may still pack a little; either it saves smaller or it says it can't
        waitUntil(timeoutMillis = 10_000) {
            host.saved.isNotEmpty() || onAllNodes(hasText("This PDF is already as small as it gets here.")).fetchSemanticsNodes().isNotEmpty()
        }
        host.saved.firstOrNull()?.let { assertEquals(1, pages(it.second)) } // pinned: page kept
    }

    @Test
    fun edit_pages_turn_move_delete_then_save() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        host.pdfs += listOf(host.file("three.pdf", textPdf("page one", "page two", "page three")))
        onNodeWithTag("open").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("editPages")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("editPages").performClick()
        onNodeWithTag("deletePages").assertIsNotEnabled()
        onNodeWithTag("card-2").performClick()
        onNodeWithTag("deletePages").performClick()
        onNodeWithTag("card-3").performClick()
        onNodeWithTag("moveUp").performClick() // now: three, one
        onNodeWithTag("turnRight").performClick()
        onNodeWithTag("make").performClick()
        waitUntil(timeoutMillis = 10_000) { host.saved.isNotEmpty() }
        val out = host.saved.single().second
        openPdf(out, null).use { f ->
            assertEquals(2u, f.pageCount()) // pinned: one deleted
            val t1 = f.text(1u)
            val t2 = f.text(2u)
            assertTrue(t1.contains("page three"), "page 1 reads: '$t1' (page 2: '$t2')")
            assertTrue(t2.contains("page one"), "page 2 reads: '$t2' (page 1: '$t1')")
            val box = f.pageSize(1u)
            assertTrue(box.width > box.height, "the turned page is wide: $box")
        }
        // the open file itself is never changed
        assertEquals(3, pages(host.files["three.pdf"]!!)) // pinned: source untouched
    }

    @Test
    fun feedback_is_in_the_main_menu_and_says_what_is_sent() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        onNodeWithTag("menu").performClick()
        onNodeWithTag("feedbackMenu").performClick()
        onNodeWithTag("feedbackSee").performClick()
        // the report names the app the endpoint expects and carries no file names (APP-RULES 67)
        onNodeWithTag("feedbackPreview").assertTextContains("\"app\": \"papeles\"", substring = true) // pinned: the endpoint's app id
        onNodeWithTag("feedbackMessage").assertExists()
    }

    @Test
    fun spanish_words_cover_every_tr_call() {
        // every literal passed to tr(...) in the shared UI has a Spanish entry (a missing one shows English to Spanish speakers)
        val src = File("../shared/src").walk().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        val used = Regex("""\btr\("((?:[^"\\]|\\.)*)"""").findAll(src).map { it.groupValues[1] }.toSet()
        val missing = used.filter { it !in com.jurisgames.papeles.ui.ES }
        assertTrue(missing.isEmpty(), "no Spanish for: $missing")
    }
}
