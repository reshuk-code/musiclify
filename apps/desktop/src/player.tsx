import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { db, deezerApi, originalRelease, withOriginalCovers, type Song } from "./api";

/** Autoplay: when the queue runs out, keep playing similar songs. On unless turned off in Settings. */
export const autoplayEnabled = () => localStorage.getItem("autoplay") !== "0";
export const setAutoplayEnabled = (on: boolean) => localStorage.setItem("autoplay", on ? "1" : "0");

const SAVED_QUEUE = "savedQueue";
const MAX_SAVED = 300;
type SavedQueue = { songs: Song[]; index: number; position: number };

function readSavedQueue(): SavedQueue | null {
  try {
    const q = JSON.parse(localStorage.getItem(SAVED_QUEUE) ?? "null") as SavedQueue | null;
    return q && Array.isArray(q.songs) && q.songs.length ? q : null;
  } catch {
    return null;
  }
}

export type RepeatMode = "off" | "all" | "one";

interface Player {
  queue: Song[];
  index: number;
  current: Song | null;
  playing: boolean;
  loading: boolean;
  error: string | null;
  position: number;
  duration: number;
  shuffle: boolean;
  repeat: RepeatMode;
  volume: number;
  playSongs: (songs: Song[], start?: number, shuffled?: boolean) => void;
  playNext: (song: Song) => void;
  addToQueue: (song: Song) => void;
  jumpTo: (index: number) => void;
  removeAt: (index: number) => void;
  toggle: () => void;
  next: () => void;
  previous: () => void;
  seek: (sec: number) => void;
  setVolume: (v: number) => void;
  toggleShuffle: () => void;
  cycleRepeat: () => void;
  retry: () => void;
  /** The <audio> element itself (the ambient light reads the beat from it). */
  audioEl: HTMLAudioElement;
  /** Sleep timer: when playback will pause (ms since epoch), or null. */
  sleepEndsAt: number | null;
  /** Sleep timer set to "end of this song". */
  sleepAtSongEnd: boolean;
  /** Pause after some minutes, at the end of this song, or turn the timer off (null). */
  setSleep: (when: number | "end" | null) => void;
  /** The queue was brought back from last time and hasn't been played yet ("continue listening"). */
  restored: boolean;
  /** This song, then similar songs (the artist's Deezer radio). */
  startRadio: (song: Song) => Promise<void>;
  /** Continue playback handed over from another device: same queue, song and position. */
  handoff: (songs: Song[], index: number, positionSec: number, repeat: RepeatMode, shuffled: boolean) => void;
}

const Ctx = createContext<Player | null>(null);

function shuffled<T>(arr: T[]): T[] {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

export function PlayerProvider({ children, onPlayed }: { children: ReactNode; onPlayed: () => void }) {
  const audio = useRef<HTMLAudioElement>(Object.assign(new Audio(), { crossOrigin: "anonymous" })); // CORS so Web Audio can read it
  const [queue, setQueue] = useState<Song[]>([]);
  const [index, setIndex] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState(0);
  const [shuffle, setShuffle] = useState(false);
  const [repeat, setRepeat] = useState<RepeatMode>("off");
  const [volume, setVolumeState] = useState(() => Number(localStorage.getItem("volume") ?? 0.9));
  const [restored, setRestored] = useState(false);
  const restoredAt = useRef(0);

  // Continue listening: bring back last session's queue, paused at the same spot.
  useEffect(() => {
    const saved = readSavedQueue();
    if (!saved) return;
    const i = Math.min(Math.max(saved.index, 0), saved.songs.length - 1);
    setQueue(saved.songs);
    setIndex(i);
    setPosition(saved.position);
    setDuration(saved.songs[i].durationSec);
    restoredAt.current = saved.position;
    setRestored(true);
  }, []);

  // Refs mirror state for event handlers.
  const [sleepEndsAt, setSleepEndsAt] = useState<number | null>(null);
  const [sleepAtSongEnd, setSleepAtSongEnd] = useState(false);
  const state = useRef({ queue, index, repeat, sleepAtSongEnd });
  state.current = { queue, index, repeat, sleepAtSongEnd };

  // Timed sleep: pause when the time is up.
  useEffect(() => {
    if (sleepEndsAt == null) return;
    const t = window.setTimeout(() => { audio.current.pause(); setSleepEndsAt(null); }, Math.max(0, sleepEndsAt - Date.now()));
    return () => window.clearTimeout(t);
  }, [sleepEndsAt]);
  const loadToken = useRef(0);
  const retried = useRef<number | null>(null);
  const isLocal = useRef(false);

  const current = queue[index] ?? null;

  const load = useCallback(async (song: Song, autoplay = true, startAt = 0) => {
    const token = ++loadToken.current;
    const a = audio.current;
    a.pause();
    setError(null);
    setLoading(true);
    setPosition(0);
    setDuration(song.durationSec);
    try {
      const src = await db.prepareSong(song);
      if (token !== loadToken.current) return; // user moved on
      isLocal.current = src.local;
      a.src = src.url;
      if (startAt > 0) {
        const seekOnce = () => { a.currentTime = startAt; a.removeEventListener("loadedmetadata", seekOnce); };
        a.addEventListener("loadedmetadata", seekOnce);
      }
      if (autoplay) await a.play();
      db.addHistory(song.id).then(onPlayed).catch(() => {});
      setMediaSession(song);
      // Greatest-hits compilation cover? Swap in the original album's, here and in the library.
      originalRelease(song).then((fixed) => {
        if (fixed.coverBig === song.coverBig && fixed.cover === song.cover) return;
        db.setAlbumArt(song.id, fixed.album, fixed.cover, fixed.coverBig).catch(() => {});
        setQueue((q) => q.map((s) => (s.id === song.id ? { ...s, album: fixed.album, cover: fixed.cover, coverBig: fixed.coverBig } : s)));
        if (token === loadToken.current) setMediaSession(fixed);
      }).catch(() => {});
    } catch (e) {
      if (token !== loadToken.current) return;
      setError(String(e));
      setLoading(false);
    }
  }, [onPlayed]);

  // Prefetch: download the next song in the background so skipping is instant.
  useEffect(() => {
    if (!current || loading) return;
    const nextSong = queue[index + 1];
    if (nextSong) db.downloadSong(nextSong).catch(() => {});
  }, [current?.id, loading]); // eslint-disable-line react-hooks/exhaustive-deps

  const goTo = useCallback((i: number) => {
    const q = state.current.queue;
    if (i < 0 || i >= q.length) return;
    retried.current = null;
    setIndex(i);
    load(q[i]);
  }, [load]);

  const next = useCallback(() => {
    const { queue: q, index: i, repeat: r } = state.current;
    if (i + 1 < q.length) goTo(i + 1);
    else if (r === "all" && q.length) goTo(0);
    else {
      audio.current.pause();
      audio.current.currentTime = 0;
    }
  }, [goTo]);

  const previous = useCallback(() => {
    if (audio.current.currentTime > 3) audio.current.currentTime = 0;
    else goTo(state.current.index - 1);
  }, [goTo]);

  // Audio element events.
  useEffect(() => {
    const a = audio.current;
    a.volume = volume;
    const on = (ev: string, fn: () => void) => a.addEventListener(ev, fn);
    const onTime = () => setPosition(a.currentTime);
    const onMeta = () => setDuration(Number.isFinite(a.duration) ? a.duration : 0);
    const onPlay = () => { setPlaying(true); setLoading(false); };
    const onPause = () => setPlaying(false);
    const onWaiting = () => setLoading(true);
    const onPlaying = () => setLoading(false);
    const onEnded = () => {
      if (state.current.sleepAtSongEnd) {
        setSleepAtSongEnd(false); // sleep timer: stop here instead of playing the next song
        return;
      }
      if (state.current.repeat === "one") {
        a.currentTime = 0;
        a.play();
      } else next();
    };
    const onError = () => {
      const song = state.current.queue[state.current.index];
      if (!song) return;
      // Streaming URLs expire; fetch a fresh one once before giving up.
      if (retried.current !== song.id) {
        retried.current = song.id;
        load(song);
      } else {
        setError("Couldn't play this song. Check your connection and try again.");
        setLoading(false);
      }
    };
    on("timeupdate", onTime); on("loadedmetadata", onMeta); on("play", onPlay); on("pause", onPause);
    on("waiting", onWaiting); on("playing", onPlaying); on("ended", onEnded); on("error", onError);
    return () => {
      a.removeEventListener("timeupdate", onTime); a.removeEventListener("loadedmetadata", onMeta);
      a.removeEventListener("play", onPlay); a.removeEventListener("pause", onPause);
      a.removeEventListener("waiting", onWaiting); a.removeEventListener("playing", onPlaying);
      a.removeEventListener("ended", onEnded); a.removeEventListener("error", onError);
    };
  }, [next, load]); // eslint-disable-line react-hooks/exhaustive-deps

  const toggle = useCallback(() => {
    const a = audio.current;
    if (!a.src) {
      // A restored queue isn't loaded yet: start it where it was left.
      const { queue: q, index: i } = state.current;
      if (q[i]) { setRestored(false); load(q[i], true, restoredAt.current); restoredAt.current = 0; }
      return;
    }
    a.paused ? a.play().catch(() => {}) : a.pause();
  }, [load]);

  useEffect(() => { if (playing) setRestored(false); }, [playing]);

  // Remember the queue (and the spot in the song) for next time.
  const posRef = useRef(0);
  posRef.current = position;
  useEffect(() => {
    if (!queue.length) return;
    const save = () => {
      const { queue: q, index: i } = state.current;
      const from = Math.max(0, i - MAX_SAVED / 2);
      const songs = q.slice(from, from + MAX_SAVED);
      localStorage.setItem(SAVED_QUEUE, JSON.stringify({ songs, index: i - from, position: Math.floor(posRef.current) }));
    };
    save();
    const t = window.setInterval(() => { if (!audio.current.paused) save(); }, 15_000);
    window.addEventListener("beforeunload", save);
    return () => { window.clearInterval(t); window.removeEventListener("beforeunload", save); };
  }, [queue, index, playing]);

  // Autoplay: once the last song starts, add 10 similar ones (minus anything queued or played lately).
  const radioFor = useRef<number | null>(null);
  useEffect(() => {
    const song = queue[index];
    if (!song || restored || repeat !== "off" || index !== queue.length - 1 || !autoplayEnabled()) return;
    if (radioFor.current === song.id) return;
    radioFor.current = song.id;
    (async () => {
      const skip = new Set([...queue.map((s) => s.id), ...(await db.recentIds(100).catch(() => [] as number[]))]);
      const pool = song.artistId ? await deezerApi.artistRadio(song.artistId, 40) : await deezerApi.chartTracks(40);
      const picked = await withOriginalCovers(pool.filter((s) => !skip.has(s.id)).slice(0, 10));
      if (!picked.length || state.current.queue[state.current.index]?.id !== song.id) return;
      await db.insertSongs(picked).catch(() => {});
      setQueue((q) => [...q, ...picked]);
    })().catch(() => {});
  }, [index, queue.length, repeat, restored]); // eslint-disable-line react-hooks/exhaustive-deps

  // Windows media keys / overlay (SMTC) via the Media Session API.
  useEffect(() => {
    const ms = navigator.mediaSession;
    if (!ms) return;
    ms.setActionHandler("play", () => (audio.current.src ? audio.current.play() : toggle()));
    ms.setActionHandler("pause", () => audio.current.pause());
    ms.setActionHandler("nexttrack", next);
    ms.setActionHandler("previoustrack", previous);
    ms.setActionHandler("seekto", (d) => { if (d.seekTime != null) audio.current.currentTime = d.seekTime; });
  }, [next, previous, toggle]);

  // Space = play/pause (unless typing).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (e.code === "Space" && !["INPUT", "TEXTAREA"].includes(t.tagName)) {
        e.preventDefault();
        toggle();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [toggle]);

  const value = useMemo<Player>(() => ({
    queue, index, current, playing, loading, error, position, duration, shuffle, repeat, volume,
    audioEl: audio.current,
    sleepEndsAt, sleepAtSongEnd, restored,
    startRadio: async (song) => {
      const pool = song.artistId ? await deezerApi.artistRadio(song.artistId, 40).catch(() => [] as Song[]) : [];
      const seen = new Set([song.id]);
      const similar = await withOriginalCovers(pool.filter((s) => !seen.has(s.id) && !!seen.add(s.id)).slice(0, 24));
      value.playSongs([song, ...similar]);
    },
    setSleep: (when) => {
      setSleepAtSongEnd(when === "end");
      setSleepEndsAt(typeof when === "number" ? Date.now() + when * 60_000 : null);
    },
    playSongs: (songs, start = 0, shuffledMode = false) => {
      if (!songs.length) return;
      db.insertSongs(songs).catch(() => {});
      let q = songs;
      let i = start;
      if (shuffledMode) {
        q = shuffled(songs);
        i = 0;
      }
      setShuffle(shuffledMode);
      setRestored(false);
      setQueue(q);
      state.current.queue = q;
      goTo(i);
    },
    playNext: (song) => {
      if (!queue.length) return value.playSongs([song]);
      setQueue((q) => [...q.slice(0, index + 1), song, ...q.slice(index + 1)]);
    },
    addToQueue: (song) => {
      if (!queue.length) return value.playSongs([song]);
      setQueue((q) => [...q, song]);
    },
    jumpTo: goTo,
    removeAt: (i) => {
      if (i === index) return;
      setQueue((q) => q.filter((_, k) => k !== i));
      if (i < index) setIndex((x) => x - 1);
    },
    toggle, next, previous,
    seek: (sec) => {
      if (!audio.current.src) restoredAt.current = sec; // restored queue: start from here when played
      else audio.current.currentTime = sec;
      setPosition(sec);
    },
    setVolume: (v) => {
      audio.current.volume = v;
      setVolumeState(v);
      localStorage.setItem("volume", String(v));
    },
    toggleShuffle: () => {
      // Shuffle the rest of the queue (keep the current song playing).
      setShuffle((on) => {
        if (!on && queue.length > index + 1) {
          setQueue((q) => [...q.slice(0, index + 1), ...shuffled(q.slice(index + 1))]);
        }
        return !on;
      });
    },
    cycleRepeat: () => setRepeat((r) => (r === "off" ? "all" : r === "all" ? "one" : "off")),
    retry: () => { if (current) { retried.current = null; load(current); } },
    handoff: (songs, i, positionSec, repeatMode, shuffledMode) => {
      if (!songs.length) return;
      const idx = Math.min(Math.max(i, 0), songs.length - 1);
      setShuffle(shuffledMode);
      setRepeat(repeatMode);
      setQueue(songs);
      state.current.queue = songs;
      retried.current = null;
      setIndex(idx);
      load(songs[idx], true, positionSec);
    },
  }), [queue, index, current, playing, loading, error, position, duration, shuffle, repeat, volume, sleepEndsAt, sleepAtSongEnd, restored, goTo, toggle, next, previous, load]); // eslint-disable-line react-hooks/exhaustive-deps

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

function setMediaSession(song: Song) {
  if (!navigator.mediaSession) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: song.title,
    artist: song.artist,
    album: song.album,
    artwork: [{ src: song.coverBig || song.cover, sizes: "500x500", type: "image/jpeg" }],
  });
}

export function usePlayer(): Player {
  const p = useContext(Ctx);
  if (!p) throw new Error("usePlayer outside provider");
  return p;
}
