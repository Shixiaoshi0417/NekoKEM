# NekoKEM v5.1.0

## 简体中文

### 本版更新

本版重写了桌面端，并加入指纹二维码核对。Android 与各平台 CLI/GUI 统一为 **5.1.0**，Core 仍为 **5.0**；NKEM、NKPR 格式和密码参数不变。

- **新的桌面应用**：Windows 与 Linux 改用 Compose Multiplatform 应用，界面与 Android 一致，提供 MIUIX（默认）和 Material 3 Expressive 两种风格，可在设置中切换；应用自带 Java 运行时，Windows 不再需要 WebView2 Runtime。macOS 改用原生 SwiftUI 应用，在 macOS 26 上采用 Apple 的液态玻璃设计，最低支持 macOS 13。原来的 Tauri 应用已删除。三个系统的应用通过同一个桥接库调用与 CLI 相同的 C17 Core，已保存的联系人、语言和设置继续使用。
- **Windows 安装包**：新增 `NekoKEM-Windows-GUI.msi` 安装包，ZIP 便携包继续提供。
- **桌面文件页与 Android 一致**：一页显示所选文件和「加密」「解密」两个按钮，下方是解密用的私钥（默认为自己的私钥，可临时另选）；收件人和私钥密码在对话框中询问。处理完成后才弹出系统保存对话框选择保存位置，关闭对话框即放弃结果；所选位置无法写入时可重新选择。
- **指纹二维码核对**：密钥页可显示自己公钥指纹的二维码。二维码只包含指纹（`NEKOKEM-FP1:` 加 64 位十六进制），不包含密钥，扫码只能核对已保存的联系人，不能添加或替换联系人。Android 可扫码核对：一致时记为已核对，不一致时显示红字警告并清除标记。桌面应用可显示二维码，不使用摄像头。Android 为此新增相机权限，仅在扫码时申请；扫码不依赖 Google Play 服务。
- **联系人（Android）**：联系人页改为小卡片，右侧显示核对标记；点卡片进入详情页，「核对指纹」单独一页，显示二维码和分组指纹，可扫码核对或手动标记。使用未核对的联系人加密前会弹出警告，可取消或继续。同一把公钥重新导入时保留核对标记，换成新的身份密钥则重置。
- **OpenSSL 4.1.0**：所有平台由 4.0.3 升级为 4.1.0，仍从官方源码按固定 SHA-256 构建并静态链接。
- **依赖与测试**：Android 构建工具链和依赖更新；仓库启用 Dependabot，并新增每周一次的长时间模糊测试。

### 兼容性与升级

- Android **5.1.0 / versionCode 16**，最低 API 26、目标 API 36，仅 arm64-v8a。沿用 v3.2.0/v3.3.0 签名，可直接覆盖升级；建议先备份公钥、加密 NKPR 私钥并验证密码。
- 文件格式与 5.0.0 完全相同：5.1.0 可解密 NKEM v3/v4/v5，读取 NKPR v1/v2 以及两段式或四段式公钥，5.0.0 与 5.1.0 可互相读取对方的文件和密钥。4.x 及更早版本的限制与 5.0.0 相同。
- 联系人的核对标记是 5.1.0 新增的记录。5.0.0 读取联系人时会忽略它，在 5.0.0 中修改备注会丢失该标记。
- 桌面应用是全新的安装包：Windows 可直接使用新的 MSI 或 ZIP，旧的 Tauri 便携包可以删除；Linux 用新的 DEB/RPM 覆盖安装；macOS 用新的 `.app` 替换旧应用。
- 未签名的 NKEM v3/v4 仍不认证发送者。AES-GCM 不提供密钥承诺性。Argon2id 保持 64 MiB、3 次迭代、并行度 4。

### 平台与验证边界

- Windows 10/11 x64：CLI、GUI 和 MSI 安装包均无 Authenticode 签名。保留本地固定 NTFS、私有 ACL 与路径检查。
- macOS Apple Silicon arm64：CLI 部署目标 11.0，原生 CI 基线 macOS 15；GUI 最低 macOS 13，使用 macOS 26 SDK 构建并在 macOS 26 上验证，更早的系统使用回退外观，未在实机上逐一验证。不提供 Intel/Rosetta 构建。使用 ad-hoc 签名，没有 Developer ID 或 Apple 公证。
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

<!-- RELEASE_ZH_METADATA -->

---

## English

### What's new

This release rewrites the desktop apps and adds fingerprint verification by QR code. Android and every CLI/GUI application are **5.1.0**; Core stays at **5.0**, and the NKEM and NKPR formats and cryptographic parameters are unchanged.

- **New desktop apps**: Windows and Linux move to a Compose Multiplatform app that looks like the Android app, in the MIUIX style (the default) or Material 3 Expressive, chosen in Settings. It carries its own Java runtime, and Windows no longer needs the WebView2 Runtime. macOS moves to a native SwiftUI app in Apple's Liquid Glass design on macOS 26, and runs on macOS 13 or newer. The earlier Tauri app is removed. All three call the same C17 Core as the CLI through one bridge library, and saved contacts, the language and settings carry over.
- **Windows installer**: a new `NekoKEM-Windows-GUI.msi` installer joins the ZIP folder package.
- **The desktop file page works as on Android**: one page shows the chosen file with Encrypt and Decrypt buttons, and below it the private key used for decryption (your own by default; another can be chosen for the moment). Recipients and the private key password are asked for in dialogs. The system save dialog opens only once the work is done; closing it discards the result, and a place that cannot be written can be chosen again.
- **Fingerprint QR verification**: the Keys page shows a QR code of your public key's fingerprint. The code holds only the fingerprint (`NEKOKEM-FP1:` and 64 hexadecimal digits), never a key, so a scan can only check a saved contact and cannot add or replace one. Android scans the code: a match marks the contact verified, and a mismatch shows a red warning and clears the mark. The desktop apps show the code and do not use a camera. Android gains the camera permission for this, requested only when scanning; scanning does not depend on Google Play services.
- **Contacts (Android)**: the Contacts page uses compact cards with the verification mark on the right. A card opens the contact's page, and Verify fingerprint is a page of its own with the QR code and the grouped fingerprint, where a contact is verified by scanning or marked by hand. Encrypting for an unverified contact asks first, and can be cancelled or continued. Importing the same public key again keeps the mark; a new identity key resets it.
- **OpenSSL 4.1.0**: every platform moves from 4.0.3 to 4.1.0, still built from the official source with a pinned SHA-256 and linked statically.
- **Dependencies and testing**: the Android build toolchain and dependencies are updated, Dependabot is enabled, and a long fuzzing run now happens every week.

### Compatibility and upgrading

- Android **5.1.0 / versionCode 16**, minimum API 26, target API 36, arm64-v8a only. The v3.2.0/v3.3.0 signer is retained for in-place updates. Back up public and encrypted NKPR private keys and verify the password first.
- File formats are the same as in 5.0.0: 5.1.0 decrypts NKEM v3/v4/v5 and reads NKPR v1/v2 and two- or four-component public keys, and 5.0.0 and 5.1.0 read each other's files and keys. The limits of 4.x and earlier are the same as for 5.0.0.
- A contact's verification mark is a record new in 5.1.0. 5.0.0 ignores it when reading contacts, and editing a note in 5.0.0 drops the mark.
- The desktop apps are new packages: on Windows use the new MSI or ZIP and delete the old Tauri folder; on Linux install the new DEB/RPM over the old one; on macOS replace the old `.app` with the new one.
- Unsigned NKEM v3/v4 still does not authenticate senders. AES-GCM provides no key commitment. Argon2id remains 64 MiB, three iterations, parallelism four.

### Platform and validation boundaries

- Windows 10/11 x64: the CLI, the GUI and the MSI installer are not Authenticode signed. Local fixed NTFS, private ACL and path checks remain.
- macOS Apple Silicon arm64: the CLI has deployment target 11.0 and a native CI baseline of macOS 15; the GUI needs macOS 13 or newer, is built with the macOS 26 SDK and tested on macOS 26, and uses a fallback appearance on earlier systems, which were not each tested on real machines. No Intel/Rosetta builds. Signatures are ad hoc, without Developer ID or Apple notarization.
- Linux GUI: built on Ubuntu 24.04, with the RPM also installed and started in Fedora 44 containers; requires glibc 2.39+. The DEB/RPM depend on Noto Sans CJK fonts and `curl` (for the update check). RPMs are not developer-GPG-signed. X11 launch tests do not establish every distribution or physical Wayland session. Static CLI packages remain available.

This remains an experimental tool without an independent professional security audit; it is not production-grade software and should not protect important or sensitive data.

### Downloads and verification

The platform table above lists all **15 packages**, accompanied by `SHA256SUMS.txt`. Verify downloaded archives and then their internal files. Every asset carries this Release run's provenance; use `gh attestation verify <file> --repo Shixiaoshi0417/NekoKEM`. A normal release creates no signing recovery envelope and publishes no signing key. Publish fills in the records below after verification succeeds.

<!-- RELEASE_EN_METADATA -->
