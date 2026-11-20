import { useEffect, useState } from "react";
import { getVersion } from "@tauri-apps/api/app";
import { invoke } from "@tauri-apps/api/core";

/** A newer desktop release on GitHub. */
export type AppUpdate = { version: string; url: string };

const RELEASES_API = "https://api.github.com/repos/reshuk-code/musiclify/releases?per_page=30";
const TAG_PREFIX = "desktop-v";
const DAY = 24 * 60 * 60 * 1000;
const KEY = { enabled: "updates.enabled", lastCheck: "updates.lastCheck", dismissed: "updates.dismissed" };

/** Compares dotted versions numerically ("0.10.0" > "0.9.3"). */
export function compareVersions(a: string, b: string): number {
  const parts = (v: string) => v.split("-")[0].split(".").map((n) => Number(n) || 0);
  const pa = parts(a), pb = parts(b);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d) return d;
  }
  return 0;
}

export const updatesEnabled = () => localStorage.getItem(KEY.enabled) !== "off";
export const setUpdatesEnabled = (on: boolean) => localStorage.setItem(KEY.enabled, on ? "on" : "off");

/**
 * Looks for a newer release tagged `desktop-vX.Y.Z` on GitHub Releases.
 * Only the public release list is read; nothing about the user or PC is sent.
 */
export async function checkForUpdate(current: string): Promise<AppUpdate | null> {
  const releases = await invoke<any[]>("fetch_json", { url: RELEASES_API });
  for (const r of releases ?? []) {
    if (r.draft || r.prerelease || !String(r.tag_name).startsWith(TAG_PREFIX)) continue;
    const version = String(r.tag_name).slice(TAG_PREFIX.length);
    if (compareVersions(version, current) <= 0) return null;
    const exe = (r.assets ?? []).find((a: any) => String(a.name).endsWith(".exe"));
    return { version, url: exe?.browser_download_url ?? r.html_url };
  }
  return null;
}

/** The update to offer in a banner (checked at most once a day), plus a way to dismiss it. */
export function useUpdate() {
  const [update, setUpdate] = useState<AppUpdate | null>(null);
  useEffect(() => {
    if (!updatesEnabled()) return;
    const last = Number(localStorage.getItem(KEY.lastCheck) ?? 0);
    if (Date.now() - last < DAY) return;
    let alive = true;
    (async () => {
      try {
        const found = await checkForUpdate(await getVersion());
        localStorage.setItem(KEY.lastCheck, String(Date.now()));
        if (alive && found && found.version !== localStorage.getItem(KEY.dismissed)) setUpdate(found);
      } catch {
        // Offline or rate-limited: try again next launch.
      }
    })();
    return () => { alive = false; };
  }, []);
  const dismiss = () => {
    if (update) localStorage.setItem(KEY.dismissed, update.version);
    setUpdate(null);
  };
  return { update, dismiss };
}
