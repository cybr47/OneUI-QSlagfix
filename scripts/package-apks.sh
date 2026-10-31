#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk_root:?Set ANDROID_HOME or ANDROID_SDK_ROOT}"
build_tools="$sdk_root/build-tools/36.0.0"
mkdir -p artifacts
cp app/build/outputs/apk/debug/app-debug.apk artifacts/app-debug.apk
release_input=app/build/outputs/apk/release/app-release-unsigned.apk

key_file=""
trap 'if [[ -n "$key_file" ]]; then unlink "$key_file"; fi' EXIT
if [[ -n "${KEYSTORE_BASE64:-}" ]]; then
  : "${KEYSTORE_PASSWORD:?Missing KEYSTORE_PASSWORD}"
  key_file="$(mktemp)"
  chmod 600 "$key_file"
  printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$key_file"
  export KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
  alias_args=()
  if [[ -n "${KEYSTORE_ALIAS:-}" ]]; then alias_args+=(--ks-key-alias "$KEYSTORE_ALIAS"); fi
  "$build_tools/apksigner" sign --ks "$key_file" "${alias_args[@]}" \
    --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
    --out artifacts/app-release.apk "$release_input"
  signing="repository release key"
else
  # Installable optimized test build; a generated debug certificate is not a release identity.
  "$build_tools/apksigner" sign --ks "$HOME/.android/debug.keystore" \
    --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --out artifacts/app-release.apk "$release_input"
  signing="TEST certificate (configure repository signing secrets for stable user releases)"
fi
for apk in artifacts/app-debug.apk artifacts/app-release.apk; do
  "$build_tools/apksigner" verify --verbose --print-certs "$apk" > "$apk.certificate.txt"
done
python3 scripts/verify-apks.py artifacts/app-debug.apk artifacts/app-release.apk
{
  printf 'Source: %s\n' "${GITHUB_SHA:-$(git rev-parse HEAD)}"
  printf 'Branch: %s\n' "${GITHUB_REF_NAME:-$(git branch --show-current)}"
  printf 'Release signing: %s\n' "$signing"
  printf 'Release: R8 optimized, resource shrunk, debuggable=false, diagnostics removed\n'
} > artifacts/BUILD.txt
(cd artifacts && sha256sum app-debug.apk app-release.apk > SHA256SUMS.txt)
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then cat artifacts/BUILD.txt >> "$GITHUB_STEP_SUMMARY"; fi
