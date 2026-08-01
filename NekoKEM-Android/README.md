# NekoKEM Android

Android App 通过窄 JNI Bridge 调用可复用的 NekoKEM C17 Core。Kotlin 和
JNI 不实现或解析 X448、ML-KEM-1024、HKDF、AES-GCM、NKEM 或 NKPR。
NKEM v1/v2/v3 与 NKPR 协议仍由 Core 统一处理。

## 工具链

- Kotlin、Jetpack Compose、Material 3、Gradle Kotlin DSL
- Android SDK 35（`minSdk 26`、`targetSdk 35`）
- Android NDK r28c（`28.2.13676358`）、CMake、C17
- 仅 `arm64-v8a`
- Android arm64 OpenSSL 3.5.6 静态 `libcrypto.a`

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
