#!/bin/sh
set -eu

readonly_repository="Shixiaoshi0417/NekoKEM"
install_dir=${NEKOKEM_INSTALL_DIR:-/usr/local/bin}
case "$install_dir" in
    /*) ;;
    *)
        printf 'NekoKEM installer error: install directory must be an absolute path\n' >&2
        exit 1
        ;;
esac

fail() {
    printf 'NekoKEM installer error: %s\n' "$1" >&2
    exit 1
}

require_tool() {
    if ! command -v "$1" >/dev/null 2>&1; then
        fail "required tool '$1' was not found; install it manually and retry"
    fi
}

kernel_name=$(uname -s 2>/dev/null || true)
if [ "$kernel_name" != "Linux" ]; then
    fail "unsupported operating system '$kernel_name'; only Linux is supported"
fi

machine_name=$(uname -m 2>/dev/null || true)
case "$machine_name" in
    x86_64 | amd64)
        release_arch="x86_64"
        ;;
    aarch64 | arm64)
        release_arch="aarch64"
        ;;
    *)
        fail "unsupported Linux architecture '$machine_name'; supported architectures are x86_64 and aarch64"
        ;;
esac

for required_tool in uname mktemp tar gzip sha256sum awk install mkdir chmod \
    id basename rm; do
    require_tool "$required_tool"
done
if command -v curl >/dev/null 2>&1; then
    download_tool="curl"
elif command -v wget >/dev/null 2>&1; then
    download_tool="wget"
else
    fail "curl or wget is required to download the GitHub Release; this installer does not install dependencies"
fi

tmp_base=${TMPDIR:-/tmp}
work_dir=$(mktemp -d "$tmp_base/nekokem-install.XXXXXX") || \
    fail "cannot create a private temporary directory"
cleanup() {
    work_name=$(basename -- "$work_dir")
    if [ -n "$work_dir" ] && [ -d "$work_dir" ] &&
       case "$work_name" in nekokem-install.*) true ;; *) false ;; esac; then
        rm -rf -- "$work_dir"
    fi
}
trap cleanup EXIT HUP INT TERM

package_name="NekoKEM-linux-$release_arch"
archive_name="$package_name.tar.gz"
archive_path="$work_dir/$archive_name"
release_url="https://github.com/$readonly_repository/releases/latest/download/$archive_name"
release_sums_name="SHA256SUMS.txt"
release_sums_path="$work_dir/$release_sums_name"
release_sums_url="https://github.com/$readonly_repository/releases/latest/download/$release_sums_name"

download_release_file() {
    source_url=$1
    destination_path=$2
    display_name=$3
    if [ "$download_tool" = "curl" ]; then
        curl --fail --location --silent --show-error --proto '=https' \
            --tlsv1.2 --retry 3 --output "$destination_path" \
            "$source_url" || fail "cannot download $display_name from the latest GitHub Release"
    else
        wget --quiet --https-only --output-document="$destination_path" \
            "$source_url" || fail "cannot download $display_name from the latest GitHub Release"
    fi
    [ -s "$destination_path" ] || fail "downloaded $display_name is empty"
}

printf 'Downloading the latest NekoKEM Linux release for %s...\n' \
    "$release_arch"
download_release_file "$release_url" "$archive_path" "$archive_name"
download_release_file "$release_sums_url" "$release_sums_path" \
    "$release_sums_name"

expected_archive_sha=$(awk -v archive="$archive_name" '
    NF != 2 || length($1) != 64 || $1 ~ /[^0123456789abcdefABCDEF]/ ||
        $2 !~ /^[A-Za-z0-9._-]+$/ {
        invalid = 1
        next
    }
    $2 == archive {
        archive_count++
        archive_sha = tolower($1)
    }
    END {
        if (invalid || archive_count != 1) {
            exit 1
        }
        print archive_sha
    }
' "$release_sums_path") || fail "SHA256SUMS.txt has an invalid structure"
(
    cd "$work_dir"
    printf '%s  %s\n' "$expected_archive_sha" "$archive_name" | \
        sha256sum -c -
) || fail "release archive SHA-256 verification failed; the release was not installed"

member_list="$work_dir/archive-members"
tar -tzf "$archive_path" > "$member_list" || \
    fail "downloaded file is not a valid gzip-compressed tar archive"
awk -v root="$package_name" '
    $0 == root "/" { directory_count++; next }
    $0 == root "/LICENSE" { license_count++; next }
    $0 == root "/README" { readme_count++; next }
    $0 == root "/SHA256SUMS" { sums_count++; next }
    $0 == root "/nekokem" { binary_count++; next }
    { invalid = 1 }
    END {
        if (invalid || directory_count != 1 || license_count != 1 ||
            readme_count != 1 || sums_count != 1 || binary_count != 1) {
            exit 1
        }
    }
' "$member_list" || fail "release archive contains unexpected or duplicate entries"

tar -xzf "$archive_path" --directory "$work_dir" --no-same-owner \
    --no-same-permissions || fail "cannot extract the release archive"
package_dir="$work_dir/$package_name"
for regular_file in LICENSE README SHA256SUMS nekokem; do
    [ -f "$package_dir/$regular_file" ] &&
        [ ! -L "$package_dir/$regular_file" ] ||
        fail "release member '$regular_file' is not a regular file"
done

awk '
    NF != 2 || length($1) != 64 || $1 ~ /[^0123456789abcdefABCDEF]/ {
        invalid = 1
        next
    }
    $2 == "LICENSE" { license_count++; next }
    $2 == "README" { readme_count++; next }
    $2 == "nekokem" { binary_count++; next }
    { invalid = 1 }
    END {
        if (invalid || NR != 3 || license_count != 1 || readme_count != 1 ||
            binary_count != 1) {
            exit 1
        }
    }
' "$package_dir/SHA256SUMS" || fail "SHA256SUMS has an invalid structure"
(
    cd "$package_dir"
    sha256sum -c SHA256SUMS
) || fail "SHA-256 verification failed; the release was not installed"

chmod 0755 "$package_dir/nekokem" || fail "cannot mark the binary executable"
package_version=$("$package_dir/nekokem" --version 2>/dev/null) ||
    fail "the downloaded binary cannot run on this system"
case "$package_version" in
    "NekoKEM "*) ;;
    *) fail "the downloaded executable returned an unexpected version string" ;;
esac

install_target="$install_dir/nekokem"
if [ -d "$install_dir" ] && [ -w "$install_dir" ]; then
    install -m 0755 "$package_dir/nekokem" "$install_target" ||
        fail "cannot install $install_target"
elif [ "$(id -u 2>/dev/null || printf 'unknown')" = "0" ]; then
    install -d -m 0755 "$install_dir" ||
        fail "cannot create $install_dir"
    install -m 0755 "$package_dir/nekokem" "$install_target" ||
        fail "cannot install $install_target"
elif command -v sudo >/dev/null 2>&1; then
    printf 'Installing to %s with sudo...\n' "$install_dir"
    sudo install -d -m 0755 "$install_dir" ||
        fail "sudo could not create $install_dir"
    sudo install -m 0755 "$package_dir/nekokem" "$install_target" ||
        fail "sudo could not install $install_target"
else
    printf '%s\n' \
        "No write permission for $install_dir and sudo is not available." \
        "Re-run this installer as root, or choose a writable absolute directory:" \
        "  NEKOKEM_INSTALL_DIR=\"\$HOME/.local/bin\" sh install.sh" >&2
    exit 1
fi

installed_version=$(PATH="$install_dir:$PATH" nekokem --version 2>/dev/null) ||
    fail "installation completed, but 'nekokem --version' failed"
[ "$installed_version" = "$package_version" ] ||
    fail "installed binary version does not match the verified package"
printf 'Installed %s to %s\n' "$installed_version" "$install_target"
