#!/usr/bin/env bash
# Validate public apksigner output only. Never read or print a private key.
set -euo pipefail
report=${1:?Usage: verify-signing-report.sh report.txt}
expected=650a17f2bbc6d3cf7ac436e3ce7d4cbc1381cfd29052d6a8e06e70361ef48e8e
mapfile -t fingerprints < <(sed -nE 's/^(V[0-9.]+ )?Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)$/\2/p; s/^V[0-9.]+ Signer: certificate SHA-256 digest: ([0-9a-f]+)$/\1/p' "$report")
if [ "${#fingerprints[@]}" -eq 0 ]; then
  echo '::error::No recognized signing certificate in verification report' >&2
  exit 1
fi
for fingerprint in "${fingerprints[@]}"; do
  if [ "$fingerprint" != "$expected" ]; then
    echo '::error::Signing certificate changed; an explicitly reviewed migration is required' >&2
    exit 1
  fi
done
echo 'Existing FocusFlow signing certificate confirmed (compatibility check, not a private-key security audit).'
