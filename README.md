# NekoKEM

<p align="center">
  <img src="icon.png" alt="NekoKEM 项目图标" width="160">
</p>

NekoKEM 是一个用于学习 OpenSSL 3.5 EVP API 的实验性后量子文件加密工具。默认 Hybrid 加密生成 NKEM v3；解密按 version 严格分派并兼容 v1/v2。

| 模式 | 密钥建立 | KDF | 文件加密 | 容器 |
|---|---|---|---|---|
| v1 | ML-KEM-1024 | HKDF-SHA256 | AES-256-GCM | version 1 / algorithm id 1 |
| v2 hybrid | X448 + ML-KEM-1024 | HKDF-SHA512 | AES-256-GCM | version 2 / algorithm id 2 |
| v3 hybrid（默认） | X448 + ML-KEM-1024 | HKDF-SHA512 | AES-256-GCM | version 3 / algorithm id 3 |

本项目不自行实现任何密码算法，也不依赖 liboqs。**它没有经过安全审计，不应被视为生产级软件，也不应用来保护重要或敏感数据。**

## 发布版本 v3.1.1

Android App 版本为 `3.1.1`，Core 版本保持 `3.1`，application ID 为
`com.shixiaoshi0417.nekokem`。Android 工程及构建说明见
[`NekoKEM-Android/README.md`](NekoKEM-Android/README.md)。App 版本 3.1.1 不改变
协议编号：默认文件容器仍为 **NKEM v3**，NKPR 格式保持不变。

v3.1.1 新增无需运行时共享库依赖的 Linux x86_64/aarch64 CLI 发行包、
自动安装脚本和 GitHub Actions 构建流程。安装脚本自动选择最新 GitHub
Release 中与本机架构匹配的包，先使用 Release 顶层 `SHA256SUMS.txt` 验证
归档，再验证包内文件的 SHA-256：

```sh
curl --fail --location --output install.sh \
    https://raw.githubusercontent.com/Shixiaoshi0417/NekoKEM/main/install.sh
less install.sh
sh install.sh
```

Linux CLI 支持 `nekokem --version`。此发行补丁不改变 Core API、密码参数、
NKEM v1/v2/v3 或 NKPR 格式。

## 安装依赖

Debian 13：

```sh
sudo apt install libssl-dev build-essential
```

需要 OpenSSL 3.5 或更高版本，因为 ML-KEM 的 EVP 支持从 OpenSSL 3.5 开始提供。

如需构建 AFL++ parser fuzz harness，额外安装：

```sh
sudo apt install afl++
```

## 编译

```sh
make
```

构建使用 C17，并链接 OpenSSL `libcrypto`。Hybrid 私钥保护使用 OpenSSL 3.5 provider 提供的 Argon2id，不需要额外安装 `libargon2`。默认构建启用 `-Werror`、`-fstack-protector-strong`、`-D_FORTIFY_SOURCE=3`、`-fPIE` 和 `-pie`。

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

显示：

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

交互模式不要求选择版本；加密固定生成 v3 Hybrid，解密自动识别 v1/v2/v3。Hybrid 算法仍为 X448 + ML-KEM-1024、HKDF-SHA512 和 AES-256-GCM。

### 生成密钥

选择“生成密钥”会确保当前工作目录存在权限为 `0700` 的 `keys/`，并生成：

- `keys/public.key`：依次包含 X448 和 ML-KEM-1024 的明文公钥；
- `keys/private.key.enc`：包含两个完整私钥 PEM 的加密 NKPR 容器，权限为 `0600`。

生成前会要求输入并确认私钥保护密码；终端回显在两次输入期间均关闭。不会创建明文 `private.key`。

### 加密文件

可以输入公钥文件路径，也可以粘贴两个 PEM 公钥块。随后输入原文件路径。程序会确保当前工作目录下存在权限为 `0700` 的 `encrypted/` 目录；如果不存在则自动创建。

输出只使用原文件名，并保存到 `encrypted/`，同时追加 `.nkem`：

```text
plaintext/test.jpg -> encrypted/test.jpg.nkem
```

### 解密文件

可以输入私钥文件路径，也可以继续粘贴两个兼容的明文 PEM 私钥块。路径以 `.enc` 结尾时，程序会自动关闭终端回显并提示输入密码，在内存中认证、解密并解析 NKPR；解出的 PEM 不会写入磁盘。输入旧的 `private.key` 时仍按原有明文 PEM 逻辑读取。

粘贴旧式私钥内容时终端回显同样会被临时关闭；内部使用的 `0600` 临时密钥文件会在操作结束后删除。

输入文件必须以 `.nkem` 结尾。程序会确保当前工作目录下存在权限为 `0700` 的 `plaintext/`；如果不存在则自动创建。输出只使用容器文件名，自动去掉 `.nkem`，并恢复到 `plaintext/`：

```text
encrypted/test.jpg.nkem -> plaintext/test.jpg
```

解密仍使用原子输出。只有 Hybrid 密钥解封装及 AES-GCM 认证全部成功，目标文件才会提交；失败时不会留下未认证明文。

### 公钥指纹

“查看公钥指纹”接受公钥文件或粘贴内容，并显示大写、冒号分隔的 SHA-256 指纹。指纹输入是带域分隔字符串的两个公钥 DER SubjectPublicKeyInfo 编码，按 X448、ML-KEM-1024 顺序排列，每段前带 4 字节大端长度。因此同一对公钥不受 PEM 换行方式影响。

## 参数化兼容命令

参数化命令继续保留，供脚本、开发测试及旧文件兼容使用。默认 keygen 与交互模式一致，生成受密码保护的 Hybrid 密钥：

```sh
./nekokem keygen
```

`keygen hybrid` 保留为含义相同的显式兼容别名：

```sh
./nekokem keygen hybrid
```

两种写法都会提示输入两次密码，且不会把密码放入命令行参数；输出 `keys/public.key` 与 `keys/private.key.enc`。

只有需要生成旧式 v1 测试密钥时，才使用显式明文兼容选项：

```sh
./nekokem keygen legacy-v1
```

该命令会先打印明文私钥警告，然后输出：

- `keys/public.key`：PEM SubjectPublicKeyInfo；
- `keys/private.key`：未加口令的 PEM 私钥，文件权限强制为 `0600`。

hybrid `public.key` 顺序保存两个 PEM public-key 块：

1. X448 public key；
2. ML-KEM-1024 public key。

两个 private-key PEM 块按相同顺序在内存中序列化，随后整体加密到 `keys/private.key.enc`；不会生成明文 Hybrid `keys/private.key`。加密容器权限强制为 `0600`。`keygen legacy-v1` 生成的 v1 私钥与 Hybrid 密钥文件不能混用。

v1 加密和解密示例：

```sh
./nekokem encrypt test.txt encrypted/test.nkem keys/public.key
./nekokem decrypt encrypted/test.nkem output.txt keys/private.key
```

默认 v3 hybrid 加密和解密：

```sh
./nekokem encrypt hybrid test.txt encrypted/test-v3.nkem keys/public.key
./nekokem decrypt hybrid encrypted/test-v3.nkem output.txt keys/private.key.enc
```

Hybrid decrypt 看到 `.enc` 后会自动提示一次密码。为兼容已有部署，命令仍接受包含 X448、ML-KEM-1024 两个 PEM 块的旧式明文 `private.key`。

命令可以使用其他位置的相应 PEM 密钥：

```text
./nekokem encrypt input_file output_file public.key
./nekokem decrypt input_file output_file private.key
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

## NKEM v1 文件格式

所有多字节整数采用大端序。固定头部为 24 字节：

| 偏移 | 长度 | 字段 |
|---:|---:|---|
| 0 | 4 | magic：ASCII `NKEM` |
| 4 | 1 | version：`1` |
| 5 | 1 | algorithm id：`1`，表示 ML-KEM-1024/HKDF-SHA256/AES-256-GCM |
| 6 | 2 | header length：`24` |
| 8 | 4 | ML-KEM 密文长度 |
| 12 | 8 | AES-GCM 密文长度 |
| 20 | 1 | nonce 长度：`12` |
| 21 | 1 | authentication tag 长度：`16` |
| 22 | 2 | 保留字段：`0` |

头部后依次为：

```text
ML-KEM ciphertext || 12-byte nonce || AES-GCM ciphertext || 16-byte tag
```

每次加密通过 `RAND_bytes` 生成新的 96 位 nonce。ML-KEM 共享秘密作为 HKDF 输入，nonce 作为 HKDF salt，协议字符串作为 HKDF info，派生出 32 字节 AES 密钥。固定头部、ML-KEM 密文和 nonce 同时作为 AES-GCM AAD，因此格式元数据也受到认证保护。

NKEM v1 是本项目自定义的实验格式，不是标准协议，未来版本可能不兼容。

## NKEM v2 hybrid 文件格式

v2 使用 X448 临时密钥协商和 ML-KEM-1024 封装。加密端生成一次性 X448 密钥对，并按以下顺序组合共享秘密：

```text
x448_secret || mlkem_secret
```

组合结果通过 HKDF-SHA512 派生 32 字节 AES 密钥。每个文件的 12 字节随机 nonce 同时作为 HKDF salt。

v2 固定头部为 28 字节，所有多字节整数采用大端序：

| 偏移 | 长度 | 字段 |
|---:|---:|---|
| 0 | 4 | magic：ASCII `NKEM` |
| 4 | 1 | version：`2` |
| 5 | 1 | algorithm id：`2`，表示 HYBRID-X448-MLKEM1024/HKDF-SHA512/AES-256-GCM |
| 6 | 2 | header length：`28` |
| 8 | 2 | X448 ephemeral public-key 长度，当前固定为 `56` |
| 10 | 2 | 保留字段：`0` |
| 12 | 4 | ML-KEM ciphertext 长度 |
| 16 | 8 | AES-GCM ciphertext 长度 |
| 24 | 1 | nonce 长度：`12` |
| 25 | 1 | authentication tag 长度：`16` |
| 26 | 2 | 保留字段：`0` |

头部后依次为：

```text
X448 ephemeral public key ||
ML-KEM ciphertext ||
12-byte nonce ||
AES-GCM ciphertext ||
16-byte authentication tag
```

固定头部、X448 临时公钥、ML-KEM 密文和 nonce 全部作为 AES-GCM AAD。v2 兼容解码器严格检查版本、算法 ID、长度和尾随数据；默认 Core 解密 API 则先读取 version，再分派到对应的 v1/v2/v3 路径。

NKEM v2 同样是本项目自定义的实验格式，并不是标准 hybrid KEM 协议。

## NKEM v3 hybrid 文件格式

v3 保持 v2 的 X448、ML-KEM-1024、共享秘密组合和 HKDF-SHA512 流程，仅把 HKDF salt 与 AES-GCM nonce 分离。每个文件分别通过 `RAND_bytes` 生成 32 字节 salt 和 12 字节 nonce；两者连同固定头部、X448 临时公钥和 ML-KEM 密文一起纳入 GCM AAD。完整布局见 [`docs/NKEM-v3.md`](docs/NKEM-v3.md)。默认 `nekokem_encrypt_file()` 写出 v3，`nekokem_decrypt_file()` 按 version 严格分派 v1/v2/v3。

## 安全实现说明

### OpenSSL EVP

- X448、ML-KEM-1024、HKDF、Argon2id 和 AES-256-GCM 均使用 OpenSSL 3.5 EVP/provider API，不自行实现密码算法，也不引入 liboqs；
- 所有 `EVP_PKEY`、`EVP_PKEY_CTX`、`EVP_CIPHER_CTX`、`EVP_KDF`、`EVP_KDF_CTX`、`EVP_MD_CTX` 和 `BIO` 都有统一的成功/失败释放路径；
- OpenSSL 错误路径通过 `ERR_get_error()` 循环取出并清空当前线程的错误队列；
- 私钥保留在 OpenSSL 的不透明 `EVP_PKEY`/provider 对象内，不导出原始私钥副本，并使用 `EVP_PKEY_free()` 释放。

### 敏感数据清零

- `src/secure_mem.c` 统一提供 `secure_mem_clear()` 和 `secure_free()`，底层分别使用 `OPENSSL_cleanse()` 与 `OPENSSL_clear_free()`，不使用可能被死存储消除优化掉的 `memset()` 清理秘密；
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
- Hybrid keygen 直接把两个私钥 PEM 写入 OpenSSL memory BIO，再加密为 NKPR 暂存文件，并与公钥一致提交；没有明文私钥输出文件或明文私钥临时文件；
- CLI 的 Core 接口当前是路径式 API。粘贴 PEM 因而使用 `/tmp` 下独占 `0700` 目录中的 `0600`、`O_NOFOLLOW|O_CLOEXEC` 临时文件，并在所有返回路径删除文件和目录。直接 memory BIO 需要新增内部 Core 适配层，memfd 的 `/proc/self/fd` 路径又会与私钥 `O_NOFOLLOW` 策略冲突；本阶段不改变公开 Core API，后续可在独立 API 设计中消除该临时路径；
- `secure_free()` 只用于 `OPENSSL_malloc()` 分配的敏感缓冲区，普通路径字符串和公开元数据仍由匹配的常规分配器释放。

## 自动测试

```sh
make test
```

测试会在临时目录中执行：

- 首先使用 GCC `-fanalyzer` 检查泄漏、空指针、未初始化读取、重复释放等资源问题；
- 构建启用 `-fsanitize=undefined` 且禁止恢复的临时二进制，并覆盖成功路径、缺失输入、无效输出目录、截断容器、篡改密文、无效私钥和错误私钥；
- 自动生成 NKEM/NKPR 截断、错误长度、非法版本、非法算法 ID、随机字节和超长字段，并在 UBSan 下调用正式静默解析入口；
- 对 CLI 普通输入和粘贴 PEM 分别实施单行上限，超长输入完整消费后拒绝，不会把残余字节留给下一次提示；
- 验证已有 `0700` 私有目录的类型、所有者和权限，以及私钥 `0600`、所有者、普通文件、符号链接和硬链接约束；
- 通过仅测试构建启用的故障注入覆盖短写、ENOSPC、文件/目录 `fsync`、第二次 rename、公私钥回滚和临时/备份文件清理；

- 显式 `legacy-v1` keygen、v1 加密、解密、SHA-256 和 `cmp`；
- Hybrid 加密私钥 keygen、正确密码解密、SHA-256 和 `cmp`；
- 默认 v3 加密/解密回环、v1/v2 旧容器解密，以及 v1/v2 解码器拒绝 v3；
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

测试材料随后删除。

## AFL++ parser fuzzing

```sh
make fuzz-build
```

该目标使用 `afl-clang-fast` 和 UBSan 构建：

- `fuzz/bin/fuzz_nkem`：仅解析 NKEM v1/v2/v3 header 与容器总长度；
- `fuzz/bin/fuzz_nkpr`：仅解析 NKPR header、参数与容器总长度。

两个 harness 都接受一个 `argv[1]` 文件路径，拒绝超过 2 MiB 的输入，不执行密钥解封装、Argon2id、AES-GCM 或明文写出。`fuzz/seeds/` 中的有效样本只是零填充的结构样本，不含密码、私钥或真实敏感数据；同时提供多个截断样本。具体 AFL 命令见 `fuzz/README.md`。

## 安全边界

- Hybrid 私钥现在使用口令保护，但安全性仍取决于用户选择足够强且唯一的密码，以及操作系统对进程内存、终端和文件的保护；v1 兼容私钥仍是权限为 `0600` 的明文 PEM；
- 文件长度和所选算法等容器元数据不是机密；
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
