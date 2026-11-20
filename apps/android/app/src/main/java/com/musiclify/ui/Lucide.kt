// GENERATED from the Lucide icon set (ISC license, https://lucide.dev) by tools/lucide-gen.js.
// Same icons as the desktop app (lucide-react): 24x24 viewport, 2px round stroke.
package com.musiclify.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

@Suppress("unused")
object Lucide {
    private val cache = HashMap<String, ImageVector>()

    private fun icon(name: String, filled: Boolean, vararg paths: String): ImageVector = cache.getOrPut(name + filled) {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }

    val House: ImageVector get() = icon("House", false, "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8", "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Search: ImageVector get() = icon("Search", false, "m21 21-4.34-4.34", "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z")
    val Library: ImageVector get() = icon("Library", false, "m16 6 4 14", "M12 6v14", "M8 8v12", "M4 4v16")
    val Settings: ImageVector get() = icon("Settings", false, "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z")
    val SlidersHorizontal: ImageVector get() = icon("SlidersHorizontal", false, "M10 5H3", "M12 19H3", "M14 3v4", "M16 17v4", "M21 12h-9", "M21 19h-5", "M21 5h-7", "M8 10v4", "M8 12H3")
    val RefreshCw: ImageVector get() = icon("RefreshCw", false, "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5")
    val Play: ImageVector get() = icon("Play", false, "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z")
    val PlayFilled: ImageVector get() = icon("Play", true, "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z")
    val Pause: ImageVector get() = icon("Pause", false, "M15 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1Z", "M6 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1Z")
    val PauseFilled: ImageVector get() = icon("Pause", true, "M15 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1Z", "M6 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1Z")
    val SkipForward: ImageVector get() = icon("SkipForward", false, "M21 4v16", "M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z")
    val SkipForwardFilled: ImageVector get() = icon("SkipForward", true, "M21 4v16", "M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z")
    val SkipBack: ImageVector get() = icon("SkipBack", false, "M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432z", "M3 20V4")
    val SkipBackFilled: ImageVector get() = icon("SkipBack", true, "M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432z", "M3 20V4")
    val Shuffle: ImageVector get() = icon("Shuffle", false, "m18 14 4 4-4 4", "m18 2 4 4-4 4", "M2 18h1.973a4 4 0 0 0 3.3-1.7l5.454-8.6a4 4 0 0 1 3.3-1.7H22", "M2 6h1.972a4 4 0 0 1 3.6 2.2", "M22 18h-6.041a4 4 0 0 1-3.3-1.8l-.359-.45")
    val Repeat: ImageVector get() = icon("Repeat", false, "m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3")
    val Repeat1: ImageVector get() = icon("Repeat1", false, "m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3", "M11 10h1v4")
    val Heart: ImageVector get() = icon("Heart", false, "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5")
    val HeartFilled: ImageVector get() = icon("Heart", true, "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5")
    val MicVocal: ImageVector get() = icon("MicVocal", false, "m11 7.601-5.994 8.19a1 1 0 0 0 .1 1.298l.817.818a1 1 0 0 0 1.314.087L15.09 12", "M16.5 21.174C15.5 20.5 14.372 20 13 20c-2.058 0-3.928 2.356-6 2-2.072-.356-2.775-3.369-1.5-4.5", "M11 7a5 5 0 1 0 10 0a5 5 0 1 0 -10 0Z")
    val ListMusic: ImageVector get() = icon("ListMusic", false, "M16 5H3", "M11 12H3", "M11 19H3", "M21 16V5", "M15 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z")
    val Download: ImageVector get() = icon("Download", false, "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5")
    val Check: ImageVector get() = icon("Check", false, "M20 6 9 17l-5-5")
    val CircleCheck: ImageVector get() = icon("CircleCheck", false, "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "m16 9-5.5 5.5L8 12")
    val Ellipsis: ImageVector get() = icon("Ellipsis", false, "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z", "M18 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z", "M4 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z")
    val EllipsisVertical: ImageVector get() = icon("EllipsisVertical", false, "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z", "M11 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z", "M11 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z")
    val X: ImageVector get() = icon("X", false, "M18 6 6 18", "m6 6 12 12")
    val ChevronDown: ImageVector get() = icon("ChevronDown", false, "m6 9 6 6 6-6")
    val ChevronRight: ImageVector get() = icon("ChevronRight", false, "m9 18 6-6-6-6")
    val ArrowLeft: ImageVector get() = icon("ArrowLeft", false, "m12 19-7-7 7-7", "M19 12H5")
    val Moon: ImageVector get() = icon("Moon", false, "M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401")
    val CirclePlay: ImageVector get() = icon("CirclePlay", false, "M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z")
    val Plus: ImageVector get() = icon("Plus", false, "M5 12h14", "M12 5v14")
    val User: ImageVector get() = icon("User", false, "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M8 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z")
    val Trash2: ImageVector get() = icon("Trash2", false, "M10 11v6", "M14 11v6", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    val CircleMinus: ImageVector get() = icon("CircleMinus", false, "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "M8 12h8")
    val ListPlus: ImageVector get() = icon("ListPlus", false, "M16 5H3", "M11 12H3", "M16 19H3", "M18 9v6", "M21 12h-6")
    val ListEnd: ImageVector get() = icon("ListEnd", false, "M16 5H3", "M16 12H3", "M9 19H3", "m16 16-3 3 3 3", "M21 5v12a2 2 0 0 1-2 2h-6")
    val ScanQrCode: ImageVector get() = icon("ScanQrCode", false, "M17 12v4a1 1 0 0 1-1 1h-4", "M17 3h2a2 2 0 0 1 2 2v2", "M17 8V7", "M21 17v2a2 2 0 0 1-2 2h-2", "M3 7V5a2 2 0 0 1 2-2h2", "M7 17h.01", "M7 21H5a2 2 0 0 1-2-2v-2", "M8 7h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z")
    val QrCode: ImageVector get() = icon("QrCode", false, "M4 3h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M17 3h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M4 16h3a1 1 0 0 1 1 1v3a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-3a1 1 0 0 1 1 -1Z", "M21 16h-3a2 2 0 0 0-2 2v3", "M21 21v.01", "M12 7v3a2 2 0 0 1-2 2H7", "M3 12h.01", "M12 3h.01", "M12 16v.01", "M16 12h1", "M21 12v.01", "M12 21v-1")
    val ArrowUp: ImageVector get() = icon("ArrowUp", false, "m5 12 7-7 7 7", "M12 19V5")
    val ArrowDown: ImageVector get() = icon("ArrowDown", false, "M12 5v14", "m19 12-7 7-7-7")
    val Smartphone: ImageVector get() = icon("Smartphone", false, "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2Z", "M12 18h.01")
    val Monitor: ImageVector get() = icon("Monitor", false, "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2Z", "M8 21L16 21", "M12 17L12 21")
    val Lock: ImageVector get() = icon("Lock", false, "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2Z", "M7 11V7a5 5 0 0 1 10 0v4")
    val History: ImageVector get() = icon("History", false, "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2")
    val Music: ImageVector get() = icon("Music", false, "M21 16V3a1 1 0 0 0-1.28-.96l-10 2.917a1 1 0 0 0-.72.96V19", "M15 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z", "M3 19a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z")
    val Timer: ImageVector get() = icon("Timer", false, "M10 2L14 2", "M12 14L15 11", "M4 14a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z")
    val CloudOff: ImageVector get() = icon("CloudOff", false, "M10.94 5.274A7 7 0 0 1 15.71 10h1.79a4.5 4.5 0 0 1 4.222 6.057", "M18.796 18.81A4.5 4.5 0 0 1 17.5 19H9A7 7 0 0 1 5.79 5.78", "m2 2 20 20")
    val Wifi: ImageVector get() = icon("Wifi", false, "M12 20h.01", "M2 8.82a15 15 0 0 1 20 0", "M5 12.859a10 10 0 0 1 14 0", "M8.5 16.429a5 5 0 0 1 7 0")
    val Info: ImageVector get() = icon("Info", false, "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "M12 16v-4", "M12 8h.01")
    val Cookie: ImageVector get() = icon("Cookie", false, "M11 17h.01", "M11.496 2c.324-.016.558.292.529.615a4 4 0 004.235 4.368.713.713 0 01.758.757 4 4 0 004.366 4.237c.323-.03.63.204.614.527a10 10 0 01-2.915 6.566A1 1 0 114.93 4.918 10 10 0 0111.496 2", "M12 12h.01", "M16 16h.01", "M16 3h.01", "M21 4h.01", "M21 8h.01", "M7 14h.01", "M9 8h.01")
    val HardDrive: ImageVector get() = icon("HardDrive", false, "M10 16h.01", "M2.212 11.577a2 2 0 0 0-.212.896V18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5.527a2 2 0 0 0-.212-.896L18.55 5.11A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z", "M21.946 12.013H2.054", "M6 16h.01")
    val Pencil: ImageVector get() = icon("Pencil", false, "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4")
    val MonitorSmartphone: ImageVector get() = icon("MonitorSmartphone", false, "M18 8V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h8", "M10 19v-3.96 3.15", "M7 19h5", "M18 12h2a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-2a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2Z")
    val RefreshCcw: ImageVector get() = icon("RefreshCcw", false, "M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16", "M16 16h5v5")
}
