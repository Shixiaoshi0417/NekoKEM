# NekoKEM Desktop GUI

The desktop apps call the same C17 Core as the CLI through one small native
library, the bridge (`desktop/bridge`): Rust around the Core, the contact store
and the update check.

| System | App | Packages |
|---|---|---|
| Windows 10/11 x64 | Compose Multiplatform (`desktop-compose`) | `NekoKEM-Windows-GUI.msi` installer, `NekoKEM-Windows-GUI.zip` folder |
| Linux x86_64 and ARM64 | Compose Multiplatform (`desktop-compose`) | `NekoKEM-linux-<arch>-GUI.deb`, `.rpm`, `.tar.gz` folder |
| macOS, Apple Silicon | SwiftUI (`macos/gui`) | `NekoKEM-macos-arm64-GUI.zip`, `.dmg` |

The Windows and Linux app offers the Android app's two styles, MIUIX (the
default) and Material 3 Expressive, chosen in Settings. It carries its own Java
runtime; nothing else needs installing. The macOS app is native SwiftUI in
Apple's Liquid Glass design on macOS 26 and runs on macOS 13 or newer; Intel
Macs are not supported. No package is signed by a developer certificate (the
macOS app is ad-hoc signed), so verify downloads with `SHA256SUMS.txt`.

Windows 与 Linux 使用 Compose Multiplatform 应用，提供 MIUIX（默认）和 Material 3
Expressive 两种风格，自带 Java 运行时；macOS 使用原生 SwiftUI 应用，在 macOS 26
上采用液态玻璃设计，最低支持 macOS 13，仅支持 Apple Silicon。所有桌面应用通过同一个
桥接库调用与 CLI 相同的 C17 Core。安装包均未使用开发者证书签名，请用
`SHA256SUMS.txt` 校验。

## Features / 功能

- Generate an encrypted NKPR private key and public key; encrypt NKEM v3 files, or one
  NKEM v4 file for several saved contacts; decrypt both; display public-key fingerprints.
  密钥生成、文件加解密（含多人加密）、公钥指纹。
- Choose paths using native file dialogs or paste both PEM key blocks.
  支持系统文件选择器及粘贴 PEM 密钥。
- Display progress and cancel file operations safely. Show errors and preserve
  existing output on failure/cancellation through the same Core checks as the CLI.
- Automatically detect Windows/macOS display language or Linux locale and share the CLI's private saved
  language preference. English, simplified/traditional Chinese, Japanese and Korean.
- Save recipients' public keys with notes and choose one when encrypting; see
  [Public-key contacts](#public-key-contacts--公钥通讯录). 公钥通讯录与加密时选择已保存公钥。

Production cryptography stays in the shared C17 Core. The bridge validates
requests, serializes Core calls, owns zeroizing secrets, bridges progress and
cancellation and creates protected temporary pasted-key files. The apps reach
files only through it.

Text on Windows and Linux is set in MiSans (MIUIX style), Google Sans Flex
(Material style) and JetBrains Mono (fingerprints); the fonts ship with the app
(`desktop-compose/src/main/resources/fonts`). Chinese, Japanese and Korean text
uses the system's fonts; the Linux packages depend on Noto Sans CJK.
界面文字使用内置的 MiSans、Google Sans Flex 和 JetBrains Mono；中日韩文字使用系统字体，
Linux 安装包依赖 Noto Sans CJK。

## Public-key contacts / 公钥通讯录

The **Contacts** page saves recipients' public keys.
Choose a key file or paste both `PUBLIC KEY` blocks, optionally add a note of up to 512
characters, then save. Notes can be edited and entries deleted after an inline
confirmation. On **Encrypt file**, choose **Saved contact** and explicitly select a
recipient; a contact's **Use for encryption** action opens that page with it selected.
Lists and the selection show the full SHA-256 fingerprint, the list also shows the source
file name; the multi-select list also shows every full fingerprint and wraps it without truncating it. Source names drop control characters, Unicode line/paragraph separators and every Unicode format character (bidirectional and zero-width controls among them); a zero-width joiner or non-joiner stays only between two visible non-ASCII characters, as emoji and some scripts need. A completed encryption names the recipient and its fingerprint. Notes are labels only: verify fingerprints with recipients through a
trusted channel. Decryption and fingerprints keep their file/paste key sources.

Contacts live beside the shared language preference, in `LocalAppData/NekoKEM/contacts`
on Windows and `$XDG_CONFIG_HOME/nekokem/contacts` (or `$HOME/.config/nekokem/contacts`)
on Linux and macOS. Both NekoKEM directories must be private (`0700`, owner-only NTFS
ACL, no allowing macOS ACL entries). Each fingerprint has one JSON v1 record holding the
fingerprint, a sanitized source file name, the note and the public key exported by Core's
`nekokem_export_public_key`: only the two validated public components, never other PEM
blocks or text from the source. Import compares Core fingerprints of the source and the
normalized key. Records are written with Core's atomic private output (`0600` or
owner-only ACL, sync, rename) and read with Core's sensitive-file checks (regular file,
current owner, one hard link, no symbolic link/reparse point, private mode/ACL, 64 KiB).
Importing a fingerprint whose entry Core still verifies points to that entry instead of
replacing its note; explicitly importing the same key again replaces a damaged entry or one
whose stored key no longer matches its fingerprint. Up to 500 entries are
kept. No private keys, passwords, permissions, network access or browser storage are added (the only network request is the update check below).

Every encryption re-reads the selected record and writes an operation-specific private
snapshot using the pasted-key staging. Core parses that snapshot and must reproduce the
recorded fingerprint; encryption then reads only that snapshot, which is removed after
success, failure or cancellation. A missing, damaged, unsafe-permission or mismatched entry
fails with a specific error that names the contact, deselects it and requires a new explicit
choice. A request with contacts cannot also carry a key path or pasted key, and no other key
is ever substituted. Unreadable entries are counted but never listed or used. Contact changes
run exclusively with Core operations, and closing waits for an in-progress record update.
Contacts are user configuration rather than package contents; keep original public-key
backups. NKEM v3, NKPR v1 and their cryptographic parameters are unchanged.

### Several recipients / 多人加密

Several contacts can share one encrypted file. Tick contacts on the contacts page, where a
selection bar offers **Encrypt for selected** and **Clear selection**, or tick them in the
encryption page's **Recipients** list. One recipient still writes NKEM v3; two to 64
recipients write one [NKEM v4](../docs/NKEM-v4.md) file that each selected recipient decrypts
with their own private key, and nobody else can. The footer shows the format that will be
written. Every selected record is staged and verified exactly as above, in selection order,
before Core encrypts; if any one fails, nothing is encrypted, the error names that contact
and only it is deselected. Duplicate contacts, more than 64 recipients and combined key
sources are refused. Decryption detects v3 and v4 automatically; NekoKEM v3.3.2 and earlier
cannot open v4 files.

可以把同一个文件加密给多位联系人：在公钥通讯录页面勾选联系人后点击“加密给所选联系人”
（也可“清除选择”），或在加密页的“接收方”列表中勾选。选择一位时仍写出 NKEM v3；选择 2 至 64 位时
写出一个 [NKEM v4](../docs/NKEM-v4.md) 文件，每位所选接收方都能用自己的私钥解密，其他人无法解密，
页面底部会显示将写出的格式。每位联系人都按选择顺序、按上述规则重新读取并校验后才加密；任何一位失败时
不加密任何内容，错误提示指出该联系人并只取消选择它。重复联系人、超过 64 位接收方以及与其他公钥来源混用
都会被拒绝。解密会自动识别 v3 与 v4；NekoKEM v3.3.2 及更早版本无法打开 v4 文件。

公钥通讯录位于“联系人”页：可从文件或粘贴内容保存接收方公钥，备注可选且最多 512 个字符，
支持编辑备注和确认后删除。加密页选择“通讯录”后必须显式选择条目，条目上的“用于加密”会打开
加密页并选中它；列表和选择框显示完整指纹，列表同时显示来源文件名，加密完成后显示接收方及其指纹。记录保存在语言设置旁的私有
`contacts` 目录，每个指纹一条 JSON 记录，仅包含 Core 规范化导出的公钥、指纹、来源文件名和备注，
通过 Core 原子私有写入并按敏感文件规则读取。每次加密都重新读取记录、生成操作专用私有快照，
并由 Core 重新解析核对指纹后才加密；快照在成功、失败或取消后清理。条目缺失、损坏、权限异常
或指纹不符时报错并指出该联系人、取消选择它并要求重新选择，绝不改用其他公钥。备注仅用于识别，请核对完整指纹。

Rust/Core tests cover persistence across store instances, Core normalization, duplicate
fingerprints, notes, invalid keys, damaged/substituted/unknown-field records, POSIX
link/permission/hard-link rejection, private directory locations, recipient round trips,
rejection of combined key sources, missing/damaged contacts without output, cancellation
and snapshot cleanup, plus several-contact NKEM v4 files that each selected recipient
decrypts, refused duplicates and recipient limits, and failures that name the contact.

## Install / 安装

Windows: run the MSI, then open **NekoKEM** from the Start menu. Or unpack the
ZIP anywhere and open `NekoKEM\NekoKEM.exe`.

Linux: use the package matching `uname -m` (`x86_64` is Debian `amd64`,
`aarch64` is Debian `arm64`), then open **NekoKEM** from the application menu.
The app installs to `/opt/nekokem`. Ubuntu 24.04 (glibc 2.39) is the build and
test baseline; CI also installs the RPM on Fedora 44.

```sh
sudo apt install ./NekoKEM-linux-x86_64-GUI.deb     # Debian, Ubuntu
sudo dnf install ./NekoKEM-linux-x86_64-GUI.rpm     # Fedora
tar -xzf NekoKEM-linux-x86_64-GUI.tar.gz && NekoKEM-linux-x86_64-GUI/bin/NekoKEM
```

The DEB and RPM depend on Noto Sans CJK and curl; with the folder archive,
install a Chinese, Japanese and Korean font and curl yourself.

macOS: open the DMG or unpack the ZIP and move `NekoKEM.app` to Applications.
The app is ad-hoc signed, so the first launch needs **Open** from the context
menu, or System Settings, Privacy & Security, Open Anyway.

## Build / 构建

Every platform first builds the pinned static OpenSSL 4.1.0, then the bridge
against it, then the app. CI (`.github/workflows/ci.yml`) and the Release
workflow run exactly these steps.

Linux, on the native architecture, with Rust, JDK 25, `fakeroot` and `rpm`:

```sh
architecture=$(uname -m)
export NEKOKEM_OPENSSL_PREFIX=/absolute/path/to/pinned-linux-gui-openssl
bash desktop/scripts/build-linux-openssl.sh "$architecture" "$NEKOKEM_OPENSSL_PREFIX"
(cd desktop/bridge && cargo test --locked --release -- --test-threads=1 && cargo build --locked --release)
resources=linux-x64    # linux-arm64 on aarch64
python3 desktop/scripts/package-desktop.py resources \
  --library desktop/bridge/target/release/libnekokem_native.so \
  --openssl-license "$NEKOKEM_OPENSSL_PREFIX/LICENSE.txt" \
  --target "desktop-compose/native/$resources"
(cd desktop-compose && ./gradlew createDistributable packageDeb packageRpm)
```

Windows, with MSYS2 UCRT64 GCC, Rust for `x86_64-pc-windows-gnu` and JDK 25:
build OpenSSL and the CLI with `windows/scripts/build-windows-cli.sh`, build the
bridge with `cargo build --locked --release --target x86_64-pc-windows-gnu`,
copy `nekokem_native.dll` and the licences into `desktop-compose/native/windows-x64`
with `package-desktop.py resources`, then run
`gradlew.bat createDistributable packageMsi` in `desktop-compose`.

macOS, on an Apple Silicon Mac with the macOS 26 SDK and Rust:

```sh
export NEKOKEM_OPENSSL_PREFIX=/absolute/path/to/pinned-macos-openssl
bash macos/scripts/build-openssl.sh "$NEKOKEM_OPENSSL_PREFIX"
bash macos/gui/scripts/build-app.sh /absolute/path/to/output
```

`desktop/scripts/package-desktop.py linux|windows|macos` then checks the built
app (the bridge's hardening and licences, versions, package dependencies, the
macOS signature) and writes the public packages with `gui-SHA256SUMS.txt` and
their build records.

While developing the Compose app, `./gradlew run -Pnekokem.bridge=<library>`
starts it and `./gradlew screenshots -Pnekokem.bridge=<library>` renders every
page of both styles in light and dark into `build/screenshots`. The macOS app
does the same with `NekoKEM --screenshots <directory>`.

Interface text for all three systems is `desktop-compose/src/main/resources/strings.json`.

## Security boundary / 安全边界

Key generation never replaces existing files on its own: if either chosen key
path exists, the GUI reports “A key file already exists” and Core refuses as
well. Back up the old keys, then choose new paths, or tick “Replace the existing
key files” that the GUI then offers and generate again. The option covers one run
and is withdrawn when either path changes. The backend accepts replacement only for the exact pair of paths from its preceding “key exists” response, in the same app session, within five minutes; the authorization is consumed by the next operation. Replacing deletes the old private key,
so files encrypted for the old public key can no longer be decrypted. An encryption or decryption output that is
the selected key file is refused (“The output file is the key file in use”);
Core compares existing regular files by identity, including alternate spellings of the same file; symbolic-link/reparse-point key inputs are refused rather than followed. A private key
is recognized as NKPR by its contents, so Android's `private.nkpr` export works
without renaming.
生成密钥不会自行替换已有文件：所选任一密钥路径已存在时，GUI 提示“所选路径已存在密钥文件”，
Core 也会拒绝。请先备份旧密钥，再选择新的保存位置，或勾选 GUI 随后提供的“替换已有的密钥文件”
并重新生成。该选项只对一次操作有效，修改任一路径即撤销；替换会删除旧私钥，之后无法再解密用旧公钥
加密的文件。加解密输出若就是所选密钥文件会被拒绝；
Core 按已有普通文件的身份比较（包括同一文件的不同路径写法），符号链接或 reparse point 密钥输入会直接拒绝，不会跟随。后端仅接受同一应用会话中上一次“密钥已存在”响应指明的完全相同路径，授权五分钟内有效且被下一次操作消耗。私钥按文件内容识别 NKPR，Android 导出的
`private.nkpr` 无需改名即可使用。

The file page works as on Android: choose a file, press **Encrypt** or **Decrypt**,
answer the recipient and password questions, and choose where to save in the system
dialog once the work is done. Until then the output is a private staged file on disk
(never the tmpfs runtime directory), written through the same Core checks. The chosen
destination is checked by Core before the staged file is moved there; an existing file
is replaced only after the system dialog's own confirmation. Closing the dialog discards
the result. If the chosen place cannot be written, the result is kept and **Save as…**
asks again. Starting another operation, switching pages or quitting deletes an unsaved
result. Only legacy-key export still asks for its output first and reports an existing
file with “Replace the existing output file”.
文件页与 Android 一致：选择文件，按“加密”或“解密”，回答收件人与口令，处理完成后再在系统对话框里
选择保存位置。在此之前输出是磁盘上的私有暂存文件（不使用 tmpfs 运行时目录），写入时经过相同的 Core 检查。
所选位置先由 Core 检查，再把暂存文件移过去；已有文件只有在系统对话框确认后才会被替换。关闭对话框即放弃结果。
所选位置无法写入时结果会保留，可用“保存到…”重新选择。开始新的操作、切换页面或退出都会删除未保存的结果。
只有导出旧版密钥仍然先选输出，并用“覆盖已有输出文件”提示已有文件。

On Windows, the same local fixed-NTFS, owner/ACL, ancestor pinning, regular-file, hard-link,
reparse-point, device/pipe/ADS and atomic-output checks apply. The app cannot
relax them. Existing outputs must already satisfy the CLI's private output policy.
Passwords retain the CLI's 1024 UTF-8 byte limit. Private PEM text is obscured in the paste field. Pasted keys are capped at 1 MiB
and 16384 UTF-8 bytes per line, matching the CLI, and remain subject to Core format/component/tail validation.

On macOS, operations use Core's POSIX regular-file, ownership, private mode,
link/output and atomic-commit checks. Private files/directories reject extended
allow ACLs even when their mode is 0600/0700. Regular-file commits require successful fsync and F_FULLFSYNC; failure is reported. Pasted keys use an owner-only 0700 `$XDG_RUNTIME_DIR` when valid, then on macOS the per-user temporary directory (outside Time Machine), otherwise a private `$HOME/.nekokem-tmp` (the account home if HOME is unset), with a new 0700 random subdirectory, an exclusive 0600 no-follow file and descriptor-relative operations. Temporary pasted-key bytes use unbuffered stdio and are never fsynced. Cleanup rejects symlinks, extra hard links and unsafe permissions.
See the [macOS filesystem design](../macos/SECURITY-DESIGN.md) for platform limits.

On Linux, the same Core POSIX file checks and descriptor-relative pasted-key
staging apply, requiring private `0700` directories, `0600` regular files, current
ownership, one hard link and no symlinks. Pasted-key staging uses the same runtime/private-home directory policy as macOS, unbuffered writes and no `fsync`; durable output commits retain Core sync checks.

The apps load no remote content and contain no web view. The update check is a
bridge command: it runs the system curl
(`System32\curl.exe` on Windows, `/usr/bin/curl` elsewhere; the Linux packages depend on curl)
with `--disable --proto =https`, a 15-second limit and a 1 MiB response limit against the fixed
GitHub latest-release address, and the release page opens through the system URL handler with an
address built from the version number. The app cannot choose either address.
粘贴密钥优先暂存于符合当前所有者及 `0700` 要求的 `$XDG_RUNTIME_DIR`，macOS 上其次使用不进入 Time Machine 的用户临时目录，否则使用私有 `$HOME/.nekokem-tmp`；临时写入关闭 stdio 缓冲，不做持久化同步。应用不加载任何远程内容，也不含网页视图。检查更新由桥接库调用系统 curl（Windows `System32\curl.exe`，其他平台 `/usr/bin/curl`，Linux 包依赖 curl），以 `--disable --proto =https`、15 秒超时和 1 MiB 上限请求固定的 GitHub 最新版本地址；发布页由系统 URL 处理程序打开，地址按版本号拼出，应用无法指定任何地址。

Passwords and key text are never written to settings or logs. The bridge keeps
them in zeroizing memory and Core borrows the password bytes only for the call.
The Compose app writes each request into native memory that is zeroed before it
is freed. Text fields of the interface toolkits can still hold temporary copies
that the app cannot reliably erase.

Closing the window, or quitting the macOS application from its menu or ⌘Q,
during an operation requests cancellation and waits for Core
cleanup; key generation finishes safely before closing. C17 algorithms, KDF
parameters/domains, NKEM/NKPR formats, fingerprints, AAD, GCM limits and 64 KiB
streaming stay unchanged. Progress UI updates are throttled to about 10 per second,
while cancellation is checked on every Core callback.

Windows power-loss equivalence to POSIX directory fsync is unverified. Pair commits
are not cross-file crash atomic. Process death may leave protected plaintext/key
staging files; deletion is not physical erasure. See the
[Windows filesystem design](../windows/SECURITY-DESIGN.md) for the complete backend
boundary. The Windows application and installer are not Authenticode signed. This experimental project has
not undergone an independent professional security audit and must not be used to
protect important or sensitive data.

Download the packages from the [Releases page](https://github.com/Shixiaoshi0417/NekoKEM/releases)
and verify them against the release's top-level `SHA256SUMS.txt`. Releases up to
v5.0.0 carry the earlier Tauri desktop app; the apps described here ship from
v5.1.0. 请从 Releases 页面下载并先校验 SHA-256。v5.0.0 及更早版本的桌面端是旧的
Tauri 应用，本文描述的应用从 v5.1.0 开始提供。
