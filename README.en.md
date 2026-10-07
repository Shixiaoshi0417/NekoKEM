[简体中文](README.md) | [English](README.en.md)

# NekoKEM

<p align="center">
  <img src="docs/icon-rounded.png" alt="NekoKEM project icon" width="160">
</p>

NekoKEM is an experimental post-quantum file encryption tool for learning the OpenSSL EVP API. It supports NKEM v3 (one recipient) and NKEM v4 (several recipients) Hybrid file containers.

| Mode | Key establishment | KDF | File encryption | Container |
|---|---|---|---|---|
| v3 hybrid (default) | X448 + ML-KEM-1024 | HKDF-SHA512 | AES-256-GCM | version 3 / algorithm id 3 |
| v4 multi-recipient | X448 + ML-KEM-1024 per recipient | HKDF-SHA512 | AES-256-GCM key wrap and data, HMAC-SHA512 header MAC | version 4 / algorithm id 4 |

The project does not implement cryptographic algorithms itself and does not depend on liboqs. **It has not undergone a security audit, must not be considered production-grade software, and must not be used to protect important or sensitive data.**

## Release v4.0.1

Android and every platform's CLI/GUI are version `4.0.1`; Core remains `4.0`. This is a security-fix release for the findings of a security review of v4.0.0 (no P0; one P1, five P2, one P3 and hardening suggestions). File formats and cryptographic parameters are unchanged:

- **Key generation no longer overwrites existing keys**: the CLI refuses when keys exist; rotate deliberately with `keygen --replace`. Android asks for confirmation before replacing keys, and the desktop GUI reports an error. Key pairs are now published atomically without replacing anything, rollback checks file identity first, and commits work under Android's SELinux policy, which forbids apps to create hard links.
- **Outputs cannot overwrite keys in use**: encryption and decryption refuse an output path that is a public or private key file used by the same operation.
- **NKPR private keys are recognized by content**: the `.enc` suffix no longer matters, so `private.nkpr` exported from Android works in the CLI and desktop GUI.
- **Android**: recreating a page (rotation, language change) no longer clears the cache of a running operation; password fields use the password keyboard without suggestions or personalized learning; importing a private key syncs its directory; plaintext and NKEM files have separate size limits.
- **Hardening**: GitHub Actions are pinned to commit hashes, the Gradle distribution is checked against its SHA-256, Windows reads sensitive files without stdio buffering, and AFL++ adds a full-decryption fuzzer.

This review is not an independent professional security audit. Every platform package keeps the shared Rust + Tauri 2 + Vue 3 + TypeScript interface with five-language detection and transitions. Pinned OpenSSL 4.0.3, cryptographic parameters, file security checks and streaming behavior are preserved. See the complete Chinese-then-English [v4.0.1 notes](release/v4.0.1.md) and [GitHub Release](https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v4.0.1); multi-recipient encryption is introduced in the [v4.0.0 notes](release/v4.0.0.md).

Android v4.0.1 retains the v3.2.0/v3.3.0 signing identity and supports an in-place update; backing up the public key, encrypted NKPR private key and its password is recommended. Only v3.1.x installations with the old signer need backup verification followed by uninstall/reinstall; uninstalling deletes app-private keys. NKEM v3, NKEM v4, NKPR v1, cryptographic parameters and fingerprint calculation are unchanged; NKEM v4 files need v4.0.0 or later to decrypt. NKEM v1/v2 are no longer supported.

| Platform and architecture | CLI / Android | GUI |
|---|---|---|
| Android 8.0+ ARM64 | [APK](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/app-release.apk) | Interface included in APK |
| Windows 10/11 x64 | [CLI ZIP](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-windows-x86_64.zip) | [GUI ZIP](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-Windows-GUI.zip) |
| macOS Apple Silicon arm64 | [CLI TAR.GZ](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-macos-arm64.tar.gz) | [GUI ZIP](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-macos-arm64-GUI.zip) · [DMG](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-macos-arm64-GUI.dmg) |
| Linux x86_64 | [Static CLI](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-x86_64.tar.gz) | [DEB](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-x86_64-GUI.deb) · [RPM](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-x86_64-GUI.rpm) · [Portable](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-x86_64-GUI.tar.gz) |
| Linux ARM64 / aarch64 | [Static CLI](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-aarch64.tar.gz) | [DEB](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-aarch64-GUI.deb) · [RPM](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-aarch64-GUI.rpm) · [Portable](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-aarch64-GUI.tar.gz) |

Verify each downloaded archive against [SHA256SUMS.txt](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/SHA256SUMS.txt), then verify its internal checksums. Linux GUI requires glibc 2.39+, GTK3 and WebKitGTK 4.1. macOS tests run on macOS 15 with an 11.0 deployment target. Platform installation steps and signing limitations follow below.

README uses a dedicated rounded display image. Android retains the existing square artwork with its white background; Windows CLI/GUI EXE icons retain the square artwork with the outer white area made transparent. Windows applications are not Authenticode signed.

## Multi-recipient encryption

One file can be encrypted for up to 64 recipients at once. Each recipient decrypts it with their own
private key; nobody else can. In the desktop GUI, tick several contacts on the Public-key contacts page
and choose Encrypt for selected, or tick recipients in the encryption page's recipient list. On Android,
tick Select as recipient on the Public-key contacts page and choose Encrypt for Selected Recipients. One
selected contact still writes NKEM v3; two or more write one NKEM v4 file. Every contact is re-read and its
fingerprint re-validated by Core before encryption. A missing, damaged or mismatched contact fails the
whole operation and is named; no recipient is skipped or replaced by another public key. On the CLI, list
several public keys after the usual command; see [Parameterized commands](#parameterized-commands).

NKEM v4 stores one X448 + ML-KEM-1024 encapsulation and wrapped file key per recipient and encrypts the
file data once. An HMAC-SHA512 header MAC ensures every recipient decrypts the same content. See
[`docs/NKEM-v4.md`](docs/NKEM-v4.md) for the format. Decryption on every platform detects v3 and v4
automatically. This feature is available from v4.0.0; NekoKEM v3.3.2 and earlier cannot open v4 files.

## Android public-key contacts

Import a recipient's public key and add a note in the drawer's Public-key contacts page,
then choose a saved entry when encrypting. Notes can be edited, entries deleted, and
duplicate fingerprints are stored once. Contacts persist in app-private storage. Each
use validates the key and fingerprint with the existing Core; an unavailable entry
requires an explicit new selection. Notes are labels; verify fingerprints with recipients.
Contacts are separate from local default keys. Cryptographic parameters and file formats
remain unchanged. See the [Android documentation](android/README.md#公钥通讯录)
for behavior and device tests. This release also supports Android predictive back:
secondary pages and the navigation drawer follow the back gesture; see
[predictive back](android/README.md#预测性返回). These features are available from v3.3.2; v3.3.1 and earlier do not
include them.

## Desktop GUI public-key contacts

The Rust + Tauri 2 + Vue 3 + TypeScript GUI shared by Windows, macOS and Linux adds a
Public-key contacts page. Save a recipient's public key from a file or pasted text, add or
edit a note, and delete entries after confirmation. The encryption page can choose a saved
contact, and a contact's Use for encryption action opens that page with it selected. Lists
and the selection show the full SHA-256 fingerprint; each fingerprint is stored once. Entries
live in a private `contacts` directory beside the language preference and contain only the
Core-normalized public key, fingerprint, source file name and note. Every encryption re-reads
the entry and re-validates its fingerprint with the existing Core. Missing, damaged,
unsafe-permission or mismatched entries fail and require a new selection; another public key
is never substituted. Notes are labels; verify full fingerprints with recipients. All five
interface languages are updated. Cryptographic parameters, file formats and secret clearing
remain unchanged. See the [desktop GUI documentation](desktop/README.md#public-key-contacts--公钥通讯录).
This feature is available from v3.3.2; v3.3.1 and earlier do not include it.

## Historical release v3.2.0

v3.2.0 introduced five interface languages, system-language selection and security fixes, and changed the Android release signer. Its migration instructions and historical build records remain in the [v3.2.0 notes](release/v3.2.0.md). Updating from v3.2.0 to v3.3.0 does not require another uninstall.

## Historical release v3.1.1

The Android App version is `3.1.1`; the Core version remains `3.1`, and the application ID is `com.shixiaoshi0417.nekokem`. See [`android/README.md`](android/README.md) for the Android project and build instructions. App version 3.1.1 does not change protocol numbering: the default file container remains **NKEM v3**, and the NKPR format is unchanged.

v3.1.1 adds Linux x86_64/aarch64 CLI packages without runtime shared-library dependencies, an automatic installation script, and GitHub Actions builds. The installer selects the package for the local architecture from the latest GitHub Release. It first verifies the archive against the release's top-level `SHA256SUMS.txt`, then verifies SHA-256 hashes of the files inside the package:

```sh
curl --fail --location --output install.sh \
    https://raw.githubusercontent.com/Shixiaoshi0417/NekoKEM/main/linux/install.sh
less install.sh
sh install.sh
```

The Linux CLI supports `nekokem --version`. This release patch does not change the Core API, cryptographic parameters, NKEM v3, or the NKPR format.

## Dependencies

Debian 13:

```sh
sudo apt install libssl-dev build-essential
```

OpenSSL 3.5 or newer is required because EVP support for ML-KEM begins with OpenSSL 3.5. Source builds and CI on all platforms pin OpenSSL 4.0.3 and verify the official source SHA-256.

To build the AFL++ fuzz harnesses, also install:

```sh
sudo apt install afl++
```

## Building

```sh
make -C linux
```

The build uses C17 and links OpenSSL `libcrypto`. Hybrid private-key protection uses Argon2id from the OpenSSL provider; no separate `libargon2` installation is needed. The default build enables `-Werror`, `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=3`, `-fPIE`, and `-pie`.

The executable is `linux/nekokem`. The `./nekokem` examples below assume the working directory is `linux/`, or another directory containing a copy of that executable.

GitHub Actions uses [CI](.github/workflows/ci.yml) and [Release](.github/workflows/release.yml). CI builds and runs all platform security, language, interoperability, performance and GUI regression checks on PRs and main pushes. Release builds Linux CLI archives and GUI installation/portable packages, a signed Android APK, Windows CLI/GUI packages and macOS arm64 packages through manual runs or `v*` tags. See the [build workflow documentation](release/README.md) for signing identity and artifact details.

## Windows CLI and GUI

v4.0.1 provides native Windows 10/11 x64 portable packages for `nekokem.exe` and `nekokem-gui.exe`. The CLI shares Linux's complete argument parser and five-option menu; double-clicking the EXE or running it without arguments opens the menu. See the [Windows CLI documentation](windows/README.md) for building, use and imported-key permissions, and the [filesystem design](windows/SECURITY-DESIGN.md) for its boundaries. Running the CLI requires no MSYS2 runtime or OpenSSL DLL; building uses MSYS2 UCRT64 and pinned OpenSSL 4.0.3.

The Rust + Tauri 2 + Vue 3 + TypeScript GUI calls the same C17 Core through a narrow interface, with native file dialogs, progress, cancellation, keyboard navigation and transitions that follow the system's reduced-motion preference. It requires Microsoft Edge WebView2 Runtime and the bundled `WebView2Loader.dll` beside the EXE; see the [Windows GUI documentation](desktop/README.md). Neither Windows application is Authenticode signed; the build separately verifies the Microsoft SDK loader's signature and provenance.

Windows accepts local fixed NTFS only, checks ownership and ACLs, and rejects network/device/ADS/reparse paths. Protocols, cryptographic parameters and 64 KiB streaming chunks are unchanged; Windows power-loss durability is not presented as verified POSIX directory fsync. Windows stores language preferences in OS LocalAppData/NekoKEM and reads the Windows display language when no locale environment override exists. The POSIX configuration paths below apply to the Linux and macOS CLI.

## macOS Apple Silicon CLI and GUI

v4.0.1 provides a native `arm64` CLI and Rust + Tauri 2 + Vue 3 + TypeScript GUI for M-series Apple Silicon Macs. Download `NekoKEM-macos-arm64.tar.gz`, `NekoKEM-macos-arm64-GUI.zip` (containing the `.app`) or `NekoKEM-macos-arm64-GUI.dmg` from this release. See the [macOS build and usage instructions](macos/README.md) and [desktop GUI documentation](desktop/README.md). Intel/Rosetta builds are not provided.

The CLI shares Linux's argument parser, five-option menu and language catalogs. The GUI uses system WKWebView and needs no Windows WebView2 Runtime or loader. Both statically link pinned OpenSSL 4.0.3 with its arm64 assembly acceleration retained; macOS system libraries and frameworks are still runtime dependencies. Builds set a macOS 11.0 deployment target. Native CI runs on `macos-15`; this does not establish compatibility on a physical macOS 11.0 installation or performance and compatibility for every M-series generation.

The macOS file layer preserves strict ownership, permissions, symbolic-link and hard-link checks, permits only empty or deny-only extended ACLs on protected files/directories, and checks APFS/HFS+ case and Unicode name aliases. Regular output files require `fsync` and `F_FULLFSYNC`; commits require parent-directory `fsync`, without silent fallback on failure. See the [macOS filesystem design](macos/SECURITY-DESIGN.md) for limits and test boundaries. Core 4.0 adds the NKEM v4 multi-recipient container; NKEM v3, NKPR v1, cryptographic parameters and 64 KiB streaming chunks are unchanged. Packages use ad-hoc signing only, without Developer ID or Apple notarization; this neither authenticates the publisher nor guarantees Gatekeeper acceptance.

## Linux GUI

The native Linux GUI supports `x86_64` and `aarch64` (ARM64), reusing Rust + Tauri 2 + Vue 3 + TypeScript, five-language automatic detection, native file dialogs, progress/cancellation, keyboard navigation and animations honoring reduced motion. Each architecture provides `NekoKEM-linux-<architecture>-GUI.deb`, `NekoKEM-linux-<architecture>-GUI.rpm` and `NekoKEM-linux-<architecture>-GUI.tar.gz`. On Ubuntu use `sudo apt install ./NekoKEM-linux-x86_64-GUI.deb`; on Fedora use `sudo dnf install ./NekoKEM-linux-x86_64-GUI.rpm`, replacing the architecture with `aarch64` for ARM64. The installed app opens from the system application menu; the portable package uses `NekoKEM-GUI.sh`. Icons directly reuse the existing square PNG artwork with transparent outer white areas; README keeps its rounded display image.

Builds and native CI use Ubuntu 24.04, with additional native RPM installation and startup in Fedora 44 containers. RPMs are built directly and declare shared-library capabilities as dependencies. Running requires system GTK3, WebKitGTK 4.1 and glibc 2.39 or newer; portable GUI packages retain these runtime dependencies. OpenSSL 4.0.3 is statically linked with hidden symbols to avoid interposing on WebKit's system TLS libraries, retaining assembly, threads, Fortify, stack protection, PIE and full RELRO. Linux permissions, authenticated commit and cancellation cleanup reuse the CLI/Core. Core 4.0 adds the NKEM v4 multi-recipient container; NKEM v3, NKPR v1, KDF parameters and 64 KiB streaming are unchanged. See [Linux installation and building](desktop/README.md#linux-installation-and-build--linux-安装与构建) and [Linux GUI security boundaries](desktop/LINUX-SECURITY.md).

The v4.0.1 Release includes every Linux GUI installation and portable package for both architectures, alongside the existing static CLI packages. Native window tests use X11 and do not establish testing on every Linux distribution or physical Wayland session.

## Language settings

Android, Windows/macOS/Linux GUI and the CLI support Simplified Chinese (`zh-CN`), Traditional Chinese (`zh-TW`), English (`en`), Japanese (`ja`), and Korean (`ko`). The default follows the system; unsupported languages fall back to English. Chinese CN/SG regions use Simplified Chinese; TW/HK/MO use Traditional Chinese. Other regions of en/ja/ko match their language. Android responds to system-language changes when following the system. The CLI and desktop GUI detect language on startup; the GUI can also refresh detection by choosing Follow system in its language selector.

On Android, open **Settings → Language** and select **Follow system** or a language. The choice persists and the interface refreshes. Android 13+ system application-language settings share the same preference; older systems use a private preference. Language changes are available after active file operations finish. Scrollable pages and dialogs accommodate longer text and larger fonts.

CLI examples:

```sh
./nekokem --lang ja --help
./nekokem --lang zh-TW encrypt hybrid test.txt test.nkem keys/public.key
./nekokem --set-lang ko
./nekokem --set-lang system
./nekokem --lang system --help
```

`--lang` applies to one command. On Linux and macOS, `--set-lang` saves the default in `$XDG_CONFIG_HOME/nekokem/language`, or `$HOME/.config/nekokem/language` when XDG_CONFIG_HOME is missing or relative. The configuration file has mode `0600`; no root permission is required. Priority is `--lang` → saved preference → `LC_ALL` → `LC_MESSAGES` → `LANG` → platform default (English on Linux; preferred language from CoreFoundation on macOS). `system` restores automatic detection; invalid or damaged preferences safely fall back to system detection. Common POSIX locales such as `zh_CN.UTF-8` and `ja_JP.UTF-8` are accepted for system detection. Explicit ASCII or non-UTF-8 terminal settings make CLI messages fall back to English; native macOS applications without locale environment variables use UTF-8. Global language options precede the command; subsequent file arguments are preserved verbatim. Use `--` to explicitly end global option parsing. Language configuration and help never request interactive language input.

Protocol identifiers, cryptographic algorithm names, CLI options, environment variable names, and `--version` output are unchanged. Upstream OpenSSL/OS diagnostic details retain their original text. These language options are available starting with v3.2.0; older binaries may not support them.

## Directory structure

Interactive mode consistently uses these directories:

```text
plaintext/
  plaintext files

encrypted/
  NKEM ciphertext files

keys/
  key files
```

`plaintext/`, `encrypted/`, and `keys/` are created with mode `0700` when an operation needs them. For existing paths, the program rejects symbolic links, non-directories, paths not owned by the current user, and permissions other than `0700`. The compatible parameterized commands still allow a complete output path to be specified as `output_file`.

## Default interactive mode

Run directly:

```sh
./nekokem
```

With English selected, the menu displays:

```text
====================
      NekoKEM
====================

1. Generate keys
2. Encrypt file
3. Decrypt file
4. View public-key fingerprint
5. Exit
```

Interactive mode always uses NKEM v3 Hybrid. The Hybrid algorithms remain X448 + ML-KEM-1024, HKDF-SHA512, and AES-256-GCM.

### Generating keys

Selecting “Generate keys” ensures a `keys/` directory with mode `0700` exists in the working directory and generates:

- `keys/public.key`: plaintext public keys, X448 followed by ML-KEM-1024;
- `keys/private.key.enc`: an encrypted NKPR container containing both complete private-key PEM blocks, with mode `0600`.

Before generation, the program requests and confirms a private-key protection password; terminal echo is disabled during both inputs. It does not create plaintext `private.key`.

Key generation never overwrites keys: if `keys/public.key` or `keys/private.key.enc` already exists, the program refuses before asking for a password and leaves both files unchanged. To change keys, first back up the old private key (files encrypted for the old public key can only be decrypted with it), then use `keygen --replace` below.

### Encrypting files

Enter a public-key file path or paste two PEM public-key blocks, then enter the source file path. The program ensures an `encrypted/` directory with mode `0700` exists in the working directory, creating it if necessary.

Only the source filename is used for the output in `encrypted/`, with `.nkem` appended:

```text
plaintext/test.jpg -> encrypted/test.jpg.nkem
```

### Decrypting files

Enter a private-key file path or paste two compatible plaintext PEM private-key blocks. When the key file contains an NKPR container (recognized by its header, whatever the extension, for example `private.nkpr` exported by Android), the program automatically disables terminal echo and prompts for a password, then authenticates, decrypts, and parses NKPR in memory. The decrypted PEM is not written to disk. Legacy plaintext PEM private keys such as `private.key` need no password.

Terminal echo is also temporarily disabled when pasting legacy private-key contents. The internal temporary key file has mode `0600` and is deleted after the operation.

The input file must end in `.nkem`. The program ensures a `plaintext/` directory with mode `0700` exists in the working directory, creating it if necessary. It uses only the container filename, removes `.nkem`, and restores the file into `plaintext/`:

```text
encrypted/test.jpg.nkem -> plaintext/test.jpg
```

Decryption still uses atomic output. The destination is committed only after Hybrid key decapsulation and AES-GCM authentication both succeed; failure does not leave unauthenticated plaintext in the destination.

### Public-key fingerprints

“View public-key fingerprint” accepts a public-key file or pasted contents and displays an uppercase, colon-separated SHA-256 fingerprint. Its input consists of the two public-key DER SubjectPublicKeyInfo encodings with a domain-separation string, ordered X448 then ML-KEM-1024; each encoding is preceded by a 4-byte big-endian length. The same keypair therefore has the same fingerprint regardless of PEM line endings.

## Parameterized commands

Parameterized commands support scripts and development tests. Default keygen matches interactive mode and generates password-protected Hybrid keys:

```sh
./nekokem keygen
```

`keygen hybrid` remains an explicit compatible alias with the same meaning:

```sh
./nekokem keygen hybrid
```

Both forms prompt for the password twice without placing it in command-line arguments. They output `keys/public.key` and `keys/private.key.enc`, and refuse without overwriting anything if either key file already exists.

To rotate keys deliberately, back up the old private key first and pass `--replace`. The command warns that files encrypted for the old public key can only be decrypted with the old private key, and offers Ctrl+C before asking for a password:

```sh
./nekokem keygen --replace
```

Default v3 hybrid encryption and decryption:

```sh
./nekokem encrypt hybrid test.txt encrypted/test-v3.nkem keys/public.key
./nekokem decrypt hybrid encrypted/test-v3.nkem output.txt keys/private.key.enc
```

Listing two or more public keys after the usual encryption command writes one NKEM v4 file that every recipient can decrypt; an invalid or repeated public key creates no output:

```sh
./nekokem encrypt hybrid test.txt encrypted/team.nkem alice.key bob.key carol.key
```

Hybrid decrypt prompts once for a password when the key file contains NKPR; it decides by the file header, not the extension. For compatibility with existing deployments, the command still accepts legacy plaintext `private.key` containing X448 and ML-KEM-1024 PEM blocks.

The output path cannot be a key file the operation uses: decryption cannot write over the private key, and encryption cannot write over any public key. Files are compared by identity rather than by path text, so aliases such as `./keys/../keys/private.key.enc` are refused too, and the key file is left unchanged.

Commands can use the corresponding PEM keys from other locations:

```text
./nekokem encrypt hybrid input_file output_file public.key
./nekokem decrypt hybrid input_file output_file private.key.enc
```

Inputs must be regular files. The tool uses chunked I/O rather than loading an entire file into memory. It first writes output to a temporary file with mode `0600` in the destination directory and calls `fsync`. Only successful encryption or GCM tag verification permits an atomic rename to the destination; the parent directory is then `fsync`ed. Authentication failure, a wrong key, cancellation, or container parsing failure does not commit the decrypted destination.

## NKPR encrypted private-key format

NKPR is a private-key storage format independent of the NKEM file container. NKEM v3 does not change NKPR version 1. NKPR currently fixes these parameters:

- Argon2id: 64 MiB (`65536` KiB), 3 iterations, parallelism 4, Argon2 version 1.3;
- 32-byte random salt;
- 32-byte derived key;
- AES-256-GCM, a 12-byte random nonce, and a 16-byte authentication tag.

The fixed header is 36 bytes; all multi-byte integers are big-endian:

| Offset | Length | Field |
|---:|---:|---|
| 0 | 4 | magic: ASCII `NKPR` |
| 4 | 1 | container version: `1` |
| 5 | 1 | KDF id: `1`, Argon2id |
| 6 | 1 | cipher id: `1`, AES-256-GCM |
| 7 | 1 | flags: `0` |
| 8 | 4 | Argon2 memory cost: `65536` KiB |
| 12 | 4 | Argon2 iterations: `3` |
| 16 | 4 | Argon2 parallelism: `4` |
| 20 | 4 | Argon2 version: `0x13` |
| 24 | 2 | salt length: `32` |
| 26 | 1 | nonce length: `12` |
| 27 | 1 | tag length: `16` |
| 28 | 8 | encrypted PEM length |

The header is followed by:

```text
32-byte salt || 12-byte nonce || encrypted hybrid private PEM || 16-byte tag
```

The complete `header || salt || nonce` is AES-GCM AAD. The reader strictly validates the version, algorithms, parameters, lengths, and GCM tag. An incorrect password or any change to authenticated fields or ciphertext causes failure. Decryption buffers always stay in memory, including data produced before tag verification, and are cleared on failure.

## NKEM v3 hybrid file format

v3 uses X448, ML-KEM-1024, combined shared secrets, and HKDF-SHA512, separating the HKDF salt from the AES-GCM nonce. For each file, `RAND_bytes` independently generates a 32-byte salt and a 12-byte nonce. Both are included in GCM AAD together with the fixed header, X448 ephemeral public key, and ML-KEM ciphertext. See [`docs/NKEM-v3.md`](docs/NKEM-v3.md) for the full layout. `nekokem_encrypt_file()` writes v3; `nekokem_decrypt_file()` accepts v3 and v4.

## NKEM v4 multi-recipient file format

v4 encrypts the file data once under a random 32-byte file key and stores one entry per recipient, up to 64: an X448 ephemeral public key, an ML-KEM-1024 ciphertext and the file key wrapped with AES-256-GCM. The wrap key is derived with HKDF-SHA512 from that recipient's two shared secrets and binds the X448 ephemeral and recipient public keys. An HMAC-SHA512 header MAC covers the header, salt and every recipient entry and commits to the file key, so all recipients decrypt the same content. Entries carry no fingerprint; decryption tries every entry, and neither its error nor its timing shows which entry is the key's. See [`docs/NKEM-v4.md`](docs/NKEM-v4.md) for the full layout. `nekokem_encrypt_file_multi_with_progress()` writes v4 for two or more public keys and v3 for one.

## Security implementation

### OpenSSL EVP

- X448, ML-KEM-1024, HKDF, Argon2id, and AES-256-GCM use OpenSSL EVP/provider APIs. The project neither implements cryptographic algorithms nor introduces liboqs.
- Every `EVP_PKEY`, `EVP_PKEY_CTX`, `EVP_CIPHER_CTX`, `EVP_KDF`, `EVP_KDF_CTX`, `EVP_MD_CTX`, and `BIO` has unified success/failure cleanup paths.
- OpenSSL error paths repeatedly call `ERR_get_error()` to retrieve and clear the current thread's error queue.
- Private keys remain inside opaque OpenSSL `EVP_PKEY`/provider objects, without exporting raw private-key copies, and are released with `EVP_PKEY_free()`.

### Clearing sensitive data

- `core/src/secure_mem.c` provides `secure_mem_clear()` and `secure_free()`, using `OPENSSL_cleanse()` and `OPENSSL_clear_free()`, respectively. It does not use `memset()` for secrets, which dead-store elimination could remove.
- ML-KEM/X448 shared secrets are cleared and released immediately after HKDF, rather than retained until the file operation ends.
- The Hybrid HKDF input `x448_secret || mlkem_secret` is the only combined copy required by the protocol; it is cleared immediately after releasing the KDF context.
- AES keys and AES chunk buffers containing plaintext are cleared on every success and failure exit.
- Private-key passwords, confirmation copies, Argon2id-derived keys, and complete Hybrid PEM in memory are cleared on every success and failure exit. Passwords are released immediately after successful PEM loading and parsing.
- KEM failure paths retain the original allocation capacity so the entire allocated secret buffer is cleared even if OpenSSL modifies the output length.
- Private keys are opened with `O_NOFOLLOW|O_CLOEXEC` and checked before reading: current-user ownership, mode `0600`, a nonempty regular file, and one hard link. PEM is parsed through one bounded OpenSSL buffer and a memory BIO referencing that buffer, then immediately cleared and released. Sensitive-file streams and atomic-output streams disable stdio buffering to reduce uncontrolled copies.

### Build protections

- All warnings are errors: `-Werror`.
- Stack corruption detection: `-fstack-protector-strong`.
- libc bounds hardening: `-D_FORTIFY_SOURCE=3`.
- Position-independent executables: compile with `-fPIE`, link with `-pie`.
- Existing `-Wall`, `-Wextra`, `-Wconversion`, `-Wshadow`, `-Wformat=2`, and `-Wstrict-prototypes` remain enabled.

### Resource lifecycle management

- Dynamic resources have a single owner and `goto cleanup` paths; ownership transfer immediately clears the source pointer.
- Private-key objects are released immediately after decapsulation, shared secrets are cleared immediately after KDF, and AES keys live only until file encryption/decryption finishes.
- Output is still written to a temporary file in the same directory. Contents are `fsync`ed before rename, and the parent directory after rename. Authentication, parsing, cancellation, or I/O failure closes and removes temporary files.
- Public/private key generation uses one rollback-capable transaction. Both files are written and `fsync`ed before publishing. If the second rename or directory `fsync` fails, old public/private keys are restored; when previously absent, both new files are removed, avoiding an update to only one key.
- On POSIX, paired key commits hold interprocess locks in their parent directories through rollback and cleanup. Rollback checks the published inode before removing a file, preserving a replacement made by another writer. The mode `0600` lock file `.nekokem-pair.lock` remains in each directory; do not delete it while operations are running.
- Hybrid keygen writes both private-key PEM blocks directly into an OpenSSL memory BIO, encrypts them into an NKPR temporary file, and commits it together with the public key. There is no plaintext private-key output or temporary file.
- The CLI currently uses a path-based Core API. Pasted PEM therefore uses a `0600`, `O_NOFOLLOW|O_CLOEXEC` temporary file inside an exclusive `0700` directory under `/tmp`, and deletes both on all return paths. Direct memory BIO use would require a new internal Core adapter; memfd's `/proc/self/fd` paths conflict with the private-key `O_NOFOLLOW` policy. This stage does not change the public Core API; a separate future API design can eliminate the temporary path.
- `secure_free()` is used only for sensitive buffers allocated by `OPENSSL_malloc()`; ordinary path strings and public metadata are released by their matching normal allocators.

## Performance measurements

[Large-file baseline and reproduction](performance/README.md) records the
v3.3.1 released Linux x86_64 CLI's raw 1 GiB / 8 GiB samples, CPU time, memory
and I/O accounting. `scripts/profile_large_files.py` uses disposable
password-protected keys, verifies SHA-256 every round, and keeps at most two
large files. Use `scripts/benchmark_throughput.py` for paired version comparisons
on Linux/Windows. Speed depends on hardware, filesystems and cache state.

## Automated tests

```sh
make -C linux test
```

The tests run in temporary directories:

- GCC `-fanalyzer` first checks resource issues such as leaks, null pointers, uninitialized reads, and double frees.
- A temporary binary with `-fsanitize=undefined` and recovery disabled covers success, missing input, invalid output directories, truncated containers, tampered ciphertext, invalid private keys, and wrong private keys.
- Automatically generated NKEM/NKPR truncations, incorrect lengths, invalid versions, invalid algorithm IDs, random bytes, and oversized fields call the production quiet parser under UBSan.
- CLI ordinary input and pasted PEM each have line-length limits; oversized input is fully consumed before rejection, so no remaining bytes feed the next prompt.
- Checks cover existing `0700` private-directory type, ownership, and permissions, plus private-key `0600`, ownership, regular-file, symbolic-link, and hard-link restrictions.
- Fault injection enabled only in test builds covers short writes, ENOSPC, file/directory `fsync`, the second rename, public/private rollback, and temporary/backup cleanup.
- Hybrid encrypted private-key keygen, correct-password decryption, SHA-256, and `cmp`.
- Default v3 encryption/decryption round trips and rejection of containers with legacy version numbers.
- Wrong passwords and tampered NKPR must fail authentication without producing a plaintext destination.
- Hybrid keygen must leave no plaintext `private.key`, PEM contents, or atomic temporary files.
- Modified hybrid ciphertext must fail authentication without producing a plaintext destination.
- Wrong hybrid private keys must fail authentication without producing a plaintext destination.
- `keys/private.key.enc` must have magic `NKPR` and mode `0600`.
- Default menus, interactive Hybrid key generation, and SHA-256 fingerprints.
- Interactive encryption automatically creates `encrypted/` with mode `0700` and maps absolute input paths to `encrypted/<filename>.nkem`.
- Interactive decryption automatically creates `plaintext/` with mode `0700` and restores `encrypted/<filename>.nkem` to `plaintext/<filename>`.
- SHA-256 and `cmp` also check plaintext restored in interactive mode.
- Pseudoterminal sentinel tests separately confirm echo is disabled for pasted private keys and protection passwords; test logs have mode `0600`.
- Five-language resource/catalog completeness, format parameter consistency, Locale/config/flag priority, persistence, safe fallback, noninteractive help, and localized errors. The document checker verifies bilingual navigation and relative links.

Test materials are deleted afterward. Android JVM and actual API 26/35 device instrumentation checks run in CI; assembling a test APK alone does not count as device-test success. Device runs retain JNI/SAF integration coverage and exercise language switching, Activity recreation, process restart, system application-language synchronization, accessibility, dark mode, and increased font scale.

## AFL++ fuzzing

```sh
make -C linux fuzz-build
```

This target builds with `afl-clang-fast` and UBSan:

- `core/fuzz/bin/fuzz_nkem`: parses only NKEM v3 and v4 headers and total container lengths;
- `core/fuzz/bin/fuzz_nkpr`: parses only NKPR headers, parameters, and total container lengths;
- `core/fuzz/bin/fuzz_decrypt`: fully decrypts the input, covering X448 and ML-KEM-1024 decapsulation, v4 entry unwrapping and header MAC, streaming AES-GCM authentication, and the atomic output commit and rollback.

All three harnesses accept an `argv[1]` file path. The two parser harnesses reject inputs above 2 MiB and do not execute decapsulation, Argon2id, AES-GCM, or plaintext output; their seeds in `core/fuzz/seeds/` are zero-filled structural samples without passwords, private keys, or real sensitive data, and several truncated samples are included. Seeds for `fuzz_decrypt` are generated at every build: a throwaway plaintext-PEM test key and genuine v3, empty v3 and two-recipient v4 containers for it, so mutations reach trial decryption, the MAC and payload authentication. The key protects nothing and is never committed. See `core/fuzz/README.md` for AFL commands.

## Security boundaries

- Hybrid private keys are now password-protected, but security still depends on strong, unique passwords and operating-system protection of process memory, terminals, and files.
- Container metadata such as file length and the selected algorithms is not confidential. NKEM v4 also reveals the number of recipients, but not who they are.
- NKEM does not authenticate senders. v4 recipients share the file key, so any recipient can create a new file reusing the same recipient list; the list does not prove who sent a file.
- v4 hides which entry belongs to which key, not whether a key can decrypt the file: anyone who can submit the file for decryption and see the result learns that from the unmodified file.
- The X448/ML-KEM combination and this project's KDF/AAD binding are experimental designs, not a standardized hybrid KEM.
- Project-internal parser fuzzing has been performed, but there has been no independent third-party audit or broad interoperability testing.
- This implementation is not a substitute for mature, audited file encryption protocols and key management systems.

## Bug reports and feature requests

Use [GitHub Issues](https://github.com/Shixiaoshi0417/NekoKEM/issues) for ordinary bugs, compatibility issues, and feature requests. Avoid attaching private keys, passwords, sensitive plaintext, or other personal information.

## Security notice and vulnerability reports

NekoKEM uses modern public cryptographic algorithms but has not yet undergone an independent professional security audit. It is currently not recommended for applications requiring formal compliance certification, or for protecting high-value data that must remain confidential long-term.

Do not report security vulnerabilities in public GitHub Issues. Follow [`SECURITY.en.md`](SECURITY.en.md) and report privately to [shixiaoshi@shixiaoshi0417.com](mailto:shixiaoshi@shixiaoshi0417.com).

## License

This project is licensed under the [Apache License 2.0](LICENSE).
