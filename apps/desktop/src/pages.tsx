import { useEffect, useMemo, useRef, useState } from "react";
import { BarChart3, Download, Heart, History, Link2, Plus, RefreshCw, Search as SearchIcon, X } from "lucide-react";
import { db, deezerApi, POPULAR_ARTISTS, withOriginalCovers, type Artist, type Song } from "./api";
import { AlbumCard } from "./extra";
import { ArtistTile, Cover, Empty, NameDialog, PlayShuffle, Section, Shelf, SongCard, SongRow, Spinner } from "./components";
import { usePlayer } from "./player";
import { useStore } from "./store";

export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [nonce, setNonce] = useState(0);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    fn().then((d) => alive && setData(d)).catch((e) => alive && setError(String(e))).finally(() => alive && setLoading(false));
    return () => { alive = false; };
  }, [...deps, nonce]); // eslint-disable-line react-hooks/exhaustive-deps
  return { data, error, loading, reload: () => setNonce((n) => n + 1) };
}

function greeting() {
  const h = new Date().getHours();
  return h >= 5 && h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
}

function interleave<T>(lists: T[][]): T[] {
  const out: T[] = [];
  const max = Math.max(0, ...lists.map((l) => l.length));
  for (let i = 0; i < max; i++) for (const l of lists) if (l[i]) out.push(l[i]);
  return out;
}

function shuffle<T>(arr: T[]): T[] {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

// ---------------------------------------------------------------- home

type Feed = { chart: Song[]; tops: { artist: Artist; songs: Song[] }[]; mix: Song[]; suggestions: Artist[] };

// The home feed (and its mix) lives outside the page, so leaving home and coming back shows the same mix
// and the mix page lists exactly what Play plays. It's rebuilt on Refresh, when favourites change,
// or once it's older than FEED_TTL.
const FEED_TTL = 30 * 60 * 1000;
let feedCache: { key: string; at: number; data: Feed } | null = null;

export function HomePage() {
  const store = useStore();
  const p = usePlayer();
  const favKey = store.favorites.map((f) => f.id).join(",");
  const recent = useAsync(() => db.recent(20), [store.version]);
  const likedCount = useAsync(async () => (await db.liked()).length, [store.version]);
  const force = useRef(false);
  const feed = useAsync(async (): Promise<Feed> => {
    if (!force.current && feedCache && feedCache.key === favKey && Date.now() - feedCache.at < FEED_TTL) return feedCache.data;
    force.current = false;
    const favs = store.favorites;
    // favorites are oldest-first: the last 2 are the newest (show a just-added artist right away),
    // plus a random 4 of the rest so every artist gets a turn.
    const newest = favs.slice(-2).reverse();
    const picked = [...newest, ...shuffle(favs.slice(0, -2)).slice(0, 6 - newest.length)];
    const [chart, tops, related] = await Promise.all([
      deezerApi.chartTracks(30).then(withOriginalCovers).catch(() => [] as Song[]),
      // Deeper pool, shuffled, so the same 15 songs don't come back every time.
      Promise.all(picked.map(async (a) => ({ artist: a, songs: await withOriginalCovers(shuffle(await deezerApi.artistTop(a.id, 50).catch(() => [] as Song[])).slice(0, 15)) }))),
      Promise.all(picked.slice(0, 4).map((a) => deezerApi.related(a.id, 8).catch(() => [] as Artist[]))),
    ]);
    const favIds = new Set(favs.map((f) => f.id));
    const seen = new Set<number>();
    const suggestions = related.flat().filter((a) => !favIds.has(a.id) && !seen.has(a.id) && seen.add(a.id)).slice(0, 14);
    const withSongs = tops.filter((t) => t.songs.length);
    const data = { chart, tops: withSongs, mix: shuffle(interleave(withSongs.map((t) => t.songs)).slice(0, 30)), suggestions };
    if (withSongs.length) feedCache = { key: favKey, at: Date.now(), data };
    return data;
  }, [favKey]);
  const refreshFeed = () => { force.current = true; feed.reload(); };

  const mix = feed.data?.mix ?? [];
  const mixOffline = mix.filter((s) => store.downloadedIds.has(s.id)).length;
  const mixArtists = [...new Set(mix.map((s) => s.artist))];

  // "Jump back in": liked, the mix, playlists, then recently played songs.
  type Tile = { key: string; label: string; cover?: string | null; icon?: React.ReactNode; playing?: boolean; onClick: () => void };
  const tiles: Tile[] = [
    { key: "liked", label: "Liked Songs", icon: <Heart size={22} fill="currentColor" />, onClick: () => store.navigate({ name: "liked" }) },
  ];
  if (mix.length) tiles.push({ key: "mix", label: "Your mix", cover: mix[0].cover, playing: !!p.current && mix.some((s) => s.id === p.current!.id), onClick: () => store.navigate({ name: "mix" }) });
  for (const pl of store.playlists.slice(0, 2)) tiles.push({ key: `pl${pl.id}`, label: pl.name, cover: pl.cover, onClick: () => store.navigate({ name: "playlist", id: pl.id }) });
  (recent.data ?? []).forEach((s, i) => {
    if (tiles.length < 6) tiles.push({ key: `r${s.id}`, label: s.title, cover: s.cover, playing: p.current?.id === s.id, onClick: () => p.playSongs(recent.data!, i) });
  });

  return (
    <div className="page">
      <div className="topbar">
        <label className="search-bar top-search">
          <SearchIcon size={18} />
          <span className="sr-only">Search songs or artists</span>
          <input placeholder="Search songs, artists" onChange={(e) => { sessionStorage.setItem("q", e.target.value); store.navigate({ name: "search" }); }} />
          <kbd>Ctrl K</kbd>
        </label>
        <button className="icon-btn" onClick={refreshFeed} aria-label="Refresh"><RefreshCw size={18} /></button>
        <span className="avatar" role="img" aria-label={`Profile: ${store.profile?.name ?? ""}`}>{(store.profile?.name || "?").slice(0, 1).toUpperCase()}</span>
      </div>

      <h1 className="greet">{greeting()}{store.profile?.name ? `, ${store.profile.name}` : ""}</h1>
      <div className="jump-grid">
        {tiles.map((t) => (
          <button key={t.key} className="jump" onClick={t.onClick}>
            {t.icon ? <span className="jump-art liked-art">{t.icon}</span> : <Cover src={t.cover} size={64} radius={0} className="jump-art" />}
            <span className="jump-label">{t.label}</span>
            {t.playing && p.playing && <span className="eq" aria-label="Playing"><i /><i /><i /></span>}
          </button>
        ))}
      </div>

      {feed.loading && !feed.data && <div className="center-box"><Spinner /></div>}
      {feed.error && !feed.data && <Empty text="Couldn't load music. Check your internet connection." action="Retry" onAction={refreshFeed} />}

      {feed.data && (
        <>
          {!!mix.length && (
            <section className="mix-hero">
              <button className="collage" onClick={() => store.navigate({ name: "mix" })} aria-label="Open Your mix">
                {mix.slice(0, 4).map((s) => <Cover key={s.id} src={s.cover} radius={0} />)}
              </button>
              <div className="mix-info">
                <span className="kicker accent">Made for you</span>
                <h2 className="mix-title"><button className="link" onClick={() => store.navigate({ name: "mix" })}>Your mix</button></h2>
                <p className="muted">{mixArtists.slice(0, 3).join(", ")}{mixArtists.length > 3 ? ` and ${mixArtists.length - 3} more` : ""} · {mix.length} songs{mixOffline ? ` · ${mixOffline} offline` : ""}</p>
                <PlayShuffle songs={mix} />
              </div>
            </section>
          )}
          {feed.data.tops.map(({ artist, songs }) => (
            <Section key={artist.id} title={`More from ${artist.name}`}
              action={<button className="link muted small strong" onClick={() => store.navigate({ name: "artist", id: artist.id })}>See artist</button>}>
              <Shelf>{songs.map((s, i) => <SongCard key={s.id} song={s} onPlay={() => p.playSongs(songs, i)} />)}</Shelf>
            </Section>
          ))}
          {!!feed.data.suggestions.length && (
            <Section title="Artists you might like">
              <Shelf>{feed.data.suggestions.map((a) => <ArtistTile key={a.id} artist={a} onClick={() => store.navigate({ name: "artist", id: a.id })} />)}</Shelf>
            </Section>
          )}
          {!!feed.data.chart.length && (
            <Section title="Top charts" subtitle="Popular right now">
              <Shelf>{feed.data.chart.map((s, i) => <SongCard key={s.id} song={s} onPlay={() => p.playSongs(feed.data!.chart, i)} />)}</Shelf>
            </Section>
          )}
          {likedCount.data === 0 && !recent.data?.length && (
            <p className="muted small">Tip: tap ♡ on songs you love. They show up in Liked Songs and help shape your mix.</p>
          )}
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- search

export function SearchPage() {
  const store = useStore();
  const p = usePlayer();
  const [q, setQ] = useState(() => sessionStorage.getItem("q") ?? "");
  const [debounced, setDebounced] = useState(q);
  useEffect(() => {
    sessionStorage.setItem("q", q);
    const t = setTimeout(() => setDebounced(q.trim()), 300);
    return () => clearTimeout(t);
  }, [q]);
  const results = useAsync(async () => {
    if (!debounced) return null;
    const [songs, artists] = await Promise.all([deezerApi.searchTracks(debounced), deezerApi.searchArtists(debounced, 10)]);
    return { songs, artists };
  }, [debounced]);
  // Show results right away, then swap greatest-hits covers for the original albums'.
  const [fixed, setFixed] = useState<Song[] | null>(null);
  useEffect(() => {
    setFixed(null);
    const songs = results.data?.songs;
    if (!songs?.length) return;
    let alive = true;
    withOriginalCovers(songs).then((s) => alive && setFixed(s));
    return () => { alive = false; };
  }, [results.data]);
  const songs = fixed ?? results.data?.songs ?? [];

  const [recents, setRecents] = useState<string[]>(() => { try { return JSON.parse(localStorage.getItem("recentSearches") ?? "[]"); } catch { return []; } });
  const saveRecents = (list: string[]) => { setRecents(list); localStorage.setItem("recentSearches", JSON.stringify(list)); };
  /** Remember a search the user acted on (Enter, or opened a result), not every half-typed word. */
  const remember = () => {
    const t = q.trim();
    if (t.length >= 2) saveRecents([t, ...recents.filter((r) => r.toLowerCase() !== t.toLowerCase())].slice(0, 10));
  };

  return (
    <div className="page">
      <div className="search-bar">
        <SearchIcon size={18} />
        <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === "Enter" && remember()} placeholder="What do you want to listen to?" />
        {q && <button className="icon-btn" onClick={() => setQ("")} aria-label="Clear"><X size={16} /></button>}
      </div>
      {!debounced && !!recents.length && (
        <Section title="Recent searches" action={<button className="link muted small strong" onClick={() => saveRecents([])}>Clear all</button>}>
          <div className="recent-searches">
            {recents.map((r) => (
              <span key={r} className="recent-chip">
                <button className="link" onClick={() => setQ(r)}><History size={14} />{r}</button>
                <button className="icon-btn sm" onClick={() => saveRecents(recents.filter((x) => x !== r))} aria-label={`Remove "${r}"`}><X size={14} /></button>
              </span>
            ))}
          </div>
        </Section>
      )}
      {!debounced && (
        <Section title="Browse artists">
          <div className="grid-artists">
            {POPULAR_ARTISTS.slice(0, 24).map((a) => <ArtistTile key={a.id} artist={a} size={120} onClick={() => store.navigate({ name: "artist", id: a.id })} />)}
          </div>
        </Section>
      )}
      {debounced && results.loading && <div className="center-box"><Spinner /></div>}
      {debounced && results.error && <Empty text={`Search failed: ${results.error}`} />}
      {results.data && (
        <>
          {!results.data.songs.length && !results.data.artists.length && <Empty text={`No results for "${debounced}".`} />}
          {!!results.data.artists.length && (
            <Section title="Artists">
              <Shelf>{results.data.artists.map((a) => <ArtistTile key={a.id} artist={a} size={120} onClick={() => { remember(); store.navigate({ name: "artist", id: a.id }); }} />)}</Shelf>
            </Section>
          )}
          {!!songs.length && (
            <Section title="Songs">
              <div className="rows">
                {songs.map((s, i) => <SongRow key={s.id} song={s} index={i} onPlay={() => { remember(); p.playSongs(songs, i); }} />)}
              </div>
            </Section>
          )}
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- library

export function LibraryPage() {
  const store = useStore();
  const [creating, setCreating] = useState(false);
  const counts = useAsync(async () => {
    const [liked, downloads, recent] = await Promise.all([db.liked(), db.downloads(), db.recent(500)]);
    return { liked: liked.length, downloads: downloads.length, recent: recent.length };
  }, [store.version]);

  const entry = (title: string, sub: string, icon: React.ReactNode, onClick: () => void) => (
    <button className="lib-entry" onClick={onClick}>
      <span className="lib-icon">{icon}</span>
      <span><b>{title}</b><span className="muted small block">{sub}</span></span>
    </button>
  );

  return (
    <div className="page">
      <header className="page-head">
        <h1>Your Library</h1>
        <button className="btn ghost round sm" onClick={() => setCreating(true)}><Plus size={16} />New playlist</button>
      </header>
      <div className="lib-grid">
        {entry("Liked Songs", `${counts.data?.liked ?? 0} songs`, <Heart size={22} fill="currentColor" />, () => store.navigate({ name: "liked" }))}
        {entry("Downloads", `${counts.data?.downloads ?? 0} songs · plays offline`, <Download size={22} />, () => store.navigate({ name: "downloads" }))}
        {entry("Recently played", `${counts.data?.recent ?? 0} songs`, <History size={22} />, () => store.navigate({ name: "history" }))}
        {entry("Your stats", "Top songs, artists and minutes listened", <BarChart3 size={22} />, () => store.navigate({ name: "stats" }))}
        {entry("Import playlist", "From a Spotify or YouTube link", <Link2 size={22} />, () => store.navigate({ name: "import" }))}
      </div>
      <Section title="Playlists">
        {!store.playlists.length && <Empty text="No playlists yet." action="Create playlist" onAction={() => setCreating(true)} />}
        <div className="playlist-grid">
          {store.playlists.map((pl) => (
            <button key={pl.id} className="card" onClick={() => store.navigate({ name: "playlist", id: pl.id })}>
              <div className="card-art"><Cover src={pl.cover} radius={8} /></div>
              <span className="card-title">{pl.name}</span>
              <span className="card-sub">{pl.songCount} songs</span>
            </button>
          ))}
        </div>
      </Section>
      {creating && <NameDialog title="New playlist" confirm="Create" onClose={() => setCreating(false)} onConfirm={async (n) => {
        await db.createPlaylist(n); store.refresh(); setCreating(false);
      }} />}
    </div>
  );
}

function ListPage({ title, kicker, songs, empty, cover, extra, onRemove }: {
  title: string; kicker: string; songs: Song[] | null; empty: string; cover?: string | null;
  extra?: React.ReactNode; onRemove?: (s: Song) => void;
}) {
  const p = usePlayer();
  return (
    <div className="page">
      <header className="hero">
        <Cover src={cover ?? songs?.[0]?.coverBig} size={180} radius={10} />
        <div>
          <span className="kicker">{kicker}</span>
          <h1 className="hero-title">{title}</h1>
          <p className="muted">{songs ? `${songs.length} songs` : ""}</p>
          <div className="hero-actions"><PlayShuffle songs={songs ?? []} />{extra}</div>
        </div>
      </header>
      {!songs ? <div className="center-box"><Spinner /></div> : !songs.length ? <Empty text={empty} /> : (
        <div className="rows">
          {songs.map((s, i) => <SongRow key={s.id} song={s} index={i} onPlay={() => p.playSongs(songs, i)} onRemove={onRemove && (() => onRemove(s))} />)}
        </div>
      )}
    </div>
  );
}

export function MixPage() {
  const store = useStore();
  const [, bump] = useState(0);
  const songs = feedCache?.data.mix ?? [];
  const extra = (
    <button className="btn ghost round" onClick={() => { feedCache = null; bump((n) => n + 1); store.navigate({ name: "home" }); }}>
      <RefreshCw size={16} />New mix
    </button>
  );
  return <ListPage title="Your mix" kicker="Made for you" songs={songs} cover={songs[0]?.coverBig}
    empty="Follow a few artists on Home and your mix shows up here." extra={extra} />;
}

export function LikedPage() {
  const store = useStore();
  const { data } = useAsync(() => db.liked(), [store.version]);
  return <ListPage title="Liked Songs" kicker="Collection" songs={data} empty="Tap ♡ on any song to save it here." />;
}

export function DownloadsPage() {
  const store = useStore();
  const { data } = useAsync(() => db.downloads(), [store.version]);
  return <ListPage title="Downloads" kicker="On this PC" songs={data} empty="Songs you play or download are kept here, encrypted, and play offline." />;
}

export function HistoryPage() {
  const store = useStore();
  const { data } = useAsync(() => db.recent(200), [store.version]);
  return <ListPage title="Recently played" kicker="History" songs={data} empty="Nothing played yet." />;
}

export function PlaylistPage({ id }: { id: number }) {
  const store = useStore();
  const { data } = useAsync(() => db.playlistSongs(id), [id, store.version]);
  const pl = store.playlists.find((x) => x.id === id);
  const [renaming, setRenaming] = useState(false);
  return (
    <>
      <ListPage title={pl?.name ?? "Playlist"} kicker="Playlist" songs={data} cover={pl?.cover}
        empty="Add songs with the ⋯ menu on any song."
        onRemove={async (s) => { await db.removeFromPlaylist(id, s.id); store.refresh(); }}
        extra={<>
          <button className="btn ghost round sm" onClick={() => setRenaming(true)}>Rename</button>
          <button className="btn ghost round sm danger" onClick={async () => { await db.deletePlaylist(id); store.refresh(); store.back(); }}>Delete</button>
        </>} />
      {renaming && <NameDialog title="Rename playlist" confirm="Save" initial={pl?.name} onClose={() => setRenaming(false)}
        onConfirm={async (n) => { await db.renamePlaylist(id, n); store.refresh(); setRenaming(false); }} />}
    </>
  );
}

// ---------------------------------------------------------------- artist

export function ArtistPage({ id }: { id: number }) {
  const store = useStore();
  const p = usePlayer();
  const [showAll, setShowAll] = useState(false);
  const { data, error, reload } = useAsync(async () => {
    const [artist, top, releases, related] = await Promise.all([
      deezerApi.artist(id), deezerApi.artistTop(id, 50), deezerApi.artistAlbums(id).catch(() => []), deezerApi.related(id, 12).catch(() => []),
    ]);
    return {
      artist, top, related,
      albums: releases.filter((a) => a.type === "album" || a.type === "compile"),
      singles: releases.filter((a) => a.type === "single" || a.type === "ep"),
    };
  }, [id]);
  // Then swap greatest-hits covers for the original albums' (a few extra requests).
  const [fixedTop, setFixedTop] = useState<Song[] | null>(null);
  useEffect(() => {
    if (!data) return;
    let alive = true;
    withOriginalCovers(data.top).then((s) => alive && setFixedTop(s));
    return () => { alive = false; };
  }, [data]);
  if (error) return <div className="page"><Empty text="Couldn't load this artist." action="Retry" onAction={reload} /></div>;
  if (!data) return <div className="page center-box"><Spinner /></div>;
  const following = store.favorites.some((f) => f.id === id);
  const top = fixedTop ?? data.top;
  const shown = showAll ? top : top.slice(0, 10);
  return (
    <div className="page">
      <header className="hero">
        <div className="artist-pic hero-pic"><img src={data.artist.picture} alt="" /></div>
        <div>
          <span className="kicker">Artist</span>
          <h1 className="hero-title">{data.artist.name}</h1>
          <div className="hero-actions">
            <PlayShuffle songs={top} />
            <button className={`btn round sm ${following ? "ghost" : "outline"}`} onClick={async () => {
              await db.toggleFavorite(data.artist); await store.reloadFavorites();
            }}>{following ? "Following" : "Follow"}</button>
          </div>
        </div>
      </header>
      <Section title="Popular" action={top.length > 10
        ? <button className="link muted small strong" onClick={() => setShowAll((v) => !v)}>{showAll ? "Show less" : `Show all ${top.length}`}</button> : undefined}>
        <div className="rows">{shown.map((s, i) => <SongRow key={s.id} song={s} index={i} onPlay={() => p.playSongs(top, i)} />)}</div>
      </Section>
      {!!data.albums.length && (
        <Section title="Albums" subtitle={`${data.albums.length} releases`}>
          <Shelf>{data.albums.map((a) => <AlbumCard key={a.id} album={a} onClick={() => store.navigate({ name: "album", id: a.id })} />)}</Shelf>
        </Section>
      )}
      {!!data.singles.length && (
        <Section title="Singles & EPs" subtitle={`${data.singles.length} releases`}>
          <Shelf>{data.singles.map((a) => <AlbumCard key={a.id} album={a} onClick={() => store.navigate({ name: "album", id: a.id })} />)}</Shelf>
        </Section>
      )}
      {!!data.related.length && (
        <Section title="Fans also like">
          <Shelf>{data.related.map((a) => <ArtistTile key={a.id} artist={a} onClick={() => store.navigate({ name: "artist", id: a.id })} />)}</Shelf>
        </Section>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- artist picker (onboarding + settings)

export function ArtistPicker({ title, subtitle, initial, minimum, confirm, onBack, onDone }: {
  title: string; subtitle: string; initial: Artist[]; minimum: number; confirm: string;
  onBack?: () => void; onDone: (artists: Artist[]) => void;
}) {
  const [q, setQ] = useState("");
  const [selected, setSelected] = useState<Artist[]>(initial);
  const [debounced, setDebounced] = useState("");
  useEffect(() => { const t = setTimeout(() => setDebounced(q.trim()), 300); return () => clearTimeout(t); }, [q]);
  const search = useAsync(() => (debounced ? deezerApi.searchArtists(debounced, 30) : Promise.resolve(null)), [debounced]);

  const genres = useMemo(() => {
    const m = new Map<string, Artist[]>();
    for (const a of POPULAR_ARTISTS) m.set(a.genre, [...(m.get(a.genre) ?? []), a]);
    return [...m.entries()];
  }, []);
  const isSel = (a: Artist) => selected.some((s) => s.id === a.id);
  const toggle = (a: Artist) => setSelected((s) => (isSel(a) ? s.filter((x) => x.id !== a.id) : [...s, a]));
  const tiles = (list: Artist[]) => (
    <div className="grid-artists">{list.map((a) => <ArtistTile key={a.id} artist={a} size={112} selected={isSel(a)} onClick={() => toggle(a)} />)}</div>
  );

  return (
    <div className="picker">
      <div className="picker-head">
        <h1>{title}</h1>
        <p className="muted">{subtitle}</p>
        <div className="search-bar">
          <SearchIcon size={18} />
          <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search any artist" />
        </div>
      </div>
      <div className="picker-body">
        {debounced ? (search.data ? tiles(search.data) : <div className="center-box"><Spinner /></div>) : (
          <>
            {selected.some((s) => !POPULAR_ARTISTS.some((p) => p.id === s.id)) &&
              <Section title="Your picks">{tiles(selected.filter((s) => !POPULAR_ARTISTS.some((p) => p.id === s.id)))}</Section>}
            {genres.map(([g, list]) => <Section key={g} title={g}>{tiles(list)}</Section>)}
          </>
        )}
      </div>
      <footer className="picker-foot">
        {onBack && <button className="btn ghost" onClick={onBack}>Back</button>}
        <span className="muted">{selected.length} selected</span>
        <button className="btn primary round" disabled={selected.length < minimum} onClick={() => onDone(selected)}>
          {selected.length < minimum ? `Pick ${minimum - selected.length} more` : confirm}
        </button>
      </footer>
    </div>
  );
}

export function EditArtistsPage() {
  const store = useStore();
  return (
    <ArtistPicker title="Favourite artists" subtitle="Your home page is built from these." initial={store.favorites} minimum={1}
      confirm="Save" onBack={store.back}
      onDone={async (a) => { await db.setFavorites(a); await store.reloadFavorites(); store.back(); }} />
  );
}
