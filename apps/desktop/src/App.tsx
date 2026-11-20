import { useEffect, useRef, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { ChevronLeft, Download, Heart, Home, Library, PanelLeftClose, PanelLeftOpen, Plus, Search, Settings } from "lucide-react";
import { db, type Song } from "./api";
import { Cover, NameDialog } from "./components";
import { ArtistPage, DownloadsPage, EditArtistsPage, HistoryPage, HomePage, LibraryPage, LikedPage, MixPage, PlaylistPage, SearchPage } from "./pages";
import { PlayerProvider, usePlayer, type RepeatMode } from "./player";
import { NowPlayingView, PlayerBar, SidePanel, type Panel } from "./playerui";
import { Onboarding, SettingsPage } from "./setup";
import { StoreProvider, useStore, type Route } from "./store";
import "./App.css";

export default function App() {
  return (
    <StoreProvider>
      <Shell />
    </StoreProvider>
  );
}

function Shell() {
  const store = useStore();
  if (!store.profile) return <div className="boot" />;
  if (!store.profile.onboarded) return <Onboarding />;
  return (
    <PlayerProvider onPlayed={store.refresh}>
      <Main />
    </PlayerProvider>
  );
}

const songs = (n: number) => `${n} ${n === 1 ? "song" : "songs"}`;

function Main() {
  const store = useStore();
  const p = usePlayer();
  const [panel, setPanel] = useState<Panel>(null);
  const [full, setFull] = useState(false);
  const [creating, setCreating] = useState(false);
  const [counts, setCounts] = useState({ liked: 0, offline: 0 });
  // Sidebar: user can fold it to an icon rail; it also folds for the full player and narrow windows.
  const [folded, setFolded] = useState(() => localStorage.getItem("sidebarFolded") === "1");
  const [narrow, setNarrow] = useState(() => window.innerWidth < 1000);
  useEffect(() => {
    const onResize = () => setNarrow(window.innerWidth < 1000);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  const rail = folded || full || narrow;
  const toggleFold = () => setFolded((f) => { localStorage.setItem("sidebarFolded", f ? "0" : "1"); return !f; });
  const r = store.route;

  useEffect(() => {
    Promise.all([db.liked(), db.downloads()]).then(([l, d]) => setCounts({ liked: l.length, offline: d.length }));
  }, [store.version]);

  // A paired phone handed playback over: continue here at the same second, full-screen.
  const playerRef = useRef(p);
  playerRef.current = p;
  useEffect(() => {
    const un = listen<{ device: string; handoff: { songs: Song[]; index: number; positionMs: number; repeat?: RepeatMode; shuffle?: boolean } }>(
      "handoff",
      ({ payload }) => {
        const h = payload.handoff;
        playerRef.current.handoff(h.songs, h.index, (h.positionMs ?? 0) / 1000, h.repeat ?? "off", !!h.shuffle);
        setFull(true);
        store.showToast(`Continuing from ${payload.device}`);
      },
    );
    return () => { un.then((f) => f()); };
  }, [store.showToast]); // eslint-disable-line react-hooks/exhaustive-deps

  // Tray menu "Play / Pause".
  useEffect(() => {
    const un = listen("tray-toggle", () => playerRef.current.toggle());
    return () => { un.then((f) => f()); };
  }, []);

  // Ctrl+K jumps to search from anywhere.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setFull(false);
        store.navigate({ name: "search" });
        setTimeout(() => document.querySelector<HTMLInputElement>(".page .search-bar input")?.focus(), 50);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [store]);

  const go = (route: Route) => { setFull(false); store.navigate(route); };
  const nav = (route: Route, label: string, icon: React.ReactNode) => (
    <button className={`nav-item ${r.name === route.name && !full ? "active" : ""}`} onClick={() => go(route)} title={rail ? label : undefined} aria-label={label}>{icon}<span className="nav-label">{label}</span></button>
  );
  const libItem = (key: string, active: boolean, art: React.ReactNode, name: string, meta: string, onClick: () => void) => (
    <button key={key} className={`side-pl ${active ? "active" : ""}`} onClick={onClick} title={rail ? name : undefined} aria-label={name}>
      {art}<span className="side-pl-text"><span className="side-pl-name">{name}</span><span className="side-pl-meta">{meta}</span></span>
    </button>
  );

  return (
    <div className={`app ${panel && !full ? "with-panel" : ""} ${rail ? "rail" : ""}`}>
      <aside className="sidebar">
        <div className="brand">
          <img src="/icon.png" alt="" width={28} height={28} /><span className="nav-label">Musiclify</span>
          <button className="icon-btn sm fold-btn" onClick={toggleFold} disabled={full || narrow}
            aria-label={rail ? "Expand sidebar" : "Collapse sidebar"} title={rail ? "Expand sidebar" : "Collapse sidebar"}>
            {rail ? <PanelLeftOpen size={18} /> : <PanelLeftClose size={18} />}
          </button>
        </div>
        <nav>
          {nav({ name: "home" }, "Home", <Home size={20} />)}
          {nav({ name: "search" }, "Search", <Search size={20} />)}
          {nav({ name: "library" }, "Library", <Library size={20} />)}
          {nav({ name: "settings" }, "Settings", <Settings size={20} />)}
        </nav>
        <div className="side-playlists">
          <div className="side-lib-head">
            <span className="muted tiny upper">Your library</span>
            <button className="icon-btn sm" onClick={() => setCreating(true)} aria-label="New playlist"><Plus size={16} /></button>
          </div>
          {libItem("liked", r.name === "liked", <span className="lib-art accent-art"><Heart size={16} fill="currentColor" /></span>,
            "Liked Songs", songs(counts.liked), () => go({ name: "liked" }))}
          {libItem("offline", r.name === "downloads", <span className="lib-art"><Download size={16} /></span>,
            "Offline", songs(counts.offline), () => go({ name: "downloads" }))}
          {store.playlists.map((pl) => libItem(`pl${pl.id}`, r.name === "playlist" && r.id === pl.id,
            <Cover src={pl.cover} size={40} radius={8} />, pl.name, `Playlist · ${pl.songCount}`, () => go({ name: "playlist", id: pl.id })))}
        </div>
      </aside>

      <main className="content">
        {store.canGoBack && (
          <button className="back-btn" onClick={store.back} aria-label="Back"><ChevronLeft size={20} /></button>
        )}
        <Page route={r} />
      </main>

      {full && p.current && <NowPlayingView onClose={() => setFull(false)} />}
      {!full && <SidePanel panel={panel} onClose={() => setPanel(null)} />}
      <PlayerBar panel={panel} setPanel={(x) => { setFull(false); setPanel(x); }} onExpand={() => setFull((f) => !f)} />
      {store.toast && <div className="toast">{store.toast}</div>}
      {creating && <NameDialog title="New playlist" confirm="Create" onClose={() => setCreating(false)} onConfirm={async (n) => {
        await db.createPlaylist(n); store.refresh(); setCreating(false);
      }} />}
    </div>
  );
}

function Page({ route }: { route: Route }) {
  switch (route.name) {
    case "home": return <HomePage />;
    case "search": return <SearchPage />;
    case "library": return <LibraryPage />;
    case "liked": return <LikedPage />;
    case "downloads": return <DownloadsPage />;
    case "history": return <HistoryPage />;
    case "mix": return <MixPage />;
    case "playlist": return <PlaylistPage key={route.id} id={route.id} />;
    case "artist": return <ArtistPage key={route.id} id={route.id} />;
    case "settings": return <SettingsPage />;
    case "editArtists": return <EditArtistsPage />;
  }
}
