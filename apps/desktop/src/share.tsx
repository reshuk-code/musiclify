import { useEffect, useState } from "react";
import { revealItemInDir } from "@tauri-apps/plugin-opener";
import { Copy, Download } from "lucide-react";
import { db, type Stats } from "./api";
import { Modal, Spinner } from "./components";
import { useStore } from "./store";

// Share cards: story-sized (1080x1920) images of a song (optionally with a lyric line) or of
// listening stats, drawn on a canvas. Deezer's image CDN allows CORS, so covers can be drawn.

const W = 1080;
const H = 1920;

export type CardSpec =
  | { kind: "song"; title: string; artist: string; cover?: string | null; lyric?: string | null }
  | { kind: "stats"; period: string; stats: Stats };

function loadImage(src?: string | null): Promise<HTMLImageElement | null> {
  if (!src) return Promise.resolve(null);
  return new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(img);
    img.onerror = () => resolve(null);
    img.src = src;
  });
}

/** A dark colour from the cover for the background (white text must stay readable). */
function coverColor(img: HTMLImageElement | null): string {
  if (!img) return "#3a2a33";
  const c = document.createElement("canvas");
  c.width = c.height = 24;
  const g = c.getContext("2d")!;
  g.drawImage(img, 0, 0, 24, 24);
  const d = g.getImageData(0, 0, 24, 24).data;
  let best = [58, 42, 51];
  let bestScore = -1;
  for (let i = 0; i < d.length; i += 4) {
    const [r, gg, b] = [d[i], d[i + 1], d[i + 2]];
    const max = Math.max(r, gg, b), min = Math.min(r, gg, b);
    const score = (max - min) * (max > 40 && max < 235 ? 1 : 0.2);
    if (score > bestScore) { bestScore = score; best = [r, gg, b]; }
  }
  const k = 0.45; // darken
  return `rgb(${Math.round(best[0] * k)} ${Math.round(best[1] * k)} ${Math.round(best[2] * k)})`;
}

function wrap(g: CanvasRenderingContext2D, text: string, maxWidth: number, maxLines: number): string[] {
  const words = text.split(/\s+/);
  const lines: string[] = [];
  let line = "";
  for (const w of words) {
    const test = line ? `${line} ${w}` : w;
    if (g.measureText(test).width <= maxWidth || !line) line = test;
    else { lines.push(line); line = w; }
  }
  if (line) lines.push(line);
  if (lines.length > maxLines) {
    const kept = lines.slice(0, maxLines);
    let last = kept[maxLines - 1];
    while (last && g.measureText(`${last}…`).width > maxWidth) last = last.slice(0, -1);
    kept[maxLines - 1] = `${last}…`;
    return kept;
  }
  return lines;
}

function text(g: CanvasRenderingContext2D, s: string, y: number, size: number, opts: { bold?: boolean; color?: string; maxLines?: number } = {}): number {
  g.font = `${opts.bold ? 700 : 400} ${size}px ${opts.bold ? '"Space Grotesk"' : '"Inter"'}, sans-serif`;
  g.fillStyle = opts.color ?? "#fff";
  g.textAlign = "center";
  g.textBaseline = "top";
  const lines = wrap(g, s, W - 160, opts.maxLines ?? 1);
  lines.forEach((l, i) => g.fillText(l, W / 2, y + i * size * 1.25));
  return y + lines.length * size * 1.25;
}

function roundedImage(g: CanvasRenderingContext2D, img: HTMLImageElement | null, x: number, y: number, size: number) {
  g.save();
  g.beginPath();
  g.roundRect(x, y, size, size, 36);
  g.clip();
  if (img) g.drawImage(img, x, y, size, size);
  else { g.fillStyle = "rgba(255,255,255,0.2)"; g.fillRect(x, y, size, size); }
  g.restore();
}

export async function renderCard(spec: CardSpec): Promise<string> {
  await document.fonts.ready;
  const canvas = document.createElement("canvas");
  canvas.width = W;
  canvas.height = H;
  const g = canvas.getContext("2d")!;
  const coverSrc = spec.kind === "song" ? spec.cover : spec.stats.topSongs[0]?.song.coverBig || spec.stats.topSongs[0]?.song.cover;
  const img = await loadImage(coverSrc);
  const grad = g.createLinearGradient(0, 0, 0, H);
  grad.addColorStop(0, coverColor(img));
  grad.addColorStop(1, "#0e1013");
  g.fillStyle = grad;
  g.fillRect(0, 0, W, H);
  const accent = "#ffc4b3";

  if (spec.kind === "song") {
    const size = 760;
    const top = spec.lyric ? 260 : 400;
    roundedImage(g, img, (W - size) / 2, top, size);
    let y = text(g, spec.title, top + size + 90, 72, { bold: true, maxLines: 2 });
    y = text(g, spec.artist, y + 12, 46, { color: "rgba(255,255,255,0.8)" });
    if (spec.lyric) text(g, `“${spec.lyric}”`, y + 70, 58, { bold: true, color: accent, maxLines: 4 });
  } else {
    const s = spec.stats;
    let y = text(g, `My ${spec.period} in music`, 170, 64, { bold: true, maxLines: 2 });
    const size = 520;
    roundedImage(g, img, (W - size) / 2, y + 50, size);
    y += 50 + size + 80;
    y = text(g, `${s.minutes.toLocaleString()} minutes`, y, 96, { bold: true });
    y = text(g, `${s.plays} plays · ${s.songCount} songs`, y + 6, 42, { color: "rgba(255,255,255,0.8)" });
    y = text(g, "Top songs", y + 70, 40, { bold: true, color: accent });
    s.topSongs.slice(0, 5).forEach((t, i) => { y = text(g, `${i + 1}. ${t.song.title} · ${t.song.artist}`, y + 14, 42); });
    const topArtist = s.topArtists[0];
    if (topArtist) {
      y = text(g, "Top artist", y + 50, 40, { bold: true, color: accent });
      text(g, topArtist.artist, y + 10, 60, { bold: true });
    }
  }
  text(g, "Musiclify", H - 150, 44, { bold: true, color: "rgba(255,255,255,0.86)" });
  return canvas.toDataURL("image/png");
}

/** Preview a share card, then save it to Downloads or copy it to the clipboard. */
export function ShareDialog({ spec, name, onClose }: { spec: CardSpec; name: string; onClose: () => void }) {
  const store = useStore();
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => { renderCard(spec).then(setUrl).catch(() => store.showToast("Couldn't make the image.")); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const save = async () => {
    if (!url) return;
    try {
      const path = await db.saveImage(`${name} - Musiclify`, url.split(",")[1]);
      store.showToast("Saved to Downloads");
      revealItemInDir(path).catch(() => {});
      onClose();
    } catch (e) {
      store.showToast(String(e));
    }
  };
  const copy = async () => {
    if (!url) return;
    try {
      const blob = await (await fetch(url)).blob();
      await navigator.clipboard.write([new ClipboardItem({ "image/png": blob })]);
      store.showToast("Image copied. Paste it anywhere.");
      onClose();
    } catch {
      store.showToast("Couldn't copy here. Use Save instead.");
    }
  };

  return (
    <Modal title="Share" onClose={onClose}>
      <div className="share-preview">{url ? <img src={url} alt="Share card preview" /> : <Spinner />}</div>
      <div className="modal-actions">
        <button className="btn ghost" onClick={copy} disabled={!url}><Copy size={16} />Copy image</button>
        <button className="btn primary" onClick={save} disabled={!url}><Download size={16} />Save image</button>
      </div>
    </Modal>
  );
}
