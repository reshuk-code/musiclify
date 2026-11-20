/**
 * Release files, hosted as GitHub Release assets (installers are not committed to the repo).
 * Bump these when publishing a new build: see the release rule in CLAUDE.md / CONTRIBUTING.md.
 */
const RELEASES = "https://github.com/reshuk-code/musiclify/releases/download";

export const WINDOWS = {
  href: `${RELEASES}/desktop-v0.1.1/Musiclify-Setup-0.1.1.exe`,
  version: "0.1.1",
  size: "26.1 MB",
  requires: "Windows 10 or 11 (64-bit)",
};

export const ANDROID = {
  href: `${RELEASES}/android-v0.4/Musiclify-0.4.apk`,
  version: "0.4",
  size: "24.1 MB",
  requires: "Android 8.0 or newer",
};
