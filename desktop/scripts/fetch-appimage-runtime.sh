#!/usr/bin/env bash
# The AppImage type 2 runtime that heads the Linux GUI AppImage: a released static
# build, accepted only with a valid signature from the pinned release key and the
# pinned SHA-256.
set -Eeuo pipefail

readonly runtime_release=20251108
# The key that signs the runtime releases, kept as desktop/appimage/type2-runtime-signing-key.asc.
readonly signing_key=570C77ACEA40C0F1B758902CBF96CCA56490F695
if [[ $# != 2 ]]; then
    printf 'Usage: %s <x86_64|aarch64> <output file>\n' "$0" >&2
    exit 2
fi
architecture=$1
case "$architecture" in
    x86_64) runtime_sha256=2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d ;;
    aarch64) runtime_sha256=00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444 ;;
    *) echo 'Unsupported Linux desktop architecture' >&2; exit 2 ;;
esac
for tool in curl gpg gpgv sha256sum; do
    command -v "$tool" >/dev/null || { printf 'Required tool missing: %s\n' "$tool" >&2; exit 1; }
done
output=$2
repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
temporary=$(mktemp -d "${TMPDIR:-/tmp}/nekokem-appimage-runtime.XXXXXX")
trap 'rm -rf -- "$temporary"' EXIT
export GNUPGHOME="$temporary/gnupg"
mkdir -m 0700 "$GNUPGHOME"

key="$repository/desktop/appimage/type2-runtime-signing-key.asc"
primary=$(gpg --batch --quiet --show-keys --with-colons "$key" | awk -F: '$1 == "fpr" { print $10; exit }')
[[ "$primary" == "$signing_key" ]] || { echo 'The runtime signing key is not the pinned key' >&2; exit 1; }
gpg --batch --quiet --dearmor --output "$temporary/keyring.gpg" "$key"

url="https://github.com/AppImage/type2-runtime/releases/download/$runtime_release/runtime-$architecture"
for name in runtime runtime.sig; do
    suffix=${name#runtime}
    curl --fail --location --silent --show-error --proto '=https' --tlsv1.2 --retry 3 \
        -o "$temporary/$name" "$url$suffix"
done
gpgv --keyring "$temporary/keyring.gpg" --status-fd 3 "$temporary/runtime.sig" "$temporary/runtime" \
    3> "$temporary/status" 2> "$temporary/gpgv.log" || { cat "$temporary/gpgv.log" >&2; exit 1; }
# The last field of VALIDSIG is the primary key that made the signature.
signer=$(awk '$2 == "VALIDSIG" { print $NF; exit }' "$temporary/status")
[[ "$signer" == "$signing_key" ]] || { echo 'The runtime is not signed by the pinned key' >&2; exit 1; }
actual=$(sha256sum "$temporary/runtime" | cut -d ' ' -f 1)
[[ "$actual" == "$runtime_sha256" ]] || {
    printf 'The %s runtime of release %s has SHA-256 %s, not the pinned %s\n' \
        "$architecture" "$runtime_release" "$actual" "$runtime_sha256" >&2
    exit 1
}
install -D -m 0755 "$temporary/runtime" "$output"
