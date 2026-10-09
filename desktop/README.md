# NekoKEM Desktop GUI

Rust + Tauri 2 + Vue 3 + TypeScript desktop frontend, released as v4.1.0 for
Windows 10/11 x64. It requires the Microsoft Edge WebView2 Runtime. Open
`nekokem-gui.exe` to
use the app; it opens a native window without a terminal. Keep the included
Microsoft `WebView2Loader.dll` beside the EXE. The portable package
contains no Android signing material and is not Authenticode signed.

v4.1.0 also provides native Apple Silicon `aarch64-apple-darwin`
`NekoKEM.app` ZIP and DMG packages using macOS system WKWebView. macOS 11.0 is
the deployment target; native CI runs on macOS 15. Intel Macs are not supported.
本版 Release 提供 M 系列 Mac 原生应用 ZIP 与 DMG，部署目标为 macOS 11.0，
实际验证基线为 macOS 15。

Linux GUI targets native x86_64 and ARM64 with system GTK3, WebKitGTK 4.1 and
glibc 2.39 or newer. Ubuntu 24.04 is the build/test baseline; native Fedora 44
containers install and launch the RPM in CI. v4.1.0 provides `.deb`, `.rpm` and
portable `.tar.gz` packages for each architecture. Linux 原生 GUI 提供两种架构
的 DEB、RPM 和便携包；实际验证基线为 Ubuntu 24.04 与 Fedora 44 的 X11 会话。

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
- Both CLI and GUI retain the square Android artwork; the outer white area of
  the Windows EXE icons is transparent. Android keeps its existing square white
  background; README uses a separate rounded display image.
  macOS ICNS contains the same transparent square PNG artwork without editing pixels.
  Linux uses the existing transparent square PNGs directly.
  CLI/GUI 图案保持方形，EXE、macOS 与 Linux 图标外部白色区域透明；README 圆角只用于展示。
- Short entry transitions, navigation/button feedback and animated progress follow
  the system's reduced-motion preference. Windows high-contrast mode is supported.
  页面过渡、按钮反馈与进度动画遵循系统减少动态效果设置，支持 Windows 高对比度。
- Navigate operations with Up/Down, Home/End; validation focuses the affected field.
  Native file dialogs lock editing until they return. 支持键盘导航与错误字段定位。
- Save recipients' public keys with notes and choose one when encrypting; see
  [Public-key contacts](#public-key-contacts--公钥通讯录). 公钥通讯录与加密时选择已保存公钥。

Production cryptography stays in the shared C17 Core. Rust validates requests,
serializes Core calls, owns zeroizing secrets, bridges progress/cancellation and
creates protected temporary pasted-key files through the platform backend. The Vue
frontend has no filesystem, shell, arbitrary process or remote-content access.

## Public-key contacts / 公钥通讯录

The **Public-key contacts** page (fifth navigation item) saves recipients' public keys.
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
kept. No private keys, passwords, permissions, network access or browser storage are added.

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

公钥通讯录位于第五个导航项：可从文件或粘贴内容保存接收方公钥，备注可选且最多 512 个字符，
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
Frontend tests cover five-language layouts at the default and minimum window sizes, note
editing, delete confirmation, explicit single and multiple selection, the 64-recipient cap,
failure handling and scrubbing of pasted key text.

## Windows build

Use Node 24, Rust with `x86_64-pc-windows-gnu` target, MSYS2 UCRT64 GCC and the
pinned static OpenSSL 4.0.3 prefix created by `windows/scripts/build-windows-cli.sh`.
The GNU target intentionally matches the existing tested C17 Windows ABI/toolchain.

```powershell
rustup toolchain install stable-x86_64-pc-windows-gnu
rustup default stable-x86_64-pc-windows-gnu
$env:Path = 'C:\msys64\ucrt64\bin;' + $env:Path
$env:CC_x86_64_pc_windows_gnu = 'C:\msys64\ucrt64\bin\gcc.exe'
$env:NEKOKEM_OPENSSL_PREFIX = 'C:\path\to\pinned-windows-openssl'
cd desktop
npm ci --ignore-scripts
npm test
npm run build
npm run tauri -- build --target x86_64-pc-windows-gnu --no-bundle
```

Checked-in lockfiles are used with npm ci and Cargo --locked. The GNU host
toolchain also makes Tauri's resource compiler produce GNU-compatible COFF icons
and the application manifest. CI verifies the loader DLL against the checksum-locked
WebView2 SDK crate and its Microsoft Authenticode signature before packaging.

## Apple Silicon macOS build

Build on an arm64 Mac with Xcode Command Line Tools, Node 24 and Rust stable.
The macOS helper builds the pinned static OpenSSL 4.0.3 prefix and checks its
official source checksum, compiler/SDK manifest, runtime and Argon2 thread support.
GUI builds reuse that prefix. OpenSSL and project licenses enter the app before
its resources are sealed and signed.

```sh
bash macos/scripts/build-openssl.sh /absolute/path/to/pinned-macos-openssl
export NEKOKEM_OPENSSL_PREFIX=/absolute/path/to/pinned-macos-openssl
export MACOSX_DEPLOYMENT_TARGET=11.0
rustup target add aarch64-apple-darwin
cd desktop
npm ci --ignore-scripts
npm test
npm run build
TAURI_CONFIG="$(cat src-tauri/tauri.macos.conf.json)" cargo test --locked --target aarch64-apple-darwin --manifest-path src-tauri/Cargo.toml --bin nekokem-gui -- --test-threads=1
npm run tauri -- build --target aarch64-apple-darwin --config src-tauri/tauri.macos.conf.json --bundles app,dmg -- --locked
cd ..
python3 desktop/scripts/package-macos-gui.py \
  --bundle-dir desktop/src-tauri/target/aarch64-apple-darwin/release/bundle \
  --output macos/dist \
  --version 4.1.0 --source-sha "$(git rev-parse HEAD)"
```

`NekoKEM-macos-arm64-GUI.zip` contains `NekoKEM.app`, documentation, licenses,
source/build metadata and checksums. `NekoKEM-macos-arm64-GUI.dmg` contains the
same app. Packaging verifies thin arm64 Mach-O, PIE, minimum OS, system-only
dynamic imports, embedded ICNS and licenses, then mounts the DMG read-only to
confirm the same sealed app is present. CI runs real Rust/Core roundtrips,
wrong-password, pasted-key and cancellation tests, POSIX permission/link checks,
and visible native window startup with local WKWebView page-load and real
settings IPC evidence. Windows retains its original GNU/UCRT64 toolchain,
loader and PE checks.

The macOS app has an **ad-hoc signature with hardened runtime**. This is not an
Apple Developer ID signature, and the application is not notarized. The signature
checks sealed resources without establishing a trusted developer identity.
Gatekeeper may block downloaded packages. This project does not disable or bypass
macOS security protections. Mac 应用使用 ad-hoc 签名与 hardened runtime，未经
Developer ID 签名或 Apple 公证，系统可能阻止启动；这不是可信开发者身份认证。

## Linux installation and build / Linux 安装与构建

Use the package matching `uname -m`: `x86_64` (Debian `amd64`) or `aarch64`
(Debian `arm64`). On Ubuntu 24.04 install the `.deb` with apt, then open **NekoKEM**
from the application menu; no terminal is required. Its installed desktop entry
uses the existing square app icon.

```sh
sudo apt install ./NekoKEM-linux-x86_64-GUI.deb
```

Fedora 44 uses the native `.rpm`; DNF resolves GTK3/WebKitGTK 4.1 and the
versioned glibc requirement from declared library capabilities. Change the
architecture to `aarch64` for ARM64. Fedora 可安装 RPM，并从应用菜单启动。

```sh
sudo dnf install ./NekoKEM-linux-x86_64-GUI.rpm
```

The RPM is built directly by Tauri, with the same application, desktop entry,
icons and licenses as the DEB; only Tauri's three-byte bundle-format marker
differs in the executable. It is not developer GPG signed. Verify package
SHA-256 hashes; these and RPM digests check integrity, not developer identity.
Only generated public resource copies have their permissions normalized to
`0644`; private keys and configuration retain `0600`/`0700`.

For the portable archive, install system dependencies, extract it and run the
launcher. Change `x86_64` to `aarch64` for ARM64. A graphical session is required;
the archive does not bundle GTK, WebKit or glibc; the supported baseline requires
glibc 2.39 or newer.
Keep the package tree together. Linux 便携包需要系统图形运行库；安装包可从系统应用菜单打开。

```sh
sudo apt install libgtk-3-0t64 libwebkit2gtk-4.1-0 libayatana-appindicator3-1
tar -xzf NekoKEM-linux-x86_64-GUI.tar.gz
./NekoKEM-linux-x86_64-GUI/NekoKEM-GUI.sh
```

Automatic language detection uses the CLI's saved preference, then `LC_ALL`,
`LC_MESSAGES`, `LANG`, falling back to English. Five languages and **Follow system**
are available. CLI and GUI share `$XDG_CONFIG_HOME/nekokem/language`, or
`$HOME/.config/nekokem/language` when XDG is unset/relative, with mode `0600`.
The GUI renders UTF-8 even when launched with a `C/POSIX` terminal locale; the
CLI's ASCII/non-UTF-8 terminal fallback continues to apply to terminal output.
GUI 在 C/POSIX 环境下仍可手动选择五种语言。

Build on the native Ubuntu 24.04 architecture with Node 24 and Rust stable:

```sh
sudo apt install build-essential curl perl pkg-config libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev patchelf rpm
architecture=$(uname -m)
bash desktop/scripts/build-linux-openssl.sh "$architecture" /absolute/path/to/pinned-linux-gui-openssl
export NEKOKEM_OPENSSL_PREFIX=/absolute/path/to/pinned-linux-gui-openssl
rustup target add "$architecture-unknown-linux-gnu"
cd desktop
npm ci --ignore-scripts
npm test
npm run build
TAURI_CONFIG="$(cat src-tauri/tauri.linux.conf.json)" cargo test --locked --target "$architecture-unknown-linux-gnu" --manifest-path src-tauri/Cargo.toml --bin nekokem-gui -- --test-threads=1
bash scripts/build-linux-gui.sh "$architecture"
cd ..
python3 desktop/scripts/package-linux-gui.py \
  --bundle-dir "desktop/src-tauri/target/$architecture-unknown-linux-gnu/release/bundle" \
  --output linux/gui-dist --arch "$architecture" \
  --openssl-prefix "$NEKOKEM_OPENSSL_PREFIX" --source-sha "$(git rev-parse HEAD)"
```

The helper verifies the official OpenSSL 4.0.3 source checksum and builds native
static PIC libcrypto with assembly/threads, Fortify and stack protection. Core and
OpenSSL symbols stay hidden from WebKit's dynamic TLS libraries. Packaging checks
ELF PIE/full RELRO/nonexecutable stack, dependencies, licenses, the unchanged PNG
icon, desktop entry and hashes of all three packages. RPM validation checks
native architecture, digests, every ELF dependency, installed file permissions,
absence of installation scripts and matching DEB resources. CI installs the
`.deb` on Ubuntu 24.04 and the `.rpm` through DNF in native Fedora 44 containers,
then runs actual X11 WebKit startup/normal-close checks as ordinary users on both
architectures with sandboxing retained. Rust/Core and private-file tests remain.
Wayland and distributions other than this Ubuntu/Fedora baseline have not
received equivalent native testing.
The build wrapper declares the matching dynamic-loader capability in each native
RPM: `ld-linux-x86-64.so.2` on x86_64 and `ld-linux-aarch64.so.1` on ARM64.
Core's thread-local state can make this a direct ELF dependency; packaging still
checks that every actual ELF dependency is declared.
构建脚本为两种架构的 RPM 分别声明对应的动态加载器依赖，打包仍检查全部实际 ELF 依赖。
See [Linux GUI security boundaries](LINUX-SECURITY.md).

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

Encryption and decryption report an existing output before writing it. Choose another
path, or tick “Replace the existing output file” and start again. This confirmation
covers one run at the same output path and follows the same session/expiry rule as
key replacement. Without confirmation, the final Core commit is atomically create-only:
a file created by another writer during processing is preserved and reported too. On
volumes with neither hard links nor a no-replace rename (FAT or exFAT on macOS, some
network mounts), the commit instead checks the target immediately before an ordinary rename.
For decryption, re-enter the password after the first attempt clears it.
加解密会先提示已有输出文件；请选择新路径，或勾选“覆盖已有输出文件”后重新开始。
确认仅适用于同一输出路径的一次操作，使用与密钥替换相同的会话与过期规则；
未确认时 Core 最终提交以原子方式拒绝覆盖，处理过程中其他写入者新建的目标也会保留并提示；
在既不支持硬链接也不支持不覆盖重命名的卷上（如 macOS 上的 FAT、exFAT 和部分网络挂载），改为在普通重命名前一刻检查目标。
解密首次尝试清除口令后，需要重新输入。

On Windows, the same local fixed-NTFS, owner/ACL, ancestor pinning, regular-file, hard-link,
reparse-point, device/pipe/ADS and atomic-output checks apply. The frontend cannot
relax them. Existing outputs must already satisfy the CLI's private output policy.
Passwords retain the CLI's 1024 UTF-8 byte limit. Private PEM text is obscured in the paste field. Pasted keys are capped at 1 MiB
and 16384 UTF-8 bytes per line, matching the CLI, and remain subject to Core format/component/tail validation.

On macOS, operations use Core's POSIX regular-file, ownership, private mode,
link/output and atomic-commit checks. Private files/directories reject extended
allow ACLs even when their mode is 0600/0700. Regular-file commits require successful fsync and F_FULLFSYNC; failure is reported. Pasted keys use an owner-only 0700 `$XDG_RUNTIME_DIR` when valid, otherwise a private `$HOME/.nekokem-tmp` (the account home if HOME is unset), with a new 0700 random subdirectory, an exclusive 0600 no-follow file and descriptor-relative operations. Temporary pasted-key bytes use unbuffered stdio and are never fsynced. Cleanup rejects symlinks, extra hard links and unsafe permissions.
See the [macOS filesystem design](../macos/SECURITY-DESIGN.md) for platform limits.

On Linux, the same Core POSIX file checks and descriptor-relative pasted-key
staging apply, requiring private `0700` directories, `0600` regular files, current
ownership, one hard link and no symlinks. Pasted-key staging uses the same runtime/private-home directory policy as macOS, unbuffered writes and no `fsync`; durable output commits retain Core sync checks. System WebKitGTK
keeps its sandbox enabled and uses a temporary data store. See the
[Linux GUI design](LINUX-SECURITY.md) for limits and the native test baseline.

Only bundled local content runs with incognito enabled in the main WebView
(Windows InPrivate/macOS nonpersistent WKWebView/Linux ephemeral WebKitGTK storage). CSP blocks remote scripts,
frames and network requests. Only native open/save dialogs and the listed Rust
commands are exposed. The capability list grants only event listen/unlisten and open/save dialogs, rather than `core:default`. Navigation permits only the platform local app origin (`tauri://localhost`, or Windows `http://tauri.localhost`); HTTPS and new windows are refused. Debug builds additionally allow the local Vite origin. Developer tools are unavailable in the production release.
粘贴密钥优先暂存于符合当前所有者及 `0700` 要求的 `$XDG_RUNTIME_DIR`，否则使用私有 `$HOME/.nekokem-tmp`；临时写入关闭 stdio 缓冲，不做持久化同步。权限仅包含事件监听/取消监听与打开/保存对话框；导航只允许对应平台的本地应用来源，拒绝 HTTPS 与新窗口。

Passwords/key text are not stored in browser storage, configuration or logs. Both
form references and live secret input values clear before native invocation,
operation switches and unmounting. Switching away from pasted keys discards their
text. Entry animations do not retain outgoing forms. Rust uses Zeroizing<String> and Core borrows
password bytes only for the call. JavaScript/IPC can make temporary string copies;
reliable zeroization of the WebView heap is not guaranteed. This extra UI/IPC
boundary has not received an independent professional audit.

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
boundary. The v4.1.0 Windows application is not Authenticode signed. Verifying the bundled
Microsoft SDK loader does not sign the application. This experimental project has
not undergone an independent professional security audit and must not be used to
protect important or sensitive data.

Download `NekoKEM-Windows-GUI.zip` from the [v4.1.0 Release](https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v4.1.0)
and verify it against the release's top-level `SHA256SUMS.txt`. See the complete
[bilingual release notes](../release/v4.1.0.md). v4.1.0 提供便携 GUI 压缩包；下载后先校验
SHA-256，再解压并保留 EXE 旁的 Microsoft SDK loader。
