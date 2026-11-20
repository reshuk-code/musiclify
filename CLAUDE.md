# Musiclify: working rules

## Release rule (ask before every version bump)

After a major update (a new feature, a visible UI/behaviour change, or a fix users will notice) is done and builds:

1. **Ask the owner first**: "Version update garne?" List the new changes in short bullets and the proposed new versions.
2. **Only after the owner accepts**:
   - Bump the versions (all of them, kept in sync per app):
     - Desktop: `apps/desktop/package.json`, `apps/desktop/src-tauri/tauri.conf.json`, `apps/desktop/src-tauri/Cargo.toml`
     - Android: `versionCode` (+1) and `versionName` in `apps/android/app/build.gradle.kts`
   - Rebuild the installers. Desktop: push a `desktop-vX.Y.Z` tag (CI builds Windows/macOS/Linux into a draft release).
     Android: `./gradlew assembleRelease`, then **always** sign with `scripts/sign-release.sh <in> <out>` (key-rotation
     lineage) so the APK installs over old debug-signed and release-signed builds without losing data.
     Never publish an APK signed only by Gradle. Keys live in `C:/Users/Reshuk/.musiclify-keys/` (back it up).
   - Publish the builds and update the website download info (`apps/website/src/app/downloads.ts`: version, size, link).
   - Commit, push to GitHub, and deploy the website (Vercel).
3. Never bump versions, publish builds, push or deploy without that yes. Small internal changes (refactors, typos) don't need a version bump.

Versioning: SemVer-style. Patch (0.1.0 → 0.1.1) for fixes and small improvements, minor (0.1 → 0.2) for new features.
