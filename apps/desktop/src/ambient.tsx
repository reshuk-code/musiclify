import { useEffect, useRef, useState, type RefObject } from "react";
import { usePlayer } from "./player";

/**
 * Ambient light: a soft glow behind the app in the colours of the playing song's cover,
 * pulsing with the beat. Colours come from the artwork; the beat from a Web Audio analyser
 * on the player's <audio> element (audio is served with CORS, so it stays audible).
 */

const KEY = "ambient";
const EVENT = "ambient-change";
const FALLBACK = ["#ff7a59", "#ff4d6d", "#7a5cff"];

export const ambientEnabled = () => localStorage.getItem(KEY) !== "off";
export function setAmbientEnabled(on: boolean) {
  localStorage.setItem(KEY, on ? "on" : "off");
  window.dispatchEvent(new Event(EVENT));
}

function useAmbientEnabled() {
  const [on, setOn] = useState(ambientEnabled);
  useEffect(() => {
    const update = () => setOn(ambientEnabled());
    window.addEventListener(EVENT, update);
    return () => window.removeEventListener(EVENT, update);
  }, []);
  return on;
}

/** Up to three vivid, distinct colours from an image (fallback: the app's accent palette). */
export function pickColors(pixels: Uint8ClampedArray): string[] {
  const buckets = new Map<number, { r: number; g: number; b: number; n: number; score: number }>();
  for (let i = 0; i < pixels.length; i += 4) {
    const r = pixels[i], g = pixels[i + 1], b = pixels[i + 2];
    if (pixels[i + 3] < 128) continue;
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    const light = (max + min) / 510;
    if (light < 0.12 || light > 0.92) continue; // near-black/white make a dull glow
    const sat = max === min ? 0 : (max - min) / (255 - Math.abs(max + min - 255));
    const key = ((r >> 5) << 6) | ((g >> 5) << 3) | (b >> 5);
    const bucket = buckets.get(key) ?? { r: 0, g: 0, b: 0, n: 0, score: 0 };
    bucket.r += r; bucket.g += g; bucket.b += b; bucket.n++; bucket.score += 0.25 + sat;
    buckets.set(key, bucket);
  }
  const ranked = [...buckets.values()].sort((a, b) => b.score - a.score)
    .map((c) => [c.r / c.n, c.g / c.n, c.b / c.n].map(Math.round));
  const picked: number[][] = [];
  for (const c of ranked) {
    if (picked.every((p) => Math.hypot(p[0] - c[0], p[1] - c[1], p[2] - c[2]) > 70)) picked.push(c);
    if (picked.length === 3) break;
  }
  const out = picked.map(([r, g, b]) => `rgb(${r} ${g} ${b})`);
  return [...out, ...FALLBACK].slice(0, 3);
}

function useArtworkColors(url: string | null | undefined) {
  const [colors, setColors] = useState(FALLBACK);
  useEffect(() => {
    if (!url) return;
    let alive = true;
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => {
      try {
        const c = document.createElement("canvas");
        c.width = c.height = 32;
        const ctx = c.getContext("2d", { willReadFrequently: true })!;
        ctx.drawImage(img, 0, 0, 32, 32);
        if (alive) setColors(pickColors(ctx.getImageData(0, 0, 32, 32).data));
      } catch {
        // Image without CORS: keep the current colours.
      }
    };
    img.src = url;
    return () => { alive = false; };
  }, [url]);
  return colors;
}

/** Shared analyser: an <audio> element can only be routed into Web Audio once. */
let graph: { ctx: AudioContext; analyser: AnalyserNode } | null = null;
function analyserFor(audio: HTMLAudioElement) {
  if (!graph) {
    const ctx = new AudioContext();
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 1024;
    analyser.smoothingTimeConstant = 0.6;
    ctx.createMediaElementSource(audio).connect(analyser);
    analyser.connect(ctx.destination);
    graph = { ctx, analyser };
  }
  if (graph.ctx.state === "suspended") graph.ctx.resume();
  return graph.analyser;
}

/** Writes a 0..1 `--beat` value onto the element every frame while music plays. */
function useBeat(audio: HTMLAudioElement, target: RefObject<HTMLDivElement | null>, active: boolean) {
  useEffect(() => {
    const el = target.current;
    if (!el) return;
    if (!active || matchMedia("(prefers-reduced-motion: reduce)").matches) {
      el.style.setProperty("--beat", "0");
      return;
    }
    let analyser: AnalyserNode;
    try { analyser = analyserFor(audio); } catch { return; }
    const bins = new Uint8Array(analyser.frequencyBinCount);
    const binHz = analyser.context.sampleRate / analyser.fftSize;
    const bassEnd = Math.max(2, Math.round(160 / binHz)); // kick drum and bass live under ~160 Hz
    let average = 0, level = 0, frame = 0;
    const loop = () => {
      analyser.getByteFrequencyData(bins);
      let energy = 0;
      for (let i = 1; i < bassEnd; i++) energy += bins[i];
      energy /= bassEnd - 1;
      average = average * 0.95 + energy * 0.05;
      // A beat is bass energy jumping above its recent average; it then decays smoothly.
      const kick = Math.max(0, (energy - average * 1.08) / Math.max(24, 255 - average));
      level = Math.max(level * 0.88, Math.min(1, kick * 2.2));
      el.style.setProperty("--beat", level.toFixed(3));
      frame = requestAnimationFrame(loop);
    };
    frame = requestAnimationFrame(loop);
    return () => { cancelAnimationFrame(frame); el.style.setProperty("--beat", "0"); };
  }, [audio, target, active]);
}

/** `contained` renders inside its parent (the full-screen player) instead of behind the whole app. */
export function Ambient({ intense = false, contained = false }: { intense?: boolean; contained?: boolean }) {
  const p = usePlayer();
  const enabled = useAmbientEnabled();
  const ref = useRef<HTMLDivElement>(null);
  const [c1, c2, c3] = useArtworkColors(enabled ? p.current?.cover : null);
  useBeat(p.audioEl, ref, enabled && p.playing);
  if (!enabled || !p.current) return null;
  return (
    <div ref={ref} className={`ambient ${intense ? "intense" : ""} ${contained ? "contained" : ""} ${p.playing ? "playing" : ""}`} aria-hidden
      style={{ ["--c1" as any]: c1, ["--c2" as any]: c2, ["--c3" as any]: c3 }} />
  );
}
