#!/usr/bin/env bash
# Signs a release APK with Musiclify's key-rotation lineage, so it installs over BOTH:
#   - builds signed with the original debug key (0.4 and earlier), and
#   - builds signed with the release key (0.5 and later),
# keeping the user's library (Android 9+; APK Signature Scheme v3 with rotation).
#
# Usage: scripts/sign-release.sh <input.apk> <output.apk>
#
# Reads key paths and passwords from apps/android/keystore.properties (gitignored):
#   storeFile, storePassword, keyAlias, keyPassword   the release key (newest signer)
#   legacyStoreFile, legacyStorePassword, legacyKeyAlias, legacyKeyPassword   the original key
#   lineageFile                                        made once with `apksigner rotate`
set -euo pipefail

in="${1:?input apk}"; out="${2:?output apk}"
here="$(cd "$(dirname "$0")/.." && pwd)"
props="$here/keystore.properties"
[ -f "$props" ] || { echo "Missing $props" >&2; exit 1; }
prop() { grep -E "^$1=" "$props" | head -1 | cut -d= -f2-; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
bt="$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)"
apksigner="$bt/apksigner"; [ -x "$apksigner" ] || apksigner="$bt/apksigner.bat"

"$apksigner" sign \
  --ks "$(prop legacyStoreFile)" --ks-key-alias "$(prop legacyKeyAlias)" \
  --ks-pass "pass:$(prop legacyStorePassword)" --key-pass "pass:$(prop legacyKeyPassword)" \
  --next-signer \
  --ks "$(prop storeFile)" --ks-key-alias "$(prop keyAlias)" \
  --ks-pass "pass:$(prop storePassword)" --key-pass "pass:$(prop keyPassword)" \
  --lineage "$(prop lineageFile)" \
  --rotation-min-sdk-version 28 \
  --out "$out" "$in"

"$apksigner" verify --print-certs "$out" | grep -E "certificate DN" | head -2
echo "Signed: $out"
