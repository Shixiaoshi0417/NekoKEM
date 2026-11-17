[简体中文](cross-platform-roundtrip.md) | [English](cross-platform-roundtrip.en.md)

# 跨平台加解密往返

用现有 `nekokem` CLI 检查：在一个平台上加密的 NKEM 文件，拿到另一个平台上解密后，明文与加密前逐字节相同；反方向同样成立。本文不定义新的容器或算法。格式见 [NKEM v3](NKEM-v3.md)、[NKEM v4](NKEM-v4.md) 与 [NKEM v5](NKEM-v5.md)。

Linux、macOS、Windows 的 CLI 共用 `linux/src/main.c` 的参数解析。Android 与桌面 GUI 调用同一套 C Core，写出的也是 NKEM v3/v4/v5 与 NKPR。下面的字节比较用 CLI 完成；应用与 GUI 如何参与见文末。

## 通过标准

一次方向通过，当且仅当：

- 解密进程退出码为 0；
- `--lang en` 时标准输出有一行 `Decrypted NKEM <输入> -> <输出>`（`--lang zh-CN` 为 `已解密 NKEM <输入> -> <输出>`）；
- 恢复出的文件与加密前的明文长度相同、SHA-256 相同、逐字节相同；
- 签名的 v5 文件还要求解密输出里的签名者指纹等于加密方公钥的指纹。

往返通过是指两个方向都满足上面各条。A 加密给 B、B 解密，以及 B 加密给 A、A 解密，都要做。

每次加密都会重新抽取 salt、nonce 和临时密钥，因此同一明文的两次密文字节不同。密文长度由明文长度、容器版本和接收方数量决定，可以事先算出来。核对明文哈希，并用长度和头部版本字节核对密文。

失败的解密不会留下新的明文文件。本文的输出路径都是尚不存在的路径：失败之后该路径仍然不存在。空明文解密成功时会生成一个 0 字节文件，这与“路径不存在”不同。

## 前提

- 两边都使用会读写 NKEM v3、v4、v5 和 NKPR v1、v2、v3 的构建，即本仓库当前的构建：私钥留在各自机器上，NekoKEM 5.2.0 打不开新生成的 NKPR v3 私钥并不影响往返；CLI 写出的签名文件总是记录原文件名，5.1.0 及更早版本打不开。本仓库 v5.2.0 的 CLI 执行 `nekokem --version`（Windows 为 `nekokem.exe --version`）时打印 `NekoKEM 5.2.0`。报告失败时写上两边的版本字符串。NekoKEM 3.3.2 及更早版本打不开 v4 文件，4.x 及更早版本打不开 v5 文件、NKPR v2 和四段式公钥。
- 发布包：Linux `NekoKEM-linux-x86_64.tar.gz` 或 `NekoKEM-linux-aarch64.tar.gz`，macOS Apple Silicon `NekoKEM-macos-arm64.tar.gz`（无 Intel 构建），Windows `NekoKEM-windows-x86_64.zip` 中的 `nekokem.exe`。也可以使用同一提交构建出的二进制。
- 下文命令里的 `nekokem` 指该可执行文件。把它放进 `PATH`，或换成绝对路径。Windows 上把同一个参数交给 `nekokem.exe`。`--lang` 放在子命令之前。
- 在操作者自己拥有的目录中工作。Windows CLI 只接受本地固定 NTFS；网络路径、FAT/exFAT、设备路径和重解析点会在密码运算之前被拒绝。把文件复制到本地 NTFS 目录后再运行。
- 用一次性密钥和本文的公开样本。不要用仍在保护数据的密钥，也不要把 `keys/` 提交进 git。
- `nekokem keygen` 在当前目录生成四段式身份密钥：`keys/public.key`（X448、ML-KEM-1024、Ed448、ML-DSA-87 四个公钥）与 `keys/private.key.enc`（NKPR v3）。任一文件已存在时，keygen 在询问口令之前拒绝，不会覆盖。`keygen hybrid` 与 `keygen` 相同。确需替换时才使用 `keygen --replace`，替换后旧公钥对应的文件只能用旧私钥解密。
- 新口令必须是 1 至 1024 字节的有效 UTF-8，并输入两次。两次不一致时退出，`--lang en` 的提示是 `Passwords do not match`。口令从关闭回显的终端读取，或从 UTF-8 标准输入读取；不要放进命令行参数。读入时去掉行尾的一个 CR，因此以 LF 或 CRLF 结束的同一口令字节相同。解锁已有 NKPR 只提示一次，按文件头判断，不看扩展名。
- 加密默认签名：使用当前目录的 `keys/private.key.enc`（或 `--sign-key` 指定的私钥），并提示输入它的口令。`--unsigned` 不签名，不需要私钥和口令，并在标准错误打印一行未签名警告。
- NKPR 使用 Argon2id（64 MiB、3 次迭代、并行度 4）。生成密钥、签名加密和解密在提示口令后会停几秒，这是口令派生，不是挂起。
- POSIX 上私钥必须是当前用户拥有的普通文件、权限恰好 `0600`、硬链接数为 1。Windows 上必须是当前用户拥有、仅所有者可访问的 ACL、单一硬链接。公钥是普通非空文件；读取使用 `O_NOFOLLOW`，符号链接会被拒绝。公钥没有 `0600` 要求。
- 检测到不能可靠关闭回显的 MSYS/mintty 时，Windows CLI 拒绝秘密输入。使用 Windows Terminal，或按本文用 UTF-8 标准输入。

下文的 `sh` 代码块按 POSIX shell 书写，适用于 Linux、macOS 和 Git Bash。在 PowerShell 中参数相同，目录必须在本地固定 NTFS 上，可执行文件是 `.\nekokem.exe`。下面的 PowerShell 示例适用于 Windows PowerShell 5.1 及更高版本，包括 PowerShell 7。样本脚本用 here-string 经管道交给 `python -`：Windows PowerShell 5.1 在调用本机程序时会剥掉参数中的双引号，`python -c` 里再嵌双引号会变成非法 Python。加密要等 `peer-public.key` 复制到位后再执行。here-string 的结束符 `'@` 必须写在行首。

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\nekokem-roundtrip" | Out-Null
Set-Location "$env:USERPROFILE\nekokem-roundtrip"
@'
from pathlib import Path
Path("plain-text.txt").write_bytes(b"nekokem-roundtrip\n")
Path("plain-empty").write_bytes(b"")
Path("plain-binary").write_bytes(bytes(range(256)))
'@ | python -
New-Item -ItemType Directory -Force out, in | Out-Null
```

`keygen`、签名加密和 `decrypt` 会从标准输入读口令，单独执行，不要和后续命令贴在同一次管道里。加密和解密示例：

```powershell
.\nekokem.exe --lang en encrypt hybrid plain-text.txt out\text.v5.nkem peer-public.key
```

```powershell
.\nekokem.exe --lang en decrypt hybrid --trust peer-public.key --require-trusted in\text.v5.nkem in\text.restored keys\private.key.enc
```

交互菜单的“加密文件”只接受一把公钥，写出签名的 v5。多个接收方（最多 64 个）和不签名的 v3、v4 使用下文的参数化命令。

## 明文样本

在每台机器上用同一段 Python 生成样本，使字节与平台无关。Windows 若没有 `python3` 命令，把下面的 `python3` 换成 `python`。

```sh
mkdir -p "$HOME/nekokem-roundtrip"
cd "$HOME/nekokem-roundtrip"
python3 -c "from pathlib import Path; Path('plain-text.txt').write_bytes(b'nekokem-roundtrip\n'); Path('plain-empty').write_bytes(b''); Path('plain-binary').write_bytes(bytes(range(256)))"
```

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `plain-text.txt` | 18 | `380e7f841494c03f55ecac6396d1728d531262cdc475cde4799cad4e59e171bd` |
| `plain-empty` | 0 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `plain-binary` | 256 | `40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880` |

加密前先核对。下面的 Python 打印哈希，并在与上表不一致时以退出码 1 结束：

```sh
python3 -c "import hashlib,pathlib; expected={'plain-text.txt':'380e7f841494c03f55ecac6396d1728d531262cdc475cde4799cad4e59e171bd','plain-empty':'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855','plain-binary':'40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880'};
ok=True
for name,digest in expected.items():
    got=hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest(); print(got,name); ok &= got==digest
raise SystemExit(0 if ok else 1)"
```

密文长度（AES-256-GCM 的密文长度等于明文长度）：

| 容器 | 长度 |
|---|---|
| NKEM v5（默认），N 个接收方，1 ≤ N ≤ 64 | 明文长度 + 10228 + 1672 × N |
| NKEM v3（`--unsigned`，1 个接收方） | 明文长度 + 1716 |
| NKEM v4（`--unsigned`，2 ≤ N ≤ 64） | 明文长度 + 156 + 1672 × N |

因此这三个样本的单接收方 v5 密文分别是 11918、11900、12156 字节，v3 密文分别是 1734、1716、1972 字节。两名接收方的短文本，v5 是 13590 字节，v4 是 3518 字节。这些常数来自各版本头部、接收方记录和签名块的固定字段，见格式文档。v5 的 10228 包含 1024 字节的原文件名记录；CLI 总是记录原文件名，GUI 关闭“记录原文件名”后写出的 v5 是明文长度 + 9204 + 1672 × N。

头部前 4 字节是 ASCII `NKEM`，第 5 字节（偏移 4）是版本号：v3 为 `3`，v4 为 `4`，v5 为 `5`。NKPR 私钥前 4 字节是 ASCII `NKPR`。

```sh
python3 -c "import pathlib,sys; d=pathlib.Path(sys.argv[1]).read_bytes(); print(len(d), d[:4], d[4] if len(d)>4 else None)" out/text.v5.nkem
```

## 密钥

每台机器生成自己的密钥对，私钥留在本机。下面把本机叫做 A，对方叫做 B；两边都执行。

在终端里输入口令两次。需要无交互时，用 Python 写入 UTF-8 标准输入（两行相同口令）。把示例口令换成你自己的一次性口令；它不是协议常量，也不要用于真实数据。

```sh
cd "$HOME/nekokem-roundtrip"
nekokem --lang en keygen
```

```sh
python3 -c "import subprocess,sys; p=b'roundtrip-test-password\n'; subprocess.run([sys.argv[1],'--lang','en','keygen'], input=p+p, check=True)" nekokem
```

成功时 `--lang en` 打印 `Generated identity keys (X448 + ML-KEM-1024 encryption, Ed448 + ML-DSA-87 signing): keys/public.key and keys/private.key.enc`。

可选：`fingerprint` 子命令与菜单第 4 项一样，打印公钥指纹（大写、冒号分隔的 SHA-256）：

```sh
nekokem --lang en fingerprint "$HOME/nekokem-roundtrip/keys/public.key"
```

两边打印的指纹一致，说明复制过去的 `public.key` 与本机 Core 读到的是同一把公钥。指纹对 PEM 换行不敏感。四段式公钥打印的是身份指纹，解密签名文件时显示的签名者指纹就是它。

## NKEM v5：签名，双向

把对方的 `keys/public.key` 复制为本目录的 `peer-public.key`。它是 PEM，含 X448、ML-KEM-1024、Ed448、ML-DSA-87 四个 `PUBLIC KEY` 块，整文件复制。

A 加密给 B，并用 A 自己的私钥签名：

```sh
cd "$HOME/nekokem-roundtrip"
mkdir -p out
nekokem --lang en encrypt hybrid plain-text.txt out/text.v5.nkem peer-public.key
nekokem --lang en encrypt hybrid plain-empty out/empty.v5.nkem peer-public.key
nekokem --lang en encrypt hybrid plain-binary out/binary.v5.nkem peer-public.key
```

每个命令提示 `Enter the private-key password: `（`--lang zh-CN` 为 `请输入私钥密码：`），输入的是 A 自己 keygen 时的口令。退出码为 0，并打印 `Encrypted and signed NKEM v5 for 1 recipient(s) <输入> -> <输出>`。版本字节为 5，长度分别为 11918、11900、12156。

把这三个 `.nkem` 交给 B，放到 B 的 `in/`。B 用自己的私钥解密，并把 A 的公钥作为可信签名者：

```sh
cd "$HOME/nekokem-roundtrip"
mkdir -p in
nekokem --lang en decrypt hybrid --trust peer-public.key --require-trusted in/text.v5.nkem in/text.restored keys/private.key.enc
nekokem --lang en decrypt hybrid --trust peer-public.key --require-trusted in/empty.v5.nkem in/empty.restored keys/private.key.enc
nekokem --lang en decrypt hybrid --trust peer-public.key --require-trusted in/binary.v5.nkem in/binary.restored keys/private.key.enc
```

这里输入的是 B 自己的口令。每个命令在 `Decrypted NKEM <输入> -> <输出>` 之后打印：

```text
Signature: valid, signed by trusted key peer-public.key
Signer fingerprint:
<A 的身份指纹>
Original file name: plain-text.txt
```

签名者指纹必须等于 A 的 `keys/public.key` 的指纹，原文件名是 A 加密时的输入文件名。不带 `--trust` 也能解密，这时打印 `Signature: valid, but the signer is not one of your trusted keys; compare this fingerprint with the sender:` 和同一个指纹，由操作者自己核对。然后：

```sh
python3 -c "import hashlib,pathlib
pairs=[('plain-text.txt','in/text.restored'),('plain-empty','in/empty.restored'),('plain-binary','in/binary.restored')]
ok=True
for src,dst in pairs:
    a,b=pathlib.Path(src).read_bytes(),pathlib.Path(dst).read_bytes()
    print(hashlib.sha256(b).hexdigest(), dst, 'len', len(b))
    ok &= a==b
raise SystemExit(0 if ok else 1)"
```

三行哈希必须等于上表，退出码为 0。`in/empty.restored` 存在且长度为 0。

B 用 A 的公钥重复上面的加密（用 B 的私钥签名），交给 A 解密。A 的恢复文件也必须与这三个样本逐字节相同，签名者指纹等于 B 的公钥指纹。至此 v5 双向完成。

POSIX 上也可以用 `sha256sum`（macOS 可用 `shasum -a 256`）和 `cmp`。Windows 上可用 `Get-FileHash -Algorithm SHA256` 或 `certutil -hashfile <文件> SHA256`，以及 `fc /b`。比较时忽略大小写和哈希字符串中的空格。上面的 Python 比较的是原始字节。

## NKEM v3：不签名，单接收方

`--unsigned` 写出不签名的容器，供仍在使用 NekoKEM 4.x 或更早版本的接收方使用；只给出一把公钥时写出 v3。它不读取私钥，也不提示口令：

```sh
cd "$HOME/nekokem-roundtrip"
nekokem --lang en encrypt hybrid --unsigned plain-text.txt out/text.v3.nkem peer-public.key
nekokem --lang en encrypt hybrid --unsigned plain-empty out/empty.v3.nkem peer-public.key
nekokem --lang en encrypt hybrid --unsigned plain-binary out/binary.v3.nkem peer-public.key
```

每个命令退出码为 0，在标准输出打印 `Encrypted hybrid NKEM v3 <输入> -> <输出>`，在标准错误打印 `Warning: this file is not signed; recipients cannot confirm that it came from you.`。版本字节为 3，长度分别为 1734、1716、1972。

B 解密到新的输出路径：

```sh
nekokem --lang en decrypt hybrid in/text.v3.nkem in/text.v3.restored keys/private.key.enc
nekokem --lang en decrypt hybrid in/empty.v3.nkem in/empty.v3.restored keys/private.key.enc
nekokem --lang en decrypt hybrid in/binary.v3.nkem in/binary.v3.restored keys/private.key.enc
```

成功行之后打印 `Signature: none. This NKEM v3 file is not signed, so its sender cannot be confirmed.`，不打印原文件名。把上面比较脚本里的三个恢复文件名换成 `in/*.v3.restored` 再比较一次。带 `--require-signature` 解密这些文件会失败，见[预期失败](#预期失败)。

接收方使用 4.x 或更早版本时，交给对方的公钥要用 `nekokem export-legacy-public-key keys/public.key legacy-public.key` 导出：旧版本读不了四段式公钥。

## NKEM v4：两个接收方

在已经同时持有本机 `keys/public.key` 和 `peer-public.key` 的机器上，把短文本不签名地加密给双方。两把公钥必须是不同的密钥对；相同或共用组件时命令失败，提示 `Two public keys are the same or share a key component`，并且不创建输出。

```sh
cd "$HOME/nekokem-roundtrip"
nekokem --lang en encrypt hybrid --unsigned plain-text.txt out/text.v4.nkem keys/public.key peer-public.key
```

退出码为 0，打印 `Encrypted NKEM v4 for 2 recipients plain-text.txt -> out/text.v4.nkem` 和同一行未签名警告。版本字节为 4，长度为 3518。参数顺序不影响谁能解密。

本机用自己的私钥解密到一个新路径，并把同一个 `.nkem` 交给对方，对方也用自己的私钥解密。两边的恢复文件都与 `plain-text.txt` 逐字节相同。

对方再执行一次上述加密（两把公钥、新的输出文件名）并把新的 `.nkem` 送回。新密文与上一份字节不同，长度仍是 3518，两边都能解出同一份短文本。

去掉 `--unsigned` 再执行同一条命令（换一个输出文件名），得到的是两名接收方的签名 v5：提示本机私钥口令，打印 `Encrypted and signed NKEM v5 for 2 recipient(s)`，版本字节为 5，长度为 13590。两边同样各自解出同一份短文本，并看到同一个签名者指纹。

## 交换哪些文件

可以复制：

- 对方的公钥文件（`keys/public.key` 整文件）；
- `.nkem` 密文；
- 这三份公开明文样本，或只在每台机器上按上文重新生成。

留在生成它的机器上：

- `keys/private.key.enc` 和 Android 导出的 `private.nkpr`；
- 口令；
- `keys/.nekokem-pair.lock`（运行期间的目录锁，不是密钥）。

不要把私钥字节、NKPR 内容或口令写入本文、Issue、聊天记录或 git。Issue 里只需版本字符串、容器版本字节、文件长度和明文 SHA-256。

若某个检查必须让同一把私钥出现在两台机器上（CI 夹具就是这种形状）：以文件形式复制 NKPR，口令另路传递，用完删除副本。到达后修正权限。POSIX：

```sh
chmod 600 keys/private.key.enc
```

文件必须属于即将解密的用户，且硬链接数为 1。Windows 上把它放进 CLI 创建的 `keys` 目录，并按 [Windows CLI 文档](../windows/README.md) 中的 PowerShell 示例改成仅当前用户可访问的 ACL。权限不对时，CLI 会在解密之前拒绝该文件。

输出路径不能是本次使用的公钥或私钥。解密输出使用新文件名。

## 核对清单

加密：

- 退出码 0；
- v5 成功行含 `Encrypted and signed NKEM v5`，v3 成功行含 `Encrypted hybrid NKEM v3`，v4 成功行含 `Encrypted NKEM v4 for 2 recipients`；
- 前 4 字节为 `NKEM`，第 5 字节分别为 5、3 或 4；
- 长度符合上表。

解密：

- 退出码 0，成功行含 `Decrypted NKEM`；
- v5 打印 `Signature: valid`，签名者指纹等于加密方的公钥指纹，原文件名等于加密时的输入文件名；v3、v4 打印 `Signature: none`；
- 三个恢复文件的 SHA-256 等于样本表，逐字节比较退出码为 0；
- 空文件的恢复结果是存在的 0 字节文件；
- 反方向同样成立；
- 两名接收方的 v4 和 v5 文件，双方各自解出同一份短文本。

## 预期失败

以下情况退出码非 0，且指定的新输出路径仍然不存在。CLI 在调用 Core 之前就会读取 NKPR 口令。容器长度检查发生在解开 NKPR 之前：截断或尾部多余的文件会报长度错误，即使刚输入的口令是错的。错误口令那一项要用长度完好的密文，这样才会走到解开私钥。

| 操作 | `--lang en` 标准错误 |
|---|---|
| 用错误口令解密一份完好的 NKPR 所能打开的密文 | `Private-key password is incorrect or NKPR data is corrupted` |
| 用不是接收方的私钥解密 v3 | `Authentication failed: wrong key or modified NKEM file` |
| 用不是接收方的私钥解密 v4 或 v5 | `This file is not encrypted for this private key, or it was modified` |
| 删掉 v3 密文最后一个字节后再解密 | `NKEM v3 container is truncated or has trailing data` |
| 删掉 v4 密文最后一个字节后再解密 | `NKEM v4 container is truncated or has trailing data` |
| 删掉 v5 密文最后一个字节后再解密 | `NKEM v5 container is truncated or has trailing data` |
| 改动 v5 签名块里的一个字节后再解密 | `The sender signature is invalid; the file may have been modified` |
| 带 `--require-signature` 解密 v3 或 v4 | `This file is not signed, and only signed files are accepted` |
| 带 `--require-trusted` 解密签名者不在 `--trust` 列表中的 v5 | `The file is signed by an identity that is not trusted: <指纹>` |
| 当前目录没有 `keys/private.key.enc` 时不带 `--unsigned` 加密 | `Signing key not found: keys/private.key.enc` |

错误口令与 NKPR 损坏使用同一句提示，CLI 不区分二者。v3 的“密钥错误”和“密文被修改”也共用一句认证失败提示。在末尾多写一个字节与截断一样，按“截断或尾部多余数据”失败。

方便的 v3 负例：A 把只加密给 B 的 `out/text.v3.nkem` 用 A 自己的私钥解密，输出写到尚不存在的 `out/not-for-a.txt`。截断样本：

```sh
python3 -c "from pathlib import Path; src=Path('out/text.v3.nkem'); Path('out/text.v3.truncated.nkem').write_bytes(src.read_bytes()[:-1])"
nekokem --lang en decrypt hybrid out/text.v3.truncated.nkem out/truncated.txt keys/private.key.enc
```

持有对应私钥的一方执行最后这条命令。完成后 `out/truncated.txt` 仍然不存在。

`--lang zh-CN` 的对应句子是：`私钥密码错误或 NKPR 数据损坏`，`认证失败：密钥错误或 NKEM 文件被修改`，`此文件不是为该私钥加密的，或已被修改`，`NKEM v3 容器被截断或有多余尾部数据`，`NKEM v4 容器被截断或有多余尾部数据`，`NKEM v5 容器被截断或带有多余数据`，`发送者签名无效，文件可能已被修改`，`此文件未签名，当前只接受已签名文件`，`文件由不受信任的身份签名：<指纹>`，`找不到签名私钥：keys/private.key.enc`。

## Android 与桌面 GUI

Android 与桌面 GUI 默认写签名的 NKEM v5（1 至 64 个接收方）；在设置中开启不签名加密后，选择 1 个接收方时写 NKEM v3，选择 2 至 64 个时写 NKEM v4。解密按头部版本自动识别，并显示签名状态和签名者指纹。用它们参加往返时，仍然用上一节的长度、版本字节和明文 SHA-256 判断。

- 从 CLI 导出的 `keys/public.key` 导入应用或 GUI 的公钥通讯录，在应用里加密，再把得到的 `.nkem` 交给 CLI 解密。
- CLI 使用应用导出的公钥加密；应用或 GUI 解密。Android 导出的 `private.nkpr` 按文件头识别为 NKPR，CLI 和桌面 GUI 不需要改扩展名。复制到 POSIX 后执行 `chmod 600`。较早的应用生成的私钥是 NKPR v2，CLI 在 `keys/private.key.enc` 以外的路径只有加上 `--legacy-private-key` 才会打开，见 README 的[参数化命令](../README.md#参数化命令)。
- 交互菜单和 GUI 的点击步骤见 [Android 文档](../android/README.md) 与 [桌面 GUI 文档](../desktop/README.md)。

应用通过系统文件选择器读写字节，不改变 NKEM 内容。比较的是解密后的明文，不是两份 `.nkem` 是否相同。

## 与现有自动测试的关系

`make -C linux test` 在一台 Linux 机器上运行，包含默认签名的 v5 加解密、SHA-256、`cmp`、错误口令、篡改密文和错误私钥，以及 `test-multi-recipient`（进程内的 v4 测试）和 `test-signature`（进程内的 v5 测试）。它不把文件送到另一个操作系统。

CI（[.github/workflows/ci.yml](../.github/workflows/ci.yml)）里的跨平台夹具是另一件事。历史 Linux CLI 只读两段式密钥、NKPR v1 和单接收方 NKEM v3，所以跨系统交换的只有这些格式：

- `windows/tests/interop.py` 的 `generate` 在历史 Linux CLI 上生成空文件和 `bytes(range(256)) * 512` 的 v3 密文及 NKPR；
- `windows` 阶段在 Windows 上解密这些文件并加密回去；`macos/tests/interop.py` 的 `macos` 阶段在 Apple Silicon 上做同样的事；
- 作业 “Windows and macOS to historical Linux interoperability” 再用历史 Linux CLI 解密 Windows 和 macOS 产出的密文。

这两个阶段还在本机用自己的身份密钥写出签名的 v5 并以 `--trust`、`--require-trusted` 解密，但这些 v5 文件不离开生成它们的系统。

这些脚本里的密钥和口令是公开测试夹具。它们比较明文是否逐字节相同，不要求两份 `.nkem` 相同。本文的 v4 和 v5 双机步骤不在该作业里。
