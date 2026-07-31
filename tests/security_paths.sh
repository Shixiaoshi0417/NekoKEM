#!/bin/sh

set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 /path/to/nekokem" >&2
    exit 2
fi

test_program=$1
test_dir=$(mktemp -d)

cleanup()
{
    rm -f "$test_dir/nekokem" "$test_dir/plaintext.bin" \
        "$test_dir/decrypted.bin" "$test_dir/truncated-output.bin" \
        "$test_dir/tampered-output.bin" "$test_dir/invalid-key-output.bin" \
        "$test_dir/wrong-password-output.bin" \
        "$test_dir/tampered-key-output.bin" \
        "$test_dir/wrong-key-output.bin" \
        "$test_dir/correct-private.key.enc" \
        "$test_dir/invalid-private.key" \
        "$test_dir/tampered-private.key.enc" \
        "$test_dir/encrypted/valid.nkem" \
        "$test_dir/encrypted/truncated.nkem" \
        "$test_dir/encrypted/tampered.nkem" \
        "$test_dir/keys/public.key" "$test_dir/keys/private.key" \
        "$test_dir/keys/private.key.enc"
    rmdir "$test_dir/encrypted" "$test_dir/keys" "$test_dir" \
        2>/dev/null || true
}

trap cleanup EXIT INT TERM
mkdir "$test_dir/encrypted"
install -m 0755 "$test_program" "$test_dir/nekokem"
printf 'NekoKEM sanitizer error-path test\n\000\001\377\n' \
    > "$test_dir/plaintext.bin"
cd "$test_dir"
key_password='security-path-test-password'

printf '%s\n%s\n' "$key_password" "$key_password" |
    ./nekokem keygen hybrid >/dev/null
test -s keys/private.key.enc
test ! -e keys/private.key
test "$(stat -c %a keys/private.key.enc)" = 600
test "$(dd if=keys/private.key.enc bs=1 count=4 status=none)" = NKPR
./nekokem encrypt hybrid plaintext.bin encrypted/valid.nkem \
    keys/public.key >/dev/null
printf '%s\n' "$key_password" |
    ./nekokem decrypt hybrid encrypted/valid.nkem decrypted.bin \
        keys/private.key.enc >/dev/null
cmp plaintext.bin decrypted.bin

if ./nekokem encrypt hybrid missing-input.bin encrypted/missing.nkem \
    keys/public.key >/dev/null 2>&1; then
    echo "Missing plaintext was accepted" >&2
    exit 1
fi
test ! -e encrypted/missing.nkem

if ./nekokem encrypt hybrid plaintext.bin missing/output.nkem \
    keys/public.key >/dev/null 2>&1; then
    echo "Invalid output path was accepted" >&2
    exit 1
fi
test ! -e missing/output.nkem

dd if=encrypted/valid.nkem of=encrypted/truncated.nkem \
    bs=1 count=100 status=none
if ./nekokem decrypt hybrid encrypted/truncated.nkem \
    truncated-output.bin keys/private.key.enc >/dev/null 2>&1; then
    echo "Truncated container was accepted" >&2
    exit 1
fi
test ! -e truncated-output.bin

cp encrypted/valid.nkem encrypted/tampered.nkem
printf '\001' | dd of=encrypted/tampered.nkem bs=1 seek=1664 \
    count=1 conv=notrunc status=none
if cmp -s encrypted/valid.nkem encrypted/tampered.nkem; then
    printf '\377' | dd of=encrypted/tampered.nkem bs=1 seek=1664 \
        count=1 conv=notrunc status=none
fi
if printf '%s\n' "$key_password" |
    ./nekokem decrypt hybrid encrypted/tampered.nkem \
        tampered-output.bin keys/private.key.enc >/dev/null 2>&1; then
    echo "Tampered container was accepted" >&2
    exit 1
fi
test ! -e tampered-output.bin

printf 'not a PEM private key\n' > invalid-private.key
if ./nekokem decrypt hybrid encrypted/valid.nkem \
    invalid-key-output.bin invalid-private.key >/dev/null 2>&1; then
    echo "Invalid private key was accepted" >&2
    exit 1
fi
test ! -e invalid-key-output.bin

if printf 'wrong-password\n' |
    ./nekokem decrypt hybrid encrypted/valid.nkem \
        wrong-password-output.bin keys/private.key.enc \
        >/dev/null 2>&1; then
    echo "Wrong private-key password was accepted" >&2
    exit 1
fi
test ! -e wrong-password-output.bin

cp keys/private.key.enc tampered-private.key.enc
private_size=$(wc -c < tampered-private.key.enc)
private_offset=$((private_size - 1))
printf '\001' | dd of=tampered-private.key.enc bs=1 \
    seek="$private_offset" count=1 conv=notrunc status=none
if cmp -s keys/private.key.enc tampered-private.key.enc; then
    printf '\377' | dd of=tampered-private.key.enc bs=1 \
        seek="$private_offset" count=1 conv=notrunc status=none
fi
if printf '%s\n' "$key_password" |
    ./nekokem decrypt hybrid encrypted/valid.nkem \
        tampered-key-output.bin tampered-private.key.enc \
        >/dev/null 2>&1; then
    echo "Tampered protected private key was accepted" >&2
    exit 1
fi
test ! -e tampered-key-output.bin

mv keys/private.key.enc correct-private.key.enc
printf '%s\n%s\n' "$key_password" "$key_password" |
    ./nekokem keygen hybrid >/dev/null
if printf '%s\n' "$key_password" |
    ./nekokem decrypt hybrid encrypted/valid.nkem \
        wrong-key-output.bin keys/private.key.enc >/dev/null 2>&1; then
    echo "Wrong private key was accepted" >&2
    exit 1
fi
test ! -e wrong-key-output.bin

test -z "$(find . -type f -name '*.tmp.*' -print -quit)"
test -z "$(find . -type f -name 'private.key' -print -quit)"
echo "UBSan protected-private-key and error-path tests passed"
