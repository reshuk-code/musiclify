# Packaging

Store and package-manager metadata for Musiclify.

## winget (Windows)

`winget/manifests/` holds the manifest for each desktop release, laid out like
[microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs). To publish a release:

1. Copy the new version folder and update `PackageVersion`, `InstallerUrl`, `InstallerSha256`
   (`sha256sum` of the installer) and `ReleaseDate`.
2. Check it locally: `winget validate --manifest <folder>` and `winget install --manifest <folder>`.
3. Open a pull request to microsoft/winget-pkgs with the folder
   (or use [wingetcreate](https://github.com/microsoft/winget-create): `wingetcreate update ReshukCode.Musiclify ...`).

## F-Droid (Android)

App metadata (title, descriptions, screenshots, changelogs per `versionCode`) lives in
`fastlane/metadata/android/` at the repo root, where F-Droid reads it.

To get listed, open a "Request for Packaging" at
[gitlab.com/fdroid/rfp](https://gitlab.com/fdroid/rfp/-/issues). Notes for the request:

- The app uses only free libraries (QR scanning is ZXing; there is no Google Play Services).
- Expected anti-feature: **NonFreeNet** (song info from Deezer, audio from YouTube).
- Release tags are named `android-vX.Y`.
