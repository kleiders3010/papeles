package com.jurisgames.papeles.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.jurisgames.papeles.core.loadEngine
import com.jurisgames.papeles.ui.AppTheme
import com.jurisgames.papeles.ui.DarkMode
import com.jurisgames.papeles.ui.Lang
import com.jurisgames.papeles.ui.PapelesApp
import com.jurisgames.papeles.ui.SignatureStore
import org.junit.BeforeClass
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin

/**
 * The app on a phone-sized screen with a lived-in set of files: home, reading with search hits and the page strip,
 * filling a form, signing, marking up and scanning all draw. With -Pshots=<folder> it saves the pictures (the store
 * pictures come from here, APP-RULES 186; -Pstore for the Play size, -Plang=es for Spanish).
 */
@OptIn(ExperimentalTestApi::class)
class PhoneShotsTest {
    companion object {
        @BeforeClass @JvmStatic
        fun engine() {
            loadEngine(System.getProperty("pp.pdfium")!!)
        }
    }

    private val store = System.getProperty("pp.shots.store") == "true"
    private val out = System.getProperty("pp.shots")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
    private val es = Lang.spanish

    /** A two-page lease with a heading, paragraphs and a form on page 2 (name, date, a checkbox). */
    private fun lease(): ByteArray {
        val l = if (es) listOf("Contrato de alquiler", "Entre la propietaria, Marta Giménez, y el inquilino que firma abajo,",
            "se acuerda el alquiler del departamento de la calle Rivadavia 2450, 3.º B,", "por un plazo de veinticuatro meses desde el 1 de diciembre.",
            "1. Alquiler mensual", "El alquiler se paga del 1 al 10 de cada mes por transferencia bancaria.", "El monto se actualiza cada seis meses según el índice acordado.",
            "2. Depósito", "El inquilino entrega un mes de depósito, que se devuelve al terminar", "el contrato si el departamento está en buen estado.",
            "3. Mascotas", "Se permiten mascotas pequeñas con aviso previo a la propietaria.")
        else listOf("Rental agreement", "Between the owner, Martha Gibson, and the tenant who signs below, the",
            "apartment at 2450 Riverside Drive, 3B, is let for twenty-four months", "starting on the first of December.",
            "1. Monthly rent", "The rent is paid between the 1st and the 10th of each month by bank", "transfer. The amount is reviewed every six months as agreed.",
            "2. Deposit", "The tenant pays one month as a deposit, returned at the end of the", "agreement if the apartment is in good condition.",
            "3. Pets", "Small pets are allowed after telling the owner.")
        fun esc(s: String) = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        val p1 = StringBuilder("BT /F2 22 Tf 72 760 Td (${esc(l[0])}) Tj ET\n")
        var y = 720
        for (line in l.drop(1)) {
            val head = line.first().isDigit()
            if (head) y -= 14
            p1.append("BT /${if (head) "F2" else "F1"} ${if (head) 14 else 12} Tf 72 $y Td (${esc(line)}) Tj ET\n")
            y -= if (head) 22 else 18
        }
        val name = if (es) "Nombre del inquilino" else "Tenant's name"
        val date = if (es) "Fecha" else "Date"
        val ok = if (es) "Leí y acepto las condiciones" else "I have read and agree to the terms"
        val sign = if (es) "Firma" else "Signature"
        val p2 = "BT /F2 16 Tf 72 760 Td (${esc(if (es) "Datos y firma" else "Details and signature")}) Tj ET\n" +
            "BT /F1 12 Tf 72 715 Td (${esc(name)}) Tj ET\nBT /F1 12 Tf 72 675 Td (${esc(date)}) Tj ET\n" +
            "BT /F1 12 Tf 96 635 Td (${esc(ok)}) Tj ET\nBT /F1 12 Tf 72 540 Td (${esc(sign)}) Tj ET\n0.5 G 72 530 m 320 530 l S\n" +
            "0.6 G 200 708 200 20 re S 200 668 120 20 re S 72 632 14 14 re S\n"
        val box = "0 g 3 3 8 8 re f"
        return rawPdf(listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm 8 0 R >>",
            "<< /Type /Pages /Kids [4 0 R 6 0 R] /Count 2 >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 12 0 R >> >> /Contents 5 0 R >>",
            "<< /Length ${p1.length} >>\nstream\n$p1\nendstream",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 12 0 R >> >> /Contents 7 0 R /Annots [9 0 R 10 0 R 11 0 R] >>",
            "<< /Length ${p2.length} >>\nstream\n$p2\nendstream",
            "<< /Fields [9 0 R 10 0 R 11 0 R] /DA (/Helv 12 Tf 0 g) /DR << /Font << /Helv 3 0 R >> >> >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (tenant) /TU (${esc(name)}) /V () /Rect [200 708 400 728] /P 6 0 R /DA (/Helv 12 Tf 0 g) >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (date) /TU (${esc(date)}) /V () /Rect [200 668 320 688] /P 6 0 R /DA (/Helv 12 Tf 0 g) >>",
            "<< /Type /Annot /Subtype /Widget /FT /Btn /T (agree) /TU (${if (es) "Acepto las condiciones" else "I agree to the terms"}) /V /Off /AS /Off /Rect [72 632 86 646] /P 6 0 R /AP << /N << /Yes 13 0 R /Off 14 0 R >> >> >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>",
            "<< /Type /XObject /Subtype /Form /BBox [0 0 14 14] /Length ${box.length} >>\nstream\n$box\nendstream",
            "<< /Type /XObject /Subtype /Form /BBox [0 0 14 14] /Length 0 >>\nstream\n\nendstream",
        ))
    }

    /** A photo of a printed page lying a little turned on a wooden table. */
    private fun photo(): ByteArray {
        val img = BufferedImage(900, 1200, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        for (y in 0 until 1200) for (x in 0 until 900) {
            val grain = (sin(x * 0.05 + sin(y * 0.01) * 3) * 12).toInt()
            img.setRGB(x, y, java.awt.Color(120 + grain, 82 + grain / 2, 50).rgb)
        }
        g.transform(AffineTransform.getRotateInstance(-0.06, 450.0, 600.0))
        g.color = java.awt.Color(246, 243, 233)
        g.fillRect(140, 150, 620, 880)
        g.color = java.awt.Color(40, 40, 48)
        g.font = Font("SansSerif", Font.BOLD, 30)
        g.drawString(if (es) "Recibo" else "Receipt", 190, 230)
        g.font = Font("SansSerif", Font.PLAIN, 19)
        val rows = if (es) listOf("Ferretería El Tornillo", "Martillo  ·  1", "Clavos 2 in  ·  200", "Pintura blanca 4 L  ·  1", "Rodillo  ·  2", "Total") else
            listOf("Hardware & Co.", "Hammer  ·  1", "Nails 2 in  ·  200", "White paint 1 gal  ·  1", "Roller  ·  2", "Total")
        for ((i, r) in rows.withIndex()) g.drawString(r, 190, 300 + i * 52)
        for ((i, p) in listOf("12.50", "6.80", "34.90", "9.00", "63.20").withIndex()) g.drawString(p, 610, 352 + i * 52)
        g.stroke = BasicStroke(2f)
        g.drawLine(190, 545, 700, 545)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    /** A handwritten-looking signature: a few loops and a long tail, in pad units. */
    private fun signature(): List<List<Offset>> {
        // a tall first letter, then smaller letters of uneven height and width (enough points to stay round when
        // drawn big), then a long swash under it
        val a = (0..480).map { t -> val s = t / 480.0
            val first = s < 1.0 / 6
            val hump = (1 - kotlin.math.cos(s * 12 * PI)) / 2
            val tall = if (first) 2.2 else 0.7 + 0.4 * kotlin.math.abs(sin(s * 23.0))
            // the sideways swing beats the forward motion at the bottom of each letter: a small loop there
            Offset((20 + s * 190 - sin(s * 12 * PI) * (if (first) 12.0 else 7.5)).toFloat(), (60 - hump * 18 * tall - s * 6).toFloat()) }
        val b = (0..80).map { t -> val s = t / 80.0; Offset((26 + s * 230).toFloat(), (74 - sin(s * PI) * 8 - s * 14).toFloat()) }
        return listOf(a, b)
    }

    @Test
    fun phone() = flow(if (store) 1080 else 412, if (store) 1920 else 860, fit = false)

    /** APP-RULES 196: the same screens on a small phone (360 dp), every fixed button wholly on screen (PhoneFit.kt). */
    @Test
    fun everyButtonFitsASmallPhone() { if (!store) flow(360, 760, fit = true) }

    private fun flow(w: Int, h: Int, fit: Boolean) = runDesktopComposeUiTest(width = w, height = h) {
        val problems = mutableListOf<String>()
        val host = FakeHost()
        SignatureStore.save(host.appFiles, signature())
        val leaseName = if (es) "contrato.pdf" else "lease.pdf"
        host.files[leaseName] = lease()
        setContent {
            val body = @androidx.compose.runtime.Composable { AppTheme(DarkMode.Light) { PapelesApp(host) {} } }
            if (store) androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2.625f)) { body() }
            else body()
        }
        fun shot(name: String) {
            if (fit) { problems += buttonsFit(w, name, OPEN_LISTS[name] ?: 0); return }
            val o = out ?: return
            waitForIdle()
            val roots = onAllNodes(isRoot())
            val base = roots[0].captureToImage().toAwtImage()
            val img = BufferedImage(base.getWidth(null), base.getHeight(null), BufferedImage.TYPE_INT_RGB)
            img.createGraphics().apply { drawImage(base, 0, 0, null); dispose() }
            ImageIO.write(img, "png", File(o, (if (store) "store-" else "phone-") + "$name.png"))
        }
        fun open(name: String) {
            host.pdfs += listOf(host.file(name, host.files[name]!!))
            onNodeWithTag("open").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("page-1")).fetchSemanticsNodes().isNotEmpty() }
        }
        // a few files opened before, so home has a recent list
        for (n in if (es) listOf("factura-noviembre.pdf", "pasaje.pdf") else listOf("november-invoice.pdf", "boarding-pass.pdf")) {
            host.files[n] = textPdf(n.substringBefore('.'))
            open(n)
            onNodeWithTag("back").performClick()
        }
        open(leaseName)
        onNodeWithTag("back").performClick()
        shot("1-home")

        open(leaseName)
        onNodeWithTag("search").performClick()
        onNodeWithTag("searchField").performTextInput(if (es) "alquiler" else "rent")
        onNodeWithTag("searchGo").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("hitCount")).fetchSemanticsNodes().isNotEmpty() }
        mainClock.advanceTimeBy(1_500)
        shot("2-read")

        onNodeWithTag("thumb-2").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("fill")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("fill").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("input-tenant")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("input-tenant").performTextInput(if (es) "Lucía Fernández" else "Lucy Fernandez")
        onNodeWithTag("input-date").performTextInput("01/12/2026")
        onNodeWithTag("input-agree").performClick()
        mainClock.advanceTimeBy(1_500)
        shot("3-fill")
        onNodeWithTag("back").performClick()
        onNodeWithTag("leaveAnyway").performClick() // unsaved work: Back asks first (APP-RULES 203)

        onNodeWithTag("sign").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("signPage-1")).fetchSemanticsNodes().isNotEmpty() }
        // page 2 has the signature line (72..320 pt across, 312 pt from the top): scroll it up, then tap just above
        // the line; the place is worked out from the page's width, since only part of the page may be on screen
        onNode(hasScrollAction()).performScrollToIndex(1)
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("signPage-2")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("signPage-2").performTouchInput { click(Offset(width * (232f / 595f), width * (296f / 595f))) }
        mainClock.advanceTimeBy(1_000)
        shot("4-sign")
        onNodeWithTag("back").performClick()
        onNodeWithTag("leaveAnyway").performClick() // unsaved work: Back asks first (APP-RULES 203)

        onNodeWithTag("mark").performClick()
        onNodeWithTag("toolHighlight").performClick()
        onNodeWithTag("markPage-1").performTouchInput {
            // the highlighter marks the box dragged over, so the drag runs from the line's top left to its bottom right
            down(Offset(width * 0.12f, height * 0.259f)); for (k in 1..20) moveTo(Offset(width * (0.12f + 0.0262f * k), height * (0.259f + 0.00085f * k))); up()
        }
        onNodeWithTag("toolPen").performClick()
        onNodeWithTag("markPage-1").performTouchInput {
            down(Offset(width * 0.1f, height * 0.45f)); for (k in 1..30) moveTo(Offset(width * (0.1f + 0.012f * k), height * (0.45f + 0.012f * sin(k / 3.0).toFloat()))); up()
        }
        mainClock.advanceTimeBy(1_000)
        shot("5-mark")
        onNodeWithTag("back").performClick()
        onNodeWithTag("leaveAnyway").performClick() // unsaved work: Back asks first (APP-RULES 203)
        onNodeWithTag("back").performClick()

        onNodeWithTag("scan").performClick()
        host.pictures += listOf(host.file("photo.jpg", photo()))
        onNodeWithTag("add").performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasTestTag("scanPhoto")).fetchSemanticsNodes().isNotEmpty() }
        mainClock.advanceTimeBy(1_000)
        shot("6-scan")
        check(problems.isEmpty()) { problems.joinToString("\n") }
    }

    // sideways-scrolling rows each screen may have (open-ended lists only, APP-RULES 196)
    // 2-read: the page panning sideways + the page strip; 6-scan: the scanned pages strip
    private val OPEN_LISTS = mapOf("2-read" to 2, "6-scan" to 1)
}
