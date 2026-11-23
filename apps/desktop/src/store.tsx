import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { listen } from "@tauri-apps/api/event";
import { db, type Artist, type DownloadProgress, type PlaylistSummary, type Profile, type Song, type SyncEvent } from "./api";

export type Route =
  | { name: "home" }
  | { name: "search" }
  | { name: "library" }
  | { name: "liked" }
  | { name: "downloads" }
  | { name: "history" }
  | { name: "mix" }
  | { name: "playlist"; id: number }
  | { name: "artist"; id: number }
  | { name: "album"; id: number }
  | { name: "stats" }
  | { name: "import" }
  | { name: "freeSpace" }
  | { name: "settings" }
  | { name: "editArtists" };

interface Store {
  profile: Profile | null;
  reloadProfile: () => Promise<void>;
  favorites: Artist[];
  reloadFavorites: () => Promise<void>;
  likedIds: Set<number>;
  downloadedIds: Set<number>;
  progress: Record<number, number>;
  playlists: PlaylistSummary[];
  /** Bumped whenever library data changes, so lists can refetch. */
  version: number;
  refresh: () => void;
  toggleLike: (song: Song) => Promise<void>;
  route: Route;
  navigate: (r: Route) => void;
  back: () => void;
  canGoBack: boolean;
  toast: string | null;
  showToast: (msg: string) => void;
}

const Ctx = createContext<Store | null>(null);

export function StoreProvider({ children }: { children: ReactNode }) {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [favorites, setFavorites] = useState<Artist[]>([]);
  const [likedIds, setLikedIds] = useState<Set<number>>(new Set());
  const [downloadedIds, setDownloadedIds] = useState<Set<number>>(new Set());
  const [progress, setProgress] = useState<Record<number, number>>({});
  const [playlists, setPlaylists] = useState<PlaylistSummary[]>([]);
  const [version, setVersion] = useState(0);
  const [stack, setStack] = useState<Route[]>([{ name: "home" }]);
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<number | undefined>(undefined);

  const reloadProfile = useCallback(async () => setProfile(await db.profile()), []);
  const reloadFavorites = useCallback(async () => setFavorites(await db.favorites()), []);
  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    reloadProfile();
    reloadFavorites();
  }, [reloadProfile, reloadFavorites]);

  useEffect(() => {
    (async () => {
      const [liked, downloads, pls] = await Promise.all([db.liked(), db.downloads(), db.playlists()]);
      setLikedIds(new Set(liked.map((s) => s.id)));
      setDownloadedIds(new Set(downloads.map((s) => s.id)));
      setPlaylists(pls);
    })();
  }, [version]);

  // A paired phone synced: pick up its changes everywhere.
  useEffect(() => {
    const un = listen<SyncEvent>("sync-event", async ({ payload }) => {
      setVersion((v) => v + 1);
      if (payload.kind === "synced") {
        await Promise.all([reloadProfile(), reloadFavorites()]);
        setToast(`Synced with ${payload.device}`);
        window.clearTimeout(toastTimer.current);
        toastTimer.current = window.setTimeout(() => setToast(null), 2800);
      }
    });
    return () => { un.then((f) => f()); };
  }, [reloadProfile, reloadFavorites]);

  useEffect(() => {
    const un = listen<DownloadProgress>("download-progress", ({ payload }) => {
      setProgress((p) => {
        const next = { ...p };
        if (payload.done) delete next[payload.id];
        else next[payload.id] = payload.progress;
        return next;
      });
      if (payload.done && !payload.error) setVersion((v) => v + 1);
    });
    return () => {
      un.then((f) => f());
    };
  }, []);

  const toggleLike = useCallback(async (song: Song) => {
    const liked = await db.toggleLike(song);
    setLikedIds((ids) => {
      const next = new Set(ids);
      liked ? next.add(song.id) : next.delete(song.id);
      return next;
    });
    refresh();
  }, [refresh]);

  const showToast = useCallback((msg: string) => {
    setToast(msg);
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(null), 2800);
  }, []);

  const value = useMemo<Store>(() => ({
    profile, reloadProfile, favorites, reloadFavorites, likedIds, downloadedIds, progress, playlists,
    version, refresh, toggleLike,
    route: stack[stack.length - 1],
    navigate: (r) => setStack((s) => {
      const top = s[s.length - 1];
      if (JSON.stringify(top) === JSON.stringify(r)) return s;
      // Top-level sections reset the stack, like tabs.
      if (["home", "search", "library", "settings"].includes(r.name)) return [r];
      return [...s, r];
    }),
    back: () => setStack((s) => (s.length > 1 ? s.slice(0, -1) : s)),
    canGoBack: stack.length > 1,
    toast, showToast,
  }), [profile, reloadProfile, favorites, reloadFavorites, likedIds, downloadedIds, progress, playlists, version, refresh, toggleLike, stack, toast, showToast]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useStore(): Store {
  const s = useContext(Ctx);
  if (!s) throw new Error("useStore outside provider");
  return s;
}
