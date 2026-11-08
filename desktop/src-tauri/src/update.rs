// Update check: one HTTPS GET of GitHub's latest-release document through the
// operating system's curl, then a link to the release page. The address is
// fixed here; the frontend cannot choose where the App connects, and nothing
// is downloaded or installed.
use serde::{Deserialize,Serialize};
use std::process::{Command,Stdio};

const LATEST_URL:&str="https://api.github.com/repos/Shixiaoshi0417/NekoKEM/releases/latest";
const PAGE_PREFIX:&str="https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v";
/// A release document is a few KiB; curl refuses anything larger.
const MAX_RESPONSE_BYTES:usize=1<<20;

#[derive(Debug,PartialEq,Eq,Serialize)]
#[serde(rename_all="camelCase",tag="status")]
pub enum UpdateStatus{Available{version:String},Current,Failed}

#[derive(Deserialize)]
struct Release{tag_name:String,#[serde(default)] draft:bool,#[serde(default)] prerelease:bool}

/// "5.0.0" or "v5.0.0" with one to four digits per part; nothing else.
pub fn parse_version(text:&str)->Option<[u16;3]>{
    let text=text.strip_prefix('v').unwrap_or(text);
    let mut parts=[0u16;3];let mut count=0;
    for part in text.split('.'){
        if count==3||part.is_empty()||part.len()>4||!part.bytes().all(|b|b.is_ascii_digit()){return None;}
        parts[count]=part.parse().ok()?;count+=1;
    }
    (count==3).then_some(parts)
}

pub fn classify(body:&[u8],current:&str)->UpdateStatus{
    let Ok(release)=serde_json::from_slice::<Release>(body) else {return UpdateStatus::Failed};
    let (Some(remote),Some(local))=(parse_version(&release.tag_name),parse_version(current)) else {return UpdateStatus::Failed};
    if release.draft||release.prerelease{return UpdateStatus::Failed;}
    if remote>local{UpdateStatus::Available{version:format!("{}.{}.{}",remote[0],remote[1],remote[2])}}else{UpdateStatus::Current}
}

/// The release page of [version], built here and never taken from a response.
pub fn page_url(version:&str)->Option<String>{
    let [a,b,c]=parse_version(version)?;Some(format!("{PAGE_PREFIX}{a}.{b}.{c}"))
}

#[cfg(windows)]
fn system_program(name:&str)->Option<std::path::PathBuf>{
    let root=std::env::var_os("SystemRoot")?;
    let path=std::path::Path::new(&root).join("System32").join(name);
    path.is_file().then_some(path)
}
#[cfg(not(windows))]
fn system_program(name:&str)->Option<std::path::PathBuf>{
    // Fixed system locations only: a PATH entry never chooses the program.
    ["/usr/bin","/bin","/usr/local/bin"].iter().map(|dir|std::path::Path::new(dir).join(name)).find(|path|path.is_file())
}

fn quiet(command:&mut Command)->&mut Command{
    #[cfg(windows)]{use std::os::windows::process::CommandExt;command.creation_flags(0x0800_0000);}
    command.stdin(Stdio::null()).stderr(Stdio::null())
}

fn fetch()->Option<Vec<u8>>{
    #[cfg(windows)] let curl=system_program("curl.exe")?;
    #[cfg(not(windows))] let curl=system_program("curl")?;
    let output=quiet(Command::new(curl)
        // --disable (first) ignores ~/.curlrc, which could redirect or log the request.
        .args(["--disable","--silent","--fail","--proto","=https","--tlsv1.2","--max-time","15",
               "--max-filesize",&MAX_RESPONSE_BYTES.to_string(),
               "--header","Accept: application/vnd.github+json","--user-agent","NekoKEM-Desktop",LATEST_URL])
        .env_remove("CURL_HOME").env_remove("SSLKEYLOGFILE"))
        .stdout(Stdio::piped()).output().ok()?;
    (output.status.success()&&output.stdout.len()<=MAX_RESPONSE_BYTES).then_some(output.stdout)
}

pub fn check(current:&str)->UpdateStatus{
    match fetch(){Some(body)=>classify(&body,current),None=>UpdateStatus::Failed}
}

pub fn open_page(version:&str)->bool{
    let Some(url)=page_url(version) else {return false};
    #[cfg(windows)] let opened=system_program("rundll32.exe").map(|program|{let mut command=Command::new(program);command.args(["url.dll,FileProtocolHandler",&url]);command});
    #[cfg(target_os="macos")] let opened=system_program("open").map(|program|{let mut command=Command::new(program);command.arg(&url);command});
    #[cfg(all(unix,not(target_os="macos")))] let opened=system_program("xdg-open").map(|program|{let mut command=Command::new(program);command.arg(&url);command});
    opened.is_some_and(|mut command|quiet(&mut command).stdout(Stdio::null()).spawn().is_ok())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn release(tag:&str,draft:bool,prerelease:bool)->Vec<u8>{
        format!(r#"{{"tag_name":"{tag}","draft":{draft},"prerelease":{prerelease},"html_url":"https://example.invalid/"}}"#).into_bytes()
    }
    #[test]
    fn versions_compare_numerically(){
        assert_eq!(classify(&release("v5.0.10",false,false),"5.0.9"),UpdateStatus::Available{version:"5.0.10".into()});
        assert_eq!(classify(&release("v6.0.0",false,false),"5.99.99"),UpdateStatus::Available{version:"6.0.0".into()});
        assert_eq!(classify(&release("v5.0.0",false,false),"5.0.0"),UpdateStatus::Current);
        assert_eq!(classify(&release("v4.4.0",false,false),"5.0.0"),UpdateStatus::Current);
    }
    #[test]
    fn odd_documents_fail(){
        for body in [b"not json".to_vec(),b"{}".to_vec(),release("v5.1.0",true,false),release("v5.1.0",false,true),
                     release("v5.1.0-rc1",false,false),release("v5.1",false,false),release("v5.1.0/../x",false,false),release("v55555.0.0",false,false)]{
            assert_eq!(classify(&body,"5.0.0"),UpdateStatus::Failed);
        }
        assert_eq!(classify(&release("v5.1.0",false,false),"dev"),UpdateStatus::Failed);
    }
    #[test]
    fn page_is_built_from_the_version(){
        assert_eq!(page_url("5.1.0").as_deref(),Some("https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v5.1.0"));
        assert_eq!(page_url("v05.1.0").as_deref(),Some("https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v5.1.0"));
        for bad in ["5.1.0 --x","5.1.0&calc","https://evil/","",".."]{assert_eq!(page_url(bad),None);}
    }
    #[test]
    fn status_serializes_for_the_frontend(){
        assert_eq!(serde_json::to_string(&UpdateStatus::Available{version:"5.1.0".into()}).unwrap(),r#"{"status":"available","version":"5.1.0"}"#);
        assert_eq!(serde_json::to_string(&UpdateStatus::Current).unwrap(),r#"{"status":"current"}"#);
    }
}
