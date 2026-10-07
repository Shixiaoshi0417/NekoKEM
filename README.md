[简体中文](README.md) | [English](README.en.md)

# NekoKEM

<p align="center">
  <img src="docs/icon-rounded.png" alt="NekoKEM 项目图标" width="160">
</p>

NekoKEM 是一个用于学习 OpenSSL EVP API 的实验性后量子文件加密工具。支持 NKEM v3（单接收方）和 NKEM v4（多接收方）Hybrid 文件容器。

| 模式 | 密钥建立 | KDF | 文件加密 | 容器 |
|---|---|---|---|---|
| v3 hybrid（默认） | X448 + ML-KEM-1024 | HKDF-SHA512 | AES-256-GCM | version 3 / algorithm id 3 |
| v4 多人 | 每位接收方 X448 + ML-KEM-1024 | HKDF-SHA512 | AES-256-GCM 包装文件密钥与加密数据，HMAC-SHA512 头部 MAC | version 4 / algorithm id 4 |

本项目不自行实现任何密码算法，也不依赖 liboqs。**它没有经过安全审计，不应被视为生产级软件，也不应用来保护重要或敏感数据。**

## 发布版本 v4.0.1

Android 和各平台 CLI/GUI 版本统一为 `4.0.1`，Core 仍为 `4.0`。本版是安全修复版本，修复针对 v4.0.0 的一轮安全审查发现的问题（无 P0；1 项 P1、5 项 P2、1 项 P3 及加固建议），文件格式与密码参数不变：

- **生成密钥不再覆盖已有密钥**：CLI 发现已有密钥时直接拒绝，需要轮换时显式使用 `keygen --replace`；Android 替换前会请求确认，桌面 GUI 会报错。密钥对改为原子“不覆盖”发布，回滚前核对文件身份，并兼容 Android 禁止应用创建硬链接的 SELinux 策略。
- **输出不能覆盖正在使用的密钥**：加解密的输出路径若是本次使用的公钥或私钥文件，操作会被拒绝。
- **NKPR 私钥按内容识别**：不再依赖 `.enc` 后缀，Android 导出的 `private.nkpr` 可直接用于 CLI 和桌面 GUI。
- **Android**：页面重建（旋转、切换语言）不再清除进行中操作的缓存；密码框使用密码键盘并关闭联想和个性化学习；导入私钥后同步目录；明文与 NKEM 文件分别限制大小。
- **加固**：GitHub Actions 固定到提交哈希，Gradle 分发包校验 SHA-256，Windows 敏感文件读取关闭 stdio 缓冲，AFL++ 新增完整解密 fuzz。

这轮审查不等同于独立专业安全审计。各平台安装包沿用 Rust + Tauri 2 + Vue 3 + TypeScript 界面、五语言自动检测和过渡动画；固定使用 OpenSSL 4.0.3，保持原有密码参数、文件安全检查和流式处理。完整中文在上、英文在下的更新说明见 [v4.0.1](release/v4.0.1.md) 和 [GitHub Release](https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v4.0.1)；多人加密的介绍见 [v4.0.0](release/v4.0.0.md)。

Android v4.0.1 沿用 v3.2.0/v3.3.0 签名，可直接覆盖升级；建议先备份公钥、加密 NKPR 私钥和私钥密码。仅使用旧签名的 v3.1.x 用户需要在验证备份后卸载重装；卸载会删除应用内部密钥。NKEM v3、NKEM v4、NKPR v1、密码参数和指纹计算不变；NKEM v4 文件需要 v4.0.0 或更新版本才能解密。NKEM v1/v2 不再支持。

| 平台与架构 | CLI / Android | GUI |
|---|---|---|
| Android 8.0+ ARM64 | [APK](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/app-release.apk) | APK 内置界面 |
| Windows 10/11 x64 | [CLI ZIP](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-windows-x86_64.zip) | [GUI ZIP](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-Windows-GUI.zip) |
| macOS Apple Silicon arm64 | [CLI TAR.GZ](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-macos-arm64.tar.gz) | [GUI ZIP](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-macos-arm64-GUI.zip) · [DMG](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-macos-arm64-GUI.dmg) |
| Linux x86_64 | [静态 CLI](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-x86_64.tar.gz) | [DEB](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-x86_64-GUI.deb) · [RPM](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-x86_64-GUI.rpm) · [便携包](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-x86_64-GUI.tar.gz) |
| Linux ARM64 / aarch64 | [静态 CLI](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-aarch64.tar.gz) | [DEB](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-aarch64-GUI.deb) · [RPM](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-aarch64-GUI.rpm) · [便携包](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/NekoKEM-linux-aarch64-GUI.tar.gz) |

下载后先用 [SHA256SUMS.txt](https://github.com/Shixiaoshi0417/NekoKEM/releases/download/v4.0.1/SHA256SUMS.txt) 校验对应归档，再校验包内文件。Linux GUI 需要 glibc 2.39+、GTK3 和 WebKitGTK 4.1；macOS 实际验证于 macOS 15，部署目标为 11.0。安装步骤与签名限制见下文各平台说明。

README 使用专用圆角展示图；Android 保留现有方形白底图案，Windows CLI/GUI EXE 保留方形图案并将外部白色区域改为透明。Windows 应用未进行 Authenticode 签名。

## 多人加密

一个文件可以同时加密给最多 64 位接收方，每位接收方用自己的私钥都能解开，其他人无法解密。
桌面 GUI 在“公钥通讯录”页面勾选多位联系人后点击“加密给所选联系人”，或在加密页的接收方列表中勾选；
Android 在“公钥通讯录”页面勾选“选为接收方”后点击“加密给所选接收方”。只选一位时仍写出 NKEM v3；
选择两位及以上时写出一个 NKEM v4 文件。每位联系人都会在加密前重新读取并由 Core 校验指纹，
任何一位缺失、损坏或指纹不符都会让整个操作失败并指出该联系人，不会跳过或改用其他公钥。
CLI 在原有命令后列出多个公钥即可，示例见[参数化命令](#参数化命令)。

NKEM v4 为每位接收方保存一份 X448 + ML-KEM-1024 封装和被包装的文件密钥，文件数据只加密一次；
HMAC-SHA512 头部 MAC 保证所有接收方解出相同内容。格式见 [`docs/NKEM-v4.md`](docs/NKEM-v4.md)。
所有平台的解密都会自动识别 v3 与 v4。此功能自 v4.0.0 起提供；NekoKEM v3.3.2 及更早版本无法打开 v4 文件。

## Android 公钥通讯录

在侧边菜单的“公钥通讯录”导入接收方公钥并填写备注，之后在加密页选择已保存条目。
支持编辑备注、删除、相同指纹去重及 App 内持久保存。每次使用都会通过现有 Core
重新校验公钥和指纹；条目失效时需要显式重选。备注仅用于识别，请与接收方核对指纹。
自己的默认密钥与通讯录独立保存，密码参数和文件格式不变。功能说明和设备测试见
[Android 文档](android/README.md#公钥通讯录)。本版同时支持 Android 预测性返回：
二级页面和侧边菜单随返回手势跟手移动，详见 [预测性返回](android/README.md#预测性返回)。
以上功能自 v3.3.2 起提供；v3.3.1 及更早版本不包含。

## 桌面 GUI 公钥通讯录

Windows、macOS 与 Linux 共用的 Rust + Tauri 2 + Vue 3 + TypeScript GUI 新增“公钥通讯录”页面：
可从文件或粘贴内容保存接收方公钥，添加、编辑备注，确认后删除；加密页可选择“通讯录”中的条目，
条目上的“用于加密”会打开加密页并选中该条目。列表和选择框显示完整 SHA-256 指纹，相同指纹只保存一次。
条目保存在语言设置旁的私有 `contacts` 目录，仅包含 Core 规范化后的公钥、指纹、来源文件名和备注。
每次加密都会重新读取条目并由现有 Core 校验指纹；条目缺失、损坏、权限异常或指纹不符时会报错，
要求重新选择，不会改用其他公钥。备注仅用于识别，请与接收方核对完整指纹。五种界面语言同步更新，
密码参数、文件格式和秘密清理机制不变。详见 [桌面 GUI 文档](desktop/README.md#public-key-contacts--公钥通讯录)。
此功能自 v3.3.2 起提供；v3.3.1 及更早版本不包含。

## 历史版本 v3.2.0

v3.2.0 引入五种界面语言、跟随系统选项和安全修复，并更换 Android 发布签名。该次迁移说明与历史构建记录见 [v3.2.0 更新日志](release/v3.2.0.md)；从 v3.2.0 升级至 v3.3.0 不需要再次卸载。

## 历史版本 v3.1.1

Android App 版本为 `3.1.1`，Core 版本保持 `3.1`，application ID 为
`com.shixiaoshi0417.nekokem`。Android 工程及构建说明见
[`android/README.md`](android/README.md)。App 版本 3.1.1 不改变
协议编号：默认文件容器仍为 **NKEM v3**，NKPR 格式保持不变。

v3.1.1 新增无需运行时共享库依赖的 Linux x86_64/aarch64 CLI 发行包、
自动安装脚本和 GitHub Actions 构建流程。安装脚本自动选择最新 GitHub
Release 中与本机架构匹配的包，先使用 Release 顶层 `SHA256SUMS.txt` 验证
归档，再验证包内文件的 SHA-256：

```sh
curl --fail --location --output install.sh \
    https://raw.githubusercontent.com/Shixiaoshi0417/NekoKEM/main/linux/install.sh
less install.sh
sh install.sh
```

Linux CLI 支持 `nekokem --version`。此发行补丁不改变 Core API、密码参数、
NKEM v3 或 NKPR 格式。

## 安装依赖

Debian 13：

```sh
sudo apt install libssl-dev build-essential
```

需要 OpenSSL 3.5 或更高版本，因为 ML-KEM 的 EVP 支持从 OpenSSL 3.5 开始提供。各平台的源码构建与 CI 固定使用 OpenSSL 4.0.3，并校验官方源码 SHA-256。

如需构建 AFL++ fuzz harness，额外安装：

```sh
sudo apt install afl++
```

## 编译

```sh
make -C linux
```

构建使用 C17，并链接 OpenSSL `libcrypto`。Hybrid 私钥保护使用 OpenSSL provider 提供的 Argon2id，不需要额外安装 `libargon2`。默认构建启用 `-Werror`、`-fstack-protector-strong`、`-D_FORTIFY_SOURCE=3`、`-fPIE` 和 `-pie`。

可执行文件位于 `linux/nekokem`。下文的 `./nekokem` 示例假定当前目录为 `linux/`，或已将该可执行文件复制到当前目录。

GitHub Actions 统一使用 [CI](.github/workflows/ci.yml) 和 [Release](.github/workflows/release.yml)。CI 在 PR 和 main 推送时构建并执行所有平台的安全、语言、互操作、性能和 GUI 回归检查；Release 通过手动运行或 `v*` 标签构建 Linux CLI 归档和 GUI 安装/便携包、Android 签名 APK、Windows CLI/GUI 压缩包及 macOS arm64 包。签名身份与构建产物说明见[构建工作流文档](release/README.md)。

## Windows CLI 与 GUI

v4.0.1 提供原生 Windows 10/11 x64 `nekokem.exe` 和 `nekokem-gui.exe` 便携包。CLI 与 Linux 共用完整参数解析和五项菜单；双击 EXE 或不带参数运行即可进入菜单。构建、使用和导入私钥权限说明见 [Windows CLI 文档](windows/README.md)，文件安全设计见 [Windows 文件层说明](windows/SECURITY-DESIGN.md)。CLI 运行不需要 MSYS2 或 OpenSSL DLL；构建使用 MSYS2 UCRT64 与固定 OpenSSL 4.0.3。

GUI 使用 Rust + Tauri 2 + Vue 3 + TypeScript，通过窄接口调用同一 C17 Core，提供原生文件选择、进度、取消、键盘导航和遵循系统减少动态效果设置的过渡动画。运行需要 Microsoft Edge WebView2 Runtime，并将包内 `WebView2Loader.dll` 放在 EXE 旁；详见 [Windows GUI 文档](desktop/README.md)。两种 Windows 应用均未进行 Authenticode 签名；构建时单独验证 Microsoft SDK loader 的签名与来源。

Windows 仅支持本地固定 NTFS，检查所有者与 ACL，并拒绝网络、设备、替代数据流及重解析点路径。协议、密码参数及 64 KiB 流式分块不变；Windows 断电持久性不能当作 POSIX 目录 fsync 已验证。Windows 语言配置使用系统 LocalAppData/NekoKEM；无 Locale 环境覆盖时读取 Windows 显示语言。下文的 POSIX 配置路径适用于 Linux 和 macOS CLI。

## macOS Apple Silicon CLI 与 GUI

v4.0.1 提供面向 M 系列 Apple Silicon Mac 的原生 `arm64` CLI 和 Rust + Tauri 2 + Vue 3 + TypeScript GUI。CLI 包名为 `NekoKEM-macos-arm64.tar.gz`；GUI 包为 `NekoKEM-macos-arm64-GUI.zip`（包含 `.app`）和 `NekoKEM-macos-arm64-GUI.dmg`，可从本版 Release 下载。详见 [macOS 构建与使用说明](macos/README.md) 和 [桌面 GUI 文档](desktop/README.md)。不提供 Intel/Rosetta 构建。

CLI 与 Linux 共用参数解析、五项菜单和语言目录。GUI 使用系统 WKWebView，不需要 Windows 的 WebView2 Runtime 或 loader。两者静态链接固定 OpenSSL 4.0.3，保留其 arm64 汇编加速；运行仍依赖 macOS 系统库与框架。构建设置 macOS 11.0 deployment target；原生 CI 在 `macos-15` 上运行，这不代表已验证 macOS 11.0 实机或每代 M 系列芯片的兼容性、性能。

macOS 文件层保留严格的所有者、权限、符号链接和硬链接检查，受保护文件与目录只允许空或 deny-only 扩展 ACL，并检查 APFS/HFS+ 大小写及 Unicode 名称别名。普通输出文件要求 `fsync` 与 `F_FULLFSYNC`，提交后要求父目录 `fsync`，失败时不静默降级；详细限制与测试边界见 [macOS 文件安全设计](macos/SECURITY-DESIGN.md)。Core 4.0 新增 NKEM v4 多接收方容器；NKEM v3、NKPR v1、密码参数及 64 KiB 流式分块不变。包只有 ad-hoc 签名，未使用 Developer ID，也未进行 Apple 公证；这不能证明发布者身份或保证通过 Gatekeeper。

## Linux GUI

Linux 原生 GUI 支持 `x86_64` 与 `aarch64`（ARM64），复用 Rust + Tauri 2 + Vue 3 + TypeScript 界面、五语言自动检测、原生文件选择、进度/取消、键盘导航和遵循系统减少动态效果设置的动画。每种架构提供 `NekoKEM-linux-<架构>-GUI.deb`、`NekoKEM-linux-<架构>-GUI.rpm` 和 `NekoKEM-linux-<架构>-GUI.tar.gz`。Ubuntu 使用 `sudo apt install ./NekoKEM-linux-x86_64-GUI.deb`；Fedora 使用 `sudo dnf install ./NekoKEM-linux-x86_64-GUI.rpm`，ARM64 将文件名中的架构换为 `aarch64`。安装后可从系统应用菜单打开；便携包使用 `NekoKEM-GUI.sh` 启动。图标直接使用现有透明外部白色区域的方形 PNG，README 仍用圆角展示图。

构建与原生 CI 基线为 Ubuntu 24.04，CI 另在同架构 Fedora 44 容器中安装并启动 RPM。RPM 原生构建，以共享库能力声明依赖；运行需要系统 GTK3、WebKitGTK 4.1 和 glibc 2.39 或更新版本，GUI 便携包仍依赖这些系统库。OpenSSL 4.0.3 静态链接并隐藏符号，避免影响 WebKit 系统 TLS 库，保留汇编、线程、Fortify、栈保护、PIE 和完整 RELRO。Linux 文件权限、认证后提交及取消清理沿用 CLI/Core；Core 4.0 新增 NKEM v4 多接收方容器，NKEM v3、NKPR v1、KDF 参数与 64 KiB 流式处理不变。详见 [Linux 安装与构建](desktop/README.md#linux-installation-and-build--linux-安装与构建) 和 [Linux GUI 安全边界](desktop/LINUX-SECURITY.md)。

v4.0.1 Release 包含上述两种架构的全部 Linux GUI 安装包和便携包，现有静态 Linux CLI 包继续保留。原生窗口测试使用 X11 会话，不代表已验证所有 Linux 发行版或 Wayland 实机。

## 语言设置

Android、Windows/macOS/Linux GUI 和 CLI 支持简体中文（`zh-CN`）、繁体中文（`zh-TW`）、英语（`en`）、日语（`ja`）和韩语（`ko`）。默认跟随系统；不支持的语言回退英语。中文 CN/SG 地区匹配简体，TW/HK/MO 匹配繁体；en/ja/ko 的其他地区匹配对应语言。Android 跟随系统时会响应系统语言变化；CLI 和桌面 GUI 启动时检测语言，GUI 也可在语言选择器中重新选择跟随系统。

Android 在 **设置 → 语言** 中选择 **跟随系统** 或指定语言。选择会持久保存并刷新界面。Android 13 及以上与系统级应用语言设置共用同一偏好；旧系统使用私有偏好设置。文件操作结束后可切换语言。页面和对话框支持滚动，以容纳长文本及较大字体。

CLI 示例：

```sh
./nekokem --lang ja --help
./nekokem --lang zh-TW encrypt hybrid test.txt test.nkem keys/public.key
./nekokem --set-lang ko
./nekokem --set-lang system
./nekokem --lang system --help
```

`--lang` 仅影响本次命令。Linux 和 macOS 的 `--set-lang` 将默认语言保存到 `$XDG_CONFIG_HOME/nekokem/language`；XDG_CONFIG_HOME 未设置或为相对路径时使用 `$HOME/.config/nekokem/language`。配置文件权限为 `0600`，无需 root 权限。优先级为 `--lang` → 已保存设置 → `LC_ALL` → `LC_MESSAGES` → `LANG` → 平台默认语言（Linux 为英语，macOS 通过 CoreFoundation 读取首选语言）。`system` 恢复自动检测；无效或损坏的设置安全回退系统检测。系统检测支持 `zh_CN.UTF-8`、`ja_JP.UTF-8` 等常见 POSIX Locale。显式 ASCII 或非 UTF-8 终端设置下 CLI 提示回退英语；macOS 未设置 Locale 环境变量的原生应用按 UTF-8 处理。全局语言选项放在命令之前；命令之后的文件参数按原样处理。可使用 `--` 显式结束全局选项解析。语言配置和帮助不会交互询问语言。

协议标识、密码学算法名、CLI 参数、环境变量名及 `--version` 输出保持不变。上游 OpenSSL/操作系统的诊断细节保留原文。这些语言参数从 v3.2.0 开始提供，旧版二进制可能不支持。

## 目录结构

交互模式统一使用以下目录：

```text
plaintext/
  明文文件

encrypted/
  NKEM 密文文件

keys/
  密钥文件
```

`plaintext/`、`encrypted/` 和 `keys/` 会在对应操作需要时以 `0700` 创建。若目录已经存在，程序会拒绝符号链接、非目录、非当前用户所有或权限不是 `0700` 的路径。参数化兼容命令仍允许通过 `output_file` 手动指定完整输出路径。

## 默认交互模式

直接运行：

```sh
./nekokem
```

选择简体中文时显示：

```text
====================
      NekoKEM
====================

1. 生成密钥
2. 加密文件
3. 解密文件
4. 查看公钥指纹
5. 退出
```

交互模式固定使用 NKEM v3 Hybrid。Hybrid 算法仍为 X448 + ML-KEM-1024、HKDF-SHA512 和 AES-256-GCM。

### 生成密钥

选择“生成密钥”会确保当前工作目录存在权限为 `0700` 的 `keys/`，并生成：

- `keys/public.key`：依次包含 X448 和 ML-KEM-1024 的明文公钥；
- `keys/private.key.enc`：包含两个完整私钥 PEM 的加密 NKPR 容器，权限为 `0600`。

生成前会要求输入并确认私钥保护密码；终端回显在两次输入期间均关闭。不会创建明文 `private.key`。

生成密钥绝不覆盖已有密钥：只要 `keys/public.key` 或 `keys/private.key.enc` 已存在，程序会在询问密码前直接拒绝，两个文件保持不变。确需更换密钥时，请先备份旧私钥（用旧公钥加密的文件只能用旧私钥解密），再使用下文的 `keygen --replace`。

### 加密文件

可以输入公钥文件路径，也可以粘贴两个 PEM 公钥块。随后输入原文件路径。程序会确保当前工作目录下存在权限为 `0700` 的 `encrypted/` 目录；如果不存在则自动创建。

输出只使用原文件名，并保存到 `encrypted/`，同时追加 `.nkem`：

```text
plaintext/test.jpg -> encrypted/test.jpg.nkem
```

### 解密文件

可以输入私钥文件路径，也可以继续粘贴两个兼容的明文 PEM 私钥块。私钥文件内容是 NKPR 容器时（按文件头识别，与扩展名无关，例如 Android 导出的 `private.nkpr`），程序会自动关闭终端回显并提示输入密码，在内存中认证、解密并解析 NKPR；解出的 PEM 不会写入磁盘。旧的明文 PEM 私钥（如 `private.key`）不需要密码。

粘贴旧式私钥内容时终端回显同样会被临时关闭；内部使用的 `0600` 临时密钥文件会在操作结束后删除。

输入文件必须以 `.nkem` 结尾。程序会确保当前工作目录下存在权限为 `0700` 的 `plaintext/`；如果不存在则自动创建。输出只使用容器文件名，自动去掉 `.nkem`，并恢复到 `plaintext/`：

```text
encrypted/test.jpg.nkem -> plaintext/test.jpg
```

解密仍使用原子输出。只有 Hybrid 密钥解封装及 AES-GCM 认证全部成功，目标文件才会提交；失败时不会留下未认证明文。

### 公钥指纹

“查看公钥指纹”接受公钥文件或粘贴内容，并显示大写、冒号分隔的 SHA-256 指纹。指纹输入是带域分隔字符串的两个公钥 DER SubjectPublicKeyInfo 编码，按 X448、ML-KEM-1024 顺序排列，每段前带 4 字节大端长度。因此同一对公钥不受 PEM 换行方式影响。

## 参数化命令

参数化命令供脚本和开发测试使用。默认 keygen 与交互模式一致，生成受密码保护的 Hybrid 密钥：

```sh
./nekokem keygen
```

`keygen hybrid` 保留为含义相同的显式兼容别名：

```sh
./nekokem keygen hybrid
```

两种写法都会提示输入两次密码，且不会把密码放入命令行参数；输出 `keys/public.key` 与 `keys/private.key.enc`。已有任一密钥文件时直接拒绝，不会覆盖。

确需轮换密钥时，先备份旧私钥，再显式使用 `--replace`；程序会提醒用旧公钥加密的文件只能用旧私钥解密，并在询问密码前给出按 Ctrl+C 放弃的机会：

```sh
./nekokem keygen --replace
```

默认 v3 hybrid 加密和解密：

```sh
./nekokem encrypt hybrid test.txt encrypted/test-v3.nkem keys/public.key
./nekokem decrypt hybrid encrypted/test-v3.nkem output.txt keys/private.key.enc
```

在原有加密命令后列出两个或更多公钥，会写出一个所有接收方都能解密的 NKEM v4 文件；任一公钥无效或重复时不会创建输出：

```sh
./nekokem encrypt hybrid test.txt encrypted/team.nkem alice.key bob.key carol.key
```

私钥文件内容是 NKPR 时，Hybrid decrypt 会自动提示一次密码；判断依据是文件头，不看扩展名。为兼容已有部署，命令仍接受包含 X448、ML-KEM-1024 两个 PEM 块的旧式明文 `private.key`。

输出路径不能是本次操作使用的密钥文件：解密时不能写到私钥上，加密时不能写到任一公钥上。比较的是文件身份而非路径字符串，`./keys/../keys/private.key.enc` 这类别名同样会被拒绝，密钥文件保持不变。

命令可以使用其他位置的相应 PEM 密钥：

```text
./nekokem encrypt hybrid input_file output_file public.key
./nekokem decrypt hybrid input_file output_file private.key.enc
```

输入必须是普通文件。工具使用分块 I/O，不会把整个文件载入内存。输出先写入同目录下权限为 `0600` 的临时文件并执行 `fsync`；加密成功或 GCM 标签验证成功后才原子重命名为目标文件，随后 `fsync` 父目录。认证失败、密钥错误、取消或容器解析失败不会提交解密目标文件。

## NKPR 加密私钥格式

NKPR 是独立于 NKEM 文件容器的私钥存储格式；NKEM v3 不修改 NKPR version 1。当前 NKPR 固定使用：

- Argon2id：64 MiB（`65536` KiB）、3 次迭代、并行度 4、Argon2 version 1.3；
- 32 字节随机 salt；
- 32 字节派生密钥；
- AES-256-GCM、12 字节随机 nonce、16 字节认证标签。

固定头部为 36 字节，所有多字节整数采用大端序：

| 偏移 | 长度 | 字段 |
|---:|---:|---|
| 0 | 4 | magic：ASCII `NKPR` |
| 4 | 1 | container version：`1` |
| 5 | 1 | KDF id：`1`，Argon2id |
| 6 | 1 | cipher id：`1`，AES-256-GCM |
| 7 | 1 | flags：`0` |
| 8 | 4 | Argon2 memory cost：`65536` KiB |
| 12 | 4 | Argon2 iterations：`3` |
| 16 | 4 | Argon2 parallelism：`4` |
| 20 | 4 | Argon2 version：`0x13` |
| 24 | 2 | salt 长度：`32` |
| 26 | 1 | nonce 长度：`12` |
| 27 | 1 | tag 长度：`16` |
| 28 | 8 | 加密后的 PEM 长度 |

头部后依次为：

```text
32-byte salt || 12-byte nonce || encrypted hybrid private PEM || 16-byte tag
```

`header || salt || nonce` 全部作为 AES-GCM AAD。读取器严格验证版本、算法、参数、长度和 GCM 标签；错误密码与任何受认证字段或密文的修改都会失败。解密缓冲区即使在标签验证前产生数据也始终只驻留内存，并在失败路径清零。

## NKEM v3 hybrid 文件格式

v3 使用 X448、ML-KEM-1024、共享秘密组合和 HKDF-SHA512，并将 HKDF salt 与 AES-GCM nonce 分离。每个文件分别通过 `RAND_bytes` 生成 32 字节 salt 和 12 字节 nonce；两者连同固定头部、X448 临时公钥和 ML-KEM 密文一起纳入 GCM AAD。完整布局见 [`docs/NKEM-v3.md`](docs/NKEM-v3.md)。`nekokem_encrypt_file()` 写出 v3，`nekokem_decrypt_file()` 接受 v3 与 v4。

## NKEM v4 多接收方文件格式

v4 用随机 32 字节文件密钥只加密一次文件数据，并为每位接收方（最多 64 位）各保存一条记录：X448 临时公钥、ML-KEM-1024 密文和用 AES-256-GCM 包装的文件密钥。包装密钥由 HKDF-SHA512 从该接收方的两个共享秘密派生，并绑定 X448 临时公钥与接收方 X448 公钥。HMAC-SHA512 头部 MAC 覆盖头部、salt 和全部接收方记录，承诺文件密钥，使所有接收方解出相同内容；记录不含指纹，解密时尝试全部记录，失败时的报错和耗时都不暴露哪条记录属于自己。完整布局见 [`docs/NKEM-v4.md`](docs/NKEM-v4.md)。`nekokem_encrypt_file_multi_with_progress()` 为两个及以上公钥写出 v4，只有一个公钥时写出 v3。

## 安全实现说明

### OpenSSL EVP

- X448、ML-KEM-1024、HKDF、Argon2id 和 AES-256-GCM 均使用 OpenSSL EVP/provider API，不自行实现密码算法，也不引入 liboqs；
- 所有 `EVP_PKEY`、`EVP_PKEY_CTX`、`EVP_CIPHER_CTX`、`EVP_KDF`、`EVP_KDF_CTX`、`EVP_MD_CTX` 和 `BIO` 都有统一的成功/失败释放路径；
- OpenSSL 错误路径通过 `ERR_get_error()` 循环取出并清空当前线程的错误队列；
- 私钥保留在 OpenSSL 的不透明 `EVP_PKEY`/provider 对象内，不导出原始私钥副本，并使用 `EVP_PKEY_free()` 释放。

### 敏感数据清零

- `core/src/secure_mem.c` 统一提供 `secure_mem_clear()` 和 `secure_free()`，底层分别使用 `OPENSSL_cleanse()` 与 `OPENSSL_clear_free()`，不使用可能被死存储消除优化掉的 `memset()` 清理秘密；
- ML-KEM/X448 共享秘密在 HKDF 完成后立即清零释放，不再保留到整个文件操作结束；
- Hybrid HKDF 所需的 `x448_secret || mlkem_secret` 是协议要求的唯一组合副本，在 KDF 上下文释放后立即清零；
- AES 密钥和包含明文的 AES 分块缓冲区在所有成功与失败出口清零；
- 私钥密码、密码确认副本、Argon2id 派生密钥和内存中的完整 Hybrid PEM 在所有成功与失败出口清零；正确加载并解析 PEM 后立即释放密码；
- KEM 失败路径保存原始分配容量，确保即使 OpenSSL 改写输出长度，也会清零整个已分配秘密缓冲区；
- 私钥以 `O_NOFOLLOW|O_CLOEXEC` 打开，并在读取前验证为当前用户所有、`0600`、普通非空文件且硬链接数为 1；PEM 通过有大小上限的单一 OpenSSL 缓冲区和只引用该缓冲区的 memory BIO 解析，解析后立即清零释放；敏感文件流和原子输出流关闭 stdio 缓冲，减少不可控副本。

### 编译保护

- 所有警告按错误处理：`-Werror`；
- 栈破坏检测：`-fstack-protector-strong`；
- libc 边界强化：`-D_FORTIFY_SOURCE=3`；
- 位置无关可执行文件：编译使用 `-fPIE`，链接使用 `-pie`；
- 原有 `-Wall`、`-Wextra`、`-Wconversion`、`-Wshadow`、`-Wformat=2` 和 `-Wstrict-prototypes` 保持启用。

### 资源生命周期管理

- 动态资源采用单一所有者和 `goto cleanup` 路径，成功转移所有权时立即清空源指针；
- 私钥对象在解封装完成后立即释放，共享秘密在 KDF 完成后立即清零释放，AES 密钥仅存活到文件加解密结束；
- 输出仍先写入同目录的临时文件，文件内容 `fsync` 完成后才重命名，重命名后再 `fsync` 父目录；认证、解析、取消或 I/O 失败会关闭并删除临时文件；
- 公私钥生成使用同一个可回滚事务：两边都完成写入和 `fsync` 后才发布；第二次 rename 或目录 `fsync` 失败会恢复旧公私钥（原本不存在则两边都移除），避免只更新一把密钥；
- POSIX 上，成对密钥提交持有父目录的进程间锁直到回滚和清理结束；回滚前核对已发布文件的 inode，避免删除其他写入者替换后的文件。目录中的 `0600` 锁文件 `.nekokem-pair.lock` 会保留，运行期间不要删除；
- Hybrid keygen 直接把两个私钥 PEM 写入 OpenSSL memory BIO，再加密为 NKPR 暂存文件，并与公钥一致提交；没有明文私钥输出文件或明文私钥临时文件；
- CLI 的 Core 接口当前是路径式 API。粘贴 PEM 因而使用 `/tmp` 下独占 `0700` 目录中的 `0600`、`O_NOFOLLOW|O_CLOEXEC` 临时文件，并在所有返回路径删除文件和目录。直接 memory BIO 需要新增内部 Core 适配层，memfd 的 `/proc/self/fd` 路径又会与私钥 `O_NOFOLLOW` 策略冲突；本阶段不改变公开 Core API，后续可在独立 API 设计中消除该临时路径；
- `secure_free()` 只用于 `OPENSSL_malloc()` 分配的敏感缓冲区，普通路径字符串和公开元数据仍由匹配的常规分配器释放。

## 性能测量

[大文件性能基线与复现方法](performance/README.md)记录 v3.3.1 Linux x86_64
正式发布 CLI 的 1 GiB / 8 GiB 原始样本、CPU 时间、内存和 I/O 数据。
`scripts/profile_large_files.py` 使用一次性受口令保护的密钥，逐轮验证 SHA-256，
最多同时保留两份大文件。跨版本、Linux/Windows 吞吐比较继续使用
`scripts/benchmark_throughput.py`；具体速度取决于机器、文件系统和缓存状态。

## 自动测试

```sh
make -C linux test
```

测试会在临时目录中执行：

- 首先使用 GCC `-fanalyzer` 检查泄漏、空指针、未初始化读取、重复释放等资源问题；
- 构建启用 `-fsanitize=undefined` 且禁止恢复的临时二进制，并覆盖成功路径、缺失输入、无效输出目录、截断容器、篡改密文、无效私钥和错误私钥；
- 自动生成 NKEM/NKPR 截断、错误长度、非法版本、非法算法 ID、随机字节和超长字段，并在 UBSan 下调用正式静默解析入口；
- 对 CLI 普通输入和粘贴 PEM 分别实施单行上限，超长输入完整消费后拒绝，不会把残余字节留给下一次提示；
- 验证已有 `0700` 私有目录的类型、所有者和权限，以及私钥 `0600`、所有者、普通文件、符号链接和硬链接约束；
- 通过仅测试构建启用的故障注入覆盖短写、ENOSPC、文件/目录 `fsync`、第二次 rename、公私钥回滚和临时/备份文件清理；

- Hybrid 加密私钥 keygen、正确密码解密、SHA-256 和 `cmp`；
- 默认 v3 加密/解密回环，并验证旧版本号容器被拒绝；
- 错误密码和篡改 NKPR 必须认证失败，且不能产生目标明文；
- 检查 Hybrid keygen 不留下明文 `private.key`、PEM 内容或原子临时文件；
- hybrid 密文修改后必须认证失败，且不能产生目标明文；
- 使用错误 hybrid 私钥必须认证失败，且不能产生目标明文；
- 检查 `keys/private.key.enc` magic 为 `NKPR` 且权限为 `0600`；
- 检查默认菜单、交互式 Hybrid 密钥生成和 SHA-256 指纹；
- 检查交互加密自动创建权限为 `0700` 的 `encrypted/`，并把绝对路径输入正确映射为 `encrypted/<文件名>.nkem`；
- 检查交互解密自动创建权限为 `0700` 的 `plaintext/`，并把 `encrypted/<文件名>.nkem` 恢复为 `plaintext/<文件名>`；
- 对交互模式恢复的明文继续执行 SHA-256 和 `cmp`；
- 通过伪终端哨兵测试分别确认粘贴私钥和输入保护密码时关闭回显，且测试记录权限为 `0600`。

- 五语言资源和目录完整性、格式参数一致性、Locale/配置/命令行优先级、持久化、安全回退、非交互帮助及错误本地化。文档检查验证双语导航和相对链接。

测试材料随后删除。Android JVM 和真实 API 26/29/35 设备 instrumentation 在 CI 中执行；仅构建 test APK 不算设备测试通过。设备测试保留 JNI/SAF 集成覆盖，并验证语言切换、Activity 重建、进程重启、系统应用语言同步、无障碍、深色模式及字体放大。

## AFL++ fuzzing

```sh
make -C linux fuzz-build
```

该目标使用 `afl-clang-fast` 和 UBSan 构建：

- `core/fuzz/bin/fuzz_nkem`：仅解析 NKEM v3 与 v4 header 及容器总长度；
- `core/fuzz/bin/fuzz_nkpr`：仅解析 NKPR header、参数与容器总长度；
- `core/fuzz/bin/fuzz_decrypt`：对输入执行完整解密，覆盖 X448 与 ML-KEM-1024 解封装、v4 记录解包与头部 MAC、流式 AES-GCM 认证以及原子输出提交与回滚。

三个 harness 都接受一个 `argv[1]` 文件路径。两个解析器 harness 拒绝超过 2 MiB 的输入，不执行密钥解封装、Argon2id、AES-GCM 或明文写出；`core/fuzz/seeds/` 中的解析器样本只是零填充的结构样本，不含密码、私钥或真实敏感数据，同时提供多个截断样本。`fuzz_decrypt` 的种子在每次构建时生成：一把一次性的明文 PEM 测试密钥，以及用它加密的真实 v3、空 v3 和双接收方 v4 容器，因此变异能深入试解密、MAC 和载荷认证路径；该密钥不保护任何数据，也不会提交到仓库。具体 AFL 命令见 `core/fuzz/README.md`。

## 安全边界

- Hybrid 私钥现在使用口令保护，但安全性仍取决于用户选择足够强且唯一的密码，以及操作系统对进程内存、终端和文件的保护；
- 文件长度和所选算法等容器元数据不是机密；NKEM v4 还会暴露接收方数量（不暴露身份）；
- NKEM 不认证发送者：v4 的接收方共享文件密钥，任一接收方都能制作复用同一接收方列表的新文件，接收方列表不能证明发件人；
- v4 隐藏的是哪条记录属于哪把私钥，而不是某把私钥能否解开文件：能提交文件解密并看到结果的人，用原文件就能知道这一点；
- X448 与 ML-KEM 的组合及本项目的 KDF/AAD 绑定方式是实验性设计，不代表经过标准化的 hybrid KEM；
- 已进行项目内解析器 fuzz，但未经独立第三方审计或广泛互操作测试；
- 实现不能替代成熟、经过审计的文件加密协议和密钥管理系统。

## Bug 反馈与功能建议

普通 Bug、兼容性问题和功能建议请统一通过
[GitHub Issues](https://github.com/Shixiaoshi0417/NekoKEM/issues) 提交。提交前请避免附加私钥、密码、明文敏感数据或其他个人信息。

## 安全声明与漏洞报告

NekoKEM 使用现代公开密码算法，但目前尚未经过独立的专业安全审计。暂不建议将其用于需要正式合规认证，或保护高价值、需要长期保密的数据。

安全漏洞请勿公开提交 GitHub Issue。请按照 [`SECURITY.md`](SECURITY.md) 的说明私下发送至
[shixiaoshi@shixiaoshi0417.com](mailto:shixiaoshi@shixiaoshi0417.com)。

## License

本项目采用 [Apache License 2.0](LICENSE) 开源许可证。
