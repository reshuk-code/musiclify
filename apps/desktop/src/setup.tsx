import { useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import QRCode from "qrcode";
import { Smartphone, UserPlus, Wifi } from "lucide-react";
import { db, type LinkEvent, type LinkInfo, type PairedDevice } from "./api";

function timeAgo(ms: number): string {
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return new Date(ms).toLocaleDateString();
}
import { Modal, NameDialog, Spinner } from "./components";
import { ArtistPicker } from "./pages";
import { useStore } from "./store";
import { setUpdatesEnabled, updatesEnabled } from "./updates";
import { ambientEnabled, setAmbientEnabled } from "./ambient";
import { autoplayEnabled, setAutoplayEnabled } from "./player";
import { getVersion } from "@tauri-apps/api/app";

// ---------------------------------------------------------------- onboarding

export function Onboarding() {
  const store = useStore();
  const [step, setStep] = useState<"welcome" | "name" | "artists" | "link">("welcome");
  const [name, setName] = useState("");

  const finish = async () => {
    await db.setProfile(undefined, true);
    await store.reloadProfile();
    await store.reloadFavorites();
    store.refresh();
  };

  if (step === "artists") {
    return (
      <ArtistPicker title="Pick artists you like" subtitle="Choose at least 3. We'll build your home from them."
        initial={[]} minimum={3} confirm="Done" onBack={() => setStep("name")}
        onDone={async (artists) => {
          await db.setFavorites(artists);
          await db.setProfile(name, true);
          await finish();
        }} />
    );
  }

  return (
    <div className="onboard">
      <div className="onboard-card">
        <h1 className="brand-big">Musiclify</h1>
        <p className="muted">Your music, your taste. Everything stays on this computer.</p>

        {step === "welcome" && (
          <div className="choice-list">
            <button className="choice" onClick={() => setStep("name")}>
              <UserPlus size={26} />
              <span><b>Set up a new profile</b><span className="muted small block">Pick your name and favourite artists</span></span>
            </button>
            <button className="choice" onClick={() => setStep("link")}>
              <Smartphone size={26} />
              <span><b>Import from my phone</b><span className="muted small block">Scan a QR code with the Musiclify app to bring your likes, playlists and history</span></span>
            </button>
          </div>
        )}

        {step === "name" && (
          <form onSubmit={(e) => { e.preventDefault(); if (name.trim()) setStep("artists"); }}>
            <label className="label">What should we call you?</label>
            <input className="input" autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="Your name" maxLength={30} />
            <div className="modal-actions">
              <button type="button" className="btn ghost" onClick={() => setStep("welcome")}>Back</button>
              <button className="btn primary round" disabled={!name.trim()}>Continue</button>
            </div>
          </form>
        )}

        {step === "link" && (
          <LinkPanel onImported={finish} onCancel={() => setStep("welcome")} />
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- QR linking

export function LinkPanel({ onImported, onCancel }: { onImported?: () => void; onCancel: () => void }) {
  const store = useStore();
  const [info, setInfo] = useState<LinkInfo | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [offlineIds, setOfflineIds] = useState<number[]>([]);
  const [queued, setQueued] = useState(false);

  useEffect(() => {
    let alive = true;
    db.linkStart()
      .then(async (i) => {
        if (!alive) return;
        setInfo(i);
        setQr(await QRCode.toDataURL(i.payload, { width: 280, margin: 1, color: { dark: "#0f0f11", light: "#ffffff" } }));
      })
      .catch((e) => setError(String(e)));
    const un = listen<LinkEvent>("link-event", async ({ payload }) => {
      if (payload.kind === "imported") {
        const s = payload.summary;
        setStatus(
          `Imported${s.name ? ` ${s.name}'s library` : ""}: ${s.artists} favourite artists, ${s.liked} liked songs, ` +
          `${s.playlists} playlists, ${s.history} plays of history and lyrics for ${s.lyrics} songs.`,
        );
        setOfflineIds(s.offlineIds);
        setError(null);
        setDone(true);
        store.refresh();
        await store.reloadProfile();
        await store.reloadFavorites();
      } else if (payload.kind === "exported") {
        setStatus("Your library was sent to the phone.");
        setError(null);
        setDone(true);
      } else if (payload.kind === "error") {
        setError(`A device tried to connect with the wrong code (${payload.message}). Still waiting for your phone…`);
      }
    });
    return () => {
      alive = false;
      un.then((f) => f());
      db.linkStop();
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="link-panel">
      {!done && (
        <>
          <ol className="steps">
            <li>Open <b>Musiclify</b> on your phone</li>
            <li>Go to <b>Settings → Link a device</b></li>
            <li>Scan this code, then choose <b>Send my library</b> or <b>Get library</b></li>
          </ol>
          <div className="qr-box">{qr ? <img src={qr} alt="Pairing QR code" /> : error ? null : <Spinner />}</div>
          {info && <p className="muted small center"><Wifi size={13} /> Phone and PC must be on the same Wi-Fi · {info.host}:{info.port}</p>}
          <p className="muted small center">Data goes straight from your phone to this PC, encrypted with a one-time key. Nothing is uploaded anywhere.</p>
        </>
      )}
      {status && <p className="success">{status}</p>}
      {!!offlineIds.length && (
        <div className="offline-offer">
          <p><b>{offlineIds.length} songs</b> were saved offline on your phone.</p>
          {queued
            ? <p className="muted small">Downloading them one by one in the background. You can keep using Musiclify.</p>
            : <button className="btn primary round sm" onClick={async () => { await db.downloadMany(offlineIds); setQueued(true); }}>
                Download them on this PC
              </button>}
        </div>
      )}
      {error && <p className="error">{error}</p>}
      <div className="modal-actions">
        {done && onImported ? <button className="btn primary round" onClick={onImported}>Continue</button>
          : <button className="btn ghost" onClick={onCancel}>{done ? "Close" : "Cancel"}</button>}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- settings

const OS_NAME = /Mac/i.test(navigator.userAgent) ? "macOS" : /Linux/i.test(navigator.userAgent) ? "Linux" : "Windows";

export function SettingsPage() {
  const store = useStore();
  const [renaming, setRenaming] = useState(false);
  const [linking, setLinking] = useState(false);
  const [storage, setStorage] = useState(0);
  const [msg, setMsg] = useState<string | null>(null);
  const [paired, setPaired] = useState<PairedDevice[]>([]);
  const [autostart, setAutostart] = useState(true);
  const [checkUpdates, setCheckUpdates] = useState(updatesEnabled());
  const [ambient, setAmbient] = useState(ambientEnabled());
  const [autoplay, setAutoplay] = useState(autoplayEnabled());
  const [version, setVersion] = useState("");
  useEffect(() => { getVersion().then(setVersion).catch(() => {}); }, []);
  useEffect(() => { db.autostartGet().then(setAutostart).catch(() => {}); }, []);
  const p = store.profile;

  useEffect(() => { db.storageBytes().then(setStorage); db.pairedList().then(setPaired); }, [store.version]);

  const importCookies = (file: File) => {
    file.text().then(async (t) => {
      try { await db.setCookies(t); setMsg("Cookies imported."); } catch (e) { setMsg(String(e)); }
      store.reloadProfile();
    });
  };

  const row = (title: string, sub: string, action?: React.ReactNode) => (
    <div className="setting"><div><b>{title}</b><p className="muted small">{sub}</p></div>{action}</div>
  );

  return (
    <div className="page narrow">
      <h1>Settings</h1>

      <h3 className="group">Profile</h3>
      {row("Name", p?.name || "Not set", <button className="btn ghost sm" onClick={() => setRenaming(true)}>Change</button>)}
      {row("Favourite artists", store.favorites.map((f) => f.name).join(", ") || "None",
        <button className="btn ghost sm" onClick={() => store.navigate({ name: "editArtists" })}>Edit</button>)}

      <h3 className="group">Devices</h3>
      {row("Link a device", "Scan this PC's QR code with Musiclify on your phone. After that, tap Sync on the phone any time: no more scanning.",
        <button className="btn primary sm round" onClick={() => setLinking(true)}>Show QR code</button>)}
      {paired.map((d) => row(
        d.name,
        `Paired · ${d.lastSync ? `last synced ${timeAgo(d.lastSync)}` : "not synced yet"} · syncs whenever the phone opens Musiclify or taps Sync`,
        <button className="btn ghost sm" onClick={async () => { await db.unpair(d.id); setPaired(await db.pairedList()); }}>Unpair</button>,
      ))}

      {row(`Start with ${OS_NAME}`,
        "Musiclify starts quietly in the tray when you sign in, so your phone can sync or hand music over any time. Closing the window keeps it running in the tray; use Quit from the tray icon to exit.",
        <label className="switch"><input type="checkbox" checked={autostart}
          onChange={async (e) => { const on = e.target.checked; setAutostart(on); try { await db.autostartSet(on); } catch { setAutostart(!on); } }} /><span /></label>)}

      <h3 className="group">Speed</h3>
      {row("Shared song matching",
        "Songs start faster by reusing which YouTube video matches each song, found by other Musiclify users. Only song and video IDs are shared, never audio or anything about you.",
        <label className="switch"><input type="checkbox" checked={p?.shareMatches ?? true}
          onChange={async (e) => { await db.setShareMatches(e.target.checked); store.reloadProfile(); }} /><span /></label>)}

      <h3 className="group">Storage</h3>
      {row("Downloaded songs", `${(storage / 1024 / 1024).toFixed(1)} MB · encrypted, only playable in Musiclify`,
        <button className="btn ghost sm danger" onClick={async () => { if (confirm("Remove all downloaded songs?")) { await db.clearDownloads(); store.refresh(); } }}>Remove all</button>)}
      {row("Free up space", "Choose which downloads to remove, with their sizes",
        <button className="btn ghost sm" onClick={() => store.navigate({ name: "freeSpace" })}>Review</button>)}
      {row("Listening history", "Clears Recently played",
        <button className="btn ghost sm" onClick={async () => { await db.clearHistory(); store.refresh(); }}>Clear</button>)}

      <h3 className="group">Playback</h3>
      {row("Autoplay", "When your queue ends, keep playing similar songs.",
        <label className="switch"><input type="checkbox" checked={autoplay}
          onChange={(e) => { setAutoplayEnabled(e.target.checked); setAutoplay(e.target.checked); }} /><span /></label>)}

      <h3 className="group">Look</h3>
      {row("Ambient light",
        "A soft glow in the colours of the song's cover that pulses with the beat.",
        <label className="switch"><input type="checkbox" checked={ambient}
          onChange={(e) => { setAmbientEnabled(e.target.checked); setAmbient(e.target.checked); }} /><span /></label>)}

      <h3 className="group">Updates</h3>
      {row("Check for updates",
        "Once a day, look for a newer version on GitHub Releases. Only the public release list is read; nothing about you or this PC is sent.",
        <label className="switch"><input type="checkbox" checked={checkUpdates}
          onChange={(e) => { setUpdatesEnabled(e.target.checked); setCheckUpdates(e.target.checked); }} /><span /></label>)}

      <h3 className="group">YouTube</h3>
      {row("YouTube cookies", p?.hasCookies ? "Imported." : "Optional. Only needed if YouTube blocks this PC. Import a cookies.txt exported from youtube.com.",
        <div className="row-actions">
          <label className="btn ghost sm">{p?.hasCookies ? "Replace" : "Import"}<input type="file" accept=".txt" hidden onChange={(e) => e.target.files?.[0] && importCookies(e.target.files[0])} /></label>
          {p?.hasCookies && <button className="btn ghost sm" onClick={async () => { await db.setCookies(null); setMsg("Cookies removed."); store.reloadProfile(); }}>Remove</button>}
        </div>)}
      {msg && <p className="accent small">{msg}</p>}
      <p className="muted small">Cookies stay on this PC and are only used by yt-dlp for youtube.com. Use a spare Google account: YouTube may restrict accounts used for downloading.</p>

      <h3 className="group">About</h3>
      {row(`Musiclify for desktop ${version}`, "Open source (GPL-3.0). Song info from Deezer, lyrics from LRCLIB, audio via yt-dlp.")}
      {row("Personal use only", "Music is streamed from YouTube for your own listening. Respect artists and copyright.")}

      {renaming && <NameDialog title="Your name" confirm="Save" initial={p?.name} onClose={() => setRenaming(false)}
        onConfirm={async (n) => { await db.setProfile(n); await store.reloadProfile(); setRenaming(false); }} />}
      {linking && <Modal title="Link a device" onClose={() => setLinking(false)}><LinkPanel onCancel={() => setLinking(false)} /></Modal>}
    </div>
  );
}
