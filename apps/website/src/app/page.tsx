import Image from "next/image";
import type { ReactNode } from "react";
import { EyeOff, Lock, Smartphone, Wifi, X } from "lucide-react";
import { DownloadButtons, DownloadCards } from "./download-buttons";

export default function Home() {
  return (
    <>
      <Nav />
      <main className="flex-1">
        <Hero />
        <Features />
        <Privacy />
        <section id="download" className="scroll-mt-16 mx-auto flex max-w-[1180px] flex-col items-center px-6 py-24 text-center">
          <h2 className="font-display text-4xl font-medium tracking-tight sm:text-5xl">Get Musiclify</h2>
          <div className="mt-3 w-full"><DownloadCards /></div>
        </section>
        <Faq />
      </main>
      <Footer />
    </>
  );
}

function Logo({ size = 32 }: { size?: number }) {
  return <Image src="/icon.png" alt="" width={size} height={size} className="rounded-[22%]" />;
}

// ---------------------------------------------------------------- nav

function Nav() {
  return (
    <header className="sticky top-0 z-30 border-b border-[#1f1f25] bg-bg/85 backdrop-blur">
      <div className="mx-auto flex h-[68px] max-w-[1180px] items-center gap-6 px-6">
        <a href="#" className="flex items-center gap-2.5">
          <Logo />
          <span className="font-display text-[21px] font-medium text-ink">Musiclify</span>
        </a>
        <nav aria-label="Main" className="flex flex-1 flex-wrap gap-1 text-[15px] font-medium text-muted">
          <a href="#features" className="hidden rounded-lg px-3 py-2.5 hover:text-ink sm:block">Features</a>
          <a href="#privacy" className="hidden rounded-lg px-3 py-2.5 hover:text-ink sm:block">Privacy</a>
          <a href="#faq" className="hidden rounded-lg px-3 py-2.5 hover:text-ink sm:block">FAQ</a>
        </nav>
        <a href="#download" className="inline-flex h-11 items-center rounded-full bg-accent px-5 font-medium text-accent-ink transition hover:brightness-110">
          Download free
        </a>
      </div>
    </header>
  );
}

// ---------------------------------------------------------------- hero

function Hero() {
  return (
    <section className="mx-auto flex max-w-[1180px] flex-col gap-10 px-6 pt-20 sm:pt-24 lg:flex-row lg:items-center lg:gap-14">
      {/* text — left */}
      <div className="flex flex-1 flex-col gap-6">
        <span className="text-sm font-medium text-muted">Free · Open source · No account</span>
        <h1 className="max-w-[540px] font-display text-5xl font-medium leading-[1.08] tracking-[-0.03em] sm:text-6xl lg:text-7xl">
          Every song you love. On your phone and PC.
        </h1>
        <p className="max-w-[480px] text-lg leading-relaxed text-muted">
          Search any song, sing along with synced lyrics, and keep an encrypted offline library on your own devices.
          No ads, no tracking, nothing on someone else&apos;s server.
        </p>
        <DownloadButtons />
      </div>

      {/* product view — right */}
      <div className="relative w-full flex-1 lg:max-w-[600px]">
        <div className="overflow-hidden rounded-2xl border border-line bg-bg-2 shadow-[0_40px_100px_rgba(0,0,0,.6)]">
          <Image src="/screens/desktop-nowplaying.png" alt="Musiclify on Windows: the Now playing view with synced lyrics and Up next"
            width={1920} height={1009} priority className="h-auto w-full" />
        </div>
        <span className="mt-3 block font-mono text-xs text-muted">1:37 / 3:18</span>
      </div>
    </section>
  );
}

// ---------------------------------------------------------------- feature demos — small real-looking product pieces

function MiniPlaylist() {
  const songs = [
    { title: "Your mix", sub: "30 songs from 6 artists", grad: "from-[#5b6b7a] to-[#3d4a56]", active: true },
    { title: "More from Arijit Singh", sub: "Tum Hi Ho, Kesariya…", grad: "from-[#7a5b4a] to-[#5a3d2e]" },
    { title: "Artists you might like", sub: "Prateek Kuhad, Anuv Jain", grad: "from-[#4a5f7a] to-[#2e3f56]" },
  ];
  return (
    <div className="flex min-h-[300px] flex-col justify-center gap-2 rounded-2xl border border-line bg-bg-2 p-5" aria-hidden>
      <span className="mb-1 px-1 text-xs text-muted">Library</span>
      {songs.map((s) => (
        <div key={s.title} className={`flex items-center gap-3 rounded-xl px-3 py-2.5 ${s.active ? "bg-surface-2" : ""}`}>
          <span className={`size-10 shrink-0 rounded-full bg-gradient-to-br ${s.grad}`} />
          <span className="min-w-0 flex-1 text-left">
            <span className="block truncate text-sm font-medium">{s.title}</span>
            <span className="block truncate text-xs text-muted">{s.sub}</span>
          </span>
          {s.active && <span className="font-mono text-xs text-muted">1:37 / 3:18</span>}
        </div>
      ))}
    </div>
  );
}

function MiniLyrics() {
  const lines = [
    { text: "And wishin' and wishin'", time: "1:34", active: false },
    { text: "They wishin' on me", time: "1:37", active: true },
    { text: "I been movin' calm", time: "1:40", active: false },
    { text: "Don't start no trouble with me", time: "1:43", active: false },
  ];
  return (
    <div className="flex min-h-[300px] flex-col justify-center gap-1 rounded-2xl border border-line bg-[#12141a] p-6" aria-hidden>
      {lines.map((l) => (
        <div key={l.time} className="flex items-baseline gap-3">
          <span className="w-10 shrink-0 text-right font-mono text-xs text-muted">{l.time}</span>
          <span className={`font-display text-lg font-medium leading-relaxed ${l.active ? "text-accent" : "text-[#4a4e58]"}`}>{l.text}</span>
        </div>
      ))}
    </div>
  );
}

function MiniOfflineLibrary() {
  const songs = [
    { title: "God's Plan", artist: "Drake", status: "saved" },
    { title: "the cure", artist: "Olivia Rodrigo", status: "saved" },
    { title: "One Dance", artist: "Drake", status: "saving" },
  ];
  return (
    <div className="flex min-h-[300px] flex-col justify-center gap-2 rounded-2xl border border-line bg-bg-2 p-5" aria-hidden>
      <span className="mb-1 px-1 text-xs text-muted">Offline library</span>
      {songs.map((s) => (
        <div key={s.title} className={`flex items-center gap-3 rounded-xl px-3 py-2.5 ${s.status === "saving" ? "bg-surface-2" : ""}`}>
          <Lock size={18} strokeWidth={1.5} className="shrink-0 text-muted" />
          <span className="min-w-0 flex-1 text-left">
            <span className="block truncate text-sm font-medium">{s.title}</span>
            <span className="block truncate text-xs text-muted">{s.artist}</span>
          </span>
          <span className="text-xs text-muted">{s.status === "saved" ? "✓ saved" : "saving 40%"}</span>
        </div>
      ))}
    </div>
  );
}

function MiniHandoff() {
  return (
    <div className="flex min-h-[300px] items-center justify-center gap-6 rounded-2xl border border-line bg-bg-2 p-6 sm:gap-10" aria-hidden>
      {/* phone frame */}
      <div className="flex flex-col items-center gap-2">
        <div className="flex w-[120px] flex-col items-center gap-1 rounded-xl border border-line bg-[#12141a] px-3 py-4">
          <Smartphone size={18} strokeWidth={1.5} className="text-muted" />
          <span className="text-xs font-medium">God&apos;s Plan</span>
          <span className="text-[10px] text-muted">Drake</span>
          <span className="mt-1 font-mono text-xs text-accent">1:37</span>
        </div>
        <span className="text-[10px] text-muted">phone</span>
      </div>

      {/* sync indicator */}
      <div className="flex flex-col items-center gap-1">
        <span className="text-xs text-muted">⟵ ⟶</span>
        <span className="font-mono text-xs text-accent">1:37 / 3:18</span>
      </div>

      {/* desktop frame */}
      <div className="flex flex-col items-center gap-2">
        <div className="flex w-[140px] flex-col items-center gap-1 rounded-xl border border-line bg-[#12141a] px-3 py-4">
          <div className="mb-1 h-[2px] w-10 rounded-full bg-line" />
          <span className="text-xs font-medium">God&apos;s Plan</span>
          <span className="text-[10px] text-muted">Drake</span>
          <span className="mt-1 font-mono text-xs text-accent">1:37</span>
        </div>
        <span className="text-[10px] text-muted">desktop</span>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- feature rows

function FeatureRow({ kicker, title, text, demo, flip }: {
  kicker: string; title: string; text: string; demo: ReactNode; flip?: boolean;
}) {
  return (
    <div className={`flex flex-col items-center gap-10 lg:gap-14 ${flip ? "lg:flex-row-reverse" : "lg:flex-row"}`}>
      <div className="flex flex-1 flex-col gap-4">
        <span className="text-sm font-medium text-muted">{kicker}</span>
        <h2 className="font-display text-4xl font-medium leading-[1.1] tracking-tight">{title}</h2>
        <p className="text-base leading-relaxed text-muted">{text}</p>
      </div>
      <div className="w-full flex-1">{demo}</div>
    </div>
  );
}

function Features() {
  return (
    <section id="features" className="scroll-mt-16 mx-auto flex max-w-[1180px] flex-col gap-24 px-6 py-24">
      <FeatureRow kicker="Personal from day one" title="A home built from artists you love"
        text="Pick a few favourites and Musiclify mixes their best songs, finds similar artists, and remembers what you play."
        demo={<MiniPlaylist />} />
      <FeatureRow flip kicker="Sing along" title="Lyrics that follow the music"
        text="Synced lyrics scroll line by line as the song plays. Click any line to jump straight to it, on your phone or full-screen on your PC."
        demo={<MiniLyrics />} />
      <FeatureRow kicker="Offline, encrypted" title="Play once, keep it forever"
        text="Every song you play is saved encrypted on your device and plays without internet. Copy the file anywhere else and it simply won't play."
        demo={<MiniOfflineLibrary />} />
      <FeatureRow flip kicker="Phone and PC" title="Scan once, your whole library follows"
        text="Open Link a device on your PC and scan the code with your phone. Favourite artists, likes, playlists, history and lyrics move across in seconds, without any cloud."
        demo={<MiniHandoff />} />
    </section>
  );
}

// ---------------------------------------------------------------- privacy

function Privacy() {
  return (
    <section id="privacy" className="scroll-mt-16 border-y border-[#1f1f25] bg-bg-2">
      <div className="mx-auto flex max-w-[1180px] flex-col gap-12 px-6 py-24 lg:flex-row">
        <div className="flex flex-1 flex-col gap-4">
          <span className="text-sm font-medium text-muted">Privacy</span>
          <h2 className="font-display text-4xl font-medium leading-[1.1] tracking-tight">No account. No server holding your music life.</h2>
          <p className="text-base leading-relaxed text-muted">
            Your library lives on your devices. Linking phone and PC goes straight across your Wi-Fi, sealed with a one-time key.
          </p>
        </div>
        <div className="grid flex-1 gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-3 rounded-2xl border border-line bg-surface p-6">
            <span className="flex items-center gap-2 font-medium"><Wifi size={18} strokeWidth={1.5} className="text-muted" />Goes online</span>
            <ul className="space-y-2 text-sm text-muted">
              <li>Song searches, to Deezer</li><li>Lyrics lookups, to LRCLIB</li><li>Audio, from YouTube</li>
              <li>Song-to-video IDs, so songs start faster for everyone (optional)</li>
            </ul>
          </div>
          <div className="flex flex-col gap-3 rounded-2xl border border-line bg-surface p-6">
            <span className="flex items-center gap-2 font-medium"><EyeOff size={18} strokeWidth={1.5} className="text-muted" />Never leaves your devices</span>
            <ul className="space-y-2 text-sm">
              {["Your name", "Likes and playlists", "Listening history", "Downloaded songs", "YouTube cookies"].map((t) => (
                <li key={t} className="flex items-center gap-2"><X size={18} strokeWidth={1.5} className="text-muted" />{t}</li>
              ))}
            </ul>
          </div>
        </div>
      </div>
    </section>
  );
}

// ---------------------------------------------------------------- FAQ & footer

const FAQ = [
  ["Is it free?", "Yes. Free and open source under GPL-3.0, with no ads, subscriptions or accounts."],
  ["Where does the music come from?", "Song info from Deezer, lyrics from LRCLIB, and audio fetched from YouTube on your own device. Musiclify hosts no music."],
  ["Is it legal to use?", "Musiclify is meant for personal listening. YouTube's terms restrict downloading and music is copyrighted, so use it responsibly and support the artists you love."],
  ["Why does a new song take a few seconds?", "It's found and saved the first time you play it. After that it starts instantly, even offline, and your next song is prepared in the background."],
  ["What moves when I link my phone and PC?", "Your name, favourite artists, likes, playlists, history, cached lyrics and which songs you had offline (the other device can download them). YouTube cookies never move."],
  ["iPhone or Mac?", "Not yet. Windows and Android today."],
];

function Faq() {
  return (
    <section id="faq" className="scroll-mt-16 mx-auto flex max-w-[820px] flex-col gap-3 px-6 pb-24">
      <h2 className="mb-3 font-display text-3xl font-medium">Questions</h2>
      {FAQ.map(([q, a]) => (
        <details key={q} className="group rounded-2xl border border-line bg-bg-2 px-6 py-5 [&_summary::-webkit-details-marker]:hidden">
          <summary className="flex cursor-pointer list-none items-center justify-between gap-4 text-base font-medium">
            {q}
            <span className="grid size-7 shrink-0 place-items-center rounded-full bg-surface-2 text-muted transition group-open:rotate-45">+</span>
          </summary>
          <p className="mt-3 text-sm leading-relaxed text-muted">{a}</p>
        </details>
      ))}
    </section>
  );
}

function Footer() {
  return (
    <footer className="border-t border-[#1f1f25]">
      <div className="mx-auto flex max-w-[1180px] flex-wrap items-center justify-between gap-4 px-6 py-8 text-sm text-muted">
        <span className="flex items-center gap-2.5"><Logo size={24} /><span className="font-medium text-ink">Musiclify</span> · Open source (GPL-3.0)</span>
        <span>Song info from Deezer · Lyrics from LRCLIB · Not affiliated with YouTube or Deezer</span>
      </div>
    </footer>
  );
}
