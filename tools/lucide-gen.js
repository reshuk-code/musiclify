// Convert Lucide icons (same set the desktop app uses via lucide-react) into a Kotlin file of
// Compose ImageVectors: 24x24, 2px round stroke.
// Usage (needs `npm i lucide` in a scratch folder): node tools/lucide-gen.js apps/android/app/src/main/java/com/musiclify/ui/Lucide.kt
const fs = require("fs");
const lucide = require("lucide");

// Name in Kotlin -> Lucide export name. "Filled" variants also fill the shape (like fill="currentColor" on desktop).
const ICONS = {
  House: "House", Search: "Search", Library: "Library", Settings: "Settings", SlidersHorizontal: "SlidersHorizontal",
  RefreshCw: "RefreshCw", Play: "Play", Pause: "Pause", SkipForward: "SkipForward", SkipBack: "SkipBack",
  Shuffle: "Shuffle", Repeat: "Repeat", Repeat1: "Repeat1", Heart: "Heart", MicVocal: "MicVocal", ListMusic: "ListMusic",
  Download: "Download", Check: "Check", CircleCheck: "CircleCheck", Ellipsis: "Ellipsis", EllipsisVertical: "EllipsisVertical",
  X: "X", ChevronDown: "ChevronDown", ChevronRight: "ChevronRight", ArrowLeft: "ArrowLeft", Moon: "Moon",
  CirclePlay: "CirclePlay", Plus: "Plus", User: "User", Trash2: "Trash2", CircleMinus: "CircleMinus", ListPlus: "ListPlus",
  ListEnd: "ListEnd", ScanQrCode: "ScanQrCode", QrCode: "QrCode", ArrowUp: "ArrowUp", ArrowDown: "ArrowDown",
  Smartphone: "Smartphone", Monitor: "Monitor", Lock: "Lock", History: "History", Music: "Music", Timer: "Timer",
  CloudOff: "CloudOff", Wifi: "Wifi", Info: "Info", Cookie: "Cookie", HardDrive: "HardDrive", Pencil: "Pencil", MonitorSmartphone: "MonitorSmartphone", RefreshCcw: "RefreshCcw",
};
const FILLED = ["Play", "Pause", "SkipForward", "SkipBack", "Heart"];

const n = (v) => Number(v);
const fmt = (v) => String(+n(v).toFixed(3));

function rectPath({ x = 0, y = 0, width, height, rx, ry }) {
  x = n(x); y = n(y); const w = n(width), h = n(height);
  let r = n(rx ?? ry ?? 0);
  if (!r) return `M${fmt(x)} ${fmt(y)}h${fmt(w)}v${fmt(h)}h${fmt(-w)}Z`;
  r = Math.min(r, w / 2, h / 2);
  return `M${fmt(x + r)} ${fmt(y)}h${fmt(w - 2 * r)}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(r)} ${fmt(r)}v${fmt(h - 2 * r)}` +
    `a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(-r)} ${fmt(r)}h${fmt(-(w - 2 * r))}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(-r)} ${fmt(-r)}` +
    `v${fmt(-(h - 2 * r))}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(r)} ${fmt(-r)}Z`;
}

function toPath([tag, a]) {
  switch (tag) {
    case "path": return a.d;
    case "circle": { const cx = n(a.cx), cy = n(a.cy), r = n(a.r); return `M${fmt(cx - r)} ${fmt(cy)}a${fmt(r)} ${fmt(r)} 0 1 0 ${fmt(2 * r)} 0a${fmt(r)} ${fmt(r)} 0 1 0 ${fmt(-2 * r)} 0Z`; }
    case "ellipse": { const cx = n(a.cx), cy = n(a.cy), rx = n(a.rx), ry = n(a.ry); return `M${fmt(cx - rx)} ${fmt(cy)}a${fmt(rx)} ${fmt(ry)} 0 1 0 ${fmt(2 * rx)} 0a${fmt(rx)} ${fmt(ry)} 0 1 0 ${fmt(-2 * rx)} 0Z`; }
    case "rect": return rectPath(a);
    case "line": return `M${fmt(a.x1)} ${fmt(a.y1)}L${fmt(a.x2)} ${fmt(a.y2)}`;
    case "polyline": case "polygon": {
      const pts = a.points.trim().split(/[\s,]+/).map(Number);
      let d = `M${pts[0]} ${pts[1]}`;
      for (let i = 2; i < pts.length; i += 2) d += `L${pts[i]} ${pts[i + 1]}`;
      return tag === "polygon" ? d + "Z" : d;
    }
    default: throw new Error("unsupported tag " + tag);
  }
}

let out = `// GENERATED from the Lucide icon set (ISC license, https://lucide.dev) by tools/lucide-gen.js.
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
`;
for (const [kt, name] of Object.entries(ICONS)) {
  const node = lucide[name];
  if (!node) { console.error("missing icon:", name); continue; }
  const paths = node.map(toPath).map((d) => JSON.stringify(d)).join(", ");
  out += `\n    val ${kt}: ImageVector get() = icon("${kt}", false, ${paths})`;
  if (FILLED.includes(kt)) out += `\n    val ${kt}Filled: ImageVector get() = icon("${kt}", true, ${paths})`;
}
out += "\n}\n";
fs.writeFileSync(process.argv[2], out);
console.log("wrote", Object.keys(ICONS).length, "icons");
