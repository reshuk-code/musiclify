"use client";

import { useSyncExternalStore } from "react";
import { Download, Monitor, RefreshCw, Smartphone } from "lucide-react";
import { ANDROID, OTHER_DESKTOP, WINDOWS } from "./downloads";

type Platform = "windows" | "android" | "other";

const noSubscribe = () => () => {};
const detectPlatform = (): Platform => {
  const ua = navigator.userAgent;
  return /Android/i.test(ua) ? "android" : /Windows/i.test(ua) ? "windows" : "other";
};
const usePlatform = () => useSyncExternalStore(noSubscribe, detectPlatform, () => "other" as Platform);

const base = "inline-flex h-12 items-center gap-2.5 rounded-full px-6 text-base font-medium transition";
const primary = `${base} bg-accent text-accent-ink hover:brightness-110`;
const secondary = `${base} border border-line bg-bg-2 text-ink hover:border-muted`;

/** Hero buttons: the visitor's platform first; both stay available. */
export function DownloadButtons() {
  const platform = usePlatform();
  const win = (
    <a key="win" href={WINDOWS.href} download className={platform === "android" ? secondary : primary}>
      <Monitor size={18} strokeWidth={1.5} />Download for Windows
    </a>
  );
  const android = (
    <a key="and" href={ANDROID.href} download className={platform === "android" ? primary : secondary}>
      <Smartphone size={18} strokeWidth={1.5} />Get it for Android
    </a>
  );
  return <div className="flex flex-wrap gap-3">{platform === "android" ? [android, win] : [win, android]}</div>;
}

/** Download section: highlights the card for the visitor's device. */
export function DownloadCards() {
  const platform = usePlatform();
  const lead =
    platform === "windows" ? "Looks like you're on Windows. Android is one tap away too."
      : platform === "android" ? "Looks like you're on Android. There's a Windows app too."
        : "Free for Windows and Android. Install both and link them with a QR code.";
  const card = (kind: "windows" | "android") => {
    const d = kind === "windows" ? WINDOWS : ANDROID;
    const mine = platform === kind;
    return (
      <div className={`flex flex-col gap-4 rounded-2xl p-7 ${mine ? "bg-surface-2" : "border border-line bg-surface"}`}>
        <span className="flex items-center gap-2.5 font-display text-2xl font-medium">
          {kind === "windows" ? <Monitor size={18} strokeWidth={1.5} className="text-muted" /> : <Smartphone size={18} strokeWidth={1.5} className="text-muted" />}
          {kind === "windows" ? "Windows" : "Android"}
        </span>
        <span className="text-sm text-muted">v{d.version} · {d.size} · {d.requires}</span>
        <ul className="space-y-1.5 text-sm text-muted">
          {(kind === "windows"
            ? ["If SmartScreen appears: More info → Run anyway (not code-signed yet)", "Allow it on private networks when you first link your phone"]
            : ["Open the file and allow installing from your browser", "Not on Google Play: install it straight from here"]
          ).map((t) => <li key={t}>• {t}</li>)}
        </ul>
        <a href={d.href} download
          className={`mt-auto inline-flex h-12 items-center justify-center gap-2 rounded-full font-medium transition ${mine ? "bg-accent text-accent-ink hover:brightness-110" : "border border-line hover:border-muted"}`}>
          <Download size={18} strokeWidth={1.5} />{kind === "windows" ? "Download installer" : "Download APK"}
        </a>
        {kind === "android" && <AndroidUpdate />}
      </div>
    );
  };
  return (
    <>
      <p className="text-lg text-muted">{lead}</p>
      <div className="mt-10 grid w-full gap-5 text-left md:grid-cols-2">{card("windows")}{card("android")}</div>
      <p className="mt-6 text-sm text-muted">
        Also for{" "}
        {OTHER_DESKTOP.map((d, i) => (
          <span key={d.href}>
            {i > 0 && " · "}
            <a href={d.href} className="underline decoration-line underline-offset-4 hover:text-ink">{d.label}</a>
          </span>
        ))}
        {" "}· <a href="https://github.com/reshuk-code/musiclify/releases" className="underline decoration-line underline-offset-4 hover:text-ink">all releases</a>
      </p>
    </>
  );
}

/** For people who already have an older Musiclify: install over it, nothing to uninstall. */
function AndroidUpdate() {
  return (
    <div className="rounded-xl border border-line bg-bg-2 p-4 text-sm">
      <p className="font-medium text-ink">Already have Musiclify?</p>
      <p className="mt-1 text-muted">
        Install {ANDROID.version} over it, from any earlier version. Your liked songs, playlists and downloads stay.
        Needs Android 9 or newer; on Android 8, sync with your PC and reinstall.
      </p>
      <a href={ANDROID.href} download
        className="mt-3 inline-flex h-10 items-center gap-2 rounded-full bg-accent px-5 font-medium text-accent-ink transition hover:brightness-110">
        <RefreshCw size={16} strokeWidth={1.75} />Update to {ANDROID.version}
      </a>
    </div>
  );
}
