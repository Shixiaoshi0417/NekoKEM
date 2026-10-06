use serde::{Deserialize, Serialize};
use std::{ffi::{c_char,c_int,c_void,CStr,CString}, sync::{atomic::{AtomicBool,Ordering},Arc,Mutex}};
use zeroize::Zeroizing;
use std::time::{Duration,Instant};

pub static CORE_LOCK: Mutex<()> = Mutex::new(());
// Same limit as Core's NEKOKEM_MAX_RECIPIENTS for one NKEM v4 container.
pub const MAX_RECIPIENTS: usize = 64;
#[derive(Debug,Serialize)]
pub struct Failure {
    pub code: &'static str,
    // Identifies the saved contact that already holds an imported public key.
    #[serde(skip_serializing_if="Option::is_none")] pub contact: Option<String>,
}
impl Failure {
    pub fn new(code: &'static str) -> Self { Self{code,contact:None} }
    pub fn existing(contact: String) -> Self { Self{code:"contact-exists",contact:Some(contact)} }
}
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
    // Saved recipient contacts (encryption only), in the user's order. One contact
    // writes NKEM v3; two or more write one NKEM v4 file that each of them decrypts.
    // Never combined with another key source.
    #[serde(default)] pub contacts:Vec<String>,
}
#[derive(Serialize)]
#[serde(rename_all="camelCase")]
pub struct Outcome {
    pub output:Option<String>, pub fingerprint:Option<String>,
    // Core-verified fingerprints of the saved contacts used, in request order.
    pub recipients:Vec<String>,
}
pub struct Job { pub id:String, pub cancellable:bool, pub cancelled:AtomicBool }
pub struct Backend { pub active:Mutex<Option<Arc<Job>>>, pub close_after:AtomicBool }
impl Default for Backend { fn default()->Self { Self{active:Mutex::new(None),close_after:AtomicBool::new(false)} } }
impl Backend {
    pub fn reserve(&self, request:&Request)->Result<Arc<Job>,Failure> {
        if request.id.is_empty() || request.id.len()>64 || !request.id.bytes().all(|c|c.is_ascii_alphanumeric()||c==b'-') { return Err(Failure::new("invalid-request")); }
        self.claim(request.id.clone(),matches!(request.kind,Kind::Encrypt|Kind::Decrypt))
    }
    // Contact-store changes share the same exclusive slot as Core operations.
    pub fn reserve_internal(&self)->Result<Arc<Job>,Failure> { self.claim("contacts".into(),false) }
    fn claim(&self,id:String,cancellable:bool)->Result<Arc<Job>,Failure> {
        let mut active=self.active.lock().map_err(|_|Failure::new("internal"))?;
        if active.is_some() { return Err(Failure::new("busy")); }
        let job=Arc::new(Job{id,cancellable,cancelled:AtomicBool::new(false)});
        *active=Some(job.clone()); Ok(job)
    }
    pub fn cancel(&self,id:&str)->bool {
        let Ok(active)=self.active.lock() else { return false; };
        if let Some(job)=active.as_ref().filter(|j|j.id==id && j.cancellable) { job.cancelled.store(true,Ordering::Release); true } else { false }
    }
    pub fn finish(&self,job:&Arc<Job>) {
        if let Ok(mut active)=self.active.lock() { if active.as_ref().is_some_and(|j|Arc::ptr_eq(j,job)) { *active=None; } }
    }
    // Return true while native shutdown must wait for Core/secret cleanup.
    pub fn defer_close(&self)->bool {
        let Ok(active)=self.active.lock() else { return true; };
        if let Some(job)=active.as_ref() {
            self.close_after.store(true,Ordering::Release);
            job.cancelled.store(true,Ordering::Release);
            true
        } else { false }
    }
}
pub struct Reservation { pub backend:Arc<Backend>, pub job:Arc<Job> }
impl Drop for Reservation { fn drop(&mut self){ self.backend.finish(&self.job); } }

type Progress=unsafe extern "C" fn(u64,u64,*mut c_void)->c_int;
extern "C" {
    fn nekokem_generate_keypair(public:*const c_char, private:*const c_char,password:*const u8,length:usize)->c_int;
    fn nekokem_encrypt_file_with_progress(input:*const c_char,output:*const c_char,key:*const c_char,callback:Option<Progress>,data:*mut c_void)->c_int;
    fn nekokem_encrypt_file_multi_with_progress(input:*const c_char,output:*const c_char,keys:*const *const c_char,count:usize,callback:Option<Progress>,data:*mut c_void)->c_int;
    fn nekokem_decrypt_file_with_progress(input:*const c_char,output:*const c_char,key:*const c_char,password:*const u8,length:usize,callback:Option<Progress>,data:*mut c_void)->c_int;
    fn nekokem_public_key_fingerprint(key:*const c_char,output:*mut c_char,size:usize)->c_int;
    fn desktop_stage_key(bytes:*const u8,length:usize)->*mut c_char;
    fn desktop_stage_output()->*mut c_char;
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
// Core parses and validates both hybrid public components before hashing them.
pub fn fingerprint(key:&CStr)->Option<String> {
    let mut output=[0 as c_char;96];
    if unsafe{nekokem_public_key_fingerprint(key.as_ptr(),output.as_mut_ptr(),output.len())}!=1 {return None;}
    Some(unsafe{CStr::from_ptr(output.as_ptr()).to_string_lossy().into_owned()})
}
pub fn language()->String { unsafe{CStr::from_ptr(desktop_language()).to_string_lossy().into_owned()} }
pub fn preference()->String { unsafe{CStr::from_ptr(cli_language_preference()).to_string_lossy().into_owned()} }
pub fn save_language(value:&str)->Result<String,Failure> {
    if !["system","en","zh-CN","zh-TW","ja","ko"].contains(&value) {return Err(Failure::new("invalid-language"));}
    let tag=CString::new(value).unwrap();
    if unsafe{cli_language_save(tag.as_ptr())}!=1 {return Err(Failure::new("core-error"));}
    Ok(language())
}
pub struct Staged(*mut c_char);
impl Staged {
    pub fn new(text:&str)->Result<Self,Failure> {
        if text.is_empty() || text.len()>1048576 {return Err(Failure::new("key-limit"));}
        if text.as_bytes().split(|byte|*byte==b'\n').any(|line|line.len()>16384){return Err(Failure::new("key-line-limit"));}
        let pointer=unsafe{desktop_stage_key(text.as_ptr(),text.len())};
        if pointer.is_null() {return Err(Failure::new("core-error"));} Ok(Self(pointer))
    }
    // A private path that Core creates atomically; cleanup removes it and its directory.
    pub fn output()->Result<Self,Failure> {
        let pointer=unsafe{desktop_stage_output()};
        if pointer.is_null() {return Err(Failure::new("core-error"));} Ok(Self(pointer))
    }
    pub fn path(&self)->&CStr {unsafe{CStr::from_ptr(self.0)}}
    pub fn close(&mut self)->bool {if self.0.is_null(){return true;} let p=std::mem::replace(&mut self.0,std::ptr::null_mut());unsafe{desktop_remove_staged_key(p)==1}}
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
    if !request.contacts.is_empty() && (request.kind!=Kind::Encrypt || request.paste || !request.key_path.is_empty() || !request.key_text.is_empty()) {
        return Err(Failure::new("invalid-request"));
    }
    if request.contacts.len()>MAX_RECIPIENTS {return Err(Failure::new("recipient-limit"));}
    let mut seen=std::collections::HashSet::new();
    if !request.contacts.iter().all(|id|seen.insert(id.as_str())) {return Err(Failure::new("invalid-request"));}
    if request.kind==Kind::Keygen {
        if request.password.is_empty(){return Err(Failure::new("password-empty"));}
        if request.password.as_str()!=request.confirmation.as_str(){return Err(Failure::new("password-mismatch"));}
        let public=path(&request.public_path)?; let private=path(&request.private_path)?;
        let result=unsafe{nekokem_generate_keypair(public.as_ptr(),private.as_ptr(),request.password.as_ptr(),request.password.len())};
        return if result==1 {Ok(Outcome{output:Some(request.private_path),fingerprint:None,recipients:Vec::new()})}else{Err(Failure::new("core-error"))};
    }
    // Saved contacts are re-read and re-validated by Core for every encryption.
    // A missing or damaged contact fails the whole operation and names that
    // contact; no recipient is ever skipped or replaced by another key.
    let mut recipients=Vec::new();
    let mut staged=Vec::new();
    if !request.contacts.is_empty() {
        let store=crate::contacts::Store::open()?;
        for id in &request.contacts {
            let (stage,fingerprint)=store.stage(id).map_err(|error|Failure{contact:Some(id.clone()),..error})?;
            staged.push(stage); recipients.push(fingerprint);
        }
    } else if request.paste {staged.push(Staged::new(&request.key_text)?);}
    let keys=if staged.is_empty(){vec![path(&request.key_path)?]}else{staged.iter().map(|stage|stage.path().to_owned()).collect()};
    let mut state=ProgressContext{job,emit,last_emit:None};
    let data=&mut state as *mut _ as *mut c_void;
    let result=(|| {
        if request.kind==Kind::Fingerprint {
            let fingerprint=fingerprint(&keys[0]).ok_or_else(||Failure::new("core-error"))?;
            return Ok(Outcome{output:None,fingerprint:Some(fingerprint),recipients:Vec::new()});
        }
        let input=path(&request.input)?; let output=path(&request.output)?;
        let status=unsafe{match request.kind {
            Kind::Encrypt if keys.len()>1=>{
                let pointers:Vec<*const c_char>=keys.iter().map(|key|key.as_ptr()).collect();
                nekokem_encrypt_file_multi_with_progress(input.as_ptr(),output.as_ptr(),pointers.as_ptr(),pointers.len(),Some(progress::<F>),data)
            }
            Kind::Encrypt=>nekokem_encrypt_file_with_progress(input.as_ptr(),output.as_ptr(),keys[0].as_ptr(),Some(progress::<F>),data),
            Kind::Decrypt=>nekokem_decrypt_file_with_progress(input.as_ptr(),output.as_ptr(),keys[0].as_ptr(),request.password.as_ptr(),request.password.len(),Some(progress::<F>),data),
            _=>unreachable!(),
        }};
        let fingerprint=if recipients.len()==1 {recipients.first().cloned()} else {None};
        match status {1=>Ok(Outcome{output:Some(request.output.clone()),fingerprint,recipients:recipients.clone()}),-1=>Err(Failure::new("cancelled")),_=>Err(Failure::new("core-error"))}
    })();
    let mut cleaned=true;
    for stage in staged.iter_mut() {cleaned&=stage.close();}
    if !cleaned {return Err(Failure::new("cleanup-error"));}
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
    fn shutdown_waits_for_reservation_cleanup() {
        for kind in [Kind::Encrypt, Kind::Keygen] {
            let backend=Arc::new(Backend::default());
            assert!(!backend.defer_close());
            let request=request(kind);
            let job=backend.reserve(&request).unwrap();
            let reservation=Reservation{backend:backend.clone(),job:job.clone()};
            assert!(backend.defer_close());
            assert!(backend.close_after.load(Ordering::Acquire));
            assert!(job.cancelled.load(Ordering::Acquire));
            assert!(backend.reserve(&request).is_err());
            drop(reservation);
            assert!(!backend.defer_close());
        }
    }
    #[test]
    fn linked_openssl_runtime_matches_pin() {
        const OPENSSL_VERSION: c_int = 0;
        let version = unsafe { OpenSSL_version(OPENSSL_VERSION) };
        assert!(!version.is_null());
        assert!(unsafe { CStr::from_ptr(version) }.to_bytes().starts_with(b"OpenSSL 4.0.3 "),
                "GUI linked runtime does not match pinned OpenSSL 4.0.3");
    }
    fn request(kind:Kind)->Request {Request{id:"test-job".into(),kind,input:String::new(),output:String::new(),key_path:String::new(),public_path:String::new(),private_path:String::new(),password:Zeroizing::new(String::new()),confirmation:Zeroizing::new(String::new()),key_text:Zeroizing::new(String::new()),paste:false,contacts:Vec::new()}}
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
        // Argon2id uses OpenSSL's shared thread pool: hold the Core lock as the app does.
        assert_eq!({let _core=CORE_LOCK.lock().unwrap_or_else(|error|error.into_inner());
            unsafe{protected_private_key_read(encrypted.as_ptr(),password.as_ptr(),password.len(),&mut pem,&mut length)}},1);
        let text=unsafe{String::from_utf8(std::slice::from_raw_parts(pem,length).to_vec()).unwrap()};
        unsafe{CRYPTO_clear_free(pem.cast(),length,std::ptr::null(),0)};
        let mut r=request(Kind::Decrypt);r.input=dir.file("cipher.nkem");r.output=dir.file("pasted-output");r.paste=true;r.key_text=Zeroizing::new(text);run(&backend,r).unwrap();assert_eq!(fs::read(dir.file("pasted-output")).unwrap(),data);
        let mut r=request(Kind::Encrypt);r.input=dir.file("plain");r.output=dir.file("cipher.nkem");r.key_path=dir.file("public.key");let before=fs::read(&r.output).unwrap();let job=backend.reserve(&r).unwrap();let _hold=Reservation{backend:backend.clone(),job:job.clone()};let cancel=job.clone();assert_eq!(execute(r,job,move|_,_|{cancel.cancelled.store(true,Ordering::Release);}).err().unwrap().code,"cancelled");assert_eq!(fs::read(dir.file("cipher.nkem")).unwrap(),before);
    }
    #[test]
    fn contact_store_changes_share_the_exclusive_operation_slot() {
        let backend=Arc::new(Backend::default());
        let job=backend.reserve_internal().unwrap();
        assert_eq!(backend.reserve(&request(Kind::Encrypt)).err().unwrap().code,"busy");
        assert_eq!(backend.reserve_internal().err().unwrap().code,"busy");
        assert!(!backend.cancel("contacts"));
        assert!(backend.defer_close());
        backend.finish(&job);
        assert!(!backend.defer_close());
        backend.finish(&backend.reserve_internal().unwrap());
    }

    // Tests that replace the global test store location or count shared staging
    // directories must not run concurrently with each other.
    static STAGING_TESTS:Mutex<()>=Mutex::new(());
    fn staging_entries()->usize {
        // Contact-store tests stage keys only while holding the Core lock.
        let _core=CORE_LOCK.lock().unwrap_or_else(|error|error.into_inner());
        let prefix=if cfg!(windows){"nekokem-paste-"}else{"nekokem-gui-"};
        fs::read_dir(std::env::temp_dir()).unwrap().filter(|entry|entry.as_ref().unwrap().file_name().to_string_lossy().starts_with(prefix)).count()
    }

    #[test]
    fn saved_contact_encryption_uses_only_the_verified_record() {
        let _serial=STAGING_TESTS.lock().unwrap_or_else(|error|error.into_inner());
        use crate::contacts::{tests as contact,Source,Store};
        struct Override;
        impl Drop for Override {fn drop(&mut self){*contact::DIRECTORY.lock().unwrap_or_else(|error|error.into_inner())=None;}}
        let keys=contact::Directory::new();
        let contacts=keys.private("contacts");
        *contact::DIRECTORY.lock().unwrap_or_else(|error|error.into_inner())=Some(contacts.0.to_str().unwrap().into());
        let _override=Override;
        // Argon2id runs on OpenSSL's shared thread pool: like the app, run every
        // Core call under the Core lock so concurrent tests never contend for it.
        let ((alice,alice_private,alice_fingerprint),(bob,bob_private,_))={let _core=contact::core_lock();
            (contact::keypair(&keys,"alice","public-test-alice"),contact::keypair(&keys,"bob","public-test-bob"))};
        let (alice_id,bob_id)={
            let _core=contact::core_lock();
            let store=Store::open().unwrap();
            (store.save(Source::Path(&alice),"Alice").unwrap().id,store.save(Source::Path(&bob),"Bob").unwrap().id)
        };
        let data:Vec<u8>=(0..300000).map(|i|(i%251) as u8).collect();
        fs::write(keys.file("plain"),&data).unwrap();
        let staged=staging_entries();
        let backend=Arc::new(Backend::default());
        let encrypt=|contact:&str,output:&str|{let mut r=request(Kind::Encrypt);r.input=keys.file("plain");r.output=keys.file(output);r.contacts=vec![contact.into()];r};
        let decrypt=|private:&str,password:&str,output:&str|{let mut r=request(Kind::Decrypt);r.input=keys.file("alice.nkem");r.output=keys.file(output);r.key_path=private.into();r.password=Zeroizing::new(password.into());r};

        let outcome=run(&backend,encrypt(&alice_id,"alice.nkem")).unwrap();
        assert_eq!(outcome.fingerprint.as_deref(),Some(alice_fingerprint.as_str()));
        run(&backend,decrypt(&alice_private,"public-test-alice","alice.out")).unwrap();
        assert_eq!(fs::read(keys.file("alice.out")).unwrap(),data);
        assert_eq!(run(&backend,decrypt(&bob_private,"public-test-bob","bob.out")).err().unwrap().code,"core-error");

        // A contact is never combined with, or replaced by, another key source.
        let mut r=encrypt(&alice_id,"combined.nkem");r.key_path=bob.clone();
        assert_eq!(run(&backend,r).err().unwrap().code,"invalid-request");
        let mut r=encrypt(&alice_id,"combined.nkem");r.paste=true;r.key_text=Zeroizing::new(fs::read_to_string(&bob).unwrap());
        assert_eq!(run(&backend,r).err().unwrap().code,"invalid-request");
        for kind in [Kind::Decrypt,Kind::Fingerprint,Kind::Keygen] {let mut r=request(kind);r.contacts=vec![alice_id.clone()];assert_eq!(run(&backend,r).err().unwrap().code,"invalid-request");}
        assert_eq!(run(&backend,encrypt("../alice","combined.nkem")).err().unwrap().code,"invalid-request");
        assert!(fs::metadata(keys.file("combined.nkem")).is_err());

        // Deleted or damaged contacts fail without creating or replacing output.
        {let _core=contact::core_lock();Store::open().unwrap().delete(&bob_id).unwrap();}
        assert_eq!(run(&backend,encrypt(&bob_id,"bob.nkem")).err().unwrap().code,"contact-missing");
        assert!(fs::metadata(keys.file("bob.nkem")).is_err());
        let before=fs::read(keys.file("alice.nkem")).unwrap();
        fs::write(contacts.file(&format!("{alice_id}.json")),"{").unwrap();
        assert_eq!(run(&backend,encrypt(&alice_id,"alice.nkem")).err().unwrap().code,"contact-invalid");
        assert_eq!(fs::read(keys.file("alice.nkem")).unwrap(),before);

        // After an explicit re-import, cancellation still preserves the existing output.
        {let _core=contact::core_lock();Store::open().unwrap().save(Source::Path(&alice),"Alice").unwrap();}
        let r=encrypt(&alice_id,"alice.nkem");let job=backend.reserve(&r).unwrap();let _hold=Reservation{backend:backend.clone(),job:job.clone()};let cancel=job.clone();
        assert_eq!(execute(r,job,move|_,_|{cancel.cancelled.store(true,Ordering::Release);}).err().unwrap().code,"cancelled");
        assert_eq!(fs::read(keys.file("alice.nkem")).unwrap(),before);
        // Every operation-specific public-key snapshot was removed.
        assert_eq!(staging_entries(),staged);
    }
    #[test]
    fn several_saved_contacts_each_decrypt_one_file_and_failures_name_the_contact() {
        let _serial=STAGING_TESTS.lock().unwrap_or_else(|error|error.into_inner());
        use crate::contacts::{tests as contact,Source,Store};
        struct Override;
        impl Drop for Override {fn drop(&mut self){*contact::DIRECTORY.lock().unwrap_or_else(|error|error.into_inner())=None;}}
        let keys=contact::Directory::new();
        let contacts=keys.private("contacts");
        *contact::DIRECTORY.lock().unwrap_or_else(|error|error.into_inner())=Some(contacts.0.to_str().unwrap().into());
        let _override=Override;
        let people:Vec<(String,String,String)>={let _core=contact::core_lock();["alice","bob","carol"].iter()
            .map(|name|contact::keypair(&keys,name,&format!("public-test-{name}"))).collect()};
        let ids:Vec<String>={
            let _core=contact::core_lock();
            let store=Store::open().unwrap();
            people.iter().zip(["Alice","Bob","Carol"]).map(|((public,_,_),note)|store.save(Source::Path(public),note).unwrap().id).collect()
        };
        let data:Vec<u8>=(0..200000).map(|i|(i%241) as u8).collect();
        fs::write(keys.file("plain"),&data).unwrap();
        let staged=staging_entries();
        let backend=Arc::new(Backend::default());
        let encrypt=|ids:&[&String],output:&str|{let mut r=request(Kind::Encrypt);r.input=keys.file("plain");r.output=keys.file(output);r.contacts=ids.iter().map(|id|(*id).clone()).collect();r};
        let decrypt=|person:usize,output:&str|{let mut r=request(Kind::Decrypt);r.input=keys.file("group.nkem");r.output=keys.file(output);r.key_path=people[person].1.clone();r.password=Zeroizing::new(["public-test-alice","public-test-bob","public-test-carol"][person].into());r};

        // Alice and Bob each decrypt the one NKEM v4 file; Carol, not selected, cannot.
        let outcome=run(&backend,encrypt(&[&ids[1],&ids[0]],"group.nkem")).unwrap();
        assert_eq!(outcome.recipients,vec![people[1].2.clone(),people[0].2.clone()]);
        assert_eq!(outcome.fingerprint,None);
        assert_eq!(fs::read(keys.file("group.nkem")).unwrap()[4],4);
        for person in [0,1] {
            run(&backend,decrypt(person,"group.out")).unwrap();
            assert_eq!(fs::read(keys.file("group.out")).unwrap(),data);
        }
        assert_eq!(run(&backend,decrypt(2,"carol.out")).err().unwrap().code,"core-error");
        assert!(fs::metadata(keys.file("carol.out")).is_err());
        // One selected contact keeps writing NKEM v3.
        let outcome=run(&backend,encrypt(&[&ids[2]],"single.nkem")).unwrap();
        assert_eq!((outcome.fingerprint.as_deref(),outcome.recipients.len()),(Some(people[2].2.as_str()),1));
        assert_eq!(fs::read(keys.file("single.nkem")).unwrap()[4],3);

        // Duplicates, too many recipients and mixed key sources are refused.
        assert_eq!(run(&backend,encrypt(&[&ids[0],&ids[0]],"bad.nkem")).err().unwrap().code,"invalid-request");
        let many:Vec<String>=(0..=MAX_RECIPIENTS).map(|index|format!("{index:064x}")).collect();
        assert_eq!(run(&backend,encrypt(&many.iter().collect::<Vec<_>>(),"bad.nkem")).err().unwrap().code,"recipient-limit");
        let mut r=encrypt(&[&ids[0],&ids[1]],"bad.nkem");r.key_path=people[2].0.clone();
        assert_eq!(run(&backend,r).err().unwrap().code,"invalid-request");
        assert!(fs::metadata(keys.file("bad.nkem")).is_err());

        // A deleted contact fails the whole file and is named; the others are not
        // used on their own and the existing output stays unchanged.
        let before=fs::read(keys.file("group.nkem")).unwrap();
        {let _core=contact::core_lock();Store::open().unwrap().delete(&ids[1]).unwrap();}
        let failure=run(&backend,encrypt(&[&ids[0],&ids[1],&ids[2]],"group.nkem")).err().unwrap();
        assert_eq!((failure.code,failure.contact.as_deref()),("contact-missing",Some(ids[1].as_str())));
        assert_eq!(fs::read(keys.file("group.nkem")).unwrap(),before);
        fs::write(contacts.file(&format!("{}.json",ids[2])),"{").unwrap();
        let failure=run(&backend,encrypt(&[&ids[0],&ids[2]],"group.nkem")).err().unwrap();
        assert_eq!((failure.code,failure.contact.as_deref()),("contact-invalid",Some(ids[2].as_str())));
        assert_eq!(fs::read(keys.file("group.nkem")).unwrap(),before);

        // Cancelling a multi-recipient encryption commits nothing.
        {let _core=contact::core_lock();let store=Store::open().unwrap();store.save(Source::Path(&people[1].0),"Bob").unwrap();}
        let r=encrypt(&[&ids[0],&ids[1]],"group.nkem");let job=backend.reserve(&r).unwrap();let _hold=Reservation{backend:backend.clone(),job:job.clone()};let cancel=job.clone();
        assert_eq!(execute(r,job,move|_,_|{cancel.cancelled.store(true,Ordering::Release);}).err().unwrap().code,"cancelled");
        assert_eq!(fs::read(keys.file("group.nkem")).unwrap(),before);
        assert_eq!(staging_entries(),staged);
    }
    #[cfg(unix)]
    #[test]
    fn gui_language_preferences_and_locale_are_independent_of_terminal_encoding() {
        use std::ffi::OsString;
        use std::os::unix::fs::PermissionsExt;
        struct Environment(Vec<(&'static str, Option<OsString>)>);
        impl Drop for Environment {
            fn drop(&mut self) {
                for (name, value) in &self.0 {
                    match value { Some(value) => std::env::set_var(name, value),
                                  None => std::env::remove_var(name) }
                }
                language();
            }
        }
        let _core = CORE_LOCK.lock().unwrap();
        let directory = Directory::new();
        let _environment = Environment(["XDG_CONFIG_HOME", "LC_ALL", "LC_MESSAGES", "LC_CTYPE", "LANG"]
            .iter().map(|&name| (name, std::env::var_os(name))).collect());
        std::env::set_var("XDG_CONFIG_HOME", &directory.0);
        std::env::set_var("LC_ALL", "C");
        for tag in ["en", "zh-CN", "zh-TW", "ja", "ko"] {
            assert_eq!(save_language(tag).unwrap(), tag);
            assert_eq!(preference(), tag);
        }
        assert_eq!(fs::metadata(directory.0.join("nekokem/language")).unwrap().permissions().mode() & 0o777, 0o600);
        std::env::set_var("LC_ALL", "");
        std::env::set_var("LC_CTYPE", "C");
        std::env::set_var("LC_MESSAGES", "zh_HK.UTF-8");
        std::env::set_var("LANG", "ko_KR.UTF-8");
        assert_eq!(save_language("system").unwrap(), "zh-TW");
        assert_eq!(preference(), "system");
        std::env::set_var("LC_ALL", "ja_JP.UTF-8");
        assert_eq!(language(), "ja");
        std::env::set_var("LC_ALL", "");
        std::env::set_var("LC_MESSAGES", "");
        assert_eq!(language(), "ko");
        std::env::set_var("LANG", "de_DE.UTF-8");
        assert_eq!(language(), "en");
    }

    #[cfg(unix)]
    #[test]
    fn posix_staging_permissions_cleanup_and_link_rejection() {
        let _serial=STAGING_TESTS.lock().unwrap_or_else(|error|error.into_inner());
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
