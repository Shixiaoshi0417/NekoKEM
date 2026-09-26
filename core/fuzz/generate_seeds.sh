#!/bin/sh

set -eu

script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
seed_root=${1:-"$script_dir/seeds"}
nkem_dir=$seed_root/nkem
nkpr_dir=$seed_root/nkpr

mkdir -p "$nkem_dir" "$nkpr_dir"

hex_to_binary()
{
    perl -e 'local $/; $_ = <STDIN>; s/\s+//g; print pack("H*", $_)'
}

# Structurally valid NKEM v3 with synthetic public fields and no file data.
printf '%s' \
    '4e4b454d0303002000380000000000010000000000000000200c100000000000' |
    hex_to_binary > "$nkem_dir/valid-v3"
dd if=/dev/zero bs=1 count=117 status=none \
    >> "$nkem_dir/valid-v3"

# Structurally valid NKPR with one synthetic ciphertext byte.
printf '%s' \
    '4e4b5052010101000001000000000003000000040000001300200c100000000000000001' |
    hex_to_binary > "$nkpr_dir/valid-nkpr"
dd if=/dev/zero bs=1 count=61 status=none \
    >> "$nkpr_dir/valid-nkpr"

dd if="$nkem_dir/valid-v3" of="$nkem_dir/truncated-magic" \
    bs=1 count=3 status=none
dd if="$nkem_dir/valid-v3" of="$nkem_dir/truncated-header" \
    bs=1 count=12 status=none
dd if="$nkem_dir/valid-v3" of="$nkem_dir/truncated-body" \
    bs=1 count=112 status=none

dd if="$nkpr_dir/valid-nkpr" of="$nkpr_dir/truncated-magic" \
    bs=1 count=3 status=none
dd if="$nkpr_dir/valid-nkpr" of="$nkpr_dir/truncated-header" \
    bs=1 count=20 status=none
dd if="$nkpr_dir/valid-nkpr" of="$nkpr_dir/truncated-body" \
    bs=1 count=96 status=none
