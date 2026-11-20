# Contributing to Musiclify

Thanks for helping out. This guide covers how the repo is laid out, how to set it up, and what we expect from a pull request.

## Ground rules

- Be kind. Everyone here follows the [Code of Conduct](CODE_OF_CONDUCT.md).
- Musiclify is **local-first and private**: no accounts, no analytics, no tracking. Changes that send user data anywhere will not be merged.
- Never commit secrets, cookies, API keys, `.env` files, or downloaded audio.
- Security problems go through the [security policy](SECURITY.md), not public issues.

## Repository layout

```
apps/android/        Android app (Kotlin, Jetpack Compose, Media3, Room)
apps/desktop/        Windows app (Tauri 2: Rust backend in src-tauri/, React + TypeScript UI in src/)
apps/website/        Marketing / download site (Next.js, Tailwind CSS), deployed on Vercel
services/matchcache/ Optional shared song-match cache (Neon serverless function)
shared/              Assets used by more than one app (fonts, icon, curated artist list)
tools/               Dev tools: icon generator, Lucide codegen, Python CLI prototype
```

The Android and desktop apps implement the same features. **A user-facing change to one app should normally land in the other too**, in the same PR or a linked follow-up, so the two stay in sync (sync protocol, home feed, player behaviour, etc.).

## Setup

See [Building](README.md#building) in the README for full instructions. In short:

| App | Needs | Run |
|---|---|---|
| Android | JDK 17, Android SDK 36 | `cd apps/android && ./gradlew installDebug` |
| Desktop | Node 20+, Rust stable, WebView2, `yt-dlp.exe` sidecar | `cd apps/desktop && npm install && npm run tauri dev` |
| Website | Node 20+ | `cd apps/website && npm install && npm run dev` |

## Workflow

1. **Open or find an issue first** for anything bigger than a small fix, so we can agree on the approach.
2. **Fork and branch** from `main`. Name branches `type/short-description`, e.g. `feat/mix-page`, `fix/sync-crash`, `docs/readme-build`.
3. **Keep PRs focused**: one feature or fix per PR. Unrelated refactors go in their own PR.
4. **Make sure CI passes** (see below) and fill in the PR template.
5. A maintainer reviews, may ask for changes, and squash-merges.

### Commit messages

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <summary in present tense>

feat(android): open Your mix as a song list
fix(desktop): keep the mix stable across page changes
docs: explain the yt-dlp sidecar
```

Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`.
Scopes: `android`, `desktop`, `website`, `matchcache`, `shared`, `tools`, or leave it out for repo-wide changes.

## Code style

General:
- Match the style of the file you are editing. `.editorconfig` sets indentation and line endings; enable EditorConfig in your editor.
- Name things for what they do. Comment the **why**, not the what.
- No dead code, commented-out blocks, or stray debug logging in a PR.
- Handle failure paths: network calls can fail and the apps must stay usable offline.

Per platform:

| Area | Conventions | Check before pushing |
|---|---|---|
| Android (Kotlin) | [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html). UI state lives in `AppViewModel`; composables stay stateless where practical. Room schema changes need a migration and an exported schema in `app/schemas/`. | `./gradlew assembleDebug testDebugUnitTest` |
| Desktop UI (TypeScript/React) | Strict TypeScript, function components and hooks, no `any` without a reason. | `npm test && npm run build` |
| Desktop backend (Rust) | `rustfmt` defaults, no new `unwrap()` on user/network data. | `cargo test` in `src-tauri/` |
| Website (Next.js) | ESLint config in the repo. | `npm run lint && npm run build` |

## Continuous integration

Every push and PR to `main` runs `.github/workflows/ci.yml`: Android build and unit tests, desktop UI tests and build, desktop Rust tests, and website lint + build. A PR can't merge while CI is red.

**Tests:** new logic should come with a unit test. Pure helpers (parsing, matching, version checks) and the sync format are the priority: a sync bug can lose someone's library. Android tests live in `app/src/test/`, desktop UI tests in `src/__tests__/`, and Rust tests in a `#[cfg(test)] mod tests` at the bottom of the file they test.

## Releases

Maintainers cut releases. Versions follow SemVer-style numbering per app (desktop `0.1.x`, Android `0.x`), are bumped in all version files for that app, and the installers are attached to a GitHub Release (`desktop-vX.Y.Z`, `android-vX.Y`). The website links to those release files.

## License

By contributing you agree that your contributions are licensed under the [GPL-3.0-or-later](LICENSE) license of this project.
