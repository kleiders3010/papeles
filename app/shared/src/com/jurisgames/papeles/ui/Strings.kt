package com.jurisgames.papeles.ui

import java.util.Locale

/**
 * The app's words in the device's language: English, or Spanish (Latin American, "vos") when the device is set to
 * Spanish. Each English text is its own key; anything not in [ES] stays English. Placeholders are `{name}`.
 * The choice is made once at start; `-Dpp.lang=es|en` overrides it (tests).
 */
object Lang {
    @Volatile var spanish: Boolean = pick()

    fun pick(): Boolean = (System.getProperty("pp.lang")?.takeIf { it.isNotEmpty() } ?: Locale.getDefault().language) == "es"
}

fun tr(en: String): String = if (Lang.spanish) ES[en] ?: en else en

/** A count in words that read right at 1 (APP-RULES 217): "1 found", "{n} found"; Spanish "1 encontrado". */
fun plural(n: Int, one: String, many: String): String = if (n == 1) tr(one) else tr(many, "n" to n)

fun tr(en: String, vararg args: Pair<String, Any?>): String {
    var s = tr(en)
    for ((k, v) in args) s = s.replace("{$k}", v.toString())
    return s
}

/** What an error says to the person: the reason in plain words and what to do next; raw engine text never shows. */
fun userError(e: Throwable?): String = when (e) {
    is com.jurisgames.papeles.core.PpException.Password -> tr("This PDF is locked with a password.")
    is com.jurisgames.papeles.core.PpException.TooBig -> tr("This file is too big to open safely on this device.")
    is com.jurisgames.papeles.core.PpException.Damaged -> tr("This file isn't a PDF, or it is damaged.")
    is com.jurisgames.papeles.core.PpException.Empty -> tr("Nothing to make: add at least one page.")
    is com.jurisgames.papeles.core.PpException.NoEngine -> tr("The PDF reader didn't start. Reinstalling the app should fix it.")
    is OutOfMemoryError -> tr("This device ran out of memory for that. Try a smaller file.")
    else -> tr("Something went wrong. Try again.")
}

/** "1.2 MB", "350 KB". */
fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(if (Lang.spanish) Locale.forLanguageTag("es-419") else Locale.ENGLISH, "%.1f MB", bytes / 1048576.0)
    else -> "${(bytes + 1023) / 1024} KB"
}

val ES: Map<String, String> = mapOf(
    "Papeles" to "Papeles",
    "Open a PDF" to "Abrir un PDF",
    "Join PDFs" to "Unir PDFs", "joined" to "unido", "smaller" to "más chico", "edited" to "editado", "Scan {date}" to "Escaneo {date}",
    "Pictures to PDF" to "Fotos a PDF",
    "Scan" to "Escanear", "Take a photo" to "Sacar una foto", "Choose photos" to "Elegir fotos",
    "Take a photo of each page, or choose photos. The pages are straightened and cleaned on this device." to
        "Sacá una foto de cada página, o elegí fotos. Las páginas se enderezan y se limpian en este dispositivo.",
    "Drag the corners to the paper's edges." to "Arrastrá las esquinas a los bordes del papel.",
    "Document" to "Documento", "Grey" to "Gris", "Colour" to "Color", "Whole photo" to "Foto entera", "The photo" to "La foto",
    "Make smaller" to "Achicar",
    "Sign a PDF" to "Firmar un PDF", "Fill in a form" to "Completar un formulario", "Mark up a PDF" to "Marcar un PDF",
    "This PDF has no fields to fill: tap where your text goes." to "Este PDF no tiene campos para completar: tocá donde va tu texto.",
    "Recent" to "Recientes",
    "Files you open show up here." to "Los archivos que abras aparecen acá.",
    "Remove from the list" to "Sacar de la lista",
    "This file is no longer there." to "Este archivo ya no está.",
    "Back" to "Volver",
    "Search" to "Buscar",
    "Search in this PDF" to "Buscar en este PDF",
    "Zoom in" to "Acercar", "Zoom out" to "Alejar",
    "Fill in the form" to "Completar el formulario", "Fill: {name}" to "Completar: {name}",
    "Reading the form…" to "Leyendo el formulario…", "This PDF has no fields to fill." to "Este PDF no tiene campos para completar.",
    "This PDF is digitally signed. A filled copy no longer proves who signed it." to
        "Este PDF tiene firma digital. Una copia completada ya no prueba quién lo firmó.",
    "This PDF is digitally signed. A filled copy no longer proves who signed it. The open file stays as it is." to
        "Este PDF tiene firma digital. Una copia completada ya no prueba quién lo firmó. El archivo abierto no cambia.",
    "Save a filled copy?" to "¿Guardar una copia completada?", "Save the copy" to "Guardar la copia",
    "Save filled copy" to "Guardar copia completada", "Nothing changed yet" to "Todavía no cambiaste nada",
    "{n} changed" to "{n} cambiados", "1 changed" to "1 cambiado", "Or type it" to "O escribilo", "filled" to "completado",
    "A digital signature: it can't be filled here." to "Una firma digital: no se puede completar acá.",
    "This field can't be changed." to "Este campo no se puede cambiar.",
    "Sign" to "Firmar", "Sign: {name}" to "Firmar: {name}", "signed" to "firmado",
    "Tap the page where your signature goes." to "Tocá la página donde va tu firma.",
    "{n} placed. Tap again to add another." to "{n} puestas. Tocá de nuevo para agregar otra.",
    "1 placed. Tap again to add another." to "1 puesta. Tocá de nuevo para agregar otra.",
    "Draw a new signature" to "Dibujar una firma nueva", "Undo" to "Deshacer", "Smaller" to "Más chica", "Bigger" to "Más grande",
    "Save signed copy" to "Guardar copia firmada",
    "This is a picture of your signature, as on paper; it is not a digital signature." to
        "Es una imagen de tu firma, como en papel; no es una firma digital.",
    "Draw your signature" to "Dibujá tu firma", "It stays on this device." to "Queda en este dispositivo.",
    "Use this signature" to "Usar esta firma", "Clear" to "Borrar",
    "Mark up" to "Marcar", "Mark: {name}" to "Marcar: {name}", "marked" to "marcado",
    "Text" to "Texto", "Highlight" to "Resaltar", "Pen" to "Lapicera",
    "Tap where the text goes." to "Tocá donde va el texto.", "Drag over what to highlight." to "Arrastrá sobre lo que querés resaltar.",
    "Write or draw with your finger or the mouse." to "Escribí o dibujá con el dedo o el mouse.",
    "Save marked copy" to "Guardar copia marcada", "Add text" to "Agregar texto", "Add" to "Agregar", "{n} points" to "{n} puntos",
    "{n} found" to "{n} encontrados", "1 found" to "1 encontrado",
    "Not found" to "No se encontró",
    "Page {n} of {total}" to "Página {n} de {total}",
    "Go to page {n}" to "Ir a la página {n}",
    "Previous" to "Anterior",
    "Next" to "Siguiente",
    "Close search" to "Cerrar búsqueda",
    "Password" to "Contraseña",
    "Open" to "Abrir",
    "Cancel" to "Cancelar",
    "This PDF is locked. Type its password to open it." to "Este PDF tiene contraseña. Escribila para abrirlo.",
    "That password didn't open it. Check it and try again." to "Esa contraseña no lo abrió. Revisala y probá de nuevo.",
    "This PDF is locked with a password." to "Este PDF tiene contraseña.",
    "This file is too big to open safely on this device." to "Este archivo es demasiado grande para abrirlo con seguridad en este dispositivo.",
    "This file isn't a PDF, or it is damaged." to "Este archivo no es un PDF o está dañado.",
    "Nothing to make: add at least one page." to "No hay nada para armar: agregá al menos una página.",
    "The PDF reader didn't start. Reinstalling the app should fix it." to "El lector de PDF no arrancó. Reinstalar la app debería arreglarlo.",
    "This device ran out of memory for that. Try a smaller file." to "Al dispositivo no le alcanzó la memoria. Probá con un archivo más chico.",
    "Something went wrong. Try again." to "Algo salió mal. Probá de nuevo.",
    "Add PDFs" to "Agregar PDFs",
    "Add pictures" to "Agregar fotos",
    "Move up" to "Subir",
    "Move down" to "Bajar",
    "Remove" to "Quitar",
    "Save as PDF" to "Guardar como PDF",
    "Working…" to "Trabajando…",
    "Saved: {name}" to "Guardado: {name}", "Saved in {folder}: {name}" to "Guardado en {folder}: {name}", "Nothing was saved. Try again." to "No se guardó nada. Probá de nuevo.",
    "Share" to "Compartir",
    "Choose the PDFs to join, in order." to "Elegí los PDFs que querés unir, en orden.",
    "Add at least one more PDF to join." to "Agregá al menos un PDF más para unirlos.",
    "Leave without saving?" to "¿Salir sin guardar?", "What you did here hasn't been saved yet, and it will be lost." to "Lo que hiciste acá todavía no se guardó y se va a perder.",
    "Leave" to "Salir", "Keep working" to "Seguir trabajando",
    "Choose the pictures, one per page." to "Elegí las fotos, una por página.",
    "Page size" to "Tamaño de página",
    "A4" to "A4",
    "Letter" to "Carta",
    "Same as the picture" to "Igual que la foto",
    "Choose a PDF to make smaller." to "Elegí un PDF para achicar.",
    "Choose a PDF" to "Elegir un PDF",
    "For screens" to "Para pantallas",
    "For printing" to "Para imprimir",
    "Light touch" to "Un poco más chico",
    "Smallest; pictures look fine on a phone or computer." to "El más chico; las fotos se ven bien en el celular o la compu.",
    "Pictures stay sharp enough to print at home." to "Las fotos quedan bien para imprimir en casa.",
    "Barely changes how pictures look." to "Casi no cambia cómo se ven las fotos.",
    "{before} → {after}" to "{before} → {after}",
    "This PDF is already as small as it gets here." to "Este PDF ya está lo más chico que se puede acá.",
    "{n} pages" to "{n} páginas",
    "1 page" to "1 página",
    "Turn left" to "Girar a la izquierda",
    "Turn right" to "Girar a la derecha",
    "Delete page" to "Borrar página",
    "Pages: {name}" to "Páginas: {name}",
    "Tap a page to choose it." to "Tocá una página para elegirla.",
    "{n} chosen" to "{n} elegidas", "1 chosen" to "1 elegida",
    "Save chosen pages as a new PDF" to "Guardar las páginas elegidas como PDF nuevo", "chosen pages" to "páginas elegidas",
    "Split after this page" to "Separar después de esta página", "part {n}" to "parte {n}",
    "Edit pages" to "Editar páginas",
    "Appearance" to "Apariencia",
    "Same as the system" to "Igual que el sistema",
    "Light" to "Claro",
    "Dark" to "Oscuro",
    "More" to "Más",
    "Settings" to "Ajustes",
    "Close" to "Cerrar",
    "Your files never leave this device." to "Tus archivos nunca salen de este dispositivo.",
    "Menu" to "Menú",
    "Hide what's sent" to "Ocultar qué se envía",
    "Include technical details" to "Incluir detalles técnicos",
    "It couldn't be sent. Check your connection and try again, or write to hello@jurisgames.com." to
        "No se pudo enviar. Revisá la conexión y probá de nuevo, o escribí a hello@jurisgames.com.",
    "It goes to the person who makes this app. Thanks for helping!" to "Le llega a quien hace esta app. ¡Gracias por ayudar!",
    "See what's sent" to "Ver qué se envía",
    "Send feedback" to "Enviar comentarios",
    "Send" to "Enviar",
    "Sending…" to "Enviando…",
    "Thanks! It was sent." to "¡Gracias! Se envió.",
    "That email doesn't look right." to "Ese email no parece correcto.",
    "The technical details are too big. Untick them and send again." to "Los detalles técnicos son demasiado grandes. Destildalos y enviá de nuevo.",
    "Too many reports from this network today. Please try again tomorrow, or write to hello@jurisgames.com." to
        "Demasiados mensajes desde esta red hoy. Probá de nuevo mañana o escribí a hello@jurisgames.com.",
    "What happened, or what would make it better?" to "¿Qué pasó, o qué la haría mejor?",
    "Write a few words first." to "Escribí algunas palabras primero.",
    "Your email, if you'd like an answer (optional)" to "Tu email, si querés una respuesta (opcional)",
    "Something isn't working" to "Algo no funciona",
    "An idea" to "Una idea",
    "Something else" to "Otra cosa",
    "App version, system and whether the PDF reader started. Never your files or their names." to
        "Versión de la app, sistema y si el lector de PDF arrancó. Nunca tus archivos ni sus nombres.",
)
