package com.jurisgames.papeles.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few icons the app uses, as path data from Google's Material Icons (Apache-2.0), so
 * neither platform needs the large icon libraries.
 */
object Ic {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

    val More = icon("more", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zm0,2c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zm0,6c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
    val Add = icon("add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Close = icon("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val Delete = icon("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    val Check = icon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val Down = icon("down", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z")
    val Image = icon("image", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    val Search = icon("search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    val Back = icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val Up = icon("up", "M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z")
    /** Material "replay": a turn to the left; mirrored, a turn to the right. */
    val TurnLeft = icon("turnLeft", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z")
    private const val LENS = "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
    /** Material "zoom_in" / "zoom_out": the search lens with a plus or a minus. */
    val ZoomIn = icon("zoomIn", "${LENS}M12,10h-2v2H9v-2H7V9h2V7h1v2h2v1z")
    val ZoomOut = icon("zoomOut", "${LENS}M7,9h5v1H7z")
    /** Material "edit": a pencil, for filling in a form. */
    val Fill = icon("fill", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
    /** Material "draw": a pen over a line, for signing. */
    val Sign = icon("sign", "M18.85,10.39l1.06,-1.06c0.78,-0.78 0.78,-2.05 0,-2.83L18.5,5.09c-0.78,-0.78 -2.05,-0.78 -2.83,0l-1.06,1.06L18.85,10.39zM13.19,7.56L4,16.76V21h4.24l9.19,-9.19L13.19,7.56zM19,17.5c0,2.19 -2.54,3.5 -5,3.5c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1c1.54,0 3,-0.73 3,-1.5c0,-0.47 -0.48,-0.87 -1.23,-1.2l1.48,-1.48C18.32,15.45 19,16.29 19,17.5zM4.58,13.35C3.61,12.79 3,12.06 3,11c0,-1.8 1.89,-2.63 3.56,-3.36C7.59,7.18 9,6.56 9,6c0,-0.41 -0.78,-1 -2,-1C5.74,5 5.2,5.61 5.17,5.64C4.82,6.05 4.19,6.1 3.77,5.76C3.36,5.42 3.28,4.81 3.62,4.38C3.73,4.24 4.76,3 7,3c2.24,0 4,1.32 4,3c0,1.87 -1.93,2.72 -3.64,3.47C6.42,9.88 5,10.5 5,11c0,0.31 0.43,0.6 1.07,0.86L4.58,13.35z")
    /** Material "edit_note": lines with a pen, for marking a page (text, highlighter, pen). */
    /** Corner marks around a page with lines: scanning a paper. */
    val Scan = icon("scan", "M7,3H4v3H2V1h5V3zM22,6V1h-5v2h3v3H22zM7,21H4v-3H2v5h5V21zM20,18v3h-3v2h5v-5H20zM17,5H7v14h10V5zM15,8H9v2h6V8zM15,11H9v2h6V11zM15,14H9v2h6V14z")
    val Mark = icon("mark", "M3,10h11v2H3V10zM3,8h11V6H3V8zM3,16h7v-2H3V16zM18.01,12.87l0.71,-0.71c0.39,-0.39 1.02,-0.39 1.41,0l0.71,0.71c0.39,0.39 0.39,1.02 0,1.41l-0.71,0.71L18.01,12.87zM17.3,13.58l-5.3,5.3V21h2.12l5.3,-5.3L17.3,13.58z")
    val Pages = icon("pages", "M3,5v14h18V5H3zm5,12H5V7h3v10zm5.5,0h-3V7h3v10zm5.5,0h-3V7h3v10z")
}
