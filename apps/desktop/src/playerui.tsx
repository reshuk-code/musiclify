import { useEffect, useRef, useState } from "react";
import {
  AlertCircle, Check, ChevronDown, ChevronUp, Heart, ListMusic, Loader2, Maximize2, MicVocal, Moon, Pause, Play, Repeat,
  Repeat1, Shuffle, SkipBack, SkipForward, Volume1, Volume2, VolumeX, X,
} from "lucide-react";
import { formatTime, lyricsFor, type Lyrics } from "./api";
import { Cover, SongMenu } from "./components";
import { ShareDialog } from "./share";
import { usePlayer } from "./player";
import { useStore } from "./store";
import { Ambient } from "./ambient";

export type Panel = "lyrics" | "queue" | null;

// ---------------------------------------------------------------- shared pieces

function SeekBar({ className = "" }: { className?: string }) {
  const p = usePlayer();
  const [drag, setDrag] = useState<number | null>(null);
  const pos = drag ?? p.position;
  const dur = p.duration || p.current?.durationSec || 0;
  const commit = () => { if (drag !== null) { p.seek(drag); setDrag(null); } };
  return (
    <div className={`seek ${className}`}>
      <span className="tiny muted">{formatTime(pos)}</span>
      <input type="range" min={0} max={dur || 1} step={0.5} value={Math.min(pos, dur || 1)} disabled={!p.current}
        aria-label="Seek" style={{ ["--fill" as any]: `${dur ? (pos / dur) * 100 : 0}%` }}
        onChange={(e) => setDrag(Number(e.target.value))} onMouseUp={commit} onKeyUp={commit} />
      <span className="tiny muted">{formatTime(dur)}</span>
    </div>
  );
}

function Controls({ big = false }: { big?: boolean }) {
  const p = usePlayer();
  const s = big ? 26 : 20;
  return (
    <div className={`controls ${big ? "big" : ""}`}>
      <button className={`icon-btn ${p.shuffle ? "on" : ""}`} onClick={p.toggleShuffle} aria-label={p.shuffle ? "Shuffle on" : "Shuffle off"}><Shuffle size={big ? 22 : 18} /></button>
      <button className="icon-btn" onClick={p.previous} aria-label="Previous"><SkipBack size={s} fill="currentColor" /></button>
      <button className="play-btn" onClick={p.toggle} disabled={!p.current} aria-label={p.playing ? "Pause" : "Play"}>
        {p.loading && !p.playing ? <Loader2 size={s} className="spin" /> : p.playing ? <Pause size={s} fill="currentColor" /> : <Play size={s} fill="currentColor" />}
      </button>
      <button className="icon-btn" onClick={p.next} aria-label="Next"><SkipForward size={s} fill="currentColor" /></button>
      <button className={`icon-btn ${p.repeat !== "off" ? "on" : ""}`} onClick={p.cycleRepeat} aria-label={`Repeat ${p.repeat}`}>
        {p.repeat === "one" ? <Repeat1 size={big ? 22 : 18} /> : <Repeat size={big ? 22 : 18} />}
      </button>
    </div>
  );
}

/** First play of a song: Finding → Saving x% → Ready offline. */
function SaveStatus() {
  const p = usePlayer();
  const store = useStore();
  const song = p.current;
  if (!song) return null;
  const progress = store.progress[song.id];
  if (store.downloadedIds.has(song.id)) return <span className="pill ok"><Check size={13} strokeWidth={3} />Offline</span>;
  if (progress !== undefined) return <span className="pill">Saving · {Math.round(progress * 100)}%</span>;
  if (p.loading) return <span className="pill">Finding song…</span>;
  if (p.restored) return <span className="pill">Continue listening</span>;
  return null;
}

// ---------------------------------------------------------------- player bar

export function PlayerBar({ panel, setPanel, onExpand }: { panel: Panel; setPanel: (p: Panel) => void; onExpand: () => void }) {
  const p = usePlayer();
  const store = useStore();
  const song = p.current;
  const liked = song ? store.likedIds.has(song.id) : false;
  const VolIcon = p.volume === 0 ? VolumeX : p.volume < 0.5 ? Volume1 : Volume2;

  return (
    <footer className="player">
      <div className="player-left">
        {song && (
          <>
            <button className="cover-btn" onClick={onExpand} aria-label="Open now playing">
              <Cover src={song.cover} size={56} radius={6} />
              <span className="cover-expand"><ChevronUp size={18} /></span>
            </button>
            <div className="player-meta">
              <span className="row-title">{song.title}</span>
              <button className="row-artist link" onClick={() => song.artistId && store.navigate({ name: "artist", id: song.artistId })}>{song.artist}</button>
              <span className="player-status"><SaveStatus /></span>
            </div>
            <button className={`icon-btn like ${liked ? "on" : ""}`} onClick={() => store.toggleLike(song)} aria-label={liked ? "Remove from Liked" : "Like"}>
              <Heart size={18} fill={liked ? "currentColor" : "none"} />
            </button>
          </>
        )}
      </div>

      <div className="player-center">
        <Controls />
        {p.error ? (
          <div className="player-error"><AlertCircle size={14} />{p.error}<button className="link accent" onClick={p.retry}>Retry</button></div>
        ) : <SeekBar />}
      </div>

      <div className="player-right">
        <button className={`icon-btn ${panel === "lyrics" ? "on" : ""}`} onClick={() => setPanel(panel === "lyrics" ? null : "lyrics")} aria-label="Lyrics"><MicVocal size={18} /></button>
        <button className={`icon-btn ${panel === "queue" ? "on" : ""}`} onClick={() => setPanel(panel === "queue" ? null : "queue")} aria-label="Queue"><ListMusic size={18} /></button>
        <SleepTimer />
        <button className="icon-btn" onClick={() => p.setVolume(p.volume === 0 ? 0.8 : 0)} aria-label={p.volume === 0 ? "Unmute" : "Mute"}><VolIcon size={18} /></button>
        <input className="volume" type="range" min={0} max={1} step={0.01} value={p.volume}
          style={{ ["--fill" as any]: `${p.volume * 100}%` }} onChange={(e) => p.setVolume(Number(e.target.value))} aria-label="Volume" />
        <button className="icon-btn" onClick={onExpand} disabled={!song} aria-label="Full-screen player"><Maximize2 size={17} /></button>
      </div>
    </footer>
  );
}

// ---------------------------------------------------------------- now playing (full view)

export function NowPlayingView({ onClose }: { onClose: () => void }) {
  const p = usePlayer();
  const store = useStore();
  const [tab, setTab] = useState<"lyrics" | "queue">("lyrics");
  const song = p.current;

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  if (!song) return null;
  const liked = store.likedIds.has(song.id);
  const upNext = p.queue.slice(p.index + 1, p.index + 5);

  return (
    <div className="np" role="dialog" aria-label="Now playing">
      <Ambient intense contained />
      <header className="np-head">
        <button className="icon-btn round-bg" onClick={onClose} aria-label="Close now playing"><ChevronDown size={22} /></button>
        <div className="np-from"><span className="kicker">Playing from</span><b>Your queue</b></div>
        <div className="tabs" role="tablist" aria-label="Side view">
          <button role="tab" aria-selected={tab === "lyrics"} className={tab === "lyrics" ? "on" : ""} onClick={() => setTab("lyrics")}>Lyrics</button>
          <button role="tab" aria-selected={tab === "queue"} className={tab === "queue" ? "on" : ""} onClick={() => setTab("queue")}>Up next</button>
        </div>
        <SongMenu song={song} />
      </header>

      <div className="np-body">
        <section className="np-left">
          <Cover src={song.coverBig || song.cover} radius={18} className="np-cover" />
          <div className="np-meta">
            <div className="np-titles">
              <h1 className="np-title">{song.title}</h1>
              <button className="link np-artist" onClick={() => { onClose(); if (song.artistId) store.navigate({ name: "artist", id: song.artistId }); }}>{song.artist}</button>
            </div>
            <SaveStatus />
            <button className={`icon-btn round-bg like-big ${liked ? "on" : ""}`} onClick={() => store.toggleLike(song)} aria-label={liked ? "Remove from Liked" : "Like"}>
              <Heart size={22} fill={liked ? "currentColor" : "none"} />
            </button>
          </div>
          {p.error
            ? <div className="player-error"><AlertCircle size={14} />{p.error}<button className="link accent" onClick={p.retry}>Retry</button></div>
            : <SeekBar className="np-seek" />}
          <Controls big />
        </section>

        <section className="np-right">
          {tab === "lyrics" ? <LyricsView big /> : <QueueView />}
        </section>
      </div>

      {tab === "lyrics" && !!upNext.length && (
        <footer className="np-next">
          <span className="kicker">Up next</span>
          {upNext.map((s, i) => (
            <button key={`${s.id}-${i}`} className="np-chip" onClick={() => p.jumpTo(p.index + 1 + i)}>
              <Cover src={s.cover} size={40} radius={8} />
              <span><b>{s.title}</b><span className="muted small block">{s.artist}{store.downloadedIds.has(s.id) ? " · offline" : ""}</span></span>
            </button>
          ))}
        </footer>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- side panel

export function SidePanel({ panel, onClose }: { panel: Panel; onClose: () => void }) {
  if (!panel) return null;
  return (
    <aside className="side-panel">
      <header className="side-head">
        <h3>{panel === "lyrics" ? "Lyrics" : "Queue"}</h3>
        <button className="icon-btn" onClick={onClose} aria-label="Close"><X size={18} /></button>
      </header>
      {panel === "lyrics" ? <LyricsView /> : <QueueView />}
    </aside>
  );
}

function LyricsView({ big = false }: { big?: boolean }) {
  const p = usePlayer();
  const song = p.current;
  const [lyrics, setLyrics] = useState<Lyrics | null>(null);
  const [shareLine, setShareLine] = useState<string | null>(null);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setLyrics(null);
    if (song) lyricsFor(song).then(setLyrics).catch(() => setLyrics({ synced: false, lines: [] }));
  }, [song?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const ms = p.position * 1000;
  const active = lyrics?.synced ? lyrics.lines.findLastIndex((l) => l.startMs <= ms) : -1;

  useEffect(() => {
    if (active < 0) return;
    box.current?.querySelector(`[data-i="${active}"]`)?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [active]);

  if (!song) return <p className="muted pad">Play a song to see its lyrics.</p>;
  if (!lyrics) return <div className="pad"><Loader2 className="spin" /></div>;
  if (!lyrics.lines.length) return <p className="muted pad">No lyrics found for this song.</p>;
  return (
    <div className={`lyrics ${big ? "big" : ""}`} ref={box}>
      {!lyrics.synced && <p className="muted tiny">Timestamps not available for this song</p>}
      {lyrics.lines.map((l, i) => (
        <p key={i} data-i={i} className={`lyric ${i === active ? "active" : ""} ${lyrics.synced ? "synced" : ""} ${i < active ? "past" : ""}`}
          onClick={() => lyrics.synced && p.seek(l.startMs / 1000)}
          onContextMenu={(e) => { e.preventDefault(); setShareLine(l.text); }} title="Right-click to share this line">{l.text}</p>
      ))}
      {big && <p className="muted small lyric-credit">Lyrics from LRCLIB · click a line to jump there · right-click to share it</p>}
      {shareLine && <ShareDialog spec={{ kind: "song", title: song.title, artist: song.artist, cover: song.coverBig || song.cover, lyric: shareLine }}
        name={`${song.title} - ${song.artist}`} onClose={() => setShareLine(null)} />}
    </div>
  );
}

function QueueView() {
  const p = usePlayer();
  if (!p.queue.length) return <p className="muted pad">The queue is empty.</p>;
  return (
    <div className="queue">
      {p.queue.map((s, i) => (
        <div key={`${s.id}-${i}`} className={`queue-item ${i === p.index ? "is-current" : ""} ${i < p.index ? "past" : ""}`}>
          <button className="queue-main" onClick={() => p.jumpTo(i)}>
            <Cover src={s.cover} size={40} radius={4} />
            <span><span className="row-title">{s.title}</span><span className="muted small block">{s.artist}</span></span>
          </button>
          {i !== p.index && <button className="icon-btn" onClick={() => p.removeAt(i)} aria-label="Remove from queue"><X size={16} /></button>}
        </div>
      ))}
    </div>
  );
}

/** Moon button with a small menu: pause after N minutes or at the end of this song. */
function SleepTimer() {
  const p = usePlayer();
  const [open, setOpen] = useState(false);
  const [, tick] = useState(0);
  const ref = useRef<HTMLDivElement>(null);
  const on = p.sleepEndsAt != null || p.sleepAtSongEnd;

  // Close on outside click; refresh the countdown while the menu is open.
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    const t = window.setInterval(() => tick((n) => n + 1), 15_000);
    document.addEventListener("mousedown", close);
    return () => { document.removeEventListener("mousedown", close); window.clearInterval(t); };
  }, [open]);

  const minsLeft = p.sleepEndsAt != null ? Math.max(1, Math.ceil((p.sleepEndsAt - Date.now()) / 60_000)) : 0;
  const status = p.sleepAtSongEnd ? "Pausing when this song ends" : p.sleepEndsAt != null ? `Pausing in ${minsLeft} min` : null;
  const pick = (when: number | "end" | null) => { p.setSleep(when); setOpen(false); };

  return (
    <div className="sleep" ref={ref}>
      <button className={`icon-btn ${on ? "on" : ""}`} onClick={() => setOpen((o) => !o)}
        aria-label={status ?? "Sleep timer"} title={status ?? "Sleep timer"} aria-expanded={open}><Moon size={17} /></button>
      {open && (
        <div className="sleep-menu" role="menu">
          <span className="muted tiny upper">Sleep timer</span>
          {status && <span className="accent small">{status}</span>}
          {[15, 30, 45, 60].map((m) => <button key={m} role="menuitem" onClick={() => pick(m)}>{m} minutes</button>)}
          <button role="menuitem" onClick={() => pick("end")}>End of this song</button>
          {on && <button role="menuitem" className="danger" onClick={() => pick(null)}>Turn off</button>}
        </div>
      )}
    </div>
  );
}
