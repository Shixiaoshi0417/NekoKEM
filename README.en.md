[简体中文](README.md) | [English](README.en.md)

# NekoKEM

<p align="center">
  <img src="icon.png" alt="NekoKEM project icon" width="160">
</p>

NekoKEM is an experimental post-quantum file encryption tool for learning the OpenSSL EVP API. It supports only NKEM v3 Hybrid file containers.

| Mode | Key establishment | KDF | File encryption | Container |
|---|---|---|---|---|
| v3 hybrid (default) | X448 + ML-KEM-1024 | HKDF-SHA512 | AES-256-GCM | version 3 / algorithm id 3 |

The project does not implement cryptographic algorithms itself and does not depend on liboqs. **It has not undergone a security audit, must not be considered production-grade software, and must not be used to protect important or sensitive data.**

## Release v3.2.0

Android and Linux CLI versions are `3.2.0`; Core remains `3.1`. See the [release notes](release/v3.2.0.md) for five interface languages, system-language selection and security fixes. **The Android signing key has changed: export the public key and encrypted NKPR private key, verify the backup and retain the private-key password before uninstalling and reinstalling. Uninstalling removes app-private keys.** NKEM v3 and NKPR v1 formats are unchanged; NKEM v1/v2 are no longer supported.

## Release v3.1.1

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

To build the AFL++ parser fuzz harnesses, also install:

```sh
sudo apt install afl++
```

## Building

```sh
make -C linux
```

The build uses C17 and links OpenSSL `libcrypto`. Hybrid private-key protection uses Argon2id from the OpenSSL provider; no separate `libargon2` installation is needed. The default build enables `-Werror`, `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=3`, `-fPIE`, and `-pie`.

The executable is `linux/nekokem`. The `./nekokem` examples below assume the working directory is `linux/`, or another directory containing a copy of that executable.

GitHub Actions uses [CI](.github/workflows/ci.yml) and [Release](.github/workflows/release.yml). CI builds and runs all platform security, language, interoperability, performance and GUI regression checks on PRs and main pushes. Release builds Linux archives, a signed Android APK and Windows CLI/GUI packages through manual runs or `v*` tags. See the [build workflow documentation](release/README.md) for signing identity and artifact details.

## Windows CLI (test builds)

A native Windows 10/11 x64 `nekokem.exe` is available. See the
[Windows CLI documentation](windows/README.md) for building, use and imported-key
permissions, and the [filesystem design](windows/SECURITY-DESIGN.md) for boundaries.
Running requires no MSYS2 runtime or OpenSSL DLL; building uses MSYS2 UCRT64 and
pinned OpenSSL 4.0.3. Windows CI provides test builds; these are not added to the
existing v3.2.0 Release and do not use Android signing material.

The first version accepts local NTFS only and rejects network/device/ADS/reparse
paths. Protocols, cryptographic parameters and 64 KiB streaming remain unchanged;
Windows power-loss durability is not presented as verified POSIX directory fsync.
Windows stores language preferences in OS LocalAppData/NekoKEM and reads the OS user
language when no locale environment override exists. The POSIX configuration paths
and terminal fallback rules below apply to the Linux CLI.

## Language settings

Android and the CLI support Simplified Chinese (`zh-CN`), Traditional Chinese (`zh-TW`), English (`en`), Japanese (`ja`), and Korean (`ko`). The default follows the system; unsupported languages fall back to English. Chinese CN/SG regions use Simplified Chinese; TW/HK/MO use Traditional Chinese. Other regions of en/ja/ko match their language. Following the system responds to system-language changes.

On Android, open **Settings → Language** and select **Follow system** or a language. The choice persists and the interface refreshes. Android 13+ system application-language settings share the same preference; older systems use a private preference. Language changes are available after active file operations finish. Scrollable pages and dialogs accommodate longer text and larger fonts.

CLI examples:

```sh
./nekokem --lang ja --help
./nekokem --lang zh-TW encrypt hybrid test.txt test.nkem keys/public.key
./nekokem --set-lang ko
./nekokem --set-lang system
./nekokem --lang system --help
```

`--lang` applies to one command. `--set-lang` saves the default in `$XDG_CONFIG_HOME/nekokem/language`, or `$HOME/.config/nekokem/language` when XDG_CONFIG_HOME is missing or relative. The configuration file has mode `0600`; no root permission is required. Priority is `--lang` → saved preference → `LC_ALL` → `LC_MESSAGES` → `LANG` → English. `system` restores automatic detection; invalid or damaged preferences safely fall back to system detection. Common POSIX locales such as `zh_CN.UTF-8` and `ja_JP.UTF-8` are accepted for system detection. On an ASCII or non-UTF-8 terminal, CLI messages fall back to English. Global language options precede the command; subsequent file arguments are preserved verbatim. Use `--` to explicitly end global option parsing. Language configuration and help never request interactive language input.

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

### Encrypting files

Enter a public-key file path or paste two PEM public-key blocks, then enter the source file path. The program ensures an `encrypted/` directory with mode `0700` exists in the working directory, creating it if necessary.

Only the source filename is used for the output in `encrypted/`, with `.nkem` appended:

```text
plaintext/test.jpg -> encrypted/test.jpg.nkem
```

### Decrypting files

Enter a private-key file path or paste two compatible plaintext PEM private-key blocks. When the path ends in `.enc`, the program automatically disables terminal echo and prompts for a password, then authenticates, decrypts, and parses NKPR in memory. The decrypted PEM is not written to disk. Existing `private.key` files are still read using the original plaintext PEM logic.

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

Both forms prompt for the password twice without placing it in command-line arguments. They output `keys/public.key` and `keys/private.key.enc`.

Default v3 hybrid encryption and decryption:

```sh
./nekokem encrypt hybrid test.txt encrypted/test-v3.nkem keys/public.key
./nekokem decrypt hybrid encrypted/test-v3.nkem output.txt keys/private.key.enc
```

Hybrid decrypt automatically prompts once for a password when it sees `.enc`. For compatibility with existing deployments, the command still accepts legacy plaintext `private.key` containing X448 and ML-KEM-1024 PEM blocks.

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

v3 uses X448, ML-KEM-1024, combined shared secrets, and HKDF-SHA512, separating the HKDF salt from the AES-GCM nonce. For each file, `RAND_bytes` independently generates a 32-byte salt and a 12-byte nonce. Both are included in GCM AAD together with the fixed header, X448 ephemeral public key, and ML-KEM ciphertext. See [`docs/NKEM-v3.md`](docs/NKEM-v3.md) for the full layout. `nekokem_encrypt_file()` writes v3; `nekokem_decrypt_file()` accepts only v3.

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
- Hybrid keygen writes both private-key PEM blocks directly into an OpenSSL memory BIO, encrypts them into an NKPR temporary file, and commits it together with the public key. There is no plaintext private-key output or temporary file.
- The CLI currently uses a path-based Core API. Pasted PEM therefore uses a `0600`, `O_NOFOLLOW|O_CLOEXEC` temporary file inside an exclusive `0700` directory under `/tmp`, and deletes both on all return paths. Direct memory BIO use would require a new internal Core adapter; memfd's `/proc/self/fd` paths conflict with the private-key `O_NOFOLLOW` policy. This stage does not change the public Core API; a separate future API design can eliminate the temporary path.
- `secure_free()` is used only for sensitive buffers allocated by `OPENSSL_malloc()`; ordinary path strings and public metadata are released by their matching normal allocators.

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

## AFL++ parser fuzzing

```sh
make -C linux fuzz-build
```

This target builds with `afl-clang-fast` and UBSan:

- `core/fuzz/bin/fuzz_nkem`: parses only NKEM v3 headers and total container lengths;
- `core/fuzz/bin/fuzz_nkpr`: parses only NKPR headers, parameters, and total container lengths.

Both harnesses accept an `argv[1]` file path, reject inputs above 2 MiB, and do not execute decapsulation, Argon2id, AES-GCM, or plaintext output. Valid seeds in `core/fuzz/seeds/` are zero-filled structural samples without passwords, private keys, or real sensitive data; several truncated samples are included. See `core/fuzz/README.md` for AFL commands.

## Security boundaries

- Hybrid private keys are now password-protected, but security still depends on strong, unique passwords and operating-system protection of process memory, terminals, and files.
- Container metadata such as file length and the selected algorithms is not confidential.
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
