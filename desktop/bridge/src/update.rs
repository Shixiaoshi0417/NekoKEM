// Update check: one HTTPS GET of GitHub's latest-release document through the
// operating system's curl, then a link to the release page. The address is
// fixed here; the frontend cannot choose where the App connects, and nothing
// is downloaded or installed.
use serde::{Deserialize,Serialize};
use std::ffi::{c_char,c_int,c_void,CStr,CString};
use std::process::{Command,Stdio};

const LATEST_URL:&str="https://api.github.com/repos/Shixiaoshi0417/NekoKEM/releases/latest";
const PAGE_PREFIX:&str="https://github.com/Shixiaoshi0417/NekoKEM/releases/tag/v";
/// A release document is a few KiB; curl refuses anything larger.
const MAX_RESPONSE_BYTES:usize=1<<20;

#[derive(Debug,PartialEq,Eq,Serialize)]
#[serde(rename_all="camelCase",tag="status")]
pub enum UpdateStatus{Available{version:String},Current,Failed}

// The update choices live in a private file beside the contacts directory.
const PREFERENCES_FILE:&str="update.json";
const MAX_PREFERENCES_BYTES:usize=4096;
extern "C" {
    fn desktop_contacts_directory(path:*mut c_char,capacity:usize)->c_int;
    fn desktop_write_private(path:*const c_char,bytes:*const u8,length:usize)->c_int;
    fn file_read_sensitive(path:*const c_char,maximum:usize,buffer:*mut *mut u8,length:*mut usize)->c_int;
    fn secure_free(memory:*mut c_void,length:usize);
}

/// Automatic checks are on unless turned off; a missing or damaged file means the defaults.
#[derive(Debug,Clone,PartialEq,Eq,Serialize,Deserialize)]
#[serde(rename_all="camelCase",default)]
pub struct Preferences{pub auto:bool,pub last_check:u64,pub ignored:String}
impl Default for Preferences{fn default()->Self{Self{auto:true,last_check:0,ignored:String::new()}}}
impl Preferences{
    fn valid(&self)->bool{self.ignored.is_empty()||parse_version(&self.ignored).is_some()}
}

/// The NekoKEM configuration directory: LocalAppData/NekoKEM or ~/.config/nekokem.
pub fn config_directory()->Option<String>{
    let mut buffer=vec![0 as c_char;32768];
    if unsafe{desktop_contacts_directory(buffer.as_mut_ptr(),buffer.len())}!=1{return None;}
    let contacts=unsafe{CStr::from_ptr(buffer.as_ptr())}.to_str().ok()?;
    contacts.strip_suffix("/contacts").map(str::to_owned)
}

pub fn load(directory:&str)->Preferences{
    let file=format!("{directory}/{PREFERENCES_FILE}");
    // Not yet saved is the usual first launch; Core would report it as an error.
    if std::fs::symlink_metadata(&file).is_err(){return Preferences::default();}
    let Ok(path)=CString::new(file) else {return Preferences::default()};
    let mut buffer=std::ptr::null_mut();let mut length=0;
    if unsafe{file_read_sensitive(path.as_ptr(),MAX_PREFERENCES_BYTES,&mut buffer,&mut length)}!=1{return Preferences::default();}
    let parsed=serde_json::from_slice::<Preferences>(unsafe{std::slice::from_raw_parts(buffer,length)});
    unsafe{secure_free(buffer.cast(),length)};
    parsed.ok().filter(Preferences::valid).unwrap_or_default()
}

pub fn store(directory:&str,preferences:&Preferences)->bool{
    if !preferences.valid(){return false;}
    let (Ok(bytes),Ok(path))=(serde_json::to_vec(preferences),CString::new(format!("{directory}/{PREFERENCES_FILE}"))) else {return false};
    bytes.len()<=MAX_PREFERENCES_BYTES&&unsafe{desktop_write_private(path.as_ptr(),bytes.as_ptr(),bytes.len())}==1
}

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
    fn preferences_survive_in_a_private_file_and_damage_means_defaults(){
        let _core=crate::contacts::tests::core_lock();
        let directory=crate::contacts::tests::Directory::new();
        let path=directory.0.to_str().unwrap();
        assert_eq!(load(path),Preferences::default());
        let chosen=Preferences{auto:false,last_check:42,ignored:"5.1.0".into()};
        assert!(store(path,&chosen));
        assert_eq!(load(path),chosen);
        #[cfg(unix)]{use std::os::unix::fs::PermissionsExt;
            assert_eq!(std::fs::metadata(directory.file(PREFERENCES_FILE)).unwrap().permissions().mode()&0o777,0o600);}
        assert!(!store(path,&Preferences{ignored:"../x".into(),..Preferences::default()}));
        std::fs::write(directory.file(PREFERENCES_FILE),b"{not json").ok();
        assert_eq!(load(path),Preferences::default());
    }
    #[test]
    fn status_serializes_for_the_frontend(){
        assert_eq!(serde_json::to_string(&UpdateStatus::Available{version:"5.1.0".into()}).unwrap(),r#"{"status":"available","version":"5.1.0"}"#);
        assert_eq!(serde_json::to_string(&UpdateStatus::Current).unwrap(),r#"{"status":"current"}"#);
    }
}
