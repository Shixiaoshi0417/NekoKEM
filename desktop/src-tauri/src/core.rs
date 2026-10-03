use serde::{Deserialize, Serialize};
use std::{ffi::{c_char,c_int,c_void,CStr,CString}, sync::{atomic::{AtomicBool,Ordering},Arc,Mutex}};
use zeroize::Zeroizing;
use std::time::{Duration,Instant};

pub static CORE_LOCK: Mutex<()> = Mutex::new(());
#[derive(Debug,Serialize)]
pub struct Failure { pub code: &'static str }
impl Failure { pub fn new(code: &'static str) -> Self { Self{code} } }
#[derive(Deserialize,PartialEq,Eq)]
#[serde(rename_all="lowercase")]
pub enum Kind { Keygen, Encrypt, Decrypt, Fingerprint }
#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
pub struct Request {
    pub id:String, pub kind:Kind,
    #[serde(default)] pub input:String,
    #[serde(default)] pub output:String,
    #[serde(default)] pub key_path:String,
    #[serde(default)] pub public_path:String,
    #[serde(default)] pub private_path:String,
    #[serde(default)] pub password:Zeroizing<String>,
    #[serde(default)] pub confirmation:Zeroizing<String>,
    #[serde(default)] pub key_text:Zeroizing<String>,
    #[serde(default)] pub paste:bool,
}
#[derive(Serialize)]
#[serde(rename_all="camelCase")]
pub struct Outcome { pub output:Option<String>, pub fingerprint:Option<String> }
pub struct Job { pub id:String, pub cancellable:bool, pub cancelled:AtomicBool }
pub struct Backend { pub active:Mutex<Option<Arc<Job>>>, pub close_after:AtomicBool }
impl Default for Backend { fn default()->Self { Self{active:Mutex::new(None),close_after:AtomicBool::new(false)} } }
impl Backend {
    pub fn reserve(&self, request:&Request)->Result<Arc<Job>,Failure> {
        if request.id.is_empty() || request.id.len()>64 || !request.id.bytes().all(|c|c.is_ascii_alphanumeric()||c==b'-') { return Err(Failure::new("invalid-request")); }
        let mut active=self.active.lock().map_err(|_|Failure::new("internal"))?;
        if active.is_some() { return Err(Failure::new("busy")); }
        let job=Arc::new(Job{id:request.id.clone(),cancellable:matches!(request.kind,Kind::Encrypt|Kind::Decrypt),cancelled:AtomicBool::new(false)});
        *active=Some(job.clone()); Ok(job)
    }
    pub fn cancel(&self,id:&str)->bool {
        let Ok(active)=self.active.lock() else { return false; };
        if let Some(job)=active.as_ref().filter(|j|j.id==id && j.cancellable) { job.cancelled.store(true,Ordering::Release); true } else { false }
    }
    pub fn finish(&self,job:&Arc<Job>) {
        if let Ok(mut active)=self.active.lock() { if active.as_ref().is_some_and(|j|Arc::ptr_eq(j,job)) { *active=None; } }
    }
}
pub struct Reservation { pub backend:Arc<Backend>, pub job:Arc<Job> }
impl Drop for Reservation { fn drop(&mut self){ self.backend.finish(&self.job); } }

type Progress=unsafe extern "C" fn(u64,u64,*mut c_void)->c_int;
extern "C" {
    fn nekokem_generate_keypair(public:*const c_char, private:*const c_char,password:*const u8,length:usize)->c_int;
    fn nekokem_encrypt_file_with_progress(input:*const c_char,output:*const c_char,key:*const c_char,callback:Option<Progress>,data:*mut c_void)->c_int;
    fn nekokem_decrypt_file_with_progress(input:*const c_char,output:*const c_char,key:*const c_char,password:*const u8,length:usize,callback:Option<Progress>,data:*mut c_void)->c_int;
    fn nekokem_public_key_fingerprint(key:*const c_char,output:*mut c_char,size:usize)->c_int;
    fn desktop_stage_key(bytes:*const u8,length:usize)->*mut c_char;
    fn desktop_remove_staged_key(path:*mut c_char)->c_int;
    fn desktop_language()->*const c_char;
    fn cli_language_save(language:*const c_char)->c_int;
    fn cli_language_preference()->*const c_char;
    #[cfg(test)] fn desktop_private_directory(path:*const c_char)->c_int;
}
pub fn path(value:&str)->Result<CString,Failure> {
    if value.is_empty() || value.len()>32767 {return Err(Failure::new("invalid-path"));}
    CString::new(value).map_err(|_|Failure::new("invalid-path"))
}
pub fn language()->String { unsafe{CStr::from_ptr(desktop_language()).to_string_lossy().into_owned()} }
pub fn preference()->String { unsafe{CStr::from_ptr(cli_language_preference()).to_string_lossy().into_owned()} }
pub fn save_language(value:&str)->Result<String,Failure> {
    if !["system","en","zh-CN","zh-TW","ja","ko"].contains(&value) {return Err(Failure::new("invalid-language"));}
    let tag=CString::new(value).unwrap();
    if unsafe{cli_language_save(tag.as_ptr())}!=1 {return Err(Failure::new("core-error"));}
    Ok(language())
}
struct Staged(*mut c_char);
impl Staged {
    fn new(text:&str)->Result<Self,Failure> {
        if text.is_empty() || text.len()>1048576 {return Err(Failure::new("key-limit"));}
        if text.as_bytes().split(|byte|*byte==b'\n').any(|line|line.len()>16384){return Err(Failure::new("key-line-limit"));}
        let pointer=unsafe{desktop_stage_key(text.as_ptr(),text.len())};
        if pointer.is_null() {return Err(Failure::new("core-error"));} Ok(Self(pointer))
    }
    fn close(&mut self)->bool {if self.0.is_null(){return true;} let p=std::mem::replace(&mut self.0,std::ptr::null_mut());unsafe{desktop_remove_staged_key(p)==1}}
}
impl Drop for Staged {fn drop(&mut self){let _=self.close();}}
struct ProgressContext<F:Fn(u64,u64)> { job:Arc<Job>, emit:F, last_emit:Option<Instant> }
unsafe extern "C" fn progress<F:Fn(u64,u64)>(done:u64,total:u64,data:*mut c_void)->c_int {
    // Core borrows this stack-owned context synchronously; it never retains the pointer.
    let state=unsafe{&mut *(data as *mut ProgressContext<F>)};
    if state.job.cancelled.load(Ordering::Acquire) {return 0;}
    if done==total || state.last_emit.map_or(true,|at|at.elapsed()>=Duration::from_millis(100)) {
        let emitted=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||(state.emit)(done,total)));
        if emitted.is_err(){return 0;} state.last_emit=Some(Instant::now());
    }
    i32::from(!state.job.cancelled.load(Ordering::Acquire))
}
pub fn execute<F:Fn(u64,u64)>(request:Request,job:Arc<Job>,emit:F)->Result<Outcome,Failure> {
    let _guard=CORE_LOCK.lock().map_err(|_|Failure::new("internal"))?;
    if request.password.len()>1024 || request.confirmation.len()>1024 {return Err(Failure::new("password-limit"));}
    if request.kind==Kind::Keygen {
        if request.password.is_empty(){return Err(Failure::new("password-empty"));}
        if request.password.as_str()!=request.confirmation.as_str(){return Err(Failure::new("password-mismatch"));}
        let public=path(&request.public_path)?; let private=path(&request.private_path)?;
        let result=unsafe{nekokem_generate_keypair(public.as_ptr(),private.as_ptr(),request.password.as_ptr(),request.password.len())};
        return if result==1 {Ok(Outcome{output:Some(request.private_path),fingerprint:None})}else{Err(Failure::new("core-error"))};
    }
    let mut staged=if request.paste{Some(Staged::new(&request.key_text)?)}else{None};
    let key=if let Some(stage)=staged.as_ref(){unsafe{CStr::from_ptr(stage.0).to_owned()}}else{path(&request.key_path)?};
    let mut state=ProgressContext{job,emit,last_emit:None};
    let data=&mut state as *mut _ as *mut c_void;
    let result=(|| {
        if request.kind==Kind::Fingerprint {
            let mut output=[0 as c_char;96];
            if unsafe{nekokem_public_key_fingerprint(key.as_ptr(),output.as_mut_ptr(),output.len())}!=1 {return Err(Failure::new("core-error"));}
            let fingerprint=unsafe{CStr::from_ptr(output.as_ptr()).to_string_lossy().into_owned()};
            return Ok(Outcome{output:None,fingerprint:Some(fingerprint)});
        }
        let input=path(&request.input)?; let output=path(&request.output)?;
        let status=unsafe{match request.kind {
            Kind::Encrypt=>nekokem_encrypt_file_with_progress(input.as_ptr(),output.as_ptr(),key.as_ptr(),Some(progress::<F>),data),
            Kind::Decrypt=>nekokem_decrypt_file_with_progress(input.as_ptr(),output.as_ptr(),key.as_ptr(),request.password.as_ptr(),request.password.len(),Some(progress::<F>),data),
            _=>unreachable!(),
        }};
        match status {1=>Ok(Outcome{output:Some(request.output),fingerprint:None}),-1=>Err(Failure::new("cancelled")),_=>Err(Failure::new("core-error"))}
    })();
    if staged.as_mut().is_some_and(|s|!s.close()){return Err(Failure::new("cleanup-error"));}
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs,path::PathBuf,time::{SystemTime,UNIX_EPOCH}};
    extern "C" {
        fn OpenSSL_version(kind:c_int)->*const c_char;
        fn protected_private_key_read(path:*const c_char,password:*const u8,length:usize,pem:*mut *mut u8,pem_length:*mut usize)->c_int;
        fn CRYPTO_clear_free(memory:*mut c_void,length:usize,file:*const c_char,line:c_int);
    }
    #[test]
    fn linked_openssl_runtime_matches_pin() {
        const OPENSSL_VERSION: c_int = 0;
        let version = unsafe { OpenSSL_version(OPENSSL_VERSION) };
        assert!(!version.is_null());
        assert!(unsafe { CStr::from_ptr(version) }.to_bytes().starts_with(b"OpenSSL 4.0.3 "),
                "GUI linked runtime does not match pinned OpenSSL 4.0.3");
    }
    fn request(kind:Kind)->Request {Request{id:"test-job".into(),kind,input:String::new(),output:String::new(),key_path:String::new(),public_path:String::new(),private_path:String::new(),password:Zeroizing::new(String::new()),confirmation:Zeroizing::new(String::new()),key_text:Zeroizing::new(String::new()),paste:false}}
    fn run(backend:&Arc<Backend>,r:Request)->Result<Outcome,Failure>{let job=backend.reserve(&r)?;let _hold=Reservation{backend:backend.clone(),job:job.clone()};execute(r,job,|_,_|{})}
    struct Directory(PathBuf);
    impl Directory{fn new()->Self{let path=std::env::temp_dir().join(format!("nekokem-rust-{}-{}",std::process::id(),SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()));let name=super::path(path.to_str().unwrap()).unwrap();assert_eq!(unsafe{desktop_private_directory(name.as_ptr())},1);Self(path)}fn file(&self,name:&str)->String{self.0.join(name).to_str().unwrap().into()}}
    impl Drop for Directory {fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
    #[test] fn request_bounds_busy_and_stale_cancellation(){let backend=Arc::new(Backend::default());let r=request(Kind::Encrypt);let job=backend.reserve(&r).unwrap();assert!(backend.reserve(&r).is_err());assert!(!backend.cancel("other-job"));assert!(backend.cancel(&job.id));assert!(job.cancelled.load(Ordering::Acquire));backend.finish(&job);let mut r=request(Kind::Keygen);r.password=Zeroizing::new("a".repeat(1025));assert_eq!(run(&backend,r).err().unwrap().code,"password-limit");assert!(path("a\0b").is_err());}
    #[test] fn actual_core_roundtrip_fingerprint_wrong_password_paste_and_cancel(){
        let dir=Directory::new();let backend=Arc::new(Backend::default());let mut r=request(Kind::Keygen);
        r.public_path=dir.file("public.key");r.private_path=dir.file("private.key.enc");r.password=Zeroizing::new("public-test-中文-😀".into());r.confirmation=r.password.clone();run(&backend,r).unwrap();
        let data:Vec<u8>=(0..1048576).map(|i|(i%256)as u8).collect();fs::write(dir.file("plain"),&data).unwrap();
        let mut r=request(Kind::Encrypt);r.input=dir.file("plain");r.output=dir.file("cipher.nkem");r.key_path=dir.file("public.key");run(&backend,r).unwrap();
        let mut r=request(Kind::Decrypt);r.input=dir.file("cipher.nkem");r.output=dir.file("output");r.key_path=dir.file("private.key.enc");r.password=Zeroizing::new("public-test-中文-😀".into());run(&backend,r).unwrap();assert_eq!(fs::read(dir.file("output")).unwrap(),data);
        let mut r=request(Kind::Decrypt);r.input=dir.file("cipher.nkem");r.output=dir.file("output");r.key_path=dir.file("private.key.enc");r.password=Zeroizing::new("wrong".into());assert_eq!(run(&backend,r).err().unwrap().code,"core-error");assert_eq!(fs::read(dir.file("output")).unwrap(),data);
        let mut r=request(Kind::Fingerprint);r.key_path=dir.file("public.key");let fingerprint=run(&backend,r).unwrap().fingerprint;
        let mut r=request(Kind::Fingerprint);r.paste=true;r.key_text=Zeroizing::new(fs::read_to_string(dir.file("public.key")).unwrap());assert_eq!(run(&backend,r).unwrap().fingerprint,fingerprint);
        // NKPR is a binary container. Extract this generated public test fixture
        // through the real Core solely to exercise the private-PEM paste path.
        let encrypted=path(&dir.file("private.key.enc")).unwrap();let password=b"public-test-\xe4\xb8\xad\xe6\x96\x87-\xf0\x9f\x98\x80";
        let mut pem=std::ptr::null_mut();let mut length=0;
        assert_eq!(unsafe{protected_private_key_read(encrypted.as_ptr(),password.as_ptr(),password.len(),&mut pem,&mut length)},1);
        let text=unsafe{String::from_utf8(std::slice::from_raw_parts(pem,length).to_vec()).unwrap()};
        unsafe{CRYPTO_clear_free(pem.cast(),length,std::ptr::null(),0)};
        let mut r=request(Kind::Decrypt);r.input=dir.file("cipher.nkem");r.output=dir.file("pasted-output");r.paste=true;r.key_text=Zeroizing::new(text);run(&backend,r).unwrap();assert_eq!(fs::read(dir.file("pasted-output")).unwrap(),data);
        let mut r=request(Kind::Encrypt);r.input=dir.file("plain");r.output=dir.file("cipher.nkem");r.key_path=dir.file("public.key");let before=fs::read(&r.output).unwrap();let job=backend.reserve(&r).unwrap();let _hold=Reservation{backend:backend.clone(),job:job.clone()};let cancel=job.clone();assert_eq!(execute(r,job,move|_,_|{cancel.cancelled.store(true,Ordering::Release);}).err().unwrap().code,"cancelled");assert_eq!(fs::read(dir.file("cipher.nkem")).unwrap(),before);
    }
    #[cfg(unix)]
    #[test]
    fn posix_staging_permissions_cleanup_and_link_rejection() {
        use std::os::unix::fs::{symlink, MetadataExt, PermissionsExt};
        let bytes = "public staging test fixture\n";
        let mut staged = Staged::new(bytes).unwrap();
        let name = unsafe { CStr::from_ptr(staged.0) }.to_str().unwrap();
        let file = PathBuf::from(name);
        let directory = file.parent().unwrap().to_owned();
        assert_eq!(fs::metadata(&file).unwrap().mode() & 0o777, 0o600);
        assert_eq!(fs::metadata(&file).unwrap().nlink(), 1);
        assert_eq!(fs::metadata(&directory).unwrap().mode() & 0o777, 0o700);
        assert_eq!(fs::read(&file).unwrap(), bytes.as_bytes());
        assert!(staged.close());
        assert!(!file.exists());
        assert!(!directory.exists());

        let dir = Directory::new();
        fs::set_permissions(&dir.0, fs::Permissions::from_mode(0o755)).unwrap();
        let cdir = path(dir.0.to_str().unwrap()).unwrap();
        assert_eq!(unsafe { desktop_private_directory(cdir.as_ptr()) }, 0);
        fs::set_permissions(&dir.0, fs::Permissions::from_mode(0o700)).unwrap();
        let destination = dir.file("output");
        fs::write(&destination, b"must survive").unwrap();
        symlink(&destination, dir.file("public-link")).unwrap();
        let backend = Arc::new(Backend::default());
        let mut r = request(Kind::Keygen);
        r.public_path = dir.file("public-link");
        r.private_path = dir.file("private.key.enc");
        r.password = Zeroizing::new("public test password".into());
        r.confirmation = r.password.clone();
        // POSIX Core safely replaces the symlink itself without following it.
        run(&backend, r).unwrap();
        assert_eq!(fs::read(destination).unwrap(), b"must survive");
        assert!(!fs::symlink_metadata(dir.file("public-link")).unwrap().file_type().is_symlink());
        assert_eq!(fs::metadata(dir.file("private.key.enc")).unwrap().mode() & 0o777, 0o600);

        let mut staged = Staged::new(bytes).unwrap();
        let file = PathBuf::from(unsafe { CStr::from_ptr(staged.0) }.to_str().unwrap());
        let directory = file.parent().unwrap().to_owned();
        fs::hard_link(&file, dir.file("key-hard-link")).unwrap();
        assert!(!staged.close());
        assert_eq!(fs::read(&file).unwrap(), bytes.as_bytes());
        fs::remove_file(dir.file("key-hard-link")).unwrap();
        fs::remove_file(file).unwrap();
        fs::remove_dir(directory).unwrap();
    }
}
