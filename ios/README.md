# iOS 无签名 IPA / iOS unsigned IPA

## 中文

iOS 应用复用 macOS GUI 的 SwiftUI 源码（`macos/gui/Sources/NekoKEM`，`main.swift` 除外）、Rust bridge（`desktop/bridge`）和 C17 Core，`ios/Sources` 只放 iOS 入口、系统文件选择器和文件位置。界面为文件、密钥、通讯录和设置四个标签页，支持 iPhone 与 iPad，最低 iOS 16，仅 `arm64`。

### 安装

Release 资产 `NekoKEM-iOS-arm64-unsigned.ipa` 未签名，也没有描述文件和 entitlements。用自签工具安装：

- TrollStore：直接安装，长期有效。
- AltStore、SideStore 等：用自己的 Apple ID 重签后安装；免费账号签出的应用通常每 7 天需要续签。

先用 Release 的 `SHA256SUMS.txt` 校验 IPA。IPA 内 `Payload/NekoKEM.app/build-metadata.json` 记录源码提交和版本。

### 文件位置

- 加密、解密和导出的结果保存在 App 的「文稿」目录，在「文件」App 的「我的 iPhone › NekoKEM」中可见，同名文件自动编号，不覆盖。完成后也可以直接分享。
- 新生成的密钥对写到「文稿/Keys」；已存在时会提示是否替换。
- 通过系统文件选择器选取的文件会复制进 App：密钥保存在 Application Support，待加解密的文件放在临时目录，由系统清理。
- 设置和通讯录保存在 Application Support（相当于桌面端的 `$XDG_CONFIG_HOME/nekokem`）。每次安装 App 容器路径会变，启动时会把保存的密钥路径换到新容器。
- 私钥密码可以保存到钥匙串，读取前要求 Face ID、Touch ID 或设备密码；不同步到 iCloud 钥匙串。用户自签的 App 是否能使用钥匙串取决于签名工具写入的 entitlements，不能使用时会提示未保存。

iOS 不能在后台调用 GitHub 检查更新，设置页提供打开最新 Release 页面的按钮；也没有「保存到指定文件夹」选项。

### 构建

需要原生 Apple Silicon macOS、Xcode（iOS 26 SDK）、Rust 1.99.0 及 `aarch64-apple-ios`、`aarch64-apple-ios-sim` 目标。从仓库根目录运行：

```sh
bash ios/scripts/build-openssl.sh "$PWD/ios/out/openssl" device
NEKOKEM_OPENSSL_PREFIX="$PWD/ios/out/openssl" bash ios/scripts/build-ipa.sh ios/out device
```

输出为 `ios/out/NekoKEM-iOS-arm64-unsigned.ipa` 和 `ios-SHA256SUMS.txt`。脚本不使用 Xcode 工程：先以 `staticlib` 构建 bridge 和固定的静态 OpenSSL 4.1.0，再用 `swiftc` 链接，检查可执行文件的平台、架构以及没有动态链接 OpenSSL，最后打包 `Payload/NekoKEM.app`。传入 `simulator` 则构建 ad-hoc 签名的模拟器版 `NekoKEM.app`。

CI 的 `ios` 作业构建 IPA 和模拟器版，并在 iPhone 模拟器上运行 `ios/scripts/simulator-check.sh`：通过 bridge 生成密钥、加密并解密一个文件，确认启动时能读取设置，再为每个页面截取浅色和深色截图。Release 的 `build-ios` 作业构建同一个 IPA 并纳入发布资产。

## English

The iOS app reuses the macOS GUI's SwiftUI sources (`macos/gui/Sources/NekoKEM`, except `main.swift`), the Rust bridge (`desktop/bridge`) and the C17 Core; `ios/Sources` holds only the iOS entry point, the system document picker and file locations. Files, Keys, Contacts and Settings are four tabs on iPhone and iPad, iOS 16 or newer, `arm64` only.

### Installation

The release asset `NekoKEM-iOS-arm64-unsigned.ipa` is unsigned and has no provisioning profile or entitlements. Install it with a sideloading tool:

- TrollStore: installs it directly and permanently.
- AltStore, SideStore and similar: re-sign it with your own Apple ID; apps signed with a free account usually need renewing every 7 days.

Verify the IPA against the release's `SHA256SUMS.txt` first. `Payload/NekoKEM.app/build-metadata.json` inside the IPA records the source commit and version.

### File locations

- Encrypted, decrypted and exported files go to the app's Documents folder, which the Files app shows under On My iPhone › NekoKEM. A taken name gets a number instead of being replaced. A finished file can also be shared directly.
- A new key pair is written to Documents/Keys; when one exists there, replacing it is offered.
- Files chosen in the system document picker are copied into the app: keys to Application Support, files to encrypt or decrypt to the temporary folder, which the system empties.
- Settings and contacts are kept in Application Support (the desktop's `$XDG_CONFIG_HOME/nekokem`). Each install moves the app's container; saved key paths are moved to the new container at launch.
- The private key password can be saved to Keychain and is read back only after Face ID, Touch ID or the device passcode; it is not synced to iCloud Keychain. Whether a sideloaded app can use Keychain depends on the entitlements its signing tool adds; if it cannot, the app reports that the password was not saved.

iOS cannot ask GitHub for updates in the background, so Settings has a button that opens the latest release page. There is no decrypt-folder option.

### Building

Requires native Apple Silicon macOS, Xcode with the iOS 26 SDK, and Rust 1.99.0 with the `aarch64-apple-ios` and `aarch64-apple-ios-sim` targets. From the repository root:

```sh
bash ios/scripts/build-openssl.sh "$PWD/ios/out/openssl" device
NEKOKEM_OPENSSL_PREFIX="$PWD/ios/out/openssl" bash ios/scripts/build-ipa.sh ios/out device
```

The output is `ios/out/NekoKEM-iOS-arm64-unsigned.ipa` with `ios-SHA256SUMS.txt`. No Xcode project is used: the script builds the bridge as a `staticlib` against the pinned static OpenSSL 4.1.0, links it with `swiftc`, checks the executable's platform and architecture and that OpenSSL is not linked dynamically, and packs `Payload/NekoKEM.app`. `simulator` builds an ad-hoc signed `NekoKEM.app` for the simulator instead.

The CI `ios` job builds the IPA and the simulator app, then runs `ios/scripts/simulator-check.sh` on an iPhone simulator: it generates keys, encrypts and decrypts a file through the bridge, checks that a normal start reads the settings, and captures every page in light and dark appearance. The Release `build-ios` job builds the same IPA for the release assets.
