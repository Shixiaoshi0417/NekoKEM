#!/bin/sh

set -eu

seed_root=${1:-fuzz/seeds}
nkem_dir=$seed_root/nkem
nkpr_dir=$seed_root/nkpr

mkdir -p "$nkem_dir" "$nkpr_dir"

# Structurally valid NKEM v2 with one synthetic KEM byte and no file data.
printf '%s' \
    '4e4b454d0202001c003800000000000100000000000000000c100000' |
    xxd -r -p > "$nkem_dir/valid-v2"
dd if=/dev/zero bs=1 count=85 status=none \
    >> "$nkem_dir/valid-v2"

# Structurally valid NKPR with one synthetic ciphertext byte.
printf '%s' \
    '4e4b5052010101000001000000000003000000040000001300200c100000000000000001' |
    xxd -r -p > "$nkpr_dir/valid-nkpr"
dd if=/dev/zero bs=1 count=61 status=none \
    >> "$nkpr_dir/valid-nkpr"

dd if="$nkem_dir/valid-v2" of="$nkem_dir/truncated-magic" \
    bs=1 count=3 status=none
dd if="$nkem_dir/valid-v2" of="$nkem_dir/truncated-header" \
    bs=1 count=12 status=none
dd if="$nkem_dir/valid-v2" of="$nkem_dir/truncated-body" \
    bs=1 count=112 status=none

dd if="$nkpr_dir/valid-nkpr" of="$nkpr_dir/truncated-magic" \
    bs=1 count=3 status=none
dd if="$nkpr_dir/valid-nkpr" of="$nkpr_dir/truncated-header" \
    bs=1 count=20 status=none
dd if="$nkpr_dir/valid-nkpr" of="$nkpr_dir/truncated-body" \
    bs=1 count=96 status=none
