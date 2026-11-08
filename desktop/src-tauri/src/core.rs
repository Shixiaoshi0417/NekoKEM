use serde::{Deserialize, Serialize};
use std::{ffi::{c_char,c_int,c_void,CStr,CString}, sync::{atomic::{AtomicBool,Ordering},Arc,Mutex}};
use zeroize::Zeroizing;
use std::time::{Duration,Instant,SystemTime};

pub static CORE_LOCK: Mutex<()> = Mutex::new(());
// Same limit as Core's NEKOKEM_MAX_RECIPIENTS for one NKEM v4/v5 container.
pub const MAX_RECIPIENTS: usize = 64;
#[derive(Debug,Serialize)]
pub struct Failure {
    pub code: &'static str,
    // Identifies the saved contact that already holds an imported public key.
    #[serde(skip_serializing_if="Option::is_none")] pub contact: Option<String>,
    // The new identity fingerprint the user must check before an upgrade.
    #[serde(skip_serializing_if="Option::is_none")] pub fingerprint: Option<String>,
}
impl Failure {
    pub fn new(code: &'static str) -> Self { Self{code,contact:None,fingerprint:None} }
    pub fn existing(contact: String) -> Self { Self{code:"contact-exists",contact:Some(contact),fingerprint:None} }
    /// An identity key would replace the legacy contact [contact]; anyone with
    /// the legacy key could attach signing keys, so the user checks [fingerprint].
    pub fn upgrade(contact: String, fingerprint: String) -> Self {
        Self{code:"upgrade-unconfirmed",contact:Some(contact),fingerprint:Some(fingerprint)}
    }
}
#[derive(Deserialize,PartialEq,Eq)]
#[serde(rename_all="kebab-case")]
pub enum Kind { Keygen, Encrypt, Decrypt, Fingerprint, Upgrade, ExportLegacy }
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
    // Keygen only: the user confirmed replacing existing key files.
    #[serde(default)] pub replace:bool,
    // Encryption/decryption only: one confirmed overwrite of the offered output.
    #[serde(default)] pub overwrite:bool,
    // Saved recipient contacts (encryption only), in the user's order. Signed
    // encryption writes one NKEM v5 file for all of them; unsigned writes v3
    // for one and v4 for several. Never combined with another key source.
    #[serde(default)] pub contacts:Vec<String>,
    // Encryption: the sender's identity private key, opened with `password`.
    #[serde(default)] pub sign_key_path:String,
    // Encryption: the user turned on unsigned (legacy-compatible) output.
    #[serde(default)] pub unsigned:bool,
    // Decryption: Core refuses NKEM v3/v4 files without a signature.
    #[serde(default)] pub require_signature:bool,
}
#[derive(Serialize,Default)]
#[serde(rename_all="camelCase")]
pub struct Outcome {
    pub output:Option<String>, pub fingerprint:Option<String>,
    // Core-verified fingerprints of the saved contacts used, in request order.
    pub recipients:Vec<String>,
    // NKEM version Core wrote or read.
    #[serde(skip_serializing_if="Option::is_none")] pub format:Option<i32>,
    #[serde(skip_serializing_if="Option::is_none")] pub signature:Option<Signature>,
    // Fingerprint and upgrade: the X448 + ML-KEM-1024 fingerprint NekoKEM 4.x shows.
    #[serde(skip_serializing_if="Option::is_none")] pub legacy_fingerprint:Option<String>,
    #[serde(skip_serializing_if="Option::is_none")] pub can_verify:Option<bool>,
}
// Core decides the status and matches the signer; the GUI only reports it.
#[derive(Serialize,Debug)]
#[serde(rename_all="camelCase")]
pub struct Signature {
    pub status:&'static str,
    #[serde(skip_serializing_if="Option::is_none")] pub fingerprint:Option<String>,
    // The saved contact Core matched as the signer.
    #[serde(skip_serializing_if="Option::is_none")] pub contact:Option<String>,
    // An unknown signer's public key, offered for saving as a contact.
    #[serde(skip_serializing_if="Option::is_none")] pub signer_key:Option<String>,
}
pub struct Job { pub id:String, pub cancellable:bool, pub cancelled:AtomicBool }
#[derive(PartialEq,Eq)]
enum Confirmation { Keypair(String,String), Output(String) }
// Instant stops while the machine sleeps; the wall clock covers that time.
struct Offer { target:Confirmation, at:Instant, wall:SystemTime }
const OFFER_LIFETIME:Duration=Duration::from_secs(300);
impl Offer {
    fn fresh(&self)->bool {
        self.at.elapsed()<OFFER_LIFETIME && self.wall.elapsed().is_ok_and(|elapsed|elapsed<OFFER_LIFETIME)
    }
}
pub struct Backend { pub active:Mutex<Option<Arc<Job>>>, pub close_after:AtomicBool, offer:Mutex<Option<Offer>> }
impl Default for Backend { fn default()->Self { Self{active:Mutex::new(None),close_after:AtomicBool::new(false),offer:Mutex::new(None)} } }
impl Backend {
    pub fn reserve(&self, request:&Request)->Result<Arc<Job>,Failure> {
        if request.id.is_empty() || request.id.len()>64 || !request.id.bytes().all(|c|c.is_ascii_alphanumeric()||c==b'-') { return Err(Failure::new("invalid-request")); }
        self.claim(request.id.clone(),matches!(request.kind,Kind::Encrypt|Kind::Decrypt))
    }
    // Contact-store changes share the same exclusive slot as Core operations.
    pub fn reserve_internal(&self)->Result<Arc<Job>,Failure> {
        let job=self.claim("contacts".into(),false)?;
        self.offer.lock().map_err(|_|Failure::new("internal"))?.take();
        Ok(job)
    }
    fn offer(&self,target:Confirmation)->Result<(),Failure> {
        *self.offer.lock().map_err(|_|Failure::new("internal"))?=Some(Offer{target,at:Instant::now(),wall:SystemTime::now()});
        Ok(())
    }
    fn consume_offer(&self,request:&Request)->Result<(),Failure> {
        // Each offer belongs to this backend session and only its next operation.
        // Exact path spellings are intentional; aliases require a fresh offer.
        let offer=self.offer.lock().map_err(|_|Failure::new("internal"))?.take();
        let target=if request.replace {
            Some(Confirmation::Keypair(request.public_path.clone(),request.private_path.clone()))
        } else if request.overwrite {Some(Confirmation::Output(request.output.clone()))} else {None};
        if let Some(target)=target {
            if !offer.is_some_and(|offer|offer.target==target && offer.fresh()) {
                return Err(Failure::new("invalid-request"));
            }
        }
        Ok(())
    }
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
const ENCRYPT_UNSIGNED:u32=0x1;
const VERIFY_REQUIRE_SIGNATURE:u32=0x1;
#[repr(C)] struct EncryptOptions {struct_size:usize,flags:u32,sender_private_key_path:*const c_char,password:*const u8,password_len:usize}
#[repr(C)] struct EncryptResult {struct_size:usize,format_version:c_int,signed_by_sender:c_int}
#[repr(C)] struct VerifyPolicy {struct_size:usize,flags:u32,trusted_signer_paths:*const *const c_char,trusted_signer_count:usize,signer_public_key_output_path:*const c_char}
#[repr(C)] struct VerifyResult {struct_size:usize,format_version:c_int,signature_status:c_int,trusted_signer_index:usize,signer_fingerprint:[c_char;96]}
extern "C" {
    fn nekokem_generate_keypair(public:*const c_char, private:*const c_char,password:*const u8,length:usize)->c_int;
    fn nekokem_replace_keypair(public:*const c_char, private:*const c_char,password:*const u8,length:usize)->c_int;
    fn nekokem_encrypt_file_v5_with_progress(input:*const c_char,output:*const c_char,keys:*const *const c_char,count:usize,options:*const EncryptOptions,result:*mut EncryptResult,callback:Option<Progress>,data:*mut c_void)->c_int;
    fn nekokem_decrypt_file_verified_with_progress(input:*const c_char,output:*const c_char,key:*const c_char,password:*const u8,length:usize,policy:*const VerifyPolicy,result:*mut VerifyResult,callback:Option<Progress>,data:*mut c_void)->c_int;
    fn nekokem_upgrade_to_identity_keys(public:*const c_char,private:*const c_char,password:*const u8,length:usize)->c_int;
    fn nekokem_export_legacy_public_key(key:*const c_char,output:*const c_char)->c_int;
    fn nekokem_public_key_fingerprint(key:*const c_char,output:*mut c_char,size:usize)->c_int;
    fn nekokem_public_key_legacy_fingerprint(key:*const c_char,output:*mut c_char,size:usize)->c_int;
    fn nekokem_public_key_can_verify(key:*const c_char)->c_int;
    fn file_path_exists(path:*const c_char,exists:*mut c_int)->c_int;
    fn file_paths_are_same_file(first:*const c_char,second:*const c_char,same:*mut c_int)->c_int;
    fn file_set_output_no_replace(enabled:c_int)->c_int;
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
// Path checks go through Core, never std::fs: on Windows Core refuses UNC,
// device and stream paths before opening anything, so a network path never
// makes Windows authenticate to its server. None means the check failed.
fn exists(path:&CStr)->Option<bool> {
    let mut exists=0;
    (unsafe{file_path_exists(path.as_ptr(),&mut exists)}==1).then_some(exists!=0)
}
fn same_file(first:&CStr,second:&CStr)->Option<bool> {
    let mut same=0;
    (unsafe{file_paths_are_same_file(first.as_ptr(),second.as_ptr(),&mut same)}==1).then_some(same!=0)
}
fn fingerprint_text(output:&[c_char;96])->String { unsafe{CStr::from_ptr(output.as_ptr()).to_string_lossy().into_owned()} }
// Core parses and validates every public component before hashing: the
// identity fingerprint for a four-component key, the legacy one otherwise.
pub fn fingerprint(key:&CStr)->Option<String> {
    let mut output=[0 as c_char;96];
    if unsafe{nekokem_public_key_fingerprint(key.as_ptr(),output.as_mut_ptr(),output.len())}!=1 {return None;}
    Some(fingerprint_text(&output))
}
// Over only X448 + ML-KEM-1024, for both key kinds: what NekoKEM 4.x shows.
pub fn legacy_fingerprint(key:&CStr)->Option<String> {
    let mut output=[0 as c_char;96];
    if unsafe{nekokem_public_key_legacy_fingerprint(key.as_ptr(),output.as_mut_ptr(),output.len())}!=1 {return None;}
    Some(fingerprint_text(&output))
}
pub fn can_verify(key:&CStr)->bool { (unsafe{nekokem_public_key_can_verify(key.as_ptr())})==1 }
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
    pub fn path(&self)->Option<&CStr> {
        if self.0.is_null(){None}else{Some(unsafe{CStr::from_ptr(self.0)})}
    }
    pub fn close(&mut self)->bool {if self.0.is_null(){return true;} let p=std::mem::replace(&mut self.0,std::ptr::null_mut());unsafe{desktop_remove_staged_key(p)==1}}
}
impl Drop for Staged {fn drop(&mut self){let _=self.close();}}
struct ProgressContext<F:Fn(u64,u64)> { job:Arc<Job>, emit:F, last_emit:Option<Instant> }
struct OutputMode(c_int);
impl OutputMode {
    fn new(no_replace:bool)->Self {Self(unsafe{file_set_output_no_replace(i32::from(no_replace))})}
}
impl Drop for OutputMode {fn drop(&mut self){unsafe{file_set_output_no_replace(self.0)};}}
unsafe extern "C" fn progress<F:Fn(u64,u64)>(done:u64,total:u64,data:*mut c_void)->c_int {
    // Core borrows this stack-owned context synchronously; it never retains the pointer.
    let state=unsafe{&mut *(data as *mut ProgressContext<F>)};
    if state.job.cancelled.load(Ordering::Acquire) {return 0;}
    if done==total || state.last_emit.map_or(true,|at|at.elapsed()>=Duration::from_millis(100)) {
        // Release uses panic=abort, so catch_unwind cannot recover a panic here.
        // The emitter must remain non-panicking; IPC errors are ignored by its caller.
        (state.emit)(done,total); state.last_emit=Some(Instant::now());
    }
    i32::from(!state.job.cancelled.load(Ordering::Acquire))
}
pub fn execute<F:Fn(u64,u64)>(backend:&Backend,request:Request,job:Arc<Job>,emit:F)->Result<Outcome,Failure> {
    let _guard=CORE_LOCK.lock().map_err(|_|Failure::new("internal"))?;
    backend.consume_offer(&request)?;
    if request.password.len()>1024 || request.confirmation.len()>1024 {return Err(Failure::new("password-limit"));}
    if !request.contacts.is_empty() && (request.kind!=Kind::Encrypt || request.paste || !request.key_path.is_empty() || !request.key_text.is_empty()) {
        return Err(Failure::new("invalid-request"));
    }
    if request.replace && request.kind!=Kind::Keygen {return Err(Failure::new("invalid-request"));}
    if request.overwrite && !matches!(request.kind,Kind::Encrypt|Kind::Decrypt|Kind::ExportLegacy) {return Err(Failure::new("invalid-request"));}
    if (request.kind!=Kind::Encrypt && (request.unsigned || !request.sign_key_path.is_empty())) ||
        (request.kind==Kind::Encrypt && request.unsigned && !request.sign_key_path.is_empty()) ||
        (request.require_signature && request.kind!=Kind::Decrypt) {return Err(Failure::new("invalid-request"));}
    if request.contacts.len()>MAX_RECIPIENTS {return Err(Failure::new("recipient-limit"));}
    let mut seen=std::collections::HashSet::new();
    if !request.contacts.iter().all(|id|seen.insert(id.as_str())) {return Err(Failure::new("invalid-request"));}
    if request.kind==Kind::Keygen {
        if request.password.is_empty(){return Err(Failure::new("password-empty"));}
        if request.password.as_str()!=request.confirmation.as_str(){return Err(Failure::new("password-mismatch"));}
        // Keys are replaced only after the user confirmed it; otherwise Core
        // refuses as well, and this names the reason.
        let public=path(&request.public_path)?; let private=path(&request.private_path)?;
        if !request.replace && [&public,&private].iter().any(|target|exists(target)==Some(true)) {
            backend.offer(Confirmation::Keypair(request.public_path.clone(),request.private_path.clone()))?;
            return Err(Failure::new("key-exists"));
        }
        let commit=if request.replace {nekokem_replace_keypair} else {nekokem_generate_keypair};
        let result=unsafe{commit(public.as_ptr(),private.as_ptr(),request.password.as_ptr(),request.password.len())};
        return if result==1 {Ok(Outcome{output:Some(request.private_path),..Outcome::default()})}else{Err(Failure::new("core-error"))};
    }
    if request.kind==Kind::Upgrade {
        // Keeps the encryption keys and password; adds the two signing keys.
        if request.password.is_empty(){return Err(Failure::new("password-empty"));}
        let public=path(&request.public_path)?; let private=path(&request.private_path)?;
        if can_verify(&public) {return Err(Failure::new("already-identity"));}
        if unsafe{nekokem_upgrade_to_identity_keys(public.as_ptr(),private.as_ptr(),request.password.as_ptr(),request.password.len())}!=1 {
            return Err(Failure::new("core-error"));
        }
        return Ok(Outcome{output:Some(request.public_path.clone()),fingerprint:fingerprint(&public),legacy_fingerprint:legacy_fingerprint(&public),can_verify:Some(true),..Outcome::default()});
    }
    let signing=request.kind==Kind::Encrypt && !request.unsigned;
    if signing {
        // Signing never silently falls back to an unsigned file.
        if request.sign_key_path.is_empty() {return Err(Failure::new("sign-key-missing"));}
        if exists(&path(&request.sign_key_path)?)==Some(false) {return Err(Failure::new("sign-key-missing"));}
        if request.password.is_empty(){return Err(Failure::new("password-empty"));}
        if same_file(&path(&request.output)?,&path(&request.sign_key_path)?)==Some(true) {return Err(Failure::new("output-is-key"));}
    }
    // Core refuses an output that is the key in use; this names the reason.
    if matches!(request.kind,Kind::Encrypt|Kind::Decrypt|Kind::ExportLegacy) && !request.key_path.is_empty() && !request.paste {
        if same_file(&path(&request.output)?,&path(&request.key_path)?)==Some(true) {
            return Err(Failure::new("output-is-key"));
        }
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
    let keys=if staged.is_empty(){vec![path(&request.key_path)?]}else{
        staged.iter().map(|stage|stage.path().map(CStr::to_owned).ok_or_else(||Failure::new("internal"))).collect::<Result<Vec<_>,_>>()?
    };
    // Decryption trusts every saved contact with an identity key. A contact
    // that is missing or fails Core's check is left out: that only makes the
    // reported status weaker, never stronger.
    let mut signers=Vec::new();
    if request.kind==Kind::Decrypt {
        if let Ok(store)=crate::contacts::Store::open() { signers=store.signers(); }
    }
    let mut signer_output=if request.kind==Kind::Decrypt {Some(Staged::output()?)} else {None};
    let mut state=ProgressContext{job,emit,last_emit:None};
    let data=&mut state as *mut _ as *mut c_void;
    let result=(|| {
        if request.kind==Kind::Fingerprint {
            let fingerprint=fingerprint(&keys[0]).ok_or_else(||Failure::new("core-error"))?;
            return Ok(Outcome{fingerprint:Some(fingerprint),legacy_fingerprint:legacy_fingerprint(&keys[0]),can_verify:Some(can_verify(&keys[0])),..Outcome::default()});
        }
        // Exporting a legacy key reads only the key and writes the output.
        let input=if request.kind==Kind::ExportLegacy {CString::default()} else {path(&request.input)?};
        let output=path(&request.output)?;
        if !request.overwrite && exists(&output)==Some(true) {
            backend.offer(Confirmation::Output(request.output.clone()))?;
            return Err(Failure::new("output-exists"));
        }
        // A competing writer may create the target after the preflight check.
        // Core's thread-local mode makes the actual commit atomically create-only.
        let output_mode=OutputMode::new(!request.overwrite);
        let sign_key=if signing {Some(path(&request.sign_key_path)?)} else {None};
        let mut written=EncryptResult{struct_size:std::mem::size_of::<EncryptResult>(),format_version:0,signed_by_sender:0};
        let mut verified=VerifyResult{struct_size:std::mem::size_of::<VerifyResult>(),format_version:0,signature_status:0,trusted_signer_index:0,signer_fingerprint:[0;96]};
        let signer_paths:Vec<*const c_char>=signers.iter().map(|(_,stage):&(String,Staged)|stage.path().map_or(std::ptr::null(),CStr::as_ptr)).collect();
        if signer_paths.iter().any(|pointer|pointer.is_null()) {return Err(Failure::new("internal"));}
        let status=unsafe{match request.kind {
            Kind::Encrypt=>{
                let pointers:Vec<*const c_char>=keys.iter().map(|key|key.as_ptr()).collect();
                let options=EncryptOptions{struct_size:std::mem::size_of::<EncryptOptions>(),flags:if signing {0} else {ENCRYPT_UNSIGNED},
                    sender_private_key_path:sign_key.as_ref().map_or(std::ptr::null(),|key|key.as_ptr()),
                    password:if signing {request.password.as_ptr()} else {std::ptr::null()},password_len:if signing {request.password.len()} else {0}};
                nekokem_encrypt_file_v5_with_progress(input.as_ptr(),output.as_ptr(),pointers.as_ptr(),pointers.len(),&options,&mut written,Some(progress::<F>),data)
            }
            Kind::Decrypt=>{
                let policy=VerifyPolicy{struct_size:std::mem::size_of::<VerifyPolicy>(),flags:if request.require_signature {VERIFY_REQUIRE_SIGNATURE} else {0},
                    trusted_signer_paths:if signer_paths.is_empty() {std::ptr::null()} else {signer_paths.as_ptr()},trusted_signer_count:signer_paths.len(),
                    signer_public_key_output_path:signer_output.as_ref().and_then(Staged::path).map_or(std::ptr::null(),CStr::as_ptr)};
                nekokem_decrypt_file_verified_with_progress(input.as_ptr(),output.as_ptr(),keys[0].as_ptr(),request.password.as_ptr(),request.password.len(),&policy,&mut verified,Some(progress::<F>),data)
            }
            Kind::ExportLegacy=>nekokem_export_legacy_public_key(keys[0].as_ptr(),output.as_ptr()),
            _=>unreachable!(),
        }};
        drop(output_mode);
        if status==0 && !request.overwrite && exists(&output)==Some(true) {
            backend.offer(Confirmation::Output(request.output.clone()))?;
            return Err(Failure::new("output-exists"));
        }
        match status {1=>{},-1=>return Err(Failure::new("cancelled")),-10=>return Err(Failure::new("signature-invalid")),
            -11=>return Err(Failure::new("signature-required")),-12=>return Err(Failure::new("signer-not-trusted")),_=>return Err(Failure::new("core-error"))}
        let fingerprint=if recipients.len()==1 {recipients.first().cloned()} else {None};
        let mut outcome=Outcome{output:Some(request.output.clone()),fingerprint,recipients:recipients.clone(),..Outcome::default()};
        match request.kind {
            Kind::Encrypt=>outcome.format=Some(written.format_version),
            Kind::Decrypt=>{
                outcome.format=Some(verified.format_version);
                outcome.signature=Some(match verified.signature_status {
                    1=>Signature{status:"trusted",fingerprint:Some(fingerprint_text(&verified.signer_fingerprint)),
                        contact:signers.get(verified.trusted_signer_index).map(|(id,_)|id.clone()),signer_key:None},
                    // A public key, not a secret: offered for saving as a contact.
                    2=>Signature{status:"untrusted",fingerprint:Some(fingerprint_text(&verified.signer_fingerprint)),contact:None,
                        signer_key:signer_output.as_ref().and_then(Staged::path).and_then(crate::contacts::read_public_key)},
                    _=>Signature{status:"unsigned",fingerprint:None,contact:None,signer_key:None},
                });
            }
            _=>{}
        }
        Ok(outcome)
    })();
    let mut cleaned=true;
    for stage in staged.iter_mut() {cleaned&=stage.close();}
    for (_,stage) in signers.iter_mut() {cleaned&=stage.close();}
    if let Some(stage)=signer_output.as_mut() {cleaned&=stage.close();}
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
        fn desktop_write_private(path:*const c_char,bytes:*const u8,length:usize)->c_int;
    }
    // An existing output as Core writes one: owner-only on every platform.
    // Windows Core never replaces a file other accounts can open.
    fn write_owner_only(path:&str,bytes:&[u8]) {
        let name=CString::new(path).unwrap();
        assert_eq!(unsafe{desktop_write_private(name.as_ptr(),bytes.as_ptr(),bytes.len())},1);
    }
    #[cfg(windows)]
    #[test]
    fn network_paths_are_refused_before_any_access() {
        // TEST-NET-1 never answers, so opening it would stall for seconds and
        // send this machine's credentials to whatever answers instead.
        let started=Instant::now();
        for spelling in [r"\\192.0.2.1\share\public.key",r"//192.0.2.1/share/public.key",r"\\?\UNC\192.0.2.1\share\public.key"] {
            let unc=CString::new(spelling).unwrap();
            assert_eq!(exists(&unc),None);
            assert_eq!(same_file(&unc,&unc),None);
        }
        assert!(started.elapsed()<Duration::from_secs(2));
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
    fn request(kind:Kind)->Request {Request{id:"test-job".into(),input:String::new(),output:String::new(),key_path:String::new(),public_path:String::new(),private_path:String::new(),password:Zeroizing::new(String::new()),confirmation:Zeroizing::new(String::new()),key_text:Zeroizing::new(String::new()),paste:false,replace:false,overwrite:false,contacts:Vec::new(),
        // Signing has its own tests; the others check one behaviour of all formats.
        sign_key_path:String::new(),unsigned:kind==Kind::Encrypt,require_signature:false,kind}}
    fn run(backend:&Arc<Backend>,r:Request)->Result<Outcome,Failure>{let job=backend.reserve(&r)?;let _hold=Reservation{backend:backend.clone(),job:job.clone()};execute(&backend,r,job,|_,_|{})}
    struct Directory(PathBuf);
    impl Directory{fn new()->Self{let path=std::env::temp_dir().join(format!("nekokem-rust-{}-{}",std::process::id(),SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()));let name=super::path(path.to_str().unwrap()).unwrap();assert_eq!(unsafe{desktop_private_directory(name.as_ptr())},1);Self(path)}fn file(&self,name:&str)->String{self.0.join(name).to_str().unwrap().into()}}
    impl Drop for Directory {fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
    #[test] fn request_bounds_busy_and_stale_cancellation(){let backend=Arc::new(Backend::default());let r=request(Kind::Encrypt);let job=backend.reserve(&r).unwrap();assert!(backend.reserve(&r).is_err());assert!(!backend.cancel("other-job"));assert!(backend.cancel(&job.id));assert!(job.cancelled.load(Ordering::Acquire));backend.finish(&job);let mut r=request(Kind::Keygen);r.password=Zeroizing::new("a".repeat(1025));assert_eq!(run(&backend,r).err().unwrap().code,"password-limit");assert!(path("a\0b").is_err());}
    #[test] fn keygen_replaces_existing_keys_only_when_confirmed(){
        let dir=Directory::new();let backend=Arc::new(Backend::default());
        let keygen=|replace:bool|{let mut r=request(Kind::Keygen);r.public_path=dir.file("public.key");r.private_path=dir.file("private.key.enc");r.password=Zeroizing::new("public test password".into());r.confirmation=r.password.clone();r.replace=replace;run(&backend,r)};
        assert_eq!(keygen(true).err().unwrap().code,"invalid-request");
        assert!(!std::path::Path::new(&dir.file("public.key")).exists());
        keygen(false).unwrap();
        let public=fs::read(dir.file("public.key")).unwrap();let private=fs::read(dir.file("private.key.enc")).unwrap();
        assert_eq!(keygen(false).err().unwrap().code,"key-exists");
        assert_eq!(fs::read(dir.file("public.key")).unwrap(),public);assert_eq!(fs::read(dir.file("private.key.enc")).unwrap(),private);
        keygen(true).unwrap();
        assert_ne!(fs::read(dir.file("public.key")).unwrap(),public);assert_ne!(fs::read(dir.file("private.key.enc")).unwrap(),private);
        assert_eq!(keygen(true).err().unwrap().code,"invalid-request");
        // Core removes its backups and temporary files after the replacement.
        assert!(fs::read_dir(&dir.0).unwrap().all(|entry|{let name=entry.unwrap().file_name().to_string_lossy().into_owned();!name.contains(".bak")&&!name.contains(".tmp")}));
        let mut r=request(Kind::Encrypt);r.replace=true;assert_eq!(run(&backend,r).err().unwrap().code,"invalid-request");
    }
    #[test]
    fn replacement_offers_are_session_bound_exact_single_use_and_expiring() {
        let backend=Backend::default();let other=Backend::default();
        let mut r=request(Kind::Keygen);r.public_path="public.key".into();r.private_path="private.key.enc".into();r.replace=true;
        let target=||Confirmation::Keypair("public.key".into(),"private.key.enc".into());
        backend.offer(target()).unwrap();
        assert_eq!(other.consume_offer(&r).err().unwrap().code,"invalid-request");
        r.public_path="other.key".into();assert!(backend.consume_offer(&r).is_err());
        r.public_path="public.key".into();assert!(backend.consume_offer(&r).is_err());
        backend.offer(target()).unwrap();backend.consume_offer(&r).unwrap();assert!(backend.consume_offer(&r).is_err());
        *backend.offer.lock().unwrap()=Some(Offer{target:target(),at:Instant::now()-Duration::from_secs(301),wall:SystemTime::now()});
        assert!(backend.consume_offer(&r).is_err());
        // A suspended machine: the monotonic clock barely moved, the wall clock did.
        *backend.offer.lock().unwrap()=Some(Offer{target:target(),at:Instant::now(),wall:SystemTime::now()-Duration::from_secs(301)});
        assert!(backend.consume_offer(&r).is_err());
        // A wall clock set backwards does not extend an offer either.
        *backend.offer.lock().unwrap()=Some(Offer{target:target(),at:Instant::now(),wall:SystemTime::now()+Duration::from_secs(60)});
        assert!(backend.consume_offer(&r).is_err());
        backend.offer(target()).unwrap();backend.consume_offer(&request(Kind::Fingerprint)).unwrap();assert!(backend.consume_offer(&r).is_err());
        backend.offer(target()).unwrap();let job=backend.reserve_internal().unwrap();backend.finish(&job);assert!(backend.consume_offer(&r).is_err());
    }
    #[test]
    fn existing_output_requires_one_session_confirmation() {
        let dir=Directory::new();let backend=Arc::new(Backend::default());
        let mut r=request(Kind::Keygen);r.public_path=dir.file("public.key");r.private_path=dir.file("private.key.enc");r.password=Zeroizing::new("public test password".into());r.confirmation=r.password.clone();run(&backend,r).unwrap();
        fs::write(dir.file("plain"),b"public test input").unwrap();write_owner_only(&dir.file("output"),b"existing output");
        let encrypt=|overwrite|{let mut r=request(Kind::Encrypt);r.input=dir.file("plain");r.output=dir.file("output");r.key_path=dir.file("public.key");r.overwrite=overwrite;r};
        assert_eq!(run(&backend,encrypt(true)).err().unwrap().code,"invalid-request");
        assert_eq!(run(&backend,encrypt(false)).err().unwrap().code,"output-exists");
        assert_eq!(fs::read(dir.file("output")).unwrap(),b"existing output");
        run(&backend,encrypt(true)).unwrap();assert_ne!(fs::read(dir.file("output")).unwrap(),b"existing output");
        assert_eq!(run(&backend,encrypt(true)).err().unwrap().code,"invalid-request");
    }
    #[test]
    fn an_output_created_during_encryption_is_preserved_until_confirmed() {
        let dir=Directory::new();let backend=Arc::new(Backend::default());
        let mut r=request(Kind::Keygen);r.public_path=dir.file("public.key");r.private_path=dir.file("private.key.enc");r.password=Zeroizing::new("public test password".into());r.confirmation=r.password.clone();run(&backend,r).unwrap();
        fs::write(dir.file("plain"),vec![7u8;200000]).unwrap();
        let encrypt=|overwrite|{let mut r=request(Kind::Encrypt);r.input=dir.file("plain");r.output=dir.file("race-output");r.key_path=dir.file("public.key");r.overwrite=overwrite;r};
        let r=encrypt(false);let job=backend.reserve(&r).unwrap();
        let hold=Reservation{backend:backend.clone(),job:job.clone()};
        let created=AtomicBool::new(false);let output=dir.file("race-output");
        let result=execute(&backend,r,job,|_,_|{
            if !created.swap(true,Ordering::AcqRel) {write_owner_only(&output,b"competing writer");}
        });
        drop(hold);assert!(created.load(Ordering::Acquire));
        assert_eq!(result.err().unwrap().code,"output-exists");
        assert_eq!(fs::read(&output).unwrap(),b"competing writer");
        run(&backend,encrypt(true)).unwrap();assert_ne!(fs::read(output).unwrap(),b"competing writer");
    }
    #[test]
    fn staged_path_is_absent_after_close() {
        let _serial=STAGING_TESTS.lock().unwrap_or_else(|error|error.into_inner());
        let _core=CORE_LOCK.lock().unwrap_or_else(|error|error.into_inner());
        let mut staged=Staged::new("public test bytes").unwrap();assert!(staged.path().is_some());
        assert!(staged.close());assert!(staged.path().is_none());assert!(staged.close());
    }
    #[test] fn actual_core_roundtrip_fingerprint_wrong_password_paste_and_cancel(){
        let dir=Directory::new();let backend=Arc::new(Backend::default());let mut r=request(Kind::Keygen);
        r.public_path=dir.file("public.key");r.private_path=dir.file("private.key.enc");r.password=Zeroizing::new("public-test-中文-😀".into());r.confirmation=r.password.clone();run(&backend,r).unwrap();
        let data:Vec<u8>=(0..1048576).map(|i|(i%256)as u8).collect();fs::write(dir.file("plain"),&data).unwrap();
        let mut r=request(Kind::Encrypt);r.input=dir.file("plain");r.output=dir.file("cipher.nkem");r.key_path=dir.file("public.key");run(&backend,r).unwrap();
        let mut r=request(Kind::Decrypt);r.input=dir.file("cipher.nkem");r.output=dir.file("output");r.key_path=dir.file("private.key.enc");r.password=Zeroizing::new("public-test-中文-😀".into());run(&backend,r).unwrap();assert_eq!(fs::read(dir.file("output")).unwrap(),data);
        let protected=fs::read(dir.file("private.key.enc")).unwrap();
        let mut r=request(Kind::Keygen);r.public_path=dir.file("public.key");r.private_path=dir.file("private.key.enc");r.password=Zeroizing::new("public-test-中文-😀".into());r.confirmation=r.password.clone();assert_eq!(run(&backend,r).err().unwrap().code,"key-exists");
        let mut r=request(Kind::Decrypt);r.input=dir.file("cipher.nkem");r.output=dir.file("private.key.enc");r.key_path=dir.file("private.key.enc");r.password=Zeroizing::new("public-test-中文-😀".into());assert_eq!(run(&backend,r).err().unwrap().code,"output-is-key");
        assert_eq!(fs::read(dir.file("private.key.enc")).unwrap(),protected);
        let mut r=request(Kind::Decrypt);r.input=dir.file("cipher.nkem");r.output=dir.file("wrong-output");r.key_path=dir.file("private.key.enc");r.password=Zeroizing::new("wrong".into());assert_eq!(run(&backend,r).err().unwrap().code,"core-error");assert_eq!(fs::read(dir.file("output")).unwrap(),data);
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
        let mut check=request(Kind::Encrypt);check.input=dir.file("plain");check.output=dir.file("cipher.nkem");check.key_path=dir.file("public.key");assert_eq!(run(&backend,check).err().unwrap().code,"output-exists");
        let mut r=request(Kind::Encrypt);r.input=dir.file("plain");r.output=dir.file("cipher.nkem");r.key_path=dir.file("public.key");r.overwrite=true;let before=fs::read(&r.output).unwrap();let job=backend.reserve(&r).unwrap();let _hold=Reservation{backend:backend.clone(),job:job.clone()};let cancel=job.clone();assert_eq!(execute(&backend,r,job,move|_,_|{cancel.cancelled.store(true,Ordering::Release);}).err().unwrap().code,"cancelled");assert_eq!(fs::read(dir.file("cipher.nkem")).unwrap(),before);
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
        let mut probe=Staged::output().unwrap();
        let directory=std::path::Path::new(probe.path().unwrap().to_str().unwrap()).parent().unwrap().parent().unwrap().to_owned();
        assert!(probe.close());
        fs::read_dir(directory).unwrap().filter(|entry|entry.as_ref().unwrap().file_name().to_string_lossy().starts_with(prefix)).count()
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
        assert_eq!(run(&backend,encrypt(&alice_id,"alice.nkem")).err().unwrap().code,"output-exists");
        let mut r=encrypt(&alice_id,"alice.nkem");r.overwrite=true;let job=backend.reserve(&r).unwrap();let _hold=Reservation{backend:backend.clone(),job:job.clone()};let cancel=job.clone();
        assert_eq!(execute(&backend,r,job,move|_,_|{cancel.cancelled.store(true,Ordering::Release);}).err().unwrap().code,"cancelled");
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
            let output=format!("group-{person}.out");run(&backend,decrypt(person,&output)).unwrap();
            assert_eq!(fs::read(keys.file(&output)).unwrap(),data);
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
        assert_eq!(run(&backend,encrypt(&[&ids[0],&ids[1]],"group.nkem")).err().unwrap().code,"output-exists");
        let mut r=encrypt(&[&ids[0],&ids[1]],"group.nkem");r.overwrite=true;let job=backend.reserve(&r).unwrap();let _hold=Reservation{backend:backend.clone(),job:job.clone()};let cancel=job.clone();
        assert_eq!(execute(&backend,r,job,move|_,_|{cancel.cancelled.store(true,Ordering::Release);}).err().unwrap().code,"cancelled");
        assert_eq!(fs::read(keys.file("group.nkem")).unwrap(),before);
        assert_eq!(staging_entries(),staged);
    }
    // A NekoKEM 4.x key pair: the two encryption components under NKPR v1.
    fn legacy_keypair(dir:&Directory,identity_public:&str,identity_private:&str,password:&str)->(String,String){
        extern "C" {fn protected_private_key_write_version(path:*const c_char,pem:*const u8,length:usize,password:*const u8,password_length:usize,version:u32)->c_int;}
        let public=crate::contacts::tests::legacy_export(identity_public,dir.file("legacy.public.key"));
        let source=path(identity_private).unwrap();let mut pem=std::ptr::null_mut();let mut length=0;
        assert_eq!(unsafe{protected_private_key_read(source.as_ptr(),password.as_ptr(),password.len(),&mut pem,&mut length)},1);
        let text=unsafe{String::from_utf8(std::slice::from_raw_parts(pem,length).to_vec()).unwrap()};
        unsafe{CRYPTO_clear_free(pem.cast(),length,std::ptr::null(),0)};
        let end="-----END PRIVATE KEY-----\n";
        let two:String=text.split_inclusive(end).take(2).collect();
        let private=dir.file("legacy.private.key.enc");let target=path(&private).unwrap();
        assert_eq!(unsafe{protected_private_key_write_version(target.as_ptr(),two.as_ptr(),two.len(),password.as_ptr(),password.len(),1)},1);
        (public,private)
    }
    #[test]
    fn signed_files_report_the_signer_and_the_policy_is_enforced_by_core() {
        let _serial=STAGING_TESTS.lock().unwrap_or_else(|error|error.into_inner());
        use crate::contacts::{tests as contact,Source,Store};
        struct Override;
        impl Drop for Override {fn drop(&mut self){*contact::DIRECTORY.lock().unwrap_or_else(|error|error.into_inner())=None;}}
        let keys=contact::Directory::new();
        let contacts=keys.private("contacts");
        *contact::DIRECTORY.lock().unwrap_or_else(|error|error.into_inner())=Some(contacts.0.to_str().unwrap().into());
        let _override=Override;
        let ((alice,alice_private,alice_fingerprint),(bob,bob_private,_))={let _core=contact::core_lock();
            (contact::keypair(&keys,"alice","public-test-alice"),contact::keypair(&keys,"bob","public-test-bob"))};
        let alice_id={let _core=contact::core_lock();Store::open().unwrap().save(Source::Path(&alice),"Alice").unwrap().id};
        let data:Vec<u8>=(0..100000).map(|i|(i%239) as u8).collect();
        fs::write(keys.file("plain"),&data).unwrap();
        let staged=staging_entries();
        let backend=Arc::new(Backend::default());
        let encrypt=|output:&str|{let mut r=request(Kind::Encrypt);r.input=keys.file("plain");r.output=keys.file(output);r.key_path=bob.clone();
            r.unsigned=false;r.sign_key_path=alice_private.clone();r.password=Zeroizing::new("public-test-alice".into());r};
        let decrypt=|input:&str,output:&str|{let mut r=request(Kind::Decrypt);r.input=keys.file(input);r.output=keys.file(output);r.key_path=bob_private.clone();r.password=Zeroizing::new("public-test-bob".into());r};

        // Signing is the default and never falls back to an unsigned file.
        let mut r=encrypt("bad.nkem");r.password=Zeroizing::new(String::new());assert_eq!(run(&backend,r).err().unwrap().code,"password-empty");
        let mut r=encrypt("bad.nkem");r.sign_key_path=String::new();assert_eq!(run(&backend,r).err().unwrap().code,"sign-key-missing");
        let mut r=encrypt("bad.nkem");r.sign_key_path=keys.file("missing.key.enc");assert_eq!(run(&backend,r).err().unwrap().code,"sign-key-missing");
        let mut r=encrypt("bad.nkem");r.password=Zeroizing::new("wrong".into());assert_eq!(run(&backend,r).err().unwrap().code,"core-error");
        let mut r=encrypt("bad.nkem");r.unsigned=true;assert_eq!(run(&backend,r).err().unwrap().code,"invalid-request");
        let mut r=encrypt("bad.nkem");r.output=alice_private.clone();assert_eq!(run(&backend,r).err().unwrap().code,"output-is-key");
        for kind in [Kind::Decrypt,Kind::Fingerprint] {let mut r=request(kind);r.sign_key_path=alice_private.clone();assert_eq!(run(&backend,r).err().unwrap().code,"invalid-request");}
        let mut r=request(Kind::Encrypt);r.require_signature=true;assert_eq!(run(&backend,r).err().unwrap().code,"invalid-request");
        assert!(fs::metadata(keys.file("bad.nkem")).is_err());

        let outcome=run(&backend,encrypt("signed.nkem")).unwrap();
        assert_eq!(outcome.format,Some(5));
        assert_eq!(fs::read(keys.file("signed.nkem")).unwrap()[4],5);
        // Alice is a saved identity contact: Core names her as the signer.
        let outcome=run(&backend,decrypt("signed.nkem","trusted.out")).unwrap();
        assert_eq!(fs::read(keys.file("trusted.out")).unwrap(),data);
        let signature=outcome.signature.unwrap();
        assert_eq!((signature.status,signature.fingerprint.as_deref(),signature.contact.as_deref(),signature.signer_key.is_none()),
                   ("trusted",Some(alice_fingerprint.as_str()),Some(alice_id.as_str()),true));
        assert_eq!(outcome.format,Some(5));

        // Not a contact: valid but untrusted, with her key offered for saving.
        {let _core=contact::core_lock();Store::open().unwrap().delete(&alice_id).unwrap();}
        let mut r=decrypt("signed.nkem","untrusted.out");r.require_signature=true;
        let signature=run(&backend,r).unwrap().signature.unwrap();
        assert_eq!((signature.status,signature.fingerprint.as_deref(),signature.contact),("untrusted",Some(alice_fingerprint.as_str()),None));
        let offered=signature.signer_key.unwrap();
        let saved={let _core=contact::core_lock();Store::open().unwrap().save(Source::Text(&offered),"").unwrap()};
        assert_eq!((saved.id.as_str(),saved.fingerprint.as_str(),saved.can_verify),(alice_id.as_str(),alice_fingerprint.as_str(),true));

        // Unsigned files are reported, and refused when a signature is required.
        let mut r=encrypt("unsigned.nkem");r.unsigned=true;r.sign_key_path=String::new();r.password=Zeroizing::new(String::new());
        assert_eq!(run(&backend,r).unwrap().format,Some(3));
        let outcome=run(&backend,decrypt("unsigned.nkem","unsigned.out")).unwrap();
        assert_eq!((outcome.format,outcome.signature.unwrap().status),(Some(3),"unsigned"));
        let mut r=decrypt("unsigned.nkem","required.out");r.require_signature=true;
        assert_eq!(run(&backend,r).err().unwrap().code,"signature-required");
        assert!(fs::metadata(keys.file("required.out")).is_err());

        // Signed for several saved contacts: one v5 file.
        let bob_id={let _core=contact::core_lock();Store::open().unwrap().save(Source::Path(&bob),"Bob").unwrap().id};
        let mut r=encrypt("group.nkem");r.key_path=String::new();r.contacts=vec![bob_id,alice_id];
        let outcome=run(&backend,r).unwrap();
        assert_eq!((outcome.format,outcome.recipients.len()),(Some(5),2));
        run(&backend,decrypt("group.nkem","group.out")).unwrap();
        assert_eq!(fs::read(keys.file("group.out")).unwrap(),data);

        // Both fingerprints; the exported legacy key cannot verify.
        let mut r=request(Kind::Fingerprint);r.key_path=alice.clone();let outcome=run(&backend,r).unwrap();
        assert_eq!((outcome.fingerprint.as_deref(),outcome.can_verify),(Some(alice_fingerprint.as_str()),Some(true)));
        let legacy=outcome.legacy_fingerprint.unwrap();
        let mut r=request(Kind::ExportLegacy);r.key_path=alice.clone();r.output=keys.file("alice-legacy.pub");run(&backend,r).unwrap();
        let mut r=request(Kind::ExportLegacy);r.key_path=alice.clone();r.output=keys.file("alice-legacy.pub");assert_eq!(run(&backend,r).err().unwrap().code,"output-exists");
        let mut r=request(Kind::Fingerprint);r.key_path=keys.file("alice-legacy.pub");let outcome=run(&backend,r).unwrap();
        assert_eq!((outcome.fingerprint,outcome.legacy_fingerprint,outcome.can_verify),(Some(legacy.clone()),Some(legacy),Some(false)));
        assert_eq!(staging_entries(),staged);
    }
    #[test]
    fn upgrading_a_legacy_key_keeps_its_encryption_keys_and_password() {
        let dir=Directory::new();let backend=Arc::new(Backend::default());
        let mut r=request(Kind::Keygen);r.public_path=dir.file("public.key");r.private_path=dir.file("private.key.enc");
        r.password=Zeroizing::new("public test password".into());r.confirmation=r.password.clone();run(&backend,r).unwrap();
        let (public,private)={let _core=CORE_LOCK.lock().unwrap_or_else(|error|error.into_inner());
            legacy_keypair(&dir,&dir.file("public.key"),&dir.file("private.key.enc"),"public test password")};
        let legacy=fingerprint(&path(&public).unwrap()).unwrap();
        fs::write(dir.file("plain"),b"public test input").unwrap();
        let mut r=request(Kind::Encrypt);r.input=dir.file("plain");r.output=dir.file("old.nkem");r.key_path=public.clone();run(&backend,r).unwrap();
        // A legacy key cannot sign.
        let mut r=request(Kind::Encrypt);r.input=dir.file("plain");r.output=dir.file("signed.nkem");r.key_path=public.clone();
        r.unsigned=false;r.sign_key_path=private.clone();r.password=Zeroizing::new("public test password".into());
        assert_eq!(run(&backend,r).err().unwrap().code,"core-error");
        let upgrade=|password:&str|{let mut r=request(Kind::Upgrade);r.public_path=public.clone();r.private_path=private.clone();r.password=Zeroizing::new(password.into());r};
        assert_eq!(run(&backend,upgrade("")).err().unwrap().code,"password-empty");
        assert_eq!(run(&backend,upgrade("wrong")).err().unwrap().code,"core-error");
        let outcome=run(&backend,upgrade("public test password")).unwrap();
        assert_eq!((outcome.legacy_fingerprint.as_deref(),outcome.can_verify),(Some(legacy.as_str()),Some(true)));
        assert_ne!(outcome.fingerprint.as_deref(),Some(legacy.as_str()));
        assert_eq!(run(&backend,upgrade("public test password")).err().unwrap().code,"already-identity");
        // Old files still decrypt with the same password; the key now signs.
        let mut r=request(Kind::Decrypt);r.input=dir.file("old.nkem");r.output=dir.file("old.out");r.key_path=private.clone();r.password=Zeroizing::new("public test password".into());
        run(&backend,r).unwrap();assert_eq!(fs::read(dir.file("old.out")).unwrap(),b"public test input");
        let mut r=request(Kind::Encrypt);r.input=dir.file("plain");r.output=dir.file("signed.nkem");r.key_path=public.clone();
        r.unsigned=false;r.sign_key_path=private.clone();r.password=Zeroizing::new("public test password".into());
        assert_eq!(run(&backend,r).unwrap().format,Some(5));
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
    fn staging_prefers_private_runtime_and_rejects_unsafe_runtime_or_symlinks() {
        let _serial=STAGING_TESTS.lock().unwrap_or_else(|error|error.into_inner());
        let _core=CORE_LOCK.lock().unwrap_or_else(|error|error.into_inner());
        use std::{ffi::OsString,os::unix::fs::{symlink,PermissionsExt}};
        struct Environment(Vec<(&'static str,Option<OsString>)>);
        impl Drop for Environment {fn drop(&mut self){for (name,value) in &self.0 {match value {Some(value)=>std::env::set_var(name,value),None=>std::env::remove_var(name)}}}}
        let home=Directory::new();let runtime=home.0.join("runtime");
        fs::create_dir(&runtime).unwrap();fs::set_permissions(&runtime,fs::Permissions::from_mode(0o700)).unwrap();
        let _environment=Environment(["HOME","XDG_RUNTIME_DIR"].iter().map(|&name|(name,std::env::var_os(name))).collect());
        std::env::set_var("HOME",&home.0);std::env::set_var("XDG_RUNTIME_DIR",&runtime);
        let mut stage=Staged::new("public test bytes").unwrap();
        assert!(std::path::Path::new(stage.path().unwrap().to_str().unwrap()).starts_with(&runtime));assert!(stage.close());
        fs::set_permissions(&runtime,fs::Permissions::from_mode(0o755)).unwrap();
        // Darwin uses its per-user temporary directory before the home fallback.
        // Query the OS independently; never chmod this shared per-user directory.
        #[cfg(target_os="macos")]
        let fallback={
            let output=std::process::Command::new("/usr/bin/getconf")
                .arg("DARWIN_USER_TEMP_DIR").output().unwrap();
            assert!(output.status.success());
            let directory=PathBuf::from(String::from_utf8(output.stdout).unwrap().trim_end());
            assert!(directory.is_absolute() && directory.is_dir());
            let name=path(directory.to_str().unwrap().trim_end_matches('/')).unwrap();
            assert_eq!(unsafe{desktop_private_directory(name.as_ptr())},1);
            directory
        };
        #[cfg(not(target_os="macos"))]
        let fallback=home.0.join(".nekokem-tmp");
        let check_fallback=|stage:&Staged| {
            let file=PathBuf::from(stage.path().unwrap().to_str().unwrap());
            let directory=file.parent().unwrap();
            // /var and /private/var can name the same macOS directory.
            assert_eq!(directory.parent().unwrap().canonicalize().unwrap(),fallback.canonicalize().unwrap());
            assert_eq!(fs::symlink_metadata(&file).unwrap().permissions().mode() & 0o777,0o600);
            let name=path(directory.to_str().unwrap()).unwrap();
            assert_eq!(unsafe{desktop_private_directory(name.as_ptr())},1);
        };
        let mut stage=Staged::new("public test bytes").unwrap();
        check_fallback(&stage);assert!(stage.close());
        fs::set_permissions(&runtime,fs::Permissions::from_mode(0o700)).unwrap();
        let alias=home.0.join("runtime-link");symlink(&runtime,&alias).unwrap();std::env::set_var("XDG_RUNTIME_DIR",alias);
        let mut stage=Staged::new("public test bytes").unwrap();
        check_fallback(&stage);assert!(stage.close());
        #[cfg(not(target_os="macos"))]
        {
            fs::set_permissions(&fallback,fs::Permissions::from_mode(0o755)).unwrap();
            assert!(Staged::new("public test bytes").is_err());
        }
        #[cfg(target_os="macos")]
        {
            // 0700 does not override an allowing Darwin ACL. Only alter our fixture.
            std::env::set_var("XDG_RUNTIME_DIR",&runtime);
            assert!(std::process::Command::new("/bin/chmod")
                .args(["+a","everyone allow read"]).arg(&runtime).status().unwrap().success());
            assert_eq!(fs::metadata(&runtime).unwrap().permissions().mode() & 0o777,0o700);
            let mut stage=Staged::new("public test bytes").unwrap();
            check_fallback(&stage);assert!(stage.close());
            assert!(std::process::Command::new("/bin/chmod")
                .arg("-N").arg(&runtime).status().unwrap().success());
            let mut stage=Staged::new("public test bytes").unwrap();
            assert!(std::path::Path::new(stage.path().unwrap().to_str().unwrap()).starts_with(&runtime));
            assert!(stage.close());
        }
    }

    #[cfg(unix)]
    #[test]
    fn posix_staging_permissions_cleanup_and_link_rejection() {
        let _serial=STAGING_TESTS.lock().unwrap_or_else(|error|error.into_inner());
        use std::os::unix::fs::{symlink, MetadataExt, PermissionsExt};
        let bytes = "public staging test fixture\n";
        {
        let _core=CORE_LOCK.lock().unwrap_or_else(|error|error.into_inner());
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
        }

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
        // Keygen never replaces an existing name, a symbolic link included, and
        // never writes through one: neither the backend check nor Core itself.
        assert_eq!(run(&backend, r).err().unwrap().code, "key-exists");
        let public = path(&dir.file("public-link")).unwrap();
        let private = path(&dir.file("private.key.enc")).unwrap();
        let password = b"public test password";
        assert_eq!({
            let _core = CORE_LOCK.lock().unwrap_or_else(|error| error.into_inner());
            unsafe { nekokem_generate_keypair(public.as_ptr(), private.as_ptr(), password.as_ptr(), password.len()) }
        }, 0);
        assert_eq!(fs::read(&destination).unwrap(), b"must survive");
        assert!(fs::symlink_metadata(dir.file("public-link")).unwrap().file_type().is_symlink());
        assert!(fs::symlink_metadata(dir.file("private.key.enc")).is_err());

        let _core=CORE_LOCK.lock().unwrap_or_else(|error|error.into_inner());
        let mut staged = Staged::new(bytes).unwrap();
        let file = PathBuf::from(unsafe { CStr::from_ptr(staged.0) }.to_str().unwrap());
        let directory = file.parent().unwrap().to_owned();
        // A runtime directory may be tmpfs or another volume than the test root.
        let hard_link=file.with_file_name("key-hard-link");
        fs::hard_link(&file, &hard_link).unwrap();
        assert!(!staged.close());
        assert_eq!(fs::read(&file).unwrap(), bytes.as_bytes());
        fs::remove_file(hard_link).unwrap();
        fs::remove_file(file).unwrap();
        fs::remove_dir(directory).unwrap();
    }
}
