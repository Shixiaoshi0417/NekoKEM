# NekoKEM Android v3.3.1

<p align="center">
  <img src="../docs/icon-rounded.png" alt="NekoKEM App icon" width="160">
</p>

Android App 通过窄 JNI Bridge 调用可复用的 NekoKEM C17 Core。Kotlin 和
JNI 不实现或解析 X448、ML-KEM-1024、HKDF、AES-GCM、NKEM 或 NKPR。
Core 仅处理 NKEM v3 文件容器；NKEM v1/v2 已不再支持。NKPR 是独立的
私钥容器，仍由 Core 处理且格式不变。

当前正式 App 版本为 `3.3.1`，Core 版本保持 `3.1`，Android application ID
与 namespace 均为 `com.shixiaoshi0417.nekokem`。协议容器版本仍为
**NKEM v3**，与 App 版本号相互独立；NKPR 格式保持不变。

v3.3.1 沿用 v3.2.0 发布签名，可直接覆盖升级。升级前建议导出公钥和加密 NKPR 私钥，验证备份并保存私钥密码。仅旧签名 v3.1.x 用户需要在完成备份后卸载重装；卸载会删除内部密钥。详见 [v3.3.1 中英双语更新日志](../release/v3.3.1.md)，历史签名迁移记录保留在 [v3.2.0](../release/v3.2.0.md)。

README 图标仅使用专用圆角展示图，Android launcher 继续使用现有方形白底图案。应用是未经独立专业安全审计的实验性工具，不应用于保护重要或敏感数据。

## 工具链

- Kotlin、Jetpack Compose、Material 3、Gradle Kotlin DSL
- Android SDK 35（`minSdk 26`、`targetSdk 35`）
- Android NDK r28c（`28.2.13676358`）、CMake、C17
- 仅 `arm64-v8a`
- Android arm64 OpenSSL 4.0.3 静态 `libcrypto.a`

不使用 Android 系统 OpenSSL。可复现构建脚本位于
`scripts/build-openssl-android.sh`。

## 私钥与口令

密钥只存放在 Android App 内部私有目录：

```text
context.filesDir/
└── keys/                 mode 0700
    ├── public.key
    └── private.nkpr.enc  mode 0600, NKPR
```

主页不保存口令，也不显示常驻口令输入框。生成密钥、检查私钥密码、导入
加密私钥和解密文件分别使用一次性对话框。生成时要求输入并确认非空口令；
其他操作只请求一次口令。对话框取消时不调用 Core。

口令不写入文件、SharedPreferences 或日志。Compose 对话框状态使用临时
`ByteArray`，交给一次后台调用后立即清零；JNI 的 OpenSSL 缓冲区在每条返回
路径使用 `OPENSSL_clear_free()`。App 不建立持久解锁状态，不缓存口令或
明文 PEM。

导入私钥时先选择 NKPR 文件，再请求口令。Core 在私有候选文件中验证格式和
口令，成功后才用原子重命名替换现有私钥；错误、取消或认证失败不会覆盖旧
私钥。删除私钥前显示确认对话框，并清除 `cacheDir/nekokem-work` 暂存文件。

## 公钥通讯录（开发分支）

侧边菜单的“公钥通讯录”支持导入接收方公钥、添加或编辑备注、删除条目。
加密页可选择已保存的公钥；“用于加密”也会返回文件页并选中对应条目。
备注可留空，最多 512 个字符。相同指纹只保存一个条目，重复导入时确认更新备注。
备注和文件名长度按 Unicode 字符计算（一个 emoji 计为一个字符）；原文件名会去除控制字符和
双向文本格式字符，最多保留 128 个字符。
列表显示原文件名和完整 SHA-256 指纹；备注仅用于查找，请通过可信渠道与接收方核对指纹。

通讯录与自己的默认密钥分开，保存在 `filesDir/public-key-contacts/`（`0700`）。
每条 `0600` JSON 记录包含版本、指纹、原文件名、备注和 Base64 编码的规范化公钥，
写入时同步文件并原子重命名，再同步目录。公钥先由现有 Core 校验和规范化，
不保存私钥或密码；不增加存储权限、备份或网络通信。

记录在 App 重启后重新读取。选中的条目在连续加密及 Activity 重建后保持选择；
每次加密都从记录创建独立私有快照，通过 Core 重新解析并核对指纹，临时快照在操作后清理。
条目已删除、损坏、身份不符或权限异常时拒绝加密，用户需要显式重选或恢复默认公钥。
删除自己的默认密钥不会删除通讯录；取消操作和清理工作缓存也不会删除已保存条目。
卸载或清除 App 数据会删除通讯录，请保留原始公钥备份。

现有 API 26/35 设备测试覆盖备注持久化（包括进程重启）、去重、接收方解密、取消、
无效记录、指纹不符、链接与权限检查，以及真实 Compose 页面中的备注编辑、选择、
Activity 重建和删除。NKEM/NKPR、密码算法与参数、JNI 接口均保持原有行为。

## 预测性返回（开发分支）

App 启用 Android 预测性返回（`android:enableOnBackInvokedCallback="true"`）。在密钥管理、
公钥通讯录、设置或关于页面侧滑返回时，当前页面跟随手指缩小、向内平移并显示圆角，
下方预览文件加密页；松手完成返回文件页，取消手势则页面复原。侧边菜单打开时，返回手势
由 Material 抽屉跟手关闭菜单，不会退出 App。文件页不拦截返回，由系统显示返回桌面动画。
文件操作进行中不拦截返回，原有进度对话框行为不变。Android 14 及以上提供跟手进度；
Android 8–13 收到返回时直接回到文件页或关闭菜单。

预览层只用于显示，不进入无障碍树。API 26/35 设备测试通过 Activity 的
`OnBackPressedDispatcher` 发送开始、进度、取消和完成事件，校验页面随进度缩放平移、
取消后复原、完成后回到文件页且 Activity 不结束，以及菜单关闭和无进度返回，
并保存一张手势进行中的截图。

## Storage Access Framework

文件选择使用 `OpenDocument`，输出使用 `CreateDocument`。Manifest 不申请
`READ_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE` 或管理外部存储权限。

`content://` URI 不能可靠转换为文件系统路径，因此 Android 层使用
`ContentResolver` 将输入流暂存到 App 私有目录：

```text
SAF content URI
    ↓ Android ContentResolver（只复制字节，不解析格式）
cacheDir/nekokem-work/*  mode 0600
    ↓ JNI 路径转换
nekokem_core
    ↓ 认证成功后
SAF output URI
```

工作目录权限为 `0700`，每次操作后暂存常规文件会先覆写再删除。解密时，
Core 完成 GCM 认证并原子提交私有暂存输出后，App 才请求输出文档位置。
密码错误、NKEM 认证失败或取消不会创建明文目标；已创建但未成功提交的 SAF
目标会被删除。当前暂存输入上限为 8 GiB。SAF provider 最终写入本身不具备
跨 provider 的统一原子提交保证。

加密建议名为原文件名加 `.nkem`。解密建议名移除末尾 `.nkem`；没有该后缀
时使用 `decrypted_` 前缀。保存界面允许修改名称，并按恢复后扩展名设置 MIME。

## 进度与取消

加解密在 `Dispatchers.IO` 后台协程运行。JNI 同步转发 Core 的
`processedBytes`、`totalBytes` 回调；Core 默认 v3 数据以 64 KiB 分块处理。
UI 每 150 ms 至多更新一次，速度使用最近两秒的滑动平均，并显示百分比、
已处理/总大小、实时速度和预计剩余时间。

取消按钮通过原子标志使下一次 Core 回调返回 `false`。暂存复制和最终 SAF
提交也检查同一取消状态。Core、私有缓存和 SAF 各层均只在完整成功后提交；
取消或失败会清理部分输出和临时文件。成功状态显示总耗时与平均速度。

## JNI API

- `nativeGenerateKeypairWithPassword(publicPath, privatePath, password)`
- `nativeUnlockPrivateKey(privatePath, password)`
- `nativeCheckPassword(privatePath, password)`
- `nativeHasPrivateKey(privatePath)`
- `nativeExportPublicKey(publicPath, outputPath)`
- `nativeDeletePrivateKey(privatePath)`
- `nativeGetFingerprint(publicPath)`
- `nativeEncryptFileWithProgress(inputPath, outputPath, publicPath, callback)`
- `nativeDecryptFileWithProgress(inputPath, outputPath, privatePath, password, callback)`
- 原有 `nativeEncryptFile()`、`nativeDecryptFile()` 兼容入口

JNI 只做路径、字符串、字节数组、回调和返回值转换；不解析 NKEM/NKPR，
也不实现密码算法。

## UI

密钥管理区提供生成、一次性口令检查、密钥导入导出和私钥删除。文件区提供
选择、加密和解密。App 重启后，私钥状态和公钥指纹直接从 `filesDir` 与 Core
重新读取，不依赖进程内缓存。

## 构建

在未跟踪的 `local.properties` 中配置 Android SDK，然后运行：

```sh
./gradlew assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。Android CMake 不编译
`main.c` 或 `cli.c`。

正式 APK 使用 [Release 工作流](../.github/workflows/release.yml) 构建并校验现有签名；JVM、JNI/SAF 和 API 26/35 模拟器回归统一由 [CI 工作流](../.github/workflows/ci.yml) 执行。公开附件为 `app-release.apk`，使用 Release 顶层 `SHA256SUMS.txt` 校验；签名恢复包不作为公开附件。
