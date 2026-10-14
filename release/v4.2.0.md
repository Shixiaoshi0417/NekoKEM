# NekoKEM v4.2.0

## 简体中文

### 本版更新

本版汇总 v4.1.0 之后的仓库审查修复，覆盖发布链、Android、CLI、桌面端和 Core 文件层。Android 与各平台 CLI/GUI 统一为 **4.2.0**，Core 仍为 **4.0**。NKEM v3/v4、NKPR v1、密码参数和公钥指纹算法不变。

- **发布与来源校验**：正式构建不使用 Actions 缓存；Android 构建与签名分离，只在 main 使用既有签名。独立 Publish 流程要求 CI 与 Release 对应同一 main 提交，核验包内版本、源码、构建记录、签名、校验和及每个附件的 GitHub 构建来源证明。上传失败保留可验证草稿供重试，不修改已有正式发布。
- **Android 密钥与文件操作**：密钥变更使用进程内互斥与 Core 文件锁，导入自身公钥前验证私钥配对；后台清理只处理可确认安全的过期暂存文件，保留恢复材料并说明位置。文件名查询移到后台，过滤控制符、双向格式符与不合适的零宽字符；禁用云备份和设备迁移。
- **口令与隐私**：Android 口令对话框使用可清零数组，并仅在输入口令时禁止截图。系统、输入法、JVM 与 WebView/IPC 仍可能留下不可可靠清零的临时副本。CLI 禁用 core dump；不能可靠隐藏输入的 Windows 终端会拒绝秘密输入。
- **CLI 与桌面端**：粘贴密钥使用经过权限、属主与 ACL 检查的私有目录和无缓冲暂存，不主动 fsync 临时明文；macOS 优先使用系统用户私有临时目录。改进信号清理、输出错误码、终端显示过滤与安装器的版本解析。GUI 的覆盖授权绑定会话、完整路径和单次操作，并有有效期；默认不覆盖竞争创建的输出。导航与 capability 权限收窄。
- **Core 加密输入检查**：拒绝非规范 X448 公钥编码，统一 NKPR 密码错误与认证损坏的对外错误，检查退化 X448 私钥；不依赖特定 OpenSSL 错误原因码。保留现有正常密钥和文件格式兼容性。
- **Core 文件事务**：公钥读取拒绝符号链接；改进硬链接、事务残留、锁竞争、原子不覆盖、备份和回滚检查。两份密钥备份在发布新密钥前完成，恢复失败时保留可恢复材料并报告位置；删除私钥时不误删未知或仍在使用的残留。macOS 备份不跟随符号链接，Windows 拒绝网络/设备/ADS 路径。
- **回归验证**：补充故障注入、并发输出、覆盖授权、输入与显示过滤、发布脚本及版本一致性检查。修正 Apple Silicon 暂存测试的回退目录假设，并验证 0700 目录上的允许型 macOS ACL 仍会被拒绝。

### 兼容性与升级

- Android **4.2.0 / versionCode 11**，最低 API 26、目标 API 35，仅 arm64-v8a。沿用 v3.2.0/v3.3.0 签名，可直接覆盖升级；建议先备份公钥、加密 NKPR 私钥并验证密码。仅旧签名 v3.1.x 安装需要验证备份后卸载重装，卸载会删除应用内部密钥。
- NKEM v3/v4 与 NKPR v1 保持兼容；NKEM v4 需要 v4.0.0 或更新版本。NKEM v1/v2 不再支持。OpenSSL 固定为 4.0.3，Argon2id 保持 64 MiB、3 次迭代、并行度 4。
- 默认不覆盖已有文件；密钥替换仍需明确确认。替换或删除旧私钥前请保留可用备份。POSIX 的 `.nekokem-pair.lock` 可保留，操作运行期间不要删除。
- NKEM 不认证发送者；v4 的接收方可识别自己的记录并跨文件确认参与。AES-GCM 不提供密钥承诺性。成对文件提交不宣称跨文件断电原子性；外部并发修改、SAF 提供方行为及物理擦除仍有文档所述限制。

### 平台与验证边界

- Windows 10/11 x64：CLI/GUI EXE 无 Authenticode 签名；GUI 需要 WebView2 Runtime。保留本地固定 NTFS、私有 ACL 与路径检查。
- macOS Apple Silicon arm64：部署目标 11.0，实际原生 CI 基线 macOS 15；不提供 Intel/Rosetta 构建。使用 ad-hoc hardened-runtime 签名，没有 Developer ID 或 Apple 公证。
- Linux GUI：Ubuntu 24.04 构建，Fedora 44 原生容器验证 RPM；需要 glibc 2.39+、GTK3、WebKitGTK 4.1。RPM 无开发者 GPG 签名；X11 启动检查不代表所有发行版或 Wayland 实机。静态 CLI 继续提供。

本项目仍是实验性工具，未经过独立专业安全审计，不应视为生产级软件或用于保护重要、敏感数据。

### 下载与校验

| 平台 | 正式附件 |
|---|---|
| Android ARM64 | `app-release.apk` |
| Windows x64 | `NekoKEM-windows-x86_64.zip`、`NekoKEM-Windows-GUI.zip` |
| macOS Apple Silicon | `NekoKEM-macos-arm64.tar.gz`、`NekoKEM-macos-arm64-GUI.zip`、`NekoKEM-macos-arm64-GUI.dmg` |
| Linux x86_64 | `NekoKEM-linux-x86_64.tar.gz`、`NekoKEM-linux-x86_64-GUI.deb`、`NekoKEM-linux-x86_64-GUI.rpm`、`NekoKEM-linux-x86_64-GUI.tar.gz` |
| Linux ARM64 | `NekoKEM-linux-aarch64.tar.gz`、`NekoKEM-linux-aarch64-GUI.deb`、`NekoKEM-linux-aarch64-GUI.rpm`、`NekoKEM-linux-aarch64-GUI.tar.gz` |

共 14 个包，另附 `SHA256SUMS.txt`。先校验归档，再校验包内文件；每个附件带有本次 Release 构建的来源证明，可用 `gh attestation verify <文件> --repo Shixiaoshi0417/NekoKEM` 验证。正常发布不生成签名恢复信封，也不发布签名密钥。以下记录由 Publish 在核验成功后填写。

<!-- RELEASE_ZH_METADATA -->

---

## English

### What's new

This release collects repository review fixes since v4.1.0 across publishing, Android, CLI, desktop and Core filesystem operations. Android and every CLI/GUI application are **4.2.0**; Core remains **4.0**. NKEM v3/v4, NKPR v1, cryptographic parameters and public-key fingerprint algorithms are unchanged.

- **Release verification**: production builds use no Actions caches. Android builds and signing run separately, using the existing signing identity only on main. Publish requires CI and Release to succeed for the same main commit, and verifies package versions, source/build records, signatures, checksums and GitHub provenance for every asset. Interrupted uploads retain a verifiable draft for retry; existing published releases are not modified.
- **Android key and file operations**: key mutations share a process mutex and Core file lock; importing a local public key verifies its private-key match. Background cleanup removes only safely identified expired staging files and preserves recovery materials with their locations. Filename queries move off the UI thread; display filtering covers controls, bidi formatting and unsuitable zero-width characters. Cloud backups and device transfers are excluded.
- **Passwords and privacy**: Android password dialogs use clearable arrays and block screenshots only while entering a password. The OS, keyboard, JVM and WebView/IPC may still retain temporary copies that cannot reliably be erased. The CLI disables core dumps and refuses secret input in Windows terminals that cannot reliably disable echo.
- **CLI and desktop**: pasted keys use owned, permission/ACL-checked private directories and unbuffered staging without plaintext fsync; macOS prefers its system-provided private user temporary directory. Signal cleanup, exit status, terminal display filtering and installer version resolution are improved. GUI overwrite authorization is bound to the session, exact paths, one operation and an expiry; default publication preserves competing outputs. Navigation and capabilities are restricted.
- **Core cryptographic input checks**: noncanonical X448 public encodings are refused; NKPR password/authentication errors are unified; degenerate X448 private keys are checked without relying on particular OpenSSL reason codes. Existing normal keys and formats stay compatible.
- **Core file transactions**: public-key reads reject symlinks. Hard-link, transaction-residue, lock-contention, atomic no-replace, backup and rollback checks are strengthened. Both key backups precede publication; failed recovery preserves materials and reports locations. Private-key deletion leaves unknown or active residues untouched. macOS backups do not follow symlinks, and Windows rejects network/device/ADS paths.
- **Regression coverage**: fault injection, competing outputs, overwrite authorization, input/display filtering, publishing scripts and version consistency checks are expanded. Apple Silicon staging tests now follow the actual fallback policy and verify that allowing macOS ACLs remain rejected even on 0700 directories.

### Compatibility and upgrading

- Android **4.2.0 / versionCode 11**, minimum API 26, target API 35, arm64-v8a only. The v3.2.0/v3.3.0 signer is retained for in-place updates. Back up public and encrypted NKPR private keys and verify the password first. Only old-signer v3.1.x installations need verified backups followed by uninstall/reinstall; uninstalling deletes app-private keys.
- NKEM v3/v4 and NKPR v1 remain compatible; NKEM v4 needs v4.0.0 or newer. NKEM v1/v2 are unsupported. OpenSSL stays pinned to 4.0.3 and Argon2id remains 64 MiB, three iterations, parallelism four.
- Existing files are not overwritten by default; key replacement still needs explicit confirmation. Retain usable backups before replacing or deleting old keys. POSIX `.nekokem-pair.lock` files can remain; do not delete them during operations.
- NKEM does not authenticate senders. A v4 recipient can identify its own records and participation across files. AES-GCM provides no key commitment. Paired file commits do not promise cross-file power-loss atomicity; external concurrent mutation, SAF provider behavior and physical erasure retain their documented limits.

### Platform and validation boundaries

- Windows 10/11 x64: CLI/GUI EXEs are not Authenticode signed; GUI needs WebView2 Runtime. Local fixed NTFS, private ACL and path checks remain.
- macOS Apple Silicon arm64: deployment target 11.0, native CI baseline macOS 15, no Intel/Rosetta builds. Ad-hoc hardened-runtime signatures are used, without Developer ID or Apple notarization.
- Linux GUI: built on Ubuntu 24.04, RPM tested in native Fedora 44 containers; requires glibc 2.39+, GTK3 and WebKitGTK 4.1. RPMs are not developer-GPG-signed. X11 launch tests do not establish every distribution or physical Wayland session. Static CLI packages remain available.

This remains an experimental tool without an independent professional security audit; it is not production-grade software and should not protect important or sensitive data.

### Downloads and verification

The platform table above lists all **14 packages**, accompanied by `SHA256SUMS.txt`. Verify downloaded archives and then their internal files. Every asset carries this Release run's provenance; use `gh attestation verify <file> --repo Shixiaoshi0417/NekoKEM`. Normal releases generate no signing recovery envelope and publish no signing keys. Publish fills the verified source, CI/build links, signer and hashes below.

<!-- RELEASE_EN_METADATA -->
