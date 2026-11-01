#!/usr/bin/env bash
set -euo pipefail

: "${KEYSTORE_BASE64:?Missing repository secret KEYSTORE_BASE64}"
: "${KEYSTORE_PASSWORD:?Missing repository secret KEYSTORE_PASSWORD}"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk_root:?Set ANDROID_HOME or ANDROID_SDK_ROOT}"
build_tools="$sdk_root/build-tools/36.0.0"

key_file="$(mktemp)"
trap 'unlink "$key_file"' EXIT
chmod 600 "$key_file"
printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$key_file"
export KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
alias_args=()
if [[ -n "${KEYSTORE_ALIAS:-}" ]]; then alias_args+=(--ks-key-alias "$KEYSTORE_ALIAS"); fi

mkdir -p artifacts
for variant in debug release; do
  if [[ "$variant" == debug ]]; then
    input=app/build/outputs/apk/debug/app-debug.apk
  else
    input=app/build/outputs/apk/release/app-release-unsigned.apk
  fi
  # apksigner removes the debug APK's existing test signature before signing.
  "$build_tools/apksigner" sign --ks "$key_file" "${alias_args[@]}" \
    --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
    --v4-signing-enabled false --out "artifacts/app-$variant.apk" "$input"
  "$build_tools/apksigner" verify --verbose --print-certs \
    "artifacts/app-$variant.apk" > "artifacts/app-$variant.apk.certificate.txt"
done

python3 scripts/verify-apks.py artifacts/app-debug.apk artifacts/app-release.apk
python3 - <<'PY'
from pathlib import Path
import re
certificates = []
for variant in ("debug", "release"):
    report = Path(f"artifacts/app-{variant}.apk.certificate.txt").read_text()
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", report, re.M)
    assert len(fingerprints) == 1, f"{variant}: expected exactly one signing certificate"
    certificates.append(fingerprints[0].lower())
assert certificates[0] == certificates[1], "Debug/release signing certificates differ"
Path("artifacts/SIGNING.txt").write_text(
    "Signing source: repository keystore secrets\n"
    f"Debug/release certificate SHA-256: {certificates[0]}\n")
print("PASS: both APKs use the same repository signing certificate")
PY
{
  printf 'Source: %s\n' "${GITHUB_SHA:-$(git rev-parse HEAD)}"
  printf 'Branch: %s\n' "${GITHUB_REF_NAME:-$(git branch --show-current)}"
  printf 'Signing: repository keystore secrets (debug and release)\n'
  printf 'Release: not minified (R8 off, see app/build.gradle.kts), debuggable=false, verbose diagnostics inactive\n'
} > artifacts/BUILD.txt
(cd artifacts && sha256sum app-debug.apk app-release.apk > SHA256SUMS.txt)
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  cat artifacts/BUILD.txt artifacts/SIGNING.txt >> "$GITHUB_STEP_SUMMARY"
fi
