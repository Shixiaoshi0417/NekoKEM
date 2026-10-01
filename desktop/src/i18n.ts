export const languageNames={'system':'跟随系统 · System','zh-CN':'简体中文','zh-TW':'繁體中文','en':'English','ja':'日本語','ko':'한국어'};
export type Language='en'|'zh-CN'|'zh-TW'|'ja'|'ko';
const messages={
 subtitle:['File encryption, kept on your device','文件加密，始终留在你的设备上','檔案加密，始終留在你的裝置上','ファイル暗号化を、このデバイスで','파일 암호화, 내 기기 안에서'],
 keygen:['Generate keys','生成密钥','產生金鑰','鍵を生成','키 생성'],encrypt:['Encrypt file','加密文件','加密檔案','ファイルを暗号化','파일 암호화'],decrypt:['Decrypt file','解密文件','解密檔案','ファイルを復号','파일 복호화'],fingerprint:['Public key fingerprint','公钥指纹','公開金鑰指紋','公開鍵の指紋','공개 키 지문'],exit:['Exit','退出','結束','終了','종료'],
 language:['Language','语言','語言','言語','언어'],system:['Follow system','跟随系统','跟隨系統','システムに従う','시스템 언어 사용'],
 keygenHelp:['Create a public key for sharing and an encrypted private key for yourself.','生成可分享的公钥，以及仅供自己保存的加密私钥。','產生可分享的公開金鑰，以及僅供自己保存的加密私密金鑰。','共有用の公開鍵と、ご自身で保管する暗号化秘密鍵を作成します。','공유할 공개 키와 직접 보관할 암호화된 개인 키를 생성합니다.'],
 encryptHelp:['Select a file and the recipient’s public key.','选择文件及接收方的公钥。','選擇檔案及接收者的公開金鑰。','ファイルと受信者の公開鍵を選択します。','파일과 수신자의 공개 키를 선택하세요.'],
 decryptHelp:['Restore an .nkem file with your private key.','使用你的私钥还原 .nkem 文件。','使用你的私密金鑰還原 .nkem 檔案。','秘密鍵で .nkem ファイルを復元します。','개인 키로 .nkem 파일을 복원하세요.'],
 fingerprintHelp:['Compare this fingerprint with the key owner through a trusted channel.','通过可信渠道与公钥持有人核对指纹。','透過可信管道與公開金鑰持有人核對指紋。','信頼できる方法で鍵の所有者と指紋を照合してください。','신뢰할 수 있는 방법으로 키 소유자와 지문을 확인하세요.'],
 publicPath:['Save public key','公钥保存位置','公開金鑰儲存位置','公開鍵の保存先','공개 키 저장 위치'],privatePath:['Save encrypted private key','加密私钥保存位置','加密私密金鑰儲存位置','暗号化秘密鍵の保存先','암호화된 개인 키 저장 위치'],
 input:['Input file','输入文件','輸入檔案','入力ファイル','입력 파일'],output:['Output file','输出文件','輸出檔案','出力ファイル','출력 파일'],key:['Key file','密钥文件','金鑰檔案','鍵ファイル','키 파일'],browse:['Browse…','选择…','選擇…','選択…','선택…'],path:['File path','文件路径','檔案路徑','ファイルパス','파일 경로'],paste:['Paste key contents','粘贴密钥内容','貼上金鑰內容','鍵の内容を貼り付け','키 내용 붙여넣기'],pasteHint:['Paste both PEM blocks. Up to 1 MiB.','请粘贴两个完整 PEM 区块，最多 1 MiB。','請貼上兩個完整 PEM 區塊，最多 1 MiB。','両方の PEM ブロックを貼り付けます。最大 1 MiB。','PEM 블록 두 개를 모두 붙여넣으세요. 최대 1 MiB.'],
 password:['Private key password','私钥密码','私密金鑰密碼','秘密鍵のパスワード','개인 키 암호'],confirmation:['Confirm password','确认密码','確認密碼','パスワードを確認','암호 확인'],passwordHint:['Encrypted private keys require a password. Keep it safe; it cannot be recovered.','加密私钥需要密码。请妥善保存，密码无法找回。','加密私密金鑰需要密碼。請妥善保存，密碼無法找回。','暗号化秘密鍵にはパスワードが必要です。安全に保管してください。復元はできません。','암호화된 개인 키에는 암호가 필요합니다. 복구할 수 없으므로 안전하게 보관하세요.'],
 ready:['Ready','就绪','就緒','準備完了','준비 완료'],working:['Working…','正在处理…','處理中…','処理中…','처리 중…'],preparing:['Preparing and checking keys…','正在准备并检查密钥…','正在準備並檢查金鑰…','鍵を準備・確認しています…','키 준비 및 확인 중…'],cancel:['Cancel','取消','取消','キャンセル','취소'],cancelling:['Cancelling safely…','正在安全取消…','正在安全取消…','安全にキャンセルしています…','안전하게 취소 중…'],success:['Completed','操作完成','操作完成','完了','완료'],progress:['Progress','处理进度','處理進度','進捗','진행 상황'],
 local:['Local processing','本地处理','本機處理','ローカル処理','로컬 처리'],localHint:['Your files and passwords are processed locally.','文件与密码均在本机处理。','檔案與密碼均在本機處理。','ファイルとパスワードはこのデバイスで処理します。','파일과 암호는 이 기기에서 처리됩니다.'],
 storage:['Choose local NTFS storage','请选择本地 NTFS 存储位置','請選擇本機 NTFS 儲存位置','ローカル NTFS ストレージを選択','로컬 NTFS 저장소를 선택하세요'],storageHint:['Unsafe paths or permissions are refused. Existing output is preserved if an operation fails or is cancelled.','不安全的路径或权限会被拒绝。操作失败或取消时，原有输出文件保持完整。','不安全的路徑或權限會被拒絕。操作失敗或取消時，原有輸出檔案保持完整。','安全でないパスや権限は拒否されます。失敗やキャンセル時は既存の出力を保持します。','안전하지 않은 경로나 권한은 거부됩니다. 실패하거나 취소하면 기존 출력 파일이 보존됩니다.'],
 'invalid-path':['Choose all required file paths.','请选择所有必需的文件路径。','請選擇所有必要的檔案路徑。','必要なファイルパスを選択してください。','필요한 파일 경로를 모두 선택하세요.'],
 'password-empty':['A password is required.','密码不能为空。','密碼不可為空。','パスワードを入力してください。','암호를 입력하세요.'],
 'password-limit':['Password must be at most 1024 UTF-8 bytes.','密码最多为 1024 个 UTF-8 字节。','密碼最多為 1024 個 UTF-8 位元組。','パスワードは UTF-8 で最大 1024 バイトです。','암호는 UTF-8 기준 최대 1024바이트입니다.'],
 'password-mismatch':['Passwords do not match.','两次输入的密码不一致。','兩次輸入的密碼不一致。','パスワードが一致しません。','암호가 일치하지 않습니다.'],
 'key-limit':['Paste a complete key of at most 1 MiB.','请粘贴完整密钥，大小不超过 1 MiB。','請貼上完整金鑰，大小不超過 1 MiB。','最大 1 MiB の完全な鍵を貼り付けてください。','최대 1 MiB의 완전한 키를 붙여넣으세요.'],
 'core-error':['Operation failed. Check the password, key/file format, local NTFS paths and private-key permissions.','操作失败。请检查密码、密钥/文件格式、本地 NTFS 路径及私钥权限。','操作失敗。請檢查密碼、金鑰/檔案格式、本機 NTFS 路徑及私密金鑰權限。','失敗しました。パスワード、鍵・ファイル形式、ローカル NTFS パスと秘密鍵の権限を確認してください。','작업에 실패했습니다. 암호, 키/파일 형식, 로컬 NTFS 경로와 개인 키 권한을 확인하세요.'],
 cancelled:['Cancelled; no new output was committed.','已取消，未提交新的输出文件。','已取消，未提交新的輸出檔案。','キャンセルしました。新しい出力は保存されていません。','취소되었습니다. 새 출력은 저장되지 않았습니다.'],
 'cleanup-error':['Temporary key cleanup failed. A protected temporary key may remain; the output may already exist.','临时密钥清理失败，受保护的临时密钥可能残留，输出文件也可能已经生成。','暫存金鑰清理失敗，受保護的暫存金鑰可能殘留，輸出檔案也可能已產生。','一時鍵の削除に失敗しました。保護された一時鍵と出力が残っている可能性があります。','임시 키 정리에 실패했습니다. 보호된 임시 키와 출력 파일이 남아 있을 수 있습니다.'],
 busy:['Finish the current operation first.','请先完成当前操作。','請先完成目前操作。','現在の処理が完了するまでお待ちください。','현재 작업이 끝날 때까지 기다리세요.'],
 internal:['Unable to connect to the desktop application.','无法连接到桌面应用。','無法連線至桌面應用程式。','デスクトップアプリに接続できません。','데스크톱 앱에 연결할 수 없습니다.'],
} as const;
export type Message=keyof typeof messages;
export function translate(key:Message,language:Language):string{return messages[key][['en','zh-CN','zh-TW','ja','ko'].indexOf(language)]??messages[key][0];}
export function errorCode(error:unknown):Message{const code=(error as {code?:string})?.code;return code && code in messages ? code as Message : 'internal';}
