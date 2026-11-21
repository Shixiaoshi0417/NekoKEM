# NekoKEM v5.2.0

## 简体中文

### 本版更新

本版加入原文件名还原、解密保存到指定文件夹和私钥密码保存到系统密码管理器。Android 与各平台 CLI/GUI 统一为 **5.2.0**，Core 为 **5.1**；密码参数不变，NKEM v5 新增可选的文件信息记录。

- **记录原文件名**：签名加密（NKEM v5）时，原文件名记录在加密的签名块里，只有接收方能看到；记录固定 1024 字节，不暴露文件名长度。解密时保存对话框默认使用原文件名，文件被改名后也能还原原来的格式。CLI 加密时同样记录，解密时打印原文件名。不签名的 NKEM v3/v4 不记录。
- **「记录原文件名」开关**：Android、Windows/Linux 和 macOS 应用的设置「签名」分组新增此开关，默认开启。关闭后签名文件不带文件名，格式与 5.1 写出的文件相同。不签名加密时开关不可用。CLI 总是记录。
- **解密保存到指定文件夹**：设置新增「解密 · 保存到指定文件夹」，默认关闭。开启时先选文件夹，之后解密不再弹出保存对话框，直接按原文件名保存到该文件夹；同名文件已存在时依次命名为「名字 (1).扩展名」，从不覆盖。文件夹被删除或无法写入时，结果保留并改为弹出保存对话框。
- **私钥密码保存到密码管理器**：生成、导入或核对私钥时可以勾选「保存到密码管理器」，之后需要私钥密码时可从密码管理器填入。Android 14 及以上使用系统凭据管理器，不依赖 Google Play 服务；macOS 使用登录钥匙串，填入前需要验证身份。Windows 与 Linux 暂不提供。
- **核对指纹（Android）**：核对卡片按 Signal 安全码的样式重做：二维码放在白色方框里，指纹分四列显示，按钮更大。
- **只显示一种指纹**：密钥页、完成详情、临时密钥提示和 CLI 只显示一种指纹，不再显示旧版公钥指纹。导出旧版公钥和联系人升级不受影响。
- **检查更新**：GitHub API 匿名请求被限流时，改从发布页的跳转读取最新版本，不再一直显示检查失败。
- **依赖与构建**：Compose material3 与 kotlinx 库、桥接库的 Rust 依赖和 GitHub Actions 更新；Linux GUI 包排除了不需要的全平台 Skia 库；CI 缓存 OpenSSL 构建，并对桥接库运行 clippy 和 rustfmt 检查。

### 兼容性与升级

- Android **5.2.0 / versionCode 17**，最低 API 26、目标 API 36，仅 arm64-v8a。沿用 v3.2.0/v3.3.0 签名，可直接覆盖升级；建议先备份公钥、加密 NKPR 私钥并验证密码。
- **5.1.0 及更早版本打不开记录了原文件名的文件**，会报告 NKEM v5 头部无效。给使用 5.1 的人发文件前，请在设置中关闭「记录原文件名」；用 CLI 加密的签名文件总是带文件名。
- 5.2.0 可解密 5.1.0、5.0.0 写出的全部 NKEM v3/v4/v5 文件，读取 NKPR v1/v2 以及两段式或四段式公钥。4.x 及更早版本的限制与 5.0.0 相同。
- 设置中新增的选项保存在设置文件里。回退到 5.1.0 的桌面应用会因为不认识新选项而恢复默认设置（界面风格、签名私钥路径等），需要重新设置。
- 未签名的 NKEM v3/v4 仍不认证发送者。AES-GCM 不提供密钥承诺性。Argon2id 保持 64 MiB、3 次迭代、并行度 4。

### 平台与验证边界

- Windows 10/11 x64：CLI、GUI 和 MSI 安装包均无 Authenticode 签名。保留本地固定 NTFS、私有 ACL 与路径检查。
- macOS Apple Silicon arm64：CLI 部署目标 11.0，原生 CI 基线 macOS 15；GUI 最低 macOS 13，使用 macOS 26 SDK 构建并在 macOS 26 上验证。不提供 Intel/Rosetta 构建。使用 ad-hoc 签名，没有 Developer ID 或 Apple 公证，因此钥匙串条目只保存在本机登录钥匙串，不同步到 iCloud。
- Linux GUI：Ubuntu 24.04 构建，另在 Fedora 44 容器中安装并启动 RPM；需要 glibc 2.39+。DEB/RPM 依赖 Noto Sans CJK 字体和 `curl`（检查更新）。RPM 无开发者 GPG 签名；X11 启动检查不代表所有发行版或 Wayland 实机。静态 CLI 继续提供。

本项目仍是实验性工具，未经过独立专业安全审计，不应视为生产级软件或用于保护重要、敏感数据。

### 下载与校验

| 平台 | 正式附件 |
|---|---|
| Android ARM64 | `app-release.apk` |
| Windows x64 | `NekoKEM-windows-x86_64.zip`、`NekoKEM-Windows-GUI.msi`、`NekoKEM-Windows-GUI.zip` |
| macOS Apple Silicon | `NekoKEM-macos-arm64.tar.gz`、`NekoKEM-macos-arm64-GUI.zip`、`NekoKEM-macos-arm64-GUI.dmg` |
| Linux x86_64 | `NekoKEM-linux-x86_64.tar.gz`、`NekoKEM-linux-x86_64-GUI.deb`、`NekoKEM-linux-x86_64-GUI.rpm`、`NekoKEM-linux-x86_64-GUI.tar.gz` |
| Linux ARM64 | `NekoKEM-linux-aarch64.tar.gz`、`NekoKEM-linux-aarch64-GUI.deb`、`NekoKEM-linux-aarch64-GUI.rpm`、`NekoKEM-linux-aarch64-GUI.tar.gz` |

共 15 个包，另附 `SHA256SUMS.txt`。先校验归档，再校验包内文件；每个附件带有本次 Release 构建的来源证明，可用 `gh attestation verify <文件> --repo Shixiaoshi0417/NekoKEM` 验证。正常发布不生成签名恢复信封，也不发布签名密钥。以下记录由 Publish 在核验成功后填写。

### 构建与校验记录

- 源码提交：[`56d387204de54b52b96a17445022c14ef85e7a0f`](https://github.com/Shixiaoshi0417/NekoKEM/tree/56d387204de54b52b96a17445022c14ef85e7a0f)
- CI：[运行 43685706555](https://github.com/Shixiaoshi0417/NekoKEM/actions/runs/43685706555)
- Release：[运行 43685711499](https://github.com/Shixiaoshi0417/NekoKEM/actions/runs/43685711499)
- 同一源码的 CI 与 Release 已全部成功；已核对构建产物来源、包内校验和、Android 构建签名记录及每个附件的 GitHub 构建来源证明。Android 沿用原签名证书。
- Android 签名证书 SHA-256：`5a091b86b1cb339f081c1afa2aa71b986c07597343ce336f22d866b05c39fb50`

```text
04f612a1cbe90f3edb56a6c9bded72738ce001db12b76a900b17a15e3059f4f6  NekoKEM-Windows-GUI.msi
06f81220e1fc72dfa3d7c8b6eb8b8c66a5ff77c6e2f2094eb27a3ac033fda11d  NekoKEM-Windows-GUI.zip
ccd981742fba16374777f8b9e6d3eb8af75b7130dbf5505d8d8316566954756c  NekoKEM-linux-aarch64-GUI.deb
abab8b7697304066281057001090bab92514fc63afc29880dca236001d4621cb  NekoKEM-linux-aarch64-GUI.rpm
9274a5dce4ac5d9c5f6608faabb8ffc32bff7d63e6956c53ae150a606ac45f2b  NekoKEM-linux-aarch64-GUI.tar.gz
c1fc59bebb63b11d95949881da5a50bc3bd0ec97ceb679e94dac292ffc71150c  NekoKEM-linux-aarch64.tar.gz
4036ba8f3425c1dc4421935efd3f5d4c7e38d205472bbaae219c514e9156cdb3  NekoKEM-linux-x86_64-GUI.deb
69314f601e54ca2abd2a887e2475471ee32a66f4010f509af54ea7dc6b7dd1a1  NekoKEM-linux-x86_64-GUI.rpm
8fcbf38bac687fbe7ed68936b7e010ae6ef024fc80314f7ef4b1f9cdf30bdc15  NekoKEM-linux-x86_64-GUI.tar.gz
36f66e5e7672f3d2e48ea48e454984ac53ef3791f0e7c382034dbefa9c48f82d  NekoKEM-linux-x86_64.tar.gz
bc954d3a31f9577a339c5259da5af80449f340fc14b81e52638c301ec0a2cc3e  NekoKEM-macos-arm64-GUI.dmg
1d5b7c92672d7055b3a47b410e3077e867479a8f73fbd032f6bc85a287b78c7b  NekoKEM-macos-arm64-GUI.zip
3423e57e8ad10148d368c12d8a88658670eb58a59ee0426beebf1b6d3edff8b1  NekoKEM-macos-arm64.tar.gz
64502253ca894227141a2f9db8095037d2c11fe37423048552831d1c4b24f058  NekoKEM-windows-x86_64.zip
30bda1e0c69fcad31c3ada5978449357c1e8515540e8060ac02cb5909b7ea379  app-release.apk
```

---

## English

### What's new

This release restores original file names, saves decrypted files to a chosen folder, and saves private key passwords to the system password manager. Android and every CLI/GUI application are **5.2.0**; Core is **5.1**. Cryptographic parameters are unchanged, and NKEM v5 gains an optional file-information record.

- **Original file names**: signed encryption (NKEM v5) records the original file name inside the encrypted signature block, so only recipients see it; the record is a fixed 1024 bytes and hides the name's length. On decryption the save dialog suggests the original name, so a renamed file still gets its format back. The CLI records the name too, and prints it when decrypting. Unsigned NKEM v3/v4 files record no name.
- **"Record original file name" setting**: the Signing section of Settings in the Android, Windows/Linux and macOS apps gains this switch, on by default. With it off, signed files carry no name and have the same format as files written by 5.1. The switch is unavailable while unsigned encryption is on. The CLI always records the name.
- **Save decrypted files to a folder**: a new setting, "Decrypt · Save to a set folder", off by default. Turning it on asks for a folder first; decryption then skips the save dialog and saves under the original name in that folder. When the name is taken, the file becomes "name (1).ext" and so on, never overwriting. If the folder is gone or cannot be written, the result is kept and the save dialog opens instead.
- **Private key password in the password manager**: generating, importing or checking a private key offers "Save to password manager", and later password prompts can fill it in from there. Android 14 and newer use the system credential manager, without Google Play services; macOS uses the login Keychain and asks for authentication before filling it in. Windows and Linux do not offer this yet.
- **Verify fingerprint (Android)**: the verification card is redesigned after Signal's safety number, with the QR code in a white box, the fingerprint in four columns and a larger button.
- **One fingerprint**: the Keys page, completion details, the temporary-key prompt and the CLI show one fingerprint, without the legacy public key fingerprint. Exporting the legacy public key and upgrading contacts are unaffected.
- **Update check**: when the GitHub API rate limit refuses anonymous requests, the latest version is read from the release page's redirect instead of the check failing every time.
- **Dependencies and build**: Compose material3 and kotlinx libraries, the bridge's Rust dependencies and GitHub Actions are updated; the Linux GUI packages leave out the unneeded all-platform Skia library; CI caches the OpenSSL build and runs clippy and rustfmt on the bridge.

### Compatibility and upgrading

- Android **5.2.0 / versionCode 17**, minimum API 26, target API 36, arm64-v8a only. The v3.2.0/v3.3.0 signer is retained for in-place updates. Back up public and encrypted NKPR private keys and verify the password first.
- **NekoKEM 5.1.0 and earlier cannot open a file that records its original name** and report an invalid NKEM v5 header. Before sending files to someone on 5.1, turn off "Record original file name" in Settings; signed files encrypted with the CLI always carry the name.
- 5.2.0 decrypts every NKEM v3/v4/v5 file written by 5.1.0 and 5.0.0, and reads NKPR v1/v2 and two- or four-component public keys. The limits of 4.x and earlier are the same as for 5.0.0.
- The new options are stored in the settings file. A 5.1.0 desktop app does not recognise them and falls back to its default settings (interface style, signing key path and so on), which then need setting again.
- Unsigned NKEM v3/v4 still does not authenticate senders. AES-GCM provides no key commitment. Argon2id remains 64 MiB, three iterations, parallelism four.

### Platform and validation boundaries

- Windows 10/11 x64: the CLI, the GUI and the MSI installer are not Authenticode signed. Local fixed NTFS, private ACL and path checks remain.
- macOS Apple Silicon arm64: the CLI has deployment target 11.0 and a native CI baseline of macOS 15; the GUI needs macOS 13 or newer and is built with the macOS 26 SDK and tested on macOS 26. No Intel/Rosetta builds. Signatures are ad hoc, without Developer ID or Apple notarization, so Keychain items stay in the local login Keychain and do not sync through iCloud.
- Linux GUI: built on Ubuntu 24.04, with the RPM also installed and started in Fedora 44 containers; requires glibc 2.39+. The DEB/RPM depend on Noto Sans CJK fonts and `curl` (for the update check). RPMs are not developer-GPG-signed. X11 launch tests do not establish every distribution or physical Wayland session. Static CLI packages remain available.

This remains an experimental tool without an independent professional security audit; it is not production-grade software and should not protect important or sensitive data.

### Downloads and verification

The platform table above lists all **15 packages**, accompanied by `SHA256SUMS.txt`. Verify downloaded archives and then their internal files. Every asset carries this Release run's provenance; use `gh attestation verify <file> --repo Shixiaoshi0417/NekoKEM`. A normal release creates no signing recovery envelope and publishes no signing key. Publish fills in the records below after verification succeeds.

### Build and verification records

- Source commit: [`56d387204de54b52b96a17445022c14ef85e7a0f`](https://github.com/Shixiaoshi0417/NekoKEM/tree/56d387204de54b52b96a17445022c14ef85e7a0f)
- CI: [run 43685706555](https://github.com/Shixiaoshi0417/NekoKEM/actions/runs/43685706555)
- Release: [run 43685711499](https://github.com/Shixiaoshi0417/NekoKEM/actions/runs/43685711499)
- CI and Release for the same source commit succeeded. Artifact provenance, internal checksums, Android signing build records and the GitHub build provenance attestation of every asset were verified. The Android signing identity is retained.
- Android signing certificate SHA-256: `5a091b86b1cb339f081c1afa2aa71b986c07597343ce336f22d866b05c39fb50`

```text
04f612a1cbe90f3edb56a6c9bded72738ce001db12b76a900b17a15e3059f4f6  NekoKEM-Windows-GUI.msi
06f81220e1fc72dfa3d7c8b6eb8b8c66a5ff77c6e2f2094eb27a3ac033fda11d  NekoKEM-Windows-GUI.zip
ccd981742fba16374777f8b9e6d3eb8af75b7130dbf5505d8d8316566954756c  NekoKEM-linux-aarch64-GUI.deb
abab8b7697304066281057001090bab92514fc63afc29880dca236001d4621cb  NekoKEM-linux-aarch64-GUI.rpm
9274a5dce4ac5d9c5f6608faabb8ffc32bff7d63e6956c53ae150a606ac45f2b  NekoKEM-linux-aarch64-GUI.tar.gz
c1fc59bebb63b11d95949881da5a50bc3bd0ec97ceb679e94dac292ffc71150c  NekoKEM-linux-aarch64.tar.gz
4036ba8f3425c1dc4421935efd3f5d4c7e38d205472bbaae219c514e9156cdb3  NekoKEM-linux-x86_64-GUI.deb
69314f601e54ca2abd2a887e2475471ee32a66f4010f509af54ea7dc6b7dd1a1  NekoKEM-linux-x86_64-GUI.rpm
8fcbf38bac687fbe7ed68936b7e010ae6ef024fc80314f7ef4b1f9cdf30bdc15  NekoKEM-linux-x86_64-GUI.tar.gz
36f66e5e7672f3d2e48ea48e454984ac53ef3791f0e7c382034dbefa9c48f82d  NekoKEM-linux-x86_64.tar.gz
bc954d3a31f9577a339c5259da5af80449f340fc14b81e52638c301ec0a2cc3e  NekoKEM-macos-arm64-GUI.dmg
1d5b7c92672d7055b3a47b410e3077e867479a8f73fbd032f6bc85a287b78c7b  NekoKEM-macos-arm64-GUI.zip
3423e57e8ad10148d368c12d8a88658670eb58a59ee0426beebf1b6d3edff8b1  NekoKEM-macos-arm64.tar.gz
64502253ca894227141a2f9db8095037d2c11fe37423048552831d1c4b24f058  NekoKEM-windows-x86_64.zip
30bda1e0c69fcad31c3ada5978449357c1e8515540e8060ac02cb5909b7ea379  app-release.apk
```
