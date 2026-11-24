package com.jurisgames.papeles.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.runComposeUiTest
import com.jurisgames.papeles.core.PdfInput
import com.jurisgames.papeles.core.Picture
import com.jurisgames.papeles.core.countPages
import com.jurisgames.papeles.core.formFields
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

/** Objects 1..n written as a PDF with counted xref offsets (object 1 is the catalog). */
fun rawPdf(objs: List<String>): ByteArray {
    val out = StringBuilder("%PDF-1.7\n")
    val offsets = objs.mapIndexed { i, o -> out.length.also { out.append("${i + 1} 0 obj\n$o\nendobj\n") } }
    val xref = out.length
    out.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
    for (o in offsets) out.append(String.format("%010d 00000 n \n", o))
    out.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return out.toString().toByteArray(Charsets.ISO_8859_1)
}

/** A one-page form: a text field "name" (holding "old") and a checkbox "news" whose on-state is "Si". */
fun formPdf(): ByteArray {
    val label = "BT /F1 12 Tf 72 745 Td (Your name) Tj 0 -40 Td (Send me news) Tj ET"
    val box = "0 g 2 2 8 8 re f"
    return rawPdf(listOf(
        "<< /Type /Catalog /Pages 2 0 R /AcroForm 6 0 R >>",
        "<< /Type /Pages /Kids [4 0 R] /Count 1 >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents 5 0 R /Annots [7 0 R 8 0 R] >>",
        "<< /Length ${label.length} >>\nstream\n$label\nendstream",
        "<< /Fields [7 0 R 8 0 R] /DA (/Helv 12 Tf 0 g) /DR << /Font << /Helv 3 0 R >> >> >>",
        "<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /V (old) /Rect [200 740 400 760] /P 4 0 R /DA (/Helv 12 Tf 0 g) >>",
        "<< /Type /Annot /Subtype /Widget /FT /Btn /T (news) /V /Off /AS /Off /Rect [200 700 212 712] /P 4 0 R /AP << /N << /Si 9 0 R /Off 10 0 R >> >> >>",
        "<< /Type /XObject /Subtype /Form /BBox [0 0 12 12] /Length ${box.length} >>\nstream\n$box\nendstream",
        "<< /Type /XObject /Subtype /Form /BBox [0 0 12 12] /Length 0 >>\nstream\n\nendstream",
    ))
}

fun png(w: Int, h: Int): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, (x * 255 / w shl 16) or (y * 255 / h shl 8))
    return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
}

/** Picks come from queues the test fills; saves are kept in memory. */
class FakeHost : Host {
    override val appFiles: File = Files.createTempDirectory("papeles-test").toFile()
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
        // the strip of small pages: a tap goes to that page
        onNodeWithTag("thumb-3").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("pageOf").assertTextContains("Page 1 of 3") }.isFailure } // (page 3 may share the screen with page 2: it only has to move on)
        onNodeWithTag("thumb-1").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("pageOf").assertTextContains("Page 1 of 3") }.isSuccess }
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
        assertTrue(File(host.appFiles, "recent.tsv").readText().contains("cats.pdf"))
        onNodeWithTag("recent-cats.pdf").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("page-1")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("back").performClick()
        onNodeWithTag("forget-cats.pdf").performClick()
        onNodeWithTag("noRecent").assertExists()
    }

    @Test
    fun fill_a_form_and_save_a_copy() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        host.pdfs += listOf(host.file("form.pdf", formPdf()))
        onNodeWithTag("open").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("fill")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("fill").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("input-name")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("make").assertIsNotEnabled()
        onNodeWithTag("input-name").performTextClearance()
        onNodeWithTag("input-name").performTextInput("Ana")
        onNodeWithTag("input-news").performClick()
        onAllNodes(hasTestTag("peek")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "each field shows the page around it") }
        onNodeWithTag("make").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 10_000) { host.saved.isNotEmpty() }
        val out = host.saved.single().second
        val f = formFields(PdfInput(out, null)).associateBy { it.name }
        assertEquals("Ana", f["name"]!!.value) // pinned: what was typed
        assertEquals("Si", f["news"]!!.value) // pinned: the box's own on-state, not "Yes"
        // the open file itself is never changed
        assertEquals("old", formFields(PdfInput(host.files["form.pdf"]!!, null)).first { it.name == "name" }.value) // pinned: source untouched
    }

    @Test
    fun draw_a_signature_place_it_and_save_a_signed_copy() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        host.pdfs += listOf(host.file("letter.pdf", textPdf("Dear sir")))
        onNodeWithTag("open").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("sign")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("sign").performClick()
        // no signature kept yet: the pad opens by itself
        onNodeWithTag("useSignature").assertIsNotEnabled()
        onNodeWithTag("pad").performTouchInput {
            down(Offset(width * 0.1f, height * 0.7f))
            moveTo(Offset(width * 0.3f, height * 0.2f)); moveTo(Offset(width * 0.5f, height * 0.7f)); moveTo(Offset(width * 0.9f, height * 0.3f))
            up()
        }
        onNodeWithTag("useSignature").assertIsEnabled().performClick()
        assertTrue(File(host.appFiles, "signature.txt").readText().isNotBlank(), "the signature is kept on the device")
        onNodeWithTag("make").assertIsNotEnabled()
        // (the page is taller than the window: tap in its part that shows, a fifth of the way down)
        onNodeWithTag("signPage-1").performTouchInput { click(Offset(width * 0.5f, height * 0.2f)) }
        onNodeWithTag("signHint").assertTextContains("1", substring = true)
        onNodeWithTag("make").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 10_000) { host.saved.isNotEmpty() }
        val out = host.saved.single().second
        assertEquals("letter (signed).pdf", host.saved.single().first) // pinned: named after the open file
        // the saved page has ink around its middle (where it was tapped), and none at the top corner
        openPdf(out, null).use { f ->
            val p = f.render(1u, 595)
            fun dark(x: Int, y: Int, r: Int) = (-r..r).any { dx -> (-r..r).any { dy ->
                val i = ((y + dy) * p.width.toInt() + (x + dx)) * 4
                p.rgba[i].toInt() and 0xFF < 120 && p.rgba[i + 2].toInt() and 0xFF > 60 } }
            // (a test tap is placed on the part of the page that shows, so where exactly it lands on a page taller
            // than the window is not known here; stamp_test.rs checks the exact spot)
            val cx = p.width.toInt() / 2
            assertTrue((40 until p.height.toInt() * 3 / 10 step 10).any { dark(cx, it, 30) }, "ink in the upper part, across the middle")
            assertTrue((p.height.toInt() / 2 until p.height.toInt() - 20 step 10).none { dark(cx, it, 10) }, "none lower down")
            assertTrue(!dark(30, 30, 10), "no ink in the top corner")
        }
        assertEquals(1, pages(host.files["letter.pdf"]!!)) // pinned: the open file is untouched
    }

    @Test
    fun type_highlight_and_write_on_a_page_and_save_a_marked_copy() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        host.pdfs += listOf(host.file("letter.pdf", textPdf("Dear sir")))
        onNodeWithTag("open").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("mark")).fetchSemanticsNodes().isNotEmpty() }
        val direct = runCatching {
            com.jurisgames.papeles.core.addMarks(PdfInput(host.files["letter.pdf"]!!, null), listOf(
                com.jurisgames.papeles.core.Mark.Text(1u, 60f, 250f, "Hola, sí", 16f),
                com.jurisgames.papeles.core.Mark.Highlight(1u, listOf(com.jurisgames.papeles.core.Area(60f, 400f, 200f, 30f))),
                com.jurisgames.papeles.core.Mark.Pen(1u, listOf(com.jurisgames.papeles.core.InkLine(listOf(
                    com.jurisgames.papeles.core.InkPoint(60f, 650f), com.jurisgames.papeles.core.InkPoint(400f, 650f)))), 2f)))
        }
        assertTrue(direct.isSuccess, "addMarks on its own: ${direct.exceptionOrNull()}")
        onNodeWithTag("mark").performClick()
        onNodeWithTag("make").assertIsNotEnabled()
        // highlighter: drag across the middle; pen: a line low on the page
        onNodeWithTag("toolHighlight").performClick()
        onNodeWithTag("toolHighlight").assertIsSelected()
        onNodeWithTag("markPage-1").performTouchInput {
            down(Offset(width * 0.1f, height * 0.5f)); moveTo(Offset(width * 0.4f, height * 0.52f)); moveTo(Offset(width * 0.6f, height * 0.55f)); up()
        }
        onNodeWithTag("toolPen").performClick()
        onNodeWithTag("toolPen").assertIsSelected()
        onNodeWithTag("markPage-1").performTouchInput {
            down(Offset(width * 0.1f, height * 0.8f)); moveTo(Offset(width * 0.3f, height * 0.8f)); moveTo(Offset(width * 0.5f, height * 0.8f))
            moveTo(Offset(width * 0.8f, height * 0.8f)); up()
        }
        // undo takes the last one away, and it can be drawn again
        onNodeWithTag("undoMark").performClick()
        onNodeWithTag("markPage-1").performTouchInput {
            down(Offset(width * 0.1f, height * 0.8f)); moveTo(Offset(width * 0.3f, height * 0.8f)); moveTo(Offset(width * 0.5f, height * 0.8f))
            moveTo(Offset(width * 0.8f, height * 0.8f)); up()
        }
        // text: back to the text tool, tap, type, a size up, add
        onNodeWithTag("toolText").performClick()
        onNodeWithTag("markPage-1").performTouchInput { click(Offset(width * 0.1f, height * 0.3f)) }
        onNodeWithTag("addText").assertIsNotEnabled()
        onNodeWithTag("markText").performTextInput("Hola, sí")
        onNodeWithTag("textBigger").performClick()
        onNodeWithTag("textSize").assertTextContains("16", substring = true)
        onNodeWithTag("addText").assertIsEnabled().performClick()
        waitForIdle()
        // the dialog is gone before the next tap (a tap while it closes lands on its fading layer)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("markText")).fetchSemanticsNodes().isEmpty() }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        // (through its action, not a tap: tells a tap lost after the dialog from a save that fails)
        onNodeWithTag("make").assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        runCatching { waitUntil(timeoutMillis = 10_000) { host.saved.isNotEmpty() } }.onFailure {
            println("MARK not saved: " + com.jurisgames.papeles.ui.Worker.lastError + " " + onRoot().printToString()); throw it }
        assertEquals("letter (marked).pdf", host.saved.single().first) // pinned: named after the open file
        openPdf(host.saved.single().second, null).use { f ->
            val p = f.render(1u, 600)
            val (w, h) = p.width.toInt() to p.height.toInt()
            fun px(x: Float, y: Float): IntArray { val i = ((y * h).toInt() * w + (x * w).toInt()) * 4; return IntArray(3) { p.rgba[i + it].toInt() and 0xFF } }
            fun near(x: Float, y: Float, r: Int, ok: (IntArray) -> Boolean) = (-r..r).any { dx -> (-r..r).any { dy -> ok(px(x + dx / w.toFloat(), y + dy / h.toFloat())) } }
            val dark = { c: IntArray -> c[0] < 110 && c[1] < 110 }
            val yellow = { c: IntArray -> c[0] > 200 && c[1] > 180 && c[2] < 140 }
            assertTrue(near(0.15f, 0.31f, 12, dark), "the typed text where it was tapped")
            assertTrue(yellow(px(0.35f, 0.525f)), "highlighted in the middle")
            assertTrue(!yellow(px(0.35f, 0.45f)), "nothing highlighted above it")
            assertTrue(near(0.45f, 0.8f, 3, dark), "the pen line low on the page")
            assertTrue(!near(0.45f, 0.7f, 3, dark), "and only there")
        }
        assertEquals(1, pages(host.files["letter.pdf"]!!)) // pinned: the open file is untouched
    }

    @Test
    fun a_pdf_without_fields_has_no_fill_button() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        host.pdfs += listOf(host.file("plain.pdf", textPdf("no form here")))
        onNodeWithTag("open").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("page-1")).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onNodeWithTag("fill").assertDoesNotExist()
    }

    @Test
    fun zoom_in_and_out() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        host.pdfs += listOf(host.file("zoom.pdf", textPdf("small print")))
        onNodeWithTag("open").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("page-1")).fetchSemanticsNodes().isNotEmpty() }
        val w1 = onNodeWithTag("page-1").fetchSemanticsNode().size.width
        onNodeWithTag("zoomOut").assertIsNotEnabled()
        onNodeWithTag("zoomIn").performClick()
        onNodeWithTag("zoomLevel").assertTextContains("${(com.jurisgames.papeles.ui.ZOOMS[1] * 100).toInt()}%")
        val w2 = onNodeWithTag("page-1").fetchSemanticsNode().size.width
        assertTrue(w2 > w1 * (com.jurisgames.papeles.ui.ZOOMS[1] - 0.1f), "the page got wider: $w1 -> $w2")
        repeat(com.jurisgames.papeles.ui.ZOOMS.size) { runCatching { onNodeWithTag("zoomIn").assertIsEnabled().performClick() } }
        onNodeWithTag("zoomIn").assertIsNotEnabled()
        onNodeWithTag("zoomOut").assertIsEnabled().performClick()
        onNodeWithTag("zoomLevel").assertTextContains("${(com.jurisgames.papeles.ui.ZOOMS[com.jurisgames.papeles.ui.ZOOMS.size - 2] * 100).toInt()}%")
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
        // APP-RULES 212 guard: with one PDF, Save is greyed and the screen says why
        host.pdfs += listOf(host.file("a.pdf", textPdf("first A", "second A")))
        onNodeWithTag("add").performClick()
        onNodeWithTag("make").assertIsNotEnabled()
        onNodeWithTag("needMore").assertExists()
        host.pdfs += listOf(host.file("b.pdf", textPdf("only B")))
        onNodeWithTag("add").performClick()
        onNodeWithTag("needMore").assertDoesNotExist()
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
    fun scan_two_photos_reorder_and_save_clean_pages() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        onNodeWithTag("scan").performClick()
        onNodeWithTag("make").assertIsNotEnabled()
        // a cream sheet with one dark bar on a dark table, a little tilted; the second with two bars
        fun photo(bars: Int): ByteArray {
            val img = BufferedImage(600, 700, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = java.awt.Color(70, 45, 30); g.fillRect(0, 0, 600, 700)
            g.color = java.awt.Color(235, 228, 205); g.fillPolygon(intArrayOf(110, 500, 530, 80), intArrayOf(70, 95, 600, 570), 4)
            g.color = java.awt.Color(30, 30, 35)
            for (b in 0 until bars) g.fillRect(180, 250 + b * 150, 240, 24)
            g.dispose()
            return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        }
        host.pictures += listOf(host.file("a.png", photo(1)), host.file("b.png", photo(2)))
        onNodeWithTag("takePhoto").performClick() // no camera here: it chooses photos
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("scanPhoto")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("scanPage-2").assertExists()
        // the second page goes first; the first gets the grey look
        onNodeWithTag("earlier").assertIsNotEnabled() // the first new photo shows first
        onNodeWithTag("scanPage-2").performClick()
        onNodeWithTag("later").assertIsNotEnabled()
        onNodeWithTag("earlier").performClick()
        onNodeWithTag("earlier").assertIsNotEnabled()
        onNodeWithTag("scanPage-2").performClick()
        onNodeWithTag("lookGREY").performClick()
        onNodeWithTag("lookGREY").assertIsSelected()
        onNodeWithTag("make").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 20_000) { host.saved.isNotEmpty() }
        assertEquals("Scan.pdf", host.saved.single().first) // pinned: the scanner's name for its PDF
        val out = host.saved.single().second
        assertEquals(2, pages(out)) // pinned: two photos
        openPdf(out, null).use { f ->
            // page 1 is the two-bar photo, cleaned as a document: white around, two dark bars down the middle column
            val p = f.render(1u, 400)
            val (w, h) = p.width.toInt() to p.height.toInt()
            fun v(x: Int, y: Int) = p.rgba[(y * w + x) * 4].toInt() and 0xFF
            var runs = 0
            var inRun = false
            for (y in 0 until h) {
                val dark = v(w / 2, y) < 90
                if (dark && !inRun) runs++
                inRun = dark
            }
            assertEquals(2, runs, "two bars on the first page") // pinned: the photo drawn with two
            assertTrue(v(w / 2, h / 10) > 230, "white paper above them")
        }
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

    /** APP-RULES 203/170 guard: Back over unsaved work asks first (it dropped the work in one tap); the system Back does the same */
    @Test
    fun back_over_unsaved_work_asks_first() = runComposeUiTest {
        val host = FakeHost()
        setContent { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
        host.pdfs += listOf(host.file("three.pdf", textPdf("page one", "page two", "page three")))
        onNodeWithTag("open").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("editPages")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("editPages").performClick()
        onNodeWithTag("back").performClick()
        onNodeWithTag("editPages").assertExists() // nothing changed yet: back goes straight back
        onNodeWithTag("editPages").performClick()
        onNodeWithTag("card-2").performClick()
        onNodeWithTag("turnRight").performClick()
        onNodeWithTag("back").performClick()
        onNodeWithTag("keepWorking").performClick()
        onNodeWithTag("turnRight").assertExists() // still on the page editor, the turn kept
        com.jurisgames.papeles.ui.SystemBack.current!!.invoke() // the system's Back (Android) goes the same way
        waitForIdle()
        onNodeWithTag("leaveAnyway").performClick()
        onNodeWithTag("editPages").assertExists()
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
