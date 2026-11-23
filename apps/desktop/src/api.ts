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

/** An album, single or EP. `type` is Deezer's record_type: album, single, ep or compile. */
export interface Album {
  id: number;
  title: string;
  cover: string;
  coverBig: string;
  releaseDate: string;
  type: string;
  artist: string;
}

export interface SongPlays { song: Song; plays: number }
export interface ArtistPlays { artistId: number; artist: string; plays: number; cover: string | null }
export interface Stats { minutes: number; plays: number; songCount: number; topSongs: SongPlays[]; topArtists: ArtistPlays[] }
export interface DownloadItem { song: Song; bytes: number; lastPlayed: number | null; plays: number }

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
  recentIds: (limit = 100) => invoke<number[]>("recent_ids", { limit }),
  setAlbumArt: (id: number, album: string, cover: string, coverBig: string) =>
    invoke<void>("song_set_album_art", { id, album, cover, coverBig }),
  stats: (since: number) => invoke<Stats>("stats_get", { since }),
  downloadsUsage: () => invoke<DownloadItem[]>("downloads_usage"),
  removeDownloads: (ids: number[]) => invoke<void>("remove_downloads", { ids }),
  diskSpace: () => invoke<{ free: number; total: number }>("disk_space"),
  saveImage: (name: string, pngBase64: string) => invoke<string>("save_image", { name, pngBase64 }),

  playlists: () => invoke<PlaylistSummary[]>("playlists_list"),
  createPlaylist: (name: string, song?: Song) => invoke<number>("playlist_create", { name, song: song ?? null }),
  createPlaylistWith: (name: string, songs: Song[]) => invoke<number>("playlist_create_with", { name, songs }),
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

const toAlbum = (a: any): Album => ({
  id: a.id,
  title: a.title ?? "",
  cover: a.cover_medium ?? "",
  coverBig: a.cover_xl || a.cover_big || a.cover_medium || "",
  releaseDate: a.release_date ?? "",
  type: a.record_type ?? "album",
  artist: a.artist?.name ?? "",
});

const albumCache = new Map<number, Album[]>();
const albumTrackCache = new Map<number, Song[]>();

export const deezerApi = {
  chartTracks: async (limit = 30) => tracks(await deezer("chart/0/tracks", { limit })),
  searchTracks: async (q: string) => tracks(await deezer("search/track", { q, limit: 40 })),
  searchArtists: async (q: string, limit = 20): Promise<Artist[]> =>
    ((await deezer<any>("search/artist", { q, limit })).data ?? []).map(toArtist),
  artist: async (id: number): Promise<Artist> => toArtist(await deezer<any>(`artist/${id}`)),
  artistTop: async (id: number, limit = 20) => tracks(await deezer(`artist/${id}/top`, { limit })),
  related: async (id: number, limit = 12): Promise<Artist[]> =>
    ((await deezer<any>(`artist/${id}/related`, { limit })).data ?? []).map(toArtist),
  /** Songs by the artist and similar artists (Deezer's artist radio), for autoplay. */
  artistRadio: async (id: number, limit = 40) => tracks(await deezer(`artist/${id}/radio`, { limit })),

  /** The artist's own releases, newest first, one per title (Deezer lists every edition). */
  artistAlbums: async (id: number): Promise<Album[]> => {
    const hit = albumCache.get(id);
    if (hit) return hit;
    const all: Album[] = [];
    for (let index = 0; index < 300;) {
      const page = await deezer<any>(`artist/${id}/albums`, { limit: 100, index });
      const data: Album[] = (page.data ?? []).map(toAlbum);
      all.push(...data);
      if (!data.length || !page.next) break;
      index += data.length;
    }
    const seen = new Set<string>();
    const list = all.sort((a, b) => b.releaseDate.localeCompare(a.releaseDate))
      .filter((a) => { const k = `${a.type}|${a.title.toLowerCase()}`; return !seen.has(k) && !!seen.add(k); });
    albumCache.set(id, list);
    return list;
  },
  album: async (id: number): Promise<Album> => toAlbum(await deezer<any>(`album/${id}`)),
  /** Every track on an album, with the album's cover filled in (Deezer leaves it out here). */
  albumTracks: async (album: Album): Promise<Song[]> => {
    const hit = albumTrackCache.get(album.id);
    if (hit) return hit;
    const d = await deezer<any>(`album/${album.id}/tracks`, { limit: 200 });
    const list = ((d.data ?? []) as any[])
      .map((t) => toSong({ ...t, album: { title: album.title, cover_medium: album.cover, cover_xl: album.coverBig } }))
      .filter((s): s is Song => s !== null);
    albumTrackCache.set(album.id, list);
    return list;
  },

  /** Best match for a song known only by name (imported playlists); the closest length wins. */
  findTrack: async (title: string, artist: string, durationSec = 0): Promise<Song | null> => {
    const clean = title.replace(/\s*[(\[](feat|ft|with|from|prod)[^)\]]*[)\]]/gi, "").trim();
    const queries = [artist ? `artist:"${artist}" track:"${clean}"` : null, `${artist} ${clean}`.trim()].filter(Boolean) as string[];
    for (const q of queries) {
      const found = await deezer<any>("search/track", { q, limit: 8 }).then(tracks).catch(() => [] as Song[]);
      if (!found.length) continue;
      const name = baseTitle(clean);
      const same = found.filter((s) => baseTitle(s.title) === name);
      const pool = same.length ? same : found;
      return durationSec > 0 ? pool.reduce((a, b) => (Math.abs(b.durationSec - durationSec) < Math.abs(a.durationSec - durationSec) ? b : a)) : pool[0];
    }
    return null;
  },
};

// ---------------------------------------------------------------- original covers

const COMPILATION = /\b(hits|best of|greatest|the very best|collection|essentials?|anthology|number ones|#1'?s|the singles|now that'?s what i call|playlist|top \d+|love songs|wedding)\b/i;

export const looksLikeCompilation = (albumTitle: string) => COMPILATION.test(albumTitle);

/** "Lose Yourself (From "8 Mile")" and "Stan - Remastered" -> "loseyourself", "stan". */
export function baseTitle(title: string): string {
  return title.replace(/\s*[(\[].*?[)\]]/g, "").split(" - ")[0].toLowerCase().replace(/[^\p{L}\p{N}]/gu, "");
}

const resolved = new Map<number, Song>();
const artistLocks = new Map<number, Promise<unknown>>();

/**
 * Deezer often files a hit under a greatest-hits compilation, so the app would show that
 * compilation's cover. Swap in the oldest of the artist's own albums (then singles) that has a track
 * with the same name. Songs without a better match come back unchanged.
 */
export async function originalRelease(song: Song): Promise<Song> {
  if (!song.artistId) return song;
  const hit = resolved.get(song.id);
  if (hit) return hit;
  // One lookup per artist at a time, so the rest reuse its album lists.
  const prev = artistLocks.get(song.artistId) ?? Promise.resolve();
  const run = prev.catch(() => {}).then(async () => {
    const again = resolved.get(song.id);
    if (again) return again;
    const result = (await findOriginal(song)) ?? song;
    resolved.set(song.id, result);
    return result;
  });
  artistLocks.set(song.artistId, run);
  try {
    return await run;
  } catch {
    return song; // offline or rate-limited: try again next time
  }
}

async function findOriginal(song: Song): Promise<Song | null> {
  const albums = await deezerApi.artistAlbums(song.artistId);
  const own = albums.find((a) => a.title.toLowerCase() === song.album.toLowerCase());
  if (own && own.type !== "compile" && !looksLikeCompilation(own.title)) return null;
  const name = baseTitle(song.title);
  const candidates = albums
    .filter((a) => a.type !== "compile" && !looksLikeCompilation(a.title))
    .sort((a, b) => Number(a.type !== "album") - Number(b.type !== "album") || a.releaseDate.localeCompare(b.releaseDate))
    .slice(0, 15);
  for (const album of candidates) {
    const list = await deezerApi.albumTracks(album).catch(() => null);
    if (list?.some((t) => baseTitle(t.title) === name)) {
      return { ...song, album: album.title, cover: album.cover, coverBig: album.coverBig };
    }
  }
  return null;
}

/** originalRelease for the songs in a list that look like they come from a compilation (4 at a time). */
export async function withOriginalCovers(songs: Song[]): Promise<Song[]> {
  const out = [...songs];
  const todo = songs.map((s, i) => [s, i] as const).filter(([s]) => looksLikeCompilation(s.album));
  let next = 0;
  const worker = async () => {
    while (next < todo.length) {
      const [s, i] = todo[next++];
      out[i] = await originalRelease(s);
    }
  };
  await Promise.all([worker(), worker(), worker(), worker()]);
  return out;
}

// ---------------------------------------------------------------- playlist import

export interface ImportTrack { title: string; artist: string; durationSec: number }
export interface ImportSource { name: string; service: string; tracks: ImportTrack[] }
export type ImportLink = { kind: "spotify"; type: "playlist" | "album"; id: string } | { kind: "youtube"; listId: string };

export function parseImportLink(url: string): ImportLink | null {
  const u = url.trim();
  const sp = u.match(/open\.spotify\.com\/(?:intl-[a-z-]+\/)?(?:embed\/)?(playlist|album)\/([A-Za-z0-9]+)/);
  if (sp) return { kind: "spotify", type: sp[1] as "playlist" | "album", id: sp[2] };
  if (/youtube\.com|youtu\.be/.test(u)) {
    const yt = u.match(/[?&]list=([A-Za-z0-9_-]+)/);
    if (yt) return { kind: "youtube", listId: yt[1] };
  }
  return null;
}

const VIDEO_NOISE = /\s*[(\[](official|lyric|lyrics|audio|video|visualizer|hd|4k|mv|m\/v)[^)\]]*[)\]]/gi;

/** "Artist - Song (Official Video)" or an "Artist - Topic" upload -> artist + title. */
export function fromVideo(title: string, uploader: string, durationSec: number): ImportTrack {
  const name = title.replace(VIDEO_NOISE, "").trim();
  if (uploader.endsWith(" - Topic")) return { title: name, artist: uploader.slice(0, -" - Topic".length), durationSec };
  if (name.includes(" - ")) {
    const [a, ...rest] = name.split(" - ");
    return { title: rest.join(" - ").trim(), artist: a.trim(), durationSec };
  }
  return { title: name, artist: uploader.replace(/VEVO$/, "").trim(), durationSec };
}

export async function fetchImport(url: string): Promise<ImportSource> {
  const link = parseImportLink(url);
  if (!link) throw new Error("Paste a Spotify or YouTube playlist link.");
  if (link.kind === "youtube") {
    const pl = await invoke<{ name: string; entries: { title: string; uploader: string; durationSec: number }[] }>("youtube_playlist", { listId: link.listId });
    return { name: pl.name, service: "YouTube", tracks: pl.entries.map((e) => fromVideo(e.title, e.uploader, e.durationSec)) };
  }
  const html = await invoke<string>("spotify_embed", { kind: link.type, id: link.id });
  const m = html.match(/<script id="__NEXT_DATA__" type="application\/json">([\s\S]*?)<\/script>/);
  const entity = m ? JSON.parse(m[1])?.props?.pageProps?.state?.data?.entity : null;
  if (!entity?.trackList) throw new Error(`Couldn't read this Spotify ${link.type}.`);
  const tracksList: ImportTrack[] = (entity.trackList as any[]).map((t) => ({
    title: t.title ?? "",
    artist: String(t.subtitle ?? "").split(",")[0].replace(/\u00a0/g, " ").trim(),
    durationSec: Math.round((t.duration ?? 0) / 1000),
  })).filter((t) => t.title);
  return { name: entity.name || "Spotify playlist", service: "Spotify", tracks: tracksList };
}

/** Find each imported track on Deezer, 3 at a time; keeps the playlist's order. */
export async function matchImport(src: ImportSource, onProgress: (done: number, total: number) => void, cancelled: () => boolean) {
  const found: (Song | null)[] = new Array(src.tracks.length).fill(null);
  let next = 0;
  let done = 0;
  const worker = async () => {
    while (next < src.tracks.length && !cancelled()) {
      const i = next++;
      const t = src.tracks[i];
      found[i] = await deezerApi.findTrack(t.title, t.artist, t.durationSec).catch(() => null);
      onProgress(++done, src.tracks.length);
    }
  };
  await Promise.all([worker(), worker(), worker()]);
  const seen = new Set<number>();
  const matched = found.filter((s): s is Song => !!s && !seen.has(s.id) && !!seen.add(s.id));
  const missing = src.tracks.filter((_, i) => !found[i]);
  return { matched, missing };
}

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
