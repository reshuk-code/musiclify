import { useEffect, useRef, useState, type ReactNode } from "react";
import {
  Check, CheckCircle2, Download, Heart, ListEnd, ListPlus, MoreHorizontal, Pause, Play, Plus, Trash2, User, X,
  ListMusic, MinusCircle,
} from "lucide-react";
import { db, formatTime, type Artist, type Song } from "./api";
import { usePlayer } from "./player";
import { useStore } from "./store";

export function Cover({ src, size, radius = 6, className = "" }: { src?: string | null; size?: number; radius?: number; className?: string }) {
  return (
    <div className={`cover ${className}`} style={{ width: size, height: size, borderRadius: radius }}>
      {src ? <img src={src} alt="" loading="lazy" draggable={false} /> : <ListMusic size={20} />}
    </div>
  );
}

export function Section({ title, subtitle, action, children }: { title: string; subtitle?: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="section">
      <header className="section-head">
        <div>
          <h2>{title}</h2>
          {subtitle && <p className="muted small">{subtitle}</p>}
        </div>
        {action}
      </header>
      {children}
    </section>
  );
}

export function Shelf({ children }: { children: ReactNode }) {
  return <div className="shelf">{children}</div>;
}

export function SongCard({ song, onPlay }: { song: Song; onPlay: () => void }) {
  const p = usePlayer();
  const store = useStore();
  const isCurrent = p.current?.id === song.id;
  return (
    <button className={`card ${isCurrent ? "is-current" : ""}`} onClick={onPlay} title={`${song.title} · ${song.artist}`}>
      <div className="card-art">
        <Cover src={song.cover} radius={8} />
        {store.downloadedIds.has(song.id) && <span className="offline-badge"><Check size={12} strokeWidth={3} />Offline</span>}
        <span className="card-play">{isCurrent && p.playing ? <Pause size={20} fill="currentColor" /> : <Play size={20} fill="currentColor" />}</span>
      </div>
      <span className="card-title">{song.title}</span>
      <span className="card-sub">{song.artist}</span>
    </button>
  );
}

export function ArtistTile({ artist, selected, onClick, size = 140 }: { artist: Artist; selected?: boolean; onClick: () => void; size?: number }) {
  return (
    <button className={`artist-tile ${selected ? "is-selected" : ""}`} onClick={onClick} style={{ width: size }}>
      <div className="artist-pic" style={{ width: size, height: size }}>
        {artist.picture ? <img src={artist.picture} alt="" loading="lazy" draggable={false} /> : <User size={40} />}
        {selected && <span className="artist-check"><CheckCircle2 size={40} /></span>}
      </div>
      <span className="card-title center">{artist.name}</span>
    </button>
  );
}

export function SongRow({ song, index, onPlay, onRemove }: { song: Song; index: number; onPlay: () => void; onRemove?: () => void }) {
  const p = usePlayer();
  const store = useStore();
  const isCurrent = p.current?.id === song.id;
  const liked = store.likedIds.has(song.id);
  const progress = store.progress[song.id];
  const downloaded = store.downloadedIds.has(song.id);

  return (
    <div className={`row ${isCurrent ? "is-current" : ""}`} onDoubleClick={onPlay}>
      <button className="row-index" onClick={onPlay} aria-label={`Play ${song.title}`}>
        <span className="num">{isCurrent && p.playing ? <span className="eq"><i /><i /><i /></span> : index + 1}</span>
        <Play size={14} fill="currentColor" className="play" />
      </button>
      <Cover src={song.cover} size={40} radius={4} />
      <div className="row-main">
        <span className="row-title">{song.title}</span>
        <button className="row-artist link" onClick={() => song.artistId && store.navigate({ name: "artist", id: song.artistId })}>
          {song.artist}
        </button>
      </div>
      <span className="row-album muted">{song.album}</span>
      <span className="row-status">
        {progress !== undefined ? (
          <span className="ring" style={{ ["--p" as any]: progress }} title={`Downloading ${Math.round(progress * 100)}%`} />
        ) : downloaded ? (
          <span title="Downloaded, plays offline"><Download size={14} className="accent" /></span>
        ) : null}
      </span>
      <button className={`icon-btn like ${liked ? "on" : ""}`} onClick={() => store.toggleLike(song)} aria-label={liked ? "Remove from Liked" : "Like"}>
        <Heart size={16} fill={liked ? "currentColor" : "none"} />
      </button>
      <span className="row-time muted">{formatTime(song.durationSec)}</span>
      <SongMenu song={song} onRemove={onRemove} />
    </div>
  );
}

export function SongMenu({ song, onRemove }: { song: Song; onRemove?: () => void }) {
  const p = usePlayer();
  const store = useStore();
  const [open, setOpen] = useState(false);
  const [pick, setPick] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const downloaded = store.downloadedIds.has(song.id);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [open]);

  const item = (label: string, icon: ReactNode, fn: () => void) => (
    <button className="menu-item" onClick={() => { setOpen(false); fn(); }}>{icon}{label}</button>
  );

  return (
    <div className="menu-wrap" ref={ref}>
      <button className="icon-btn" onClick={() => setOpen((o) => !o)} aria-label="More options"><MoreHorizontal size={18} /></button>
      {open && (
        <div className="menu">
          {item("Play next", <ListEnd size={16} />, () => { p.playNext(song); store.showToast("Playing next"); })}
          {item("Add to queue", <ListMusic size={16} />, () => { p.addToQueue(song); store.showToast("Added to queue"); })}
          {item("Add to playlist", <ListPlus size={16} />, () => setPick(true))}
          {downloaded
            ? item("Remove download", <Trash2 size={16} />, async () => { await db.removeDownload(song.id); store.refresh(); })
            : item("Download", <Download size={16} />, async () => {
              try { await db.downloadSong(song); store.showToast("Downloading…"); } catch (e) { store.showToast(String(e)); }
            })}
          {onRemove && item("Remove from this playlist", <MinusCircle size={16} />, onRemove)}
          {song.artistId ? item("Go to artist", <User size={16} />, () => store.navigate({ name: "artist", id: song.artistId })) : null}
        </div>
      )}
      {pick && <AddToPlaylist song={song} onClose={() => setPick(false)} />}
    </div>
  );
}

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className={`modal ${wide ? "wide" : ""}`} onMouseDown={(e) => e.stopPropagation()} role="dialog" aria-label={title}>
        <header className="modal-head">
          <h3>{title}</h3>
          <button className="icon-btn" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </header>
        {children}
      </div>
    </div>
  );
}

export function NameDialog({ title, confirm, initial = "", onClose, onConfirm }: {
  title: string; confirm: string; initial?: string; onClose: () => void; onConfirm: (name: string) => void;
}) {
  const [name, setName] = useState(initial);
  return (
    <Modal title={title} onClose={onClose}>
      <form onSubmit={(e) => { e.preventDefault(); if (name.trim()) onConfirm(name.trim()); }}>
        <input className="input" autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="Name" maxLength={60} />
        <div className="modal-actions">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn primary" disabled={!name.trim()}>{confirm}</button>
        </div>
      </form>
    </Modal>
  );
}

function AddToPlaylist({ song, onClose }: { song: Song; onClose: () => void }) {
  const store = useStore();
  const [creating, setCreating] = useState(false);
  if (creating) {
    return (
      <NameDialog title="New playlist" confirm="Create" onClose={onClose} onConfirm={async (name) => {
        await db.createPlaylist(name, song);
        store.refresh();
        store.showToast(`Added to ${name}`);
        onClose();
      }} />
    );
  }
  return (
    <Modal title="Add to playlist" onClose={onClose}>
      <div className="pick-list">
        <button className="pick" onClick={() => setCreating(true)}><span className="pick-icon"><Plus size={18} /></span>New playlist</button>
        {store.playlists.map((pl) => (
          <button key={pl.id} className="pick" onClick={async () => {
            await db.addToPlaylist(pl.id, song);
            store.refresh();
            store.showToast(`Added to ${pl.name}`);
            onClose();
          }}>
            <Cover src={pl.cover} size={40} radius={4} />
            <span>{pl.name}<span className="muted small block">{pl.songCount} songs</span></span>
          </button>
        ))}
      </div>
    </Modal>
  );
}

export function PlayShuffle({ songs }: { songs: Song[] }) {
  const p = usePlayer();
  // This list is already the queue (current song is in it and all of it is queued): the button pauses/resumes.
  const queued = new Set(p.queue.map((s) => s.id));
  const active = !!p.current && songs.some((s) => s.id === p.current!.id) && songs.every((s) => queued.has(s.id));
  const pausable = active && p.playing;
  return (
    <div className="actions">
      <button className="btn primary round" disabled={!songs.length} onClick={() => (active ? p.toggle() : p.playSongs(songs))}>
        {pausable ? <><Pause size={18} fill="currentColor" />Pause</> : <><Play size={18} fill="currentColor" />Play</>}
      </button>
      <button className="btn ghost round" disabled={!songs.length} onClick={() => p.playSongs(songs, 0, true)}>Shuffle</button>
    </div>
  );
}

export function Empty({ text, action, onAction }: { text: string; action?: string; onAction?: () => void }) {
  return (
    <div className="empty">
      <p className="muted">{text}</p>
      {action && <button className="btn ghost" onClick={onAction}>{action}</button>}
    </div>
  );
}

export function Spinner() {
  return <div className="spinner" aria-label="Loading" />;
}
