import { useEffect, useMemo, useRef, useState } from "react";
import { HardDrive, Link2, Share2 } from "lucide-react";
import { db, deezerApi, fetchImport, matchImport, type Album, type ImportTrack, type Stats } from "./api";
import { Cover, Empty, Modal, PlayShuffle, Section, SongRow, Spinner } from "./components";
import { useAsync } from "./pages";
import { usePlayer } from "./player";
import { ShareDialog } from "./share";
import { useStore } from "./store";

const fmtBytes = (b: number) =>
  b >= 1024 ** 3 ? `${(b / 1024 ** 3).toFixed(1)} GB` : b >= 1024 ** 2 ? `${(b / 1024 ** 2).toFixed(0)} MB` : `${Math.max(1, Math.round(b / 1024))} KB`;

export function albumSubtitle(a: Album): string {
  const kind = a.type === "single" ? "Single" : a.type === "ep" ? "EP" : a.type === "compile" ? "Compilation" : "Album";
  return [a.releaseDate.slice(0, 4), kind].filter(Boolean).join(" · ");
}

export function AlbumCard({ album, onClick }: { album: Album; onClick: () => void }) {
  return (
    <button className="card" onClick={onClick} title={album.title}>
      <div className="card-art"><Cover src={album.cover} radius={8} /></div>
      <span className="card-title">{album.title}</span>
      <span className="card-sub">{albumSubtitle(album)}</span>
    </button>
  );
}

// ---------------------------------------------------------------- album

export function AlbumPage({ id }: { id: number }) {
  const p = usePlayer();
  const { data, error, reload } = useAsync(async () => {
    const album = await deezerApi.album(id);
    return { album, songs: await deezerApi.albumTracks(album) };
  }, [id]);
  if (error) return <div className="page"><Empty text="Couldn't load this album." action="Retry" onAction={reload} /></div>;
  if (!data) return <div className="page center-box"><Spinner /></div>;
  const { album, songs } = data;
  return (
    <div className="page">
      <header className="hero">
        <Cover src={album.coverBig || album.cover} size={180} radius={10} />
        <div>
          <span className="kicker">{albumSubtitle(album)}</span>
          <h1 className="hero-title">{album.title}</h1>
          <p className="muted">{album.artist ? `${album.artist} · ` : ""}{songs.length} songs</p>
          <div className="hero-actions"><PlayShuffle songs={songs} /></div>
        </div>
      </header>
      {!songs.length ? <Empty text="No playable songs on this release." /> : (
        <div className="rows">{songs.map((s, i) => <SongRow key={s.id} song={s} index={i} onPlay={() => p.playSongs(songs, i)} />)}</div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- stats

type Period = "month" | "year" | "all";
const PERIODS: { key: Period; label: string }[] = [
  { key: "month", label: "This month" }, { key: "year", label: "This year" }, { key: "all", label: "All time" },
];

function since(period: Period): number {
  const d = new Date();
  if (period === "all") return 0;
  return period === "month" ? new Date(d.getFullYear(), d.getMonth(), 1).getTime() : new Date(d.getFullYear(), 0, 1).getTime();
}

export function StatsPage() {
  const store = useStore();
  const p = usePlayer();
  const [period, setPeriod] = useState<Period>("month");
  const [sharing, setSharing] = useState(false);
  const { data } = useAsync<Stats>(() => db.stats(since(period)), [period, store.version]);
  const label = PERIODS.find((x) => x.key === period)!.label;
  const songs = data?.topSongs.map((t) => t.song) ?? [];

  return (
    <div className="page">
      <header className="page-head">
        <h1>Your stats</h1>
        {!!data?.plays && <button className="btn ghost round sm" onClick={() => setSharing(true)}><Share2 size={16} />Share</button>}
      </header>
      <div className="chips">
        {PERIODS.map((x) => <button key={x.key} className={`chip ${x.key === period ? "on" : ""}`} onClick={() => setPeriod(x.key)}>{x.label}</button>)}
      </div>
      {!data ? <div className="center-box"><Spinner /></div> : !data.plays ? <Empty text={`Nothing played ${label.toLowerCase()} yet.`} /> : (
        <>
          <div className="stat-tiles">
            <div className="stat-tile"><b>{data.minutes.toLocaleString()}</b><span className="muted small">minutes</span></div>
            <div className="stat-tile"><b>{data.plays.toLocaleString()}</b><span className="muted small">plays</span></div>
            <div className="stat-tile"><b>{data.songCount.toLocaleString()}</b><span className="muted small">songs</span></div>
          </div>
          {!!data.topArtists.length && (
            <Section title="Top artists">
              <div className="rows">
                {data.topArtists.map((a, i) => (
                  <button key={a.artistId} className="lib-entry" onClick={() => a.artistId && store.navigate({ name: "artist", id: a.artistId })}>
                    <Cover src={a.cover} size={48} radius={24} />
                    <span><b>{i + 1}. {a.artist}</b><span className="muted small block">{a.plays} plays</span></span>
                  </button>
                ))}
              </div>
            </Section>
          )}
          <Section title="Top songs" subtitle="Counted each time a song starts">
            <div className="rows">{songs.map((s, i) => <SongRow key={s.id} song={s} index={i} onPlay={() => p.playSongs(songs, i)} />)}</div>
          </Section>
        </>
      )}
      {sharing && data && <ShareDialog spec={{ kind: "stats", period: label.toLowerCase(), stats: data }} name={`My ${label.toLowerCase()} in music`} onClose={() => setSharing(false)} />}
    </div>
  );
}

// ---------------------------------------------------------------- playlist import

type ImportState =
  | { step: "idle" }
  | { step: "reading" }
  | { step: "matching"; name: string; done: number; total: number }
  | { step: "done"; name: string; playlistId: number; matched: number; missing: ImportTrack[] }
  | { step: "failed"; message: string };

export function ImportPage() {
  const store = useStore();
  const [link, setLink] = useState("");
  const [state, setState] = useState<ImportState>({ step: "idle" });
  const cancelled = useRef(false);
  useEffect(() => () => { cancelled.current = true; }, []);

  const run = async () => {
    cancelled.current = false;
    setState({ step: "reading" });
    try {
      const src = await fetchImport(link);
      if (!src.tracks.length) return setState({ step: "failed", message: "That playlist is empty." });
      setState({ step: "matching", name: src.name, done: 0, total: src.tracks.length });
      const { matched, missing } = await matchImport(src, (done, total) => !cancelled.current && setState({ step: "matching", name: src.name, done, total }), () => cancelled.current);
      if (cancelled.current) return;
      if (!matched.length) return setState({ step: "failed", message: "Couldn't find any of these songs." });
      const taken = new Set(store.playlists.map((pl) => pl.name));
      let name = src.name.trim() || "Imported playlist";
      for (let n = 2; taken.has(name); n++) name = `${src.name.trim()} (${n})`;
      const playlistId = await db.createPlaylistWith(name, matched);
      store.refresh();
      setState({ step: "done", name, playlistId, matched: matched.length, missing });
    } catch (e) {
      setState({ step: "failed", message: e instanceof Error ? e.message : String(e) });
    }
  };
  const busy = state.step === "reading" || state.step === "matching";

  return (
    <div className="page narrow">
      <h1>Import playlist</h1>
      <p className="muted">Paste a link to a public Spotify playlist or album, or a YouTube / YouTube Music playlist. Musiclify finds each song and saves them as a new playlist.</p>
      <form className="import-form" onSubmit={(e) => { e.preventDefault(); if (link.trim() && !busy) run(); }}>
        <label className="search-bar">
          <Link2 size={18} />
          <input value={link} onChange={(e) => setLink(e.target.value)} placeholder="https://open.spotify.com/playlist/…" disabled={busy} autoFocus />
        </label>
        {!busy && state.step !== "done" && <button className="btn primary round" disabled={!link.trim()}>Import</button>}
      </form>
      {state.step === "failed" && <p className="error">{state.message}</p>}
      {state.step === "idle" && <p className="muted small">Spotify shares up to about 100 songs of a playlist this way. YouTube playlists can have up to 500.</p>}
      {state.step === "reading" && <div className="import-progress"><Spinner /><span>Reading the playlist…</span></div>}
      {state.step === "matching" && (
        <div className="import-progress">
          <div className="bar"><i style={{ width: `${(state.done / Math.max(1, state.total)) * 100}%` }} /></div>
          <span>Finding songs from “{state.name}”: {state.done} of {state.total}</span>
          <button className="btn ghost sm" onClick={() => { cancelled.current = true; setState({ step: "idle" }); }}>Cancel</button>
        </div>
      )}
      {state.step === "done" && (
        <>
          <p className="success">Saved “{state.name}” with {state.matched} songs.</p>
          <div className="hero-actions">
            <button className="btn primary round" onClick={() => store.navigate({ name: "playlist", id: state.playlistId })}>Open playlist</button>
            <button className="btn ghost round" onClick={() => { setLink(""); setState({ step: "idle" }); }}>Import another</button>
          </div>
          {!!state.missing.length && (
            <Section title={`Couldn't find ${state.missing.length}`}>
              <ul className="missing-list">{state.missing.map((t, i) => <li key={i} className="muted small">{t.title} · {t.artist}</li>)}</ul>
            </Section>
          )}
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- free up space

const LOW_FREE = 2 * 1024 ** 3; // under 2 GB free counts as low on a PC
const COMFORTABLE = 5 * 1024 ** 3;

/** Asked once a session when the drive is low (never removes anything by itself). */
export function useLowStoragePrompt() {
  const [prompt, setPrompt] = useState<{ free: number; used: number } | null>(null);
  useEffect(() => {
    if (Number(localStorage.getItem("storageSnooze") ?? 0) > Date.now()) return;
    Promise.all([db.diskSpace(), db.storageBytes()]).then(([space, used]) => {
      if ((space.free < LOW_FREE || space.free < space.total / 20) && used > 50 * 1024 ** 2) setPrompt({ free: space.free, used });
    }).catch(() => {});
  }, []);
  return {
    prompt,
    dismiss: () => setPrompt(null),
    snooze: () => { localStorage.setItem("storageSnooze", String(Date.now() + 3 * 24 * 3600_000)); setPrompt(null); },
  };
}

export function LowStorageDialog({ free, used, onReview, onLater, onClose }: { free: number; used: number; onReview: () => void; onLater: () => void; onClose: () => void }) {
  return (
    <Modal title="Your PC is running low on storage" onClose={onClose}>
      <div className="low-storage">
        <HardDrive size={28} />
        <p>Only <b>{fmtBytes(free)}</b> is free. Your downloaded songs use <b>{fmtBytes(used)}</b>. Removing a few you rarely play would help. You choose which ones.</p>
      </div>
      <div className="modal-actions">
        <button className="btn ghost" onClick={onLater}>Not now</button>
        <button className="btn primary" onClick={onReview}>Review songs</button>
      </div>
    </Modal>
  );
}

/**
 * Pick downloads to remove. When space is short, the least recently played are ticked until enough
 * would be freed; every box can be changed before anything is removed.
 */
export function FreeSpacePage() {
  const store = useStore();
  const [nonce, setNonce] = useState(0);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [removing, setRemoving] = useState(false);
  const { data } = useAsync(async () => {
    const [items, space] = await Promise.all([db.downloadsUsage(), db.diskSpace()]);
    return { items, space };
  }, [nonce]);
  const needed = data ? Math.max(0, Math.max(COMFORTABLE, data.space.total / 10) - data.space.free) : 0;

  useEffect(() => {
    if (!data) return;
    let sum = 0;
    const pick = new Set<number>();
    if (needed > 0) for (const it of data.items) { if (sum >= needed) break; pick.add(it.song.id); sum += it.bytes; }
    setSelected(pick);
  }, [data]); // eslint-disable-line react-hooks/exhaustive-deps

  const items = data?.items ?? [];
  const total = useMemo(() => items.reduce((a, b) => a + b.bytes, 0), [items]);
  const chosen = items.filter((it) => selected.has(it.song.id));
  const chosenBytes = chosen.reduce((a, b) => a + b.bytes, 0);
  const toggle = (id: number) => setSelected((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const allOn = items.length > 0 && selected.size === items.length;
  const lastPlayed = (t: number | null) => (t ? `played ${timeAgoShort(t)}` : "never played");

  return (
    <div className="page narrow">
      <header className="page-head">
        <h1>Free up space</h1>
        {!!items.length && <button className="btn ghost sm" onClick={() => setSelected(allOn ? new Set() : new Set(items.map((i) => i.song.id)))}>{allOn ? "Select none" : "Select all"}</button>}
      </header>
      {!data ? <div className="center-box"><Spinner /></div> : !items.length ? <Empty text="No downloaded songs." /> : (
        <>
          {needed > 0 && <p className="muted">Your PC is running low on storage. We've ticked the songs you play least. Untick any you want to keep, or tick others.</p>}
          <p className="muted small">{items.length} downloads use {fmtBytes(total)} · {fmtBytes(data.space.free)} free on this drive</p>
          <div className="free-list">
            {items.map((it) => (
              <label key={it.song.id} className={`free-row ${selected.has(it.song.id) ? "on" : ""}`}>
                <input type="checkbox" checked={selected.has(it.song.id)} onChange={() => toggle(it.song.id)} />
                <Cover src={it.song.cover} size={40} radius={4} />
                <span className="free-main"><span className="row-title">{it.song.title}</span><span className="muted small block">{it.song.artist} · {lastPlayed(it.lastPlayed)}</span></span>
                <span className="muted small">{fmtBytes(it.bytes)}</span>
              </label>
            ))}
          </div>
          <footer className="free-foot">
            <div>
              <b>Selected: {chosen.length} songs · {fmtBytes(chosenBytes)}</b>
              <span className="muted small block">Free space after: {fmtBytes(data.space.free + chosenBytes)} · removed songs stay in your library and download again when played</span>
            </div>
            <button className="btn primary round" disabled={!chosen.length || removing} onClick={async () => {
              setRemoving(true);
              try { await db.removeDownloads(chosen.map((c) => c.song.id)); store.refresh(); store.showToast(`Freed ${fmtBytes(chosenBytes)}`); }
              finally { setRemoving(false); setNonce((n) => n + 1); }
            }}>{removing ? "Removing…" : `Remove ${chosen.length} songs (${fmtBytes(chosenBytes)})`}</button>
          </footer>
        </>
      )}
    </div>
  );
}

function timeAgoShort(t: number): string {
  const days = Math.floor((Date.now() - t) / 86_400_000);
  if (days < 1) return "today";
  if (days < 30) return `${days} day${days === 1 ? "" : "s"} ago`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months} month${months === 1 ? "" : "s"} ago`;
  const years = Math.floor(months / 12);
  return `${years} year${years === 1 ? "" : "s"} ago`;
}

