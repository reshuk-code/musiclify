/**
 * Release files, hosted as GitHub Release assets (installers are not committed to the repo).
 * Bump these when publishing a new build: see the release rule in CLAUDE.md / CONTRIBUTING.md.
 */
const RELEASES = "https://github.com/reshuk-code/musiclify/releases/download";
const DESKTOP = "0.2.0";
const ANDROID_VERSION = "0.5";

export const WINDOWS = {
  href: `${RELEASES}/desktop-v${DESKTOP}/Musiclify_${DESKTOP}_x64-setup.exe`,
  version: DESKTOP,
  size: "26 MB",
  requires: "Windows 10 or 11 (64-bit)",
};

export const ANDROID = {
  href: `${RELEASES}/android-v${ANDROID_VERSION}/Musiclify-${ANDROID_VERSION}.apk`,
  version: ANDROID_VERSION,
  size: "4.3 MB",
  requires: "Android 8.0 or newer",
};

/** Other desktop builds, listed under the cards. */
export const OTHER_DESKTOP = [
  { label: "macOS (.dmg)", href: `${RELEASES}/desktop-v${DESKTOP}/Musiclify_${DESKTOP}_universal.dmg` },
  { label: "Linux (.AppImage)", href: `${RELEASES}/desktop-v${DESKTOP}/Musiclify_${DESKTOP}_amd64.AppImage` },
  { label: "Linux (.deb)", href: `${RELEASES}/desktop-v${DESKTOP}/Musiclify_${DESKTOP}_amd64.deb` },
];
