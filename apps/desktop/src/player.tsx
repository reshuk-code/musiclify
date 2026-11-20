import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { db, type Song } from "./api";

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
    if (!a.src) return;
    a.paused ? a.play().catch(() => {}) : a.pause();
  }, []);

  // Windows media keys / overlay (SMTC) via the Media Session API.
  useEffect(() => {
    const ms = navigator.mediaSession;
    if (!ms) return;
    ms.setActionHandler("play", () => audio.current.play());
    ms.setActionHandler("pause", () => audio.current.pause());
    ms.setActionHandler("nexttrack", next);
    ms.setActionHandler("previoustrack", previous);
    ms.setActionHandler("seekto", (d) => { if (d.seekTime != null) audio.current.currentTime = d.seekTime; });
  }, [next, previous]);

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
    sleepEndsAt, sleepAtSongEnd,
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
    seek: (sec) => { audio.current.currentTime = sec; setPosition(sec); },
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
  }), [queue, index, current, playing, loading, error, position, duration, shuffle, repeat, volume, sleepEndsAt, sleepAtSongEnd, goTo, toggle, next, previous, load]); // eslint-disable-line react-hooks/exhaustive-deps

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
