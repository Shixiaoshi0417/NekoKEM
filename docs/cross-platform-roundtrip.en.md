[简体中文](cross-platform-roundtrip.md) | [English](cross-platform-roundtrip.en.md)

# Cross-platform encrypt/decrypt round trip

Use the existing `nekokem` CLI to check that an NKEM file encrypted on one platform decrypts on another to the same plaintext bytes, and that the opposite direction does the same. This page does not define a new container or algorithm. The formats are [NKEM v3](NKEM-v3.md), [NKEM v4](NKEM-v4.md) and [NKEM v5](NKEM-v5.md).

The Linux, macOS, and Windows CLIs share the argument parser in `linux/src/main.c`. The Android app and the desktop GUI call the same C core and write the same NKEM v3/v4/v5 and NKPR files. The byte comparison below uses the CLI. How the app and GUI take part is at the end.

## What passes

One direction passes only when all of the following are true:

- The decrypt process exits 0.
- With `--lang en`, stdout contains `Decrypted NKEM <input> -> <output>` (`--lang zh-CN` prints `已解密 NKEM <输入> -> <输出>`).
- The restored file has the same length, the same SHA-256, and the same bytes as the plaintext that was encrypted.
- For a signed v5 file, the signer fingerprint in the decrypt output equals the fingerprint of the encrypting side's public key.

A round trip passes when both directions meet those checks: A encrypts for B and B decrypts, and B encrypts for A and A decrypts.

Every encryption draws a fresh salt, nonce, and ephemeral keys, so two ciphertexts of one plaintext differ in their bytes. Ciphertext length is fixed by the plaintext length, the container version, and the recipient count. Compare plaintext hashes. Check ciphertext by length and by the header version byte.

A failed decrypt does not create a new plaintext file. Every output path in this procedure is a path that does not exist yet; after a failure that path is still absent. A successful decrypt of an empty plaintext creates a 0-byte file, which is different from a missing path.

## Prerequisites

- Both sides need a build that reads and writes NKEM v3, v4 and v5 and NKPR v1 and v2, which means NekoKEM 5.2.0 or newer: signed files written by the CLI always record the original file name, and 5.1.0 and earlier cannot open them. In this repository's v5.2.0 CLI, `nekokem --version` (on Windows, `nekokem.exe --version`) prints `NekoKEM 5.2.0`. When reporting a failure, include both version strings. NekoKEM 3.3.2 and earlier cannot open a v4 file; 4.x and earlier cannot open a v5 file, NKPR v2 or a four-component public key.
- Release archives: Linux `NekoKEM-linux-x86_64.tar.gz` or `NekoKEM-linux-aarch64.tar.gz`, macOS Apple Silicon `NekoKEM-macos-arm64.tar.gz` (no Intel build), and `nekokem.exe` from Windows `NekoKEM-windows-x86_64.zip`. A binary built from the same commit is also fine.
- `nekokem` in the commands below means that executable. Put it on `PATH`, or substitute an absolute path. On Windows, pass the same arguments to `nekokem.exe`. Place `--lang` before the subcommand.
- Work in a directory you own. The Windows CLI accepts local fixed NTFS only. Network paths, FAT/exFAT, device paths, and reparse points are rejected before any cryptography. Copy the files onto a local NTFS directory first.
- Use a disposable key pair and the public samples in this page. Do not use a key that still protects data, and do not commit `keys/` to git.
- `nekokem keygen` writes a four-component identity key in the current directory: `keys/public.key` (X448, ML-KEM-1024, Ed448 and ML-DSA-87 public keys) and `keys/private.key.enc` (NKPR v2). If either file exists, keygen refuses before asking for a password and does not overwrite. `keygen hybrid` is the same command. Use `keygen --replace` only when you mean to rotate; files for the old public key then decrypt only with the old private key.
- A new password is 1 to 1024 bytes of valid UTF-8, entered twice. A mismatch exits; with `--lang en` the message is `Passwords do not match`. The password is read from a terminal with echo disabled, or from UTF-8 stdin. It is not a command-line argument. One trailing CR on the line is stripped, so the same password bytes with LF or CRLF match. Unlocking an existing NKPR prompts once. NKPR is detected from the file header, not the extension.
- Encryption signs by default: it uses `keys/private.key.enc` in the current directory (or the key given with `--sign-key`) and prompts for its password. `--unsigned` does not sign, needs no private key or password, and prints an unsigned-file warning on stderr.
- NKPR uses Argon2id (64 MiB, 3 iterations, parallelism 4). Key generation, signed encryption and decryption pause for a few seconds after the password prompt while the password is derived.
- On POSIX the private key must be a regular file owned by the current user, mode exactly `0600`, with one hard link. On Windows it must be owned by the current user, with an owner-only ACL and one hard link. A public key is a non-empty regular file. Reads use `O_NOFOLLOW`, so a symlink is rejected. Public keys have no `0600` requirement.
- If Windows detects an MSYS or mintty pseudoterminal that cannot reliably hide secret input, the CLI refuses the password. Use Windows Terminal, or UTF-8 stdin as shown below.

The `sh` blocks below are for a POSIX shell: Linux, macOS, or Git Bash. In PowerShell the arguments are the same, the directory must be on local fixed NTFS, and the executable is `.\nekokem.exe`. The PowerShell sample targets Windows PowerShell 5.1 and later, including PowerShell 7. It pipes a here-string to `python -`: Windows PowerShell 5.1 strips double quotes from native arguments, so a `python -c` command with embedded quotes is not valid Python. Encrypt only after `peer-public.key` is in place. The here-string terminator `'@` must be the first characters on its line.

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\nekokem-roundtrip" | Out-Null
Set-Location "$env:USERPROFILE\nekokem-roundtrip"
@'
from pathlib import Path
Path("plain-text.txt").write_bytes(b"nekokem-roundtrip\n")
Path("plain-empty").write_bytes(b"")
Path("plain-binary").write_bytes(bytes(range(256)))
'@ | python -
New-Item -ItemType Directory -Force out, in | Out-Null
```

`keygen`, signed encryption and `decrypt` read the password from stdin, so run them on their own. Do not paste them into one pipeline with the following command. Encrypt and decrypt examples:

```powershell
.\nekokem.exe --lang en encrypt hybrid plain-text.txt out\text.v5.nkem peer-public.key
```

```powershell
.\nekokem.exe --lang en decrypt hybrid --trust peer-public.key --require-trusted in\text.v5.nkem in\text.restored keys\private.key.enc
```

Menu option "Encrypt file" takes one public key and writes signed v5. Several recipients (up to 64) and unsigned v3 and v4 use the parameterized commands below.

## Plaintext samples

Generate the samples on each machine with the same Python so the bytes do not depend on the shell. If Windows has no `python3` command, use `python` in its place.

```sh
mkdir -p "$HOME/nekokem-roundtrip"
cd "$HOME/nekokem-roundtrip"
python3 -c "from pathlib import Path; Path('plain-text.txt').write_bytes(b'nekokem-roundtrip\n'); Path('plain-empty').write_bytes(b''); Path('plain-binary').write_bytes(bytes(range(256)))"
```

| File | Bytes | SHA-256 |
|---|---:|---|
| `plain-text.txt` | 18 | `380e7f841494c03f55ecac6396d1728d531262cdc475cde4799cad4e59e171bd` |
| `plain-empty` | 0 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `plain-binary` | 256 | `40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880` |

Check them before encrypting. This Python prints the hashes and exits 1 if any digest differs from the table:

```sh
python3 -c "import hashlib,pathlib; expected={'plain-text.txt':'380e7f841494c03f55ecac6396d1728d531262cdc475cde4799cad4e59e171bd','plain-empty':'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855','plain-binary':'40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880'};
ok=True
for name,digest in expected.items():
    got=hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest(); print(got,name); ok &= got==digest
raise SystemExit(0 if ok else 1)"
```

Ciphertext length (AES-256-GCM ciphertext length equals the plaintext length):

| Container | Length |
|---|---|
| NKEM v5 (default), N recipients, 1 ≤ N ≤ 64 | plaintext length + 10228 + 1672 × N |
| NKEM v3 (`--unsigned`, one recipient) | plaintext length + 1716 |
| NKEM v4 (`--unsigned`, 2 ≤ N ≤ 64) | plaintext length + 156 + 1672 × N |

The three single-recipient v5 ciphertexts are therefore 11918, 11900, and 12156 bytes, and the three v3 ciphertexts are 1734, 1716, and 1972 bytes. The two-recipient short text is 13590 bytes as v5 and 3518 bytes as v4. The constants are the fixed header, recipient entry and signature block fields in the format documents. The v5 constant 10228 includes the 1024-byte original file name record; the CLI always records the name, and a v5 file written by a GUI with "Record original file name" off is plaintext length + 9204 + 1672 × N.

The first 4 bytes are ASCII `NKEM`. Byte 5 (offset 4) is the version: `3`, `4` or `5`. An NKPR private key starts with ASCII `NKPR`.

```sh
python3 -c "import pathlib,sys; d=pathlib.Path(sys.argv[1]).read_bytes(); print(len(d), d[:4], d[4] if len(d)>4 else None)" out/text.v5.nkem
```

## Keys

Each machine generates its own key pair and keeps the private key. Call this machine A and the other B. Both sides do this.

Type the password twice at the terminal. For a non-interactive run, Python writes UTF-8 stdin (the same password on two lines). Replace the example password with your own disposable password. It is not a protocol constant and must not protect real data.

```sh
cd "$HOME/nekokem-roundtrip"
nekokem --lang en keygen
```

```sh
python3 -c "import subprocess,sys; p=b'roundtrip-test-password\n'; subprocess.run([sys.argv[1],'--lang','en','keygen'], input=p+p, check=True)" nekokem
```

On success, `--lang en` prints `Generated identity keys (X448 + ML-KEM-1024 encryption, Ed448 + ML-DSA-87 signing): keys/public.key and keys/private.key.enc`.

Optional: the `fingerprint` subcommand, like menu item 4, prints the public-key fingerprint (uppercase SHA-256, colon-separated):

```sh
nekokem --lang en fingerprint "$HOME/nekokem-roundtrip/keys/public.key"
```

The same fingerprint on both machines means the copied `public.key` is the public key Core read locally. The fingerprint does not depend on PEM line wrapping. A four-component public key prints its identity fingerprint, which is the signer fingerprint shown when a signed file is decrypted.

## NKEM v5: signed, both directions

Copy the peer's `keys/public.key` to `peer-public.key` in this directory. It is PEM with four `PUBLIC KEY` blocks: X448, ML-KEM-1024, Ed448 and ML-DSA-87. Copy the whole file.

A encrypts for B and signs with A's own private key:

```sh
cd "$HOME/nekokem-roundtrip"
mkdir -p out
nekokem --lang en encrypt hybrid plain-text.txt out/text.v5.nkem peer-public.key
nekokem --lang en encrypt hybrid plain-empty out/empty.v5.nkem peer-public.key
nekokem --lang en encrypt hybrid plain-binary out/binary.v5.nkem peer-public.key
```

Each command prompts `Enter the private-key password: ` (`请输入私钥密码：` with `--lang zh-CN`). Enter the password A used at keygen. Each exits 0 and prints `Encrypted and signed NKEM v5 for 1 recipient(s) <input> -> <output>`. The version byte is 5 and the lengths are 11918, 11900, and 12156.

Give the three `.nkem` files to B, in B's `in/` directory. B decrypts with B's private key and names A's public key as the trusted signer:

```sh
cd "$HOME/nekokem-roundtrip"
mkdir -p in
nekokem --lang en decrypt hybrid --trust peer-public.key --require-trusted in/text.v5.nkem in/text.restored keys/private.key.enc
nekokem --lang en decrypt hybrid --trust peer-public.key --require-trusted in/empty.v5.nkem in/empty.restored keys/private.key.enc
nekokem --lang en decrypt hybrid --trust peer-public.key --require-trusted in/binary.v5.nkem in/binary.restored keys/private.key.enc
```

Enter B's own password here. After `Decrypted NKEM <input> -> <output>`, each command prints:

```text
Signature: valid, signed by trusted key peer-public.key
Signer fingerprint:
<A's identity fingerprint>
Original file name: plain-text.txt
```

The signer fingerprint must equal the fingerprint of A's `keys/public.key`, and the original file name is the input file name A encrypted. Decryption also works without `--trust`; it then prints `Signature: valid, but the signer is not one of your trusted keys; compare this fingerprint with the sender:` and the same fingerprint, for the operator to compare. Then:

```sh
python3 -c "import hashlib,pathlib
pairs=[('plain-text.txt','in/text.restored'),('plain-empty','in/empty.restored'),('plain-binary','in/binary.restored')]
ok=True
for src,dst in pairs:
    a,b=pathlib.Path(src).read_bytes(),pathlib.Path(dst).read_bytes()
    print(hashlib.sha256(b).hexdigest(), dst, 'len', len(b))
    ok &= a==b
raise SystemExit(0 if ok else 1)"
```

The three hashes must match the table, and the exit code must be 0. `in/empty.restored` exists and has length 0.

B repeats the encryption with A's public key, signing with B's private key, and sends those `.nkem` files to A. A's restored files must match the three samples byte for byte, and the signer fingerprint equals the fingerprint of B's public key. That completes both v5 directions.

On POSIX, `sha256sum` (or `shasum -a 256` on macOS) and `cmp` also work. On Windows, `Get-FileHash -Algorithm SHA256` or `certutil -hashfile <file> SHA256`, and `fc /b`, also work. Ignore case and spaces inside the hash text. The Python above compares the raw bytes.

## NKEM v3: unsigned, one recipient

`--unsigned` writes an unsigned container for recipients still on NekoKEM 4.x or earlier; one public key writes v3. It reads no private key and asks for no password:

```sh
cd "$HOME/nekokem-roundtrip"
nekokem --lang en encrypt hybrid --unsigned plain-text.txt out/text.v3.nkem peer-public.key
nekokem --lang en encrypt hybrid --unsigned plain-empty out/empty.v3.nkem peer-public.key
nekokem --lang en encrypt hybrid --unsigned plain-binary out/binary.v3.nkem peer-public.key
```

Each command exits 0, prints `Encrypted hybrid NKEM v3 <input> -> <output>` on stdout and `Warning: this file is not signed; recipients cannot confirm that it came from you.` on stderr. The version byte is 3 and the lengths are 1734, 1716, and 1972.

B decrypts to new output paths:

```sh
nekokem --lang en decrypt hybrid in/text.v3.nkem in/text.v3.restored keys/private.key.enc
nekokem --lang en decrypt hybrid in/empty.v3.nkem in/empty.v3.restored keys/private.key.enc
nekokem --lang en decrypt hybrid in/binary.v3.nkem in/binary.v3.restored keys/private.key.enc
```

After the success line each prints `Signature: none. This NKEM v3 file is not signed, so its sender cannot be confirmed.` and no original file name. Run the comparison script again with the three `in/*.v3.restored` names. Decrypting these files with `--require-signature` fails; see [Expected failures](#expected-failures).

For a recipient on 4.x or earlier, give them the public key exported with `nekokem export-legacy-public-key keys/public.key legacy-public.key`: older versions cannot read a four-component public key.

## NKEM v4: two recipients

On a machine that has both `keys/public.key` and `peer-public.key`, encrypt the short text for both without signing. The two public keys must be different key pairs. If they are the same or share a component, the command fails with `Two public keys are the same or share a key component` and creates no output.

```sh
cd "$HOME/nekokem-roundtrip"
nekokem --lang en encrypt hybrid --unsigned plain-text.txt out/text.v4.nkem keys/public.key peer-public.key
```

The exit code is 0 and the line is `Encrypted NKEM v4 for 2 recipients plain-text.txt -> out/text.v4.nkem`, with the same unsigned-file warning. The version byte is 4 and the length is 3518. Argument order does not change who can decrypt.

Decrypt locally with this machine's private key to a new path, and give the same `.nkem` to the peer to decrypt with their private key. Both restored files match `plain-text.txt` byte for byte.

The peer runs the same encrypt (both public keys, a new output name) and sends the new `.nkem` back. The new ciphertext differs from the first one, the length is still 3518, and both sides decrypt the same short text.

The same command without `--unsigned` (and with a new output name) writes signed v5 for the two recipients: it prompts for this machine's private-key password and prints `Encrypted and signed NKEM v5 for 2 recipient(s)`, the version byte is 5 and the length is 13590. Both sides again decrypt the same short text and see the same signer fingerprint.

## Which files to exchange

Copy:

- The peer's public-key file (the whole `keys/public.key`).
- The `.nkem` ciphertext.
- The three public plaintext samples, or regenerate them on each machine from the bytes above.

Leave these on the machine that created them:

- `keys/private.key.enc` and Android's exported `private.nkpr`.
- The password.
- `keys/.nekokem-pair.lock` (the directory lock used while a key operation runs; it is not a key).

Do not write private-key bytes, NKPR contents, or the password into this page, an issue, a chat, or git. An issue needs the version strings, the container version byte, the file length, and the plaintext SHA-256.

If one check really needs the same private key on two machines (the CI fixtures have that shape): copy the NKPR as a file, send the password by a separate channel, and delete the copy when finished. Fix permissions on arrival. POSIX:

```sh
chmod 600 keys/private.key.enc
```

The file must be owned by the user who will decrypt, with one hard link. On Windows, put it in the `keys` directory the CLI created and apply the owner-only ACL from the PowerShell example in the [Windows CLI documentation](../windows/README.md). A private key with the wrong permissions is rejected before decrypt.

The output path must not be a public or private key used in that command. Use a new file name for decrypt output.

## Checklist

Encryption:

- Exit code 0.
- The v5 success line contains `Encrypted and signed NKEM v5`. The v3 success line contains `Encrypted hybrid NKEM v3`. The v4 success line contains `Encrypted NKEM v4 for 2 recipients`.
- The first 4 bytes are `NKEM`, and byte 5 is 5, 3 or 4.
- The length matches the table above.

Decryption:

- Exit code 0, and the success line contains `Decrypted NKEM`.
- v5 prints `Signature: valid`, the signer fingerprint equals the encrypting side's public-key fingerprint, and the original file name equals the encrypted input's name. v3 and v4 print `Signature: none`.
- The three restored SHA-256 digests match the sample table, and the byte comparison exits 0.
- The empty-file result is a 0-byte file that exists.
- The opposite direction passes as well.
- For the two-recipient v4 and v5 files, each recipient restores the same short text.

## Expected failures

Each case below exits non-zero, and the new output path is still absent. The CLI reads the NKPR password before it calls Core. The container-length check runs before the NKPR is unlocked: a truncated file or a file with trailing bytes reports the length error even when the password just entered is wrong. The wrong-password row needs an intact ciphertext, so decryption reaches private-key unlock.

| Action | `--lang en` stderr |
|---|---|
| Decrypt an intact ciphertext with the wrong NKPR password | `Private-key password is incorrect or NKPR data is corrupted` |
| Decrypt v3 with a private key that is not the recipient | `Authentication failed: wrong key or modified NKEM file` |
| Decrypt v4 or v5 with a private key that is not a recipient | `This file is not encrypted for this private key, or it was modified` |
| Delete the last byte of a v3 ciphertext, then decrypt | `NKEM v3 container is truncated or has trailing data` |
| Delete the last byte of a v4 ciphertext, then decrypt | `NKEM v4 container is truncated or has trailing data` |
| Delete the last byte of a v5 ciphertext, then decrypt | `NKEM v5 container is truncated or has trailing data` |
| Change one byte inside the v5 signature block, then decrypt | `The sender signature is invalid; the file may have been modified` |
| Decrypt v3 or v4 with `--require-signature` | `This file is not signed, and only signed files are accepted` |
| Decrypt v5 with `--require-trusted` when the signer is not in the `--trust` list | `The file is signed by an identity that is not trusted: <fingerprint>` |
| Encrypt without `--unsigned` when the current directory has no `keys/private.key.enc` | `Signing key not found: keys/private.key.enc` |

A wrong password and a damaged NKPR share one message; the CLI does not distinguish them. v3 also uses one authentication-failure message for a wrong key and for a modified ciphertext. One extra trailing byte fails the same way as truncation ("truncated or has trailing data").

A direct v3 negative check: on A, decrypt `out/text.v3.nkem` (encrypted only for B) with A's own private key, writing to `out/not-for-a.txt`, which must not exist yet. Truncation sample:

```sh
python3 -c "from pathlib import Path; src=Path('out/text.v3.nkem'); Path('out/text.v3.truncated.nkem').write_bytes(src.read_bytes()[:-1])"
nekokem --lang en decrypt hybrid out/text.v3.truncated.nkem out/truncated.txt keys/private.key.enc
```

Run that last command on the machine that holds the matching private key. Afterwards `out/truncated.txt` is still absent.

With `--lang zh-CN` the same sentences are `私钥密码错误或 NKPR 数据损坏`, `认证失败：密钥错误或 NKEM 文件被修改`, `此文件不是为该私钥加密的，或已被修改`, `NKEM v3 容器被截断或有多余尾部数据`, `NKEM v4 容器被截断或有多余尾部数据`, `NKEM v5 容器被截断或带有多余数据`, `发送者签名无效，文件可能已被修改`, `此文件未签名，当前只接受已签名文件`, `文件由不受信任的身份签名：<指纹>`, and `找不到签名私钥：keys/private.key.enc`.

## Android and the desktop GUI

The Android app and the desktop GUI write signed NKEM v5 by default, for 1 to 64 recipients. With unsigned encryption turned on in Settings they write NKEM v3 for one recipient and NKEM v4 for 2 to 64. Decrypt selects the version from the header and shows the signature status and signer fingerprint. A round trip that includes them still uses the lengths, version byte, and plaintext SHA-256 from the sections above.

- Import the CLI's `keys/public.key` into the app or GUI contact list, encrypt there, and decrypt the resulting `.nkem` with the CLI.
- Encrypt with the CLI to a public key exported from the app; decrypt in the app or GUI. Android's exported `private.nkpr` is NKPR by its header. The CLI and desktop GUI accept it without renaming. After copying it to POSIX, run `chmod 600`.
- Menu and GUI steps are in the [Android documentation](../android/README.md) and the [desktop GUI documentation](../desktop/README.md).

The app reads and writes bytes through the system file picker and does not rewrite NKEM. Compare decrypted plaintext, not whether two `.nkem` files are identical.

## Relationship to the existing automated tests

`make -C linux test` runs on one Linux machine. It includes signed v5 encrypt/decrypt by default, SHA-256, `cmp`, a wrong password, tampered ciphertext, and a wrong private key, plus `test-multi-recipient` (in-process v4 tests) and `test-signature` (in-process v5 tests). It does not send files to another operating system.

The cross-platform fixtures in CI ([.github/workflows/ci.yml](../.github/workflows/ci.yml)) are separate. The historical Linux CLI reads only two-component keys, NKPR v1 and single-recipient NKEM v3, so only those formats cross between systems:

- `windows/tests/interop.py` `generate` builds an empty file and a `bytes(range(256)) * 512` v3 ciphertext, plus an NKPR, with the historical Linux CLI.
- The `windows` phase decrypts those files on Windows and encrypts replacements. The `macos` phase of `macos/tests/interop.py` does the same on Apple Silicon.
- The job "Windows and macOS to historical Linux interoperability" decrypts the Windows and macOS ciphertexts with the historical Linux CLI.

Both phases also write signed v5 with their own identity key and decrypt it with `--trust` and `--require-trusted`, but those v5 files never leave the system that wrote them.

Keys and the password in those scripts are public test fixtures. The scripts compare plaintext bytes. They do not require two `.nkem` files to be identical. The two-machine v4 and v5 steps on this page are not part of that job.
