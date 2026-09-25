#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
    printf 'Usage: %s <x86_64|aarch64> <release-archive>\n' "$0" >&2
    exit 2
fi

release_arch=$1
archive_path=$2
case "$release_arch" in
    x86_64 | aarch64) ;;
    *) printf 'Unsupported test architecture: %s\n' "$release_arch" >&2; exit 2 ;;
esac

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
archive_path=$(CDPATH= cd -- "$(dirname -- "$archive_path")" && \
    printf '%s/%s\n' "$(pwd -P)" "$(basename -- "$archive_path")")
[ -s "$archive_path" ] || { printf 'Archive not found: %s\n' "$archive_path" >&2; exit 1; }

test_root=$(mktemp -d /tmp/nekokem-installer-tests.XXXXXX)
cleanup_tests() {
    rm -rf -- "$test_root"
}
trap cleanup_tests EXIT HUP INT TERM

if [ -n "${NEKOKEM_TEST_RUNNER:-}" ]; then
    runner_path=$(command -v "$NEKOKEM_TEST_RUNNER") || {
        printf 'Cross-architecture test runner not found: %s\n' \
            "$NEKOKEM_TEST_RUNNER" >&2
        exit 1
    }
    original_stage="$test_root/original-stage"
    mkdir "$original_stage"
    tar -xzf "$archive_path" -C "$original_stage"
    original_package="$original_stage/NekoKEM-linux-$release_arch"
    (
        cd "$original_package"
        sha256sum -c SHA256SUMS
    ) >/dev/null
    test "$("$runner_path" "$original_package/nekokem" --version)" = \
        "NekoKEM 3.1.1"

    cross_binary="$test_root/nekokem-cross-binary"
    cp "$original_package/nekokem" "$cross_binary"
    chmod 0755 "$cross_binary"
    cat > "$original_package/nekokem" <<EOF
#!/bin/sh
exec "$runner_path" "$cross_binary" "\$@"
EOF
    chmod 0755 "$original_package/nekokem"
    (
        cd "$original_package"
        sha256sum LICENSE README nekokem > SHA256SUMS
    )
    cross_archive="$test_root/NekoKEM-linux-$release_arch-cross-test.tar.gz"
    tar -czf "$cross_archive" -C "$original_stage" \
        "NekoKEM-linux-$release_arch"
    archive_path=$cross_archive
fi

mock_bin="$test_root/mock-bin"
mkdir "$mock_bin"

cat > "$mock_bin/uname" <<'MOCK_UNAME'
#!/bin/sh
case "${1:-}" in
    -s) printf '%s\n' "${NEKOKEM_TEST_UNAME_S:-Linux}" ;;
    -m) printf '%s\n' "${NEKOKEM_TEST_UNAME_M:?}" ;;
    *) exit 2 ;;
esac
MOCK_UNAME
cat > "$mock_bin/curl" <<'MOCK_CURL'
#!/bin/sh
set -eu
output=
url=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --output)
            shift
            output=${1:?}
            ;;
        https://*)
            url=$1
            ;;
    esac
    shift
done
[ -n "$output" ] && [ -n "$url" ]
archive_url="https://github.com/Shixiaoshi0417/NekoKEM/releases/latest/download/NekoKEM-linux-${NEKOKEM_TEST_ARCH}.tar.gz"
sums_url="https://github.com/Shixiaoshi0417/NekoKEM/releases/latest/download/SHA256SUMS.txt"
case "$url" in
    "$archive_url") cp "$NEKOKEM_TEST_ARCHIVE" "$output" ;;
    "$sums_url") cp "$NEKOKEM_TEST_SUMS" "$output" ;;
    *) exit 1 ;;
esac
MOCK_CURL
chmod 0755 "$mock_bin/uname" "$mock_bin/curl"

release_sums_path="$test_root/SHA256SUMS.txt"
archive_sha=$(sha256sum "$archive_path" | awk '{ print $1 }')
printf '%s  %s\n' "$archive_sha" \
    "NekoKEM-linux-$release_arch.tar.gz" > "$release_sums_path"

install_root="$test_root/install-success"
mkdir "$install_root"
PATH="$mock_bin:$PATH" \
NEKOKEM_TEST_UNAME_S=Linux \
NEKOKEM_TEST_UNAME_M="$release_arch" \
NEKOKEM_TEST_ARCH="$release_arch" \
NEKOKEM_TEST_ARCHIVE="$archive_path" \
NEKOKEM_TEST_SUMS="$release_sums_path" \
NEKOKEM_INSTALL_DIR="$install_root" \
    sh "$repo_root/install.sh" > "$test_root/success.log"
test "$("$install_root/nekokem" --version)" = "NekoKEM 3.1.1"
grep -F "Installed NekoKEM 3.1.1 to $install_root/nekokem" \
    "$test_root/success.log" >/dev/null

bad_stage="$test_root/bad-stage"
bad_archive="$test_root/NekoKEM-linux-$release_arch.tar.gz"
mkdir "$bad_stage"
tar -xzf "$archive_path" -C "$bad_stage"
printf '\000' >> "$bad_stage/NekoKEM-linux-$release_arch/nekokem"
tar -czf "$bad_archive" -C "$bad_stage" "NekoKEM-linux-$release_arch"
bad_install="$test_root/install-bad"
mkdir "$bad_install"
if PATH="$mock_bin:$PATH" \
   NEKOKEM_TEST_UNAME_S=Linux \
   NEKOKEM_TEST_UNAME_M="$release_arch" \
   NEKOKEM_TEST_ARCH="$release_arch" \
   NEKOKEM_TEST_ARCHIVE="$bad_archive" \
   NEKOKEM_TEST_SUMS="$release_sums_path" \
   NEKOKEM_INSTALL_DIR="$bad_install" \
       sh "$repo_root/install.sh" > "$test_root/bad.log" 2>&1; then
    printf 'Installer accepted a package with a bad SHA-256\n' >&2
    exit 1
fi
test ! -e "$bad_install/nekokem"
grep -F 'release archive SHA-256 verification failed' \
    "$test_root/bad.log" >/dev/null

if PATH="$mock_bin:$PATH" \
   NEKOKEM_TEST_UNAME_S=Darwin \
   NEKOKEM_TEST_UNAME_M="$release_arch" \
       sh "$repo_root/install.sh" > "$test_root/os.log" 2>&1; then
    printf 'Installer accepted a non-Linux operating system\n' >&2
    exit 1
fi
grep -F "unsupported operating system 'Darwin'" "$test_root/os.log" >/dev/null

if PATH="$mock_bin:$PATH" \
   NEKOKEM_TEST_UNAME_S=Linux \
   NEKOKEM_TEST_UNAME_M=riscv64 \
       sh "$repo_root/install.sh" > "$test_root/arch.log" 2>&1; then
    printf 'Installer accepted an unsupported architecture\n' >&2
    exit 1
fi
grep -F "unsupported Linux architecture 'riscv64'" \
    "$test_root/arch.log" >/dev/null

no_sudo_bin="$test_root/no-sudo-bin"
mkdir "$no_sudo_bin"
cp "$mock_bin/uname" "$mock_bin/curl" "$no_sudo_bin/"
for required_tool in mktemp tar gzip sha256sum awk install mkdir chmod basename \
    rm cp; do
    ln -s "$(command -v "$required_tool")" "$no_sudo_bin/$required_tool"
done
cat > "$no_sudo_bin/id" <<'MOCK_ID'
#!/bin/sh
[ "${1:-}" = "-u" ] || exit 2
printf '1000\n'
MOCK_ID
chmod 0755 "$no_sudo_bin/id"
if PATH="$no_sudo_bin" \
   NEKOKEM_TEST_UNAME_S=Linux \
   NEKOKEM_TEST_UNAME_M="$release_arch" \
   NEKOKEM_TEST_ARCH="$release_arch" \
   NEKOKEM_TEST_ARCHIVE="$archive_path" \
   NEKOKEM_TEST_SUMS="$release_sums_path" \
   NEKOKEM_INSTALL_DIR=/proc/nekokem-installer-no-sudo \
       /bin/sh "$repo_root/install.sh" > "$test_root/no-sudo.log" 2>&1; then
    printf 'Installer unexpectedly wrote without permission or sudo\n' >&2
    exit 1
fi
grep -F 'No write permission for /proc/nekokem-installer-no-sudo and sudo is not available.' \
    "$test_root/no-sudo.log" >/dev/null

printf 'Installer tests passed for %s\n' "$release_arch"
