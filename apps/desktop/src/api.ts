import { invoke } from "@tauri-apps/api/core";
import popularArtists from "./popular-artists.json";

// ---------------------------------------------------------------- types

export interface Song {
  id: number;
  title: string;
  artist: string;
  artistId: number;
  album: string;
  cover: string;
  coverBig: string;
  durationSec: number;
  liked?: boolean;
  downloaded?: boolean;
}

export interface Artist {
  id: number;
  name: string;
  picture: string;
}

export interface Profile {
  name: string;
  onboarded: boolean;
  shareMatches: boolean;
  hasCookies: boolean;
}

export interface PlaylistSummary {
  id: number;
  name: string;
  createdAt: number;
  songCount: number;
  cover: string | null;
}

export interface LyricLine {
  startMs: number;
  text: string;
}

export interface Lyrics {
  synced: boolean;
  lines: LyricLine[];
}

export interface LinkInfo {
  payload: string;
  host: string;
  port: number;
  expiresAt: number;
}

export interface ImportSummary {
  name: string;
  songs: number;
  liked: number;
  playlists: number;
  artists: number;
  history: number;
  lyrics: number;
  offlineIds: number[];
  removed: number;
}

export interface PairedDevice {
  id: string;
  name: string;
  createdAt: number;
  lastSync: number | null;
}

export type SyncEvent =
  | { kind: "synced"; device: string; summary: ImportSummary }
  | { kind: "paired" };

export type LinkEvent =
  | { kind: "imported"; summary: ImportSummary }
  | { kind: "exported" }
  | { kind: "error"; message: string }
  | { kind: "closed" };

export interface DownloadProgress {
  id: number;
  progress: number;
  done: boolean;
  error: string | null;
}

// ---------------------------------------------------------------- local library (Rust)

export const db = {
  profile: () => invoke<Profile>("get_profile"),
  setProfile: (name?: string, onboarded?: boolean) => invoke<void>("set_profile", { name, onboarded }),
  setShareMatches: (enabled: boolean) => invoke<void>("set_share_matches", { enabled }),
  setCookies: (text: string | null) => invoke<void>("set_cookies", { text }),

  favorites: () => invoke<Artist[]>("favorites_get"),
  setFavorites: (artists: Artist[]) => invoke<void>("favorites_set", { artists }),
  toggleFavorite: (artist: Artist) => invoke<boolean>("favorite_toggle", { artist }),

  insertSongs: (songs: Song[]) => invoke<void>("songs_insert", { songs }),
  toggleLike: (song: Song) => invoke<boolean>("like_toggle", { song }),
  liked: () => invoke<Song[]>("liked_list"),
  downloads: () => invoke<Song[]>("downloads_list"),
  recent: (limit = 30) => invoke<Song[]>("recent_list", { limit }),
  addHistory: (songId: number) => invoke<void>("history_add", { songId }),
  clearHistory: () => invoke<void>("history_clear"),

  playlists: () => invoke<PlaylistSummary[]>("playlists_list"),
  createPlaylist: (name: string, song?: Song) => invoke<number>("playlist_create", { name, song: song ?? null }),
  addToPlaylist: (playlistId: number, song: Song) => invoke<void>("playlist_add", { playlistId, song }),
  removeFromPlaylist: (playlistId: number, songId: number) => invoke<void>("playlist_remove", { playlistId, songId }),
  playlistSongs: (playlistId: number) => invoke<Song[]>("playlist_songs", { playlistId }),
  renamePlaylist: (playlistId: number, name: string) => invoke<void>("playlist_rename", { playlistId, name }),
  deletePlaylist: (playlistId: number) => invoke<void>("playlist_delete", { playlistId }),

  prepareSong: (song: Song) => invoke<{ url: string; local: boolean }>("prepare_song", { song }),
  downloadSong: (song: Song) => invoke<void>("download_song", { song }),
  downloadMany: (ids: number[]) => invoke<number>("download_many", { ids }),
  removeDownload: (id: number) => invoke<void>("remove_download", { id }),
  clearDownloads: () => invoke<void>("clear_downloads"),
  storageBytes: () => invoke<number>("storage_bytes"),

  autostartGet: () => invoke<boolean>("autostart_get"),
  autostartSet: (enabled: boolean) => invoke<void>("autostart_set", { enabled }),
  pairedList: () => invoke<PairedDevice[]>("paired_list"),
  unpair: (id: string) => invoke<void>("unpair", { id }),
  linkStart: () => invoke<LinkInfo>("link_start"),
  linkStop: () => invoke<void>("link_stop"),
};

const fetchJson = <T = any>(url: string) => invoke<T>("fetch_json", { url });

// ---------------------------------------------------------------- Deezer (metadata)

function toSong(t: any): Song | null {
  if (!t?.artist || t.readable === false) return null;
  return {
    id: t.id,
    title: t.title ?? "",
    artist: t.artist.name ?? "",
    artistId: t.artist.id ?? 0,
    album: t.album?.title ?? "",
    cover: t.album?.cover_medium ?? "",
    coverBig: t.album?.cover_xl || t.album?.cover_big || t.album?.cover_medium || "",
    durationSec: t.duration ?? 0,
  };
}

const toArtist = (a: any): Artist => ({ id: a.id, name: a.name, picture: a.picture_big || a.picture_medium || "" });

async function deezer<T>(path: string, params: Record<string, string | number> = {}): Promise<T> {
  const qs = new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])).toString();
  const data = await fetchJson(`https://api.deezer.com/${path}${qs ? `?${qs}` : ""}`);
  if (data?.error) throw new Error(`Deezer: ${data.error.message ?? "error"}`);
  return data as T;
}

const tracks = (d: any) => ((d?.data ?? []) as any[]).map(toSong).filter((s): s is Song => s !== null);

export const deezerApi = {
  chartTracks: async (limit = 30) => tracks(await deezer("chart/0/tracks", { limit })),
  searchTracks: async (q: string) => tracks(await deezer("search/track", { q, limit: 40 })),
  searchArtists: async (q: string, limit = 20): Promise<Artist[]> =>
    ((await deezer<any>("search/artist", { q, limit })).data ?? []).map(toArtist),
  artist: async (id: number): Promise<Artist> => toArtist(await deezer<any>(`artist/${id}`)),
  artistTop: async (id: number, limit = 20) => tracks(await deezer(`artist/${id}/top`, { limit })),
  related: async (id: number, limit = 12): Promise<Artist[]> =>
    ((await deezer<any>(`artist/${id}/related`, { limit })).data ?? []).map(toArtist),
};

/** Hand-picked well-known artists across genres (Deezer's own genre charts are unreliable). */
export const POPULAR_ARTISTS: (Artist & { genre: string })[] = popularArtists;

// ---------------------------------------------------------------- LRCLIB (lyrics)

export function cleanTitle(title: string): string {
  return title
    .replace(/\s*[(\[](feat\.?|ft\.?|with|from|remaster|live|radio edit|explicit)[^)\]]*[)\]]/gi, "")
    .replace(/\s+-\s+.*(remaster|version|edit|live|mix).*$/i, "")
    .trim();
}

function parseLrc(lrc: string): LyricLine[] {
  const out: LyricLine[] = [];
  for (const raw of lrc.split("\n")) {
    const m = raw.trim().match(/^\[(\d+):(\d+(?:\.\d+)?)\](.*)$/);
    if (!m) continue;
    const text = m[3].replace(/<\d+:\d+(?:\.\d+)?>/g, "").replace(/\s+/g, " ").trim();
    if (text) out.push({ startMs: Math.round((Number(m[1]) * 60 + Number(m[2])) * 1000), text });
  }
  return out;
}

const hasLyrics = (h: any) => Boolean(h?.syncedLyrics?.trim() || h?.plainLyrics?.trim());

export async function lyricsFor(song: Song): Promise<Lyrics> {
  const cached = await invoke<string | null>("lyrics_get", { id: song.id });
  if (cached) return JSON.parse(cached);

  const title = cleanTitle(song.title);
  const q = (p: Record<string, string>) => new URLSearchParams(p).toString();
  let hit: any = null;
  try {
    hit = await fetchJson(`https://lrclib.net/api/get?${q({ artist_name: song.artist, track_name: title, duration: String(song.durationSec) })}`);
    if (!hasLyrics(hit)) {
      const results: any[] = (await fetchJson(`https://lrclib.net/api/search?${q({ artist_name: song.artist, track_name: title })}`)) ?? [];
      hit = results
        .filter((r) => hasLyrics(r) && Math.abs((r.duration ?? 0) - song.durationSec) <= 15)
        .sort((a, b) => Number(!a.syncedLyrics) - Number(!b.syncedLyrics) ||
          Math.abs(a.duration - song.durationSec) - Math.abs(b.duration - song.durationSec))[0] ?? null;
    }
  } catch {
    return { synced: false, lines: [] }; // offline: don't cache the miss
  }

  const lyrics: Lyrics = hit?.syncedLyrics?.trim()
    ? { synced: true, lines: parseLrc(hit.syncedLyrics) }
    : hit?.plainLyrics?.trim()
      ? { synced: false, lines: hit.plainLyrics.split("\n").filter((l: string) => l.trim()).map((t: string) => ({ startMs: 0, text: t.trim() })) }
      : { synced: false, lines: [] };
  await invoke("lyrics_set", { id: song.id, json: JSON.stringify(lyrics) });
  return lyrics;
}

export function formatTime(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
