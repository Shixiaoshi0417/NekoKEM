//! Desktop public-key contacts. One private JSON record per fingerprint binds a
//! Core-normalized public key to its fingerprint, source file name and note.
//! Every use re-reads the record and re-validates it with Core; a missing or
//! damaged entry fails closed and never falls back to another public key.
use crate::core::{self, Failure, Staged};
use serde::{Deserialize, Serialize};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::io::ErrorKind;

pub const MAX_CONTACTS: usize = 500;
pub const MAX_NOTE_CHARS: usize = 512;
const MAX_NAME_CHARS: usize = 128;
const MAX_PUBLIC_KEY_BYTES: usize = 32 * 1024;
const MAX_RECORD_BYTES: usize = 64 * 1024;
// Pasted-key staging limit shared with Core's PEM input checks.
const MAX_LINE_BYTES: usize = 16384;
const RECORD_VERSION: u32 = 1;
const MISSING: &str = "contact-missing";
const INVALID: &str = "contact-invalid";
const STORAGE: &str = "contact-storage";
const MISMATCH: &str = "contact-mismatch";

extern "C" {
    fn desktop_contacts_directory(path: *mut c_char, capacity: usize) -> c_int;
    fn desktop_write_private(path: *const c_char, bytes: *const u8, length: usize) -> c_int;
    fn desktop_remove_private(directory: *const c_char, name: *const c_char) -> c_int;
    fn nekokem_export_public_key(public_key: *const c_char, output: *const c_char) -> c_int;
    fn file_read_sensitive(path: *const c_char, maximum: usize, buffer: *mut *mut u8, length: *mut usize) -> c_int;
    fn secure_free(memory: *mut c_void, length: usize);
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Contact {
    pub id: String,
    pub fingerprint: String,
    pub name: String,
    pub note: String,
}
impl Contact {
    fn label(&self) -> &str { if self.note.is_empty() { &self.name } else { &self.note } }
}

#[derive(Debug, Serialize)]
pub struct Contacts {
    pub contacts: Vec<Contact>,
    // Damaged, unsafe or unknown records are counted, never shown or used.
    pub unreadable: usize,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Record {
    version: u32,
    fingerprint: String,
    name: String,
    note: String,
    public_key: String,
}
impl Record {
    fn is_valid(&self, id: &str) -> bool {
        self.version == RECORD_VERSION && identity(&self.fingerprint).as_deref() == Some(id) &&
            self.name == clean_name(&self.name) && clean_note(&self.note).is_ok_and(|note| note == self.note) &&
            valid_public_key(&self.public_key)
    }
    fn contact(&self, id: &str) -> Contact {
        Contact { id: id.into(), fingerprint: self.fingerprint.clone(), name: self.name.clone(), note: self.note.clone() }
    }
}

pub enum Source<'a> { Path(&'a str), Text(&'a str) }

/// Callers hold `CORE_LOCK`; Core's file layer enforces every privacy check.
pub struct Store { directory: String }
impl Store {
    pub fn open() -> Result<Self, Failure> {
        #[cfg(test)]
        if let Some(directory) = tests::DIRECTORY.lock().unwrap_or_else(|error| error.into_inner()).clone() {
            return Ok(Self { directory });
        }
        let mut buffer = vec![0 as c_char; 32768];
        if unsafe { desktop_contacts_directory(buffer.as_mut_ptr(), buffer.len()) } != 1 {
            return Err(Failure::new(STORAGE));
        }
        let directory = unsafe { CStr::from_ptr(buffer.as_ptr()) }.to_str().map_err(|_| Failure::new(STORAGE))?;
        Ok(Self { directory: directory.into() })
    }

    pub fn list(&self) -> Result<Contacts, Failure> {
        let mut contacts = Vec::new();
        let mut unreadable = 0;
        for id in self.ids()? {
            match self.read(&id) {
                Ok(record) => contacts.push(record.contact(&id)),
                Err(error) if error.code == MISSING => {}
                Err(_) => unreadable += 1,
            }
        }
        contacts.sort_by_cached_key(|contact| (contact.label().to_lowercase(), contact.id.clone()));
        Ok(Contacts { contacts, unreadable })
    }

    pub fn save(&self, source: Source<'_>, note: &str) -> Result<Contact, Failure> {
        let note = clean_note(note)?;
        let (name, mut pasted, input) = match source {
            Source::Path(path) => (clean_name(file_name(path)), None, core::path(path)?),
            Source::Text(text) => {
                let stage = Staged::new(text)?;
                let input = stage.path().ok_or_else(||Failure::new("internal"))?.to_owned();
                (String::new(), Some(stage), input)
            }
        };
        let result = self.import(&input, name, note);
        if pasted.as_mut().is_some_and(|stage| !stage.close()) { return Err(Failure::new("cleanup-error")); }
        result
    }

    pub fn update_note(&self, id: &str, note: &str) -> Result<Contact, Failure> {
        let note = clean_note(note)?;
        let mut record = self.read(checked(id)?)?;
        record.note = note;
        self.write(id, &record)?;
        Ok(record.contact(id))
    }

    pub fn delete(&self, id: &str) -> Result<(), Failure> {
        if !self.exists(checked(id)?)? { return Err(Failure::new(MISSING)); }
        let directory = CString::new(self.directory.as_str()).map_err(|_| Failure::new(STORAGE))?;
        let name = CString::new(format!("{id}.json")).map_err(|_| Failure::new(STORAGE))?;
        if unsafe { desktop_remove_private(directory.as_ptr(), name.as_ptr()) } != 1 {
            return Err(Failure::new(STORAGE));
        }
        Ok(())
    }

    /// Writes an operation-specific private snapshot and verifies that Core
    /// parses it to the recorded fingerprint. Encryption reads only this file.
    pub fn stage(&self, id: &str) -> Result<(Staged, String), Failure> {
        let record = self.read(checked(id)?)?;
        let mut stage = Staged::new(&record.public_key)?;
        if stage.path().and_then(core::fingerprint).as_deref() == Some(record.fingerprint.as_str()) {
            return Ok((stage, record.fingerprint));
        }
        if !stage.close() { return Err(Failure::new("cleanup-error")); }
        Err(Failure::new(MISMATCH))
    }

    fn import(&self, input: &CStr, name: String, note: String) -> Result<Contact, Failure> {
        let fingerprint = core::fingerprint(input).ok_or_else(|| Failure::new("public-key-invalid"))?;
        let id = identity(&fingerprint).ok_or_else(|| Failure::new("public-key-invalid"))?;
        // Only an entry that Core still verifies counts as already saved.
        match self.stage(&id) {
            Ok((mut stage, _)) => {
                return Err(if stage.close() { Failure::existing(id) } else { Failure::new("cleanup-error") });
            }
            Err(error) if error.code == MISSING => {
                if self.ids()?.len() >= MAX_CONTACTS { return Err(Failure::new("contact-limit")); }
            }
            // Explicitly re-importing the same key replaces a damaged or substituted record.
            Err(error) if error.code == INVALID || error.code == MISMATCH => {}
            Err(error) => return Err(error),
        }
        // Store only Core's canonical export: the two validated public
        // components, never extra PEM blocks or text from the source file.
        let mut output = Staged::output()?;
        let result = (|| {
            if unsafe { nekokem_export_public_key(input.as_ptr(), output.path().ok_or_else(||Failure::new("internal"))?.as_ptr()) } != 1 {
                return Err(Failure::new("public-key-invalid"));
            }
            if output.path().and_then(core::fingerprint).as_deref() != Some(fingerprint.as_str()) {
                return Err(Failure::new(MISMATCH));
            }
            let public_key = read_private(output.path().ok_or_else(||Failure::new("internal"))?, MAX_PUBLIC_KEY_BYTES)
                .and_then(|bytes| String::from_utf8(bytes).ok())
                .filter(|key| valid_public_key(key))
                .ok_or_else(|| Failure::new("public-key-invalid"))?;
            let record = Record { version: RECORD_VERSION, fingerprint, name, note, public_key };
            self.write(&id, &record)?;
            Ok(record.contact(&id))
        })();
        if !output.close() { return Err(Failure::new("cleanup-error")); }
        result
    }

    fn ids(&self) -> Result<Vec<String>, Failure> {
        let mut ids = Vec::new();
        for entry in std::fs::read_dir(&self.directory).map_err(|_| Failure::new(STORAGE))? {
            let name = entry.map_err(|_| Failure::new(STORAGE))?.file_name();
            // Core's atomic temporary/backup names and foreign files are ignored.
            if let Some(id) = name.to_str().and_then(|name| name.strip_suffix(".json")).filter(|id| valid_id(id)) {
                if ids.len() == MAX_CONTACTS { return Err(Failure::new(STORAGE)); }
                ids.push(id.to_owned());
            }
        }
        Ok(ids)
    }

    fn file(&self, id: &str) -> String { format!("{}/{id}.json", self.directory) }

    fn exists(&self, id: &str) -> Result<bool, Failure> {
        match std::fs::symlink_metadata(self.file(id)) {
            Ok(_) => Ok(true),
            Err(error) if error.kind() == ErrorKind::NotFound => Ok(false),
            Err(_) => Err(Failure::new(STORAGE)),
        }
    }

    fn read(&self, id: &str) -> Result<Record, Failure> {
        if !self.exists(id)? { return Err(Failure::new(MISSING)); }
        // Core rejects links, other owners, extra hard links and non-private modes/ACLs.
        let bytes = read_private(&CString::new(self.file(id)).map_err(|_| Failure::new(STORAGE))?, MAX_RECORD_BYTES)
            .ok_or_else(|| Failure::new(INVALID))?;
        let record: Record = serde_json::from_slice(&bytes).map_err(|_| Failure::new(INVALID))?;
        if !record.is_valid(id) { return Err(Failure::new(INVALID)); }
        Ok(record)
    }

    fn write(&self, id: &str, record: &Record) -> Result<(), Failure> {
        let bytes = serde_json::to_vec(record).map_err(|_| Failure::new(STORAGE))?;
        let path = CString::new(self.file(id)).map_err(|_| Failure::new(STORAGE))?;
        if bytes.len() > MAX_RECORD_BYTES ||
            unsafe { desktop_write_private(path.as_ptr(), bytes.as_ptr(), bytes.len()) } != 1 {
            return Err(Failure::new(STORAGE));
        }
        Ok(())
    }
}

fn read_private(path: &CStr, maximum: usize) -> Option<Vec<u8>> {
    let mut buffer = std::ptr::null_mut();
    let mut length = 0;
    if unsafe { file_read_sensitive(path.as_ptr(), maximum, &mut buffer, &mut length) } != 1 { return None; }
    let bytes = unsafe { std::slice::from_raw_parts(buffer, length) }.to_vec();
    unsafe { secure_free(buffer.cast(), length) };
    Some(bytes)
}

fn checked(id: &str) -> Result<&str, Failure> {
    if valid_id(id) { Ok(id) } else { Err(Failure::new("invalid-request")) }
}

fn valid_id(id: &str) -> bool {
    id.len() == 64 && id.bytes().all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
}

// Core fingerprints are 32 SHA-256 bytes as uppercase, colon-separated hex.
fn identity(fingerprint: &str) -> Option<String> {
    let valid = fingerprint.len() == 95 && fingerprint.bytes().enumerate().all(|(index, byte)| {
        if index % 3 == 2 { byte == b':' } else { matches!(byte, b'0'..=b'9' | b'A'..=b'F') }
    });
    valid.then(|| fingerprint.replace(':', "").to_ascii_lowercase())
}

fn valid_public_key(key: &str) -> bool {
    !key.is_empty() && key.len() <= MAX_PUBLIC_KEY_BYTES && key.is_ascii() && !key.contains('\0') &&
        key.starts_with("-----BEGIN PUBLIC KEY-----") && key.split('\n').all(|line| line.len() <= MAX_LINE_BYTES)
}

pub fn clean_note(note: &str) -> Result<String, Failure> {
    let note = note.trim();
    if note.chars().count() > MAX_NOTE_CHARS || note.chars().any(|c| c.is_control() && c != '\n' && c != '\t') {
        return Err(Failure::new("note-limit"));
    }
    Ok(note.into())
}

fn file_name(path: &str) -> &str { path.rsplit(['/', '\\']).next().unwrap_or_default() }

// Source names come from files received from others: drop control and
// bidirectional formatting characters so a name cannot disguise itself.
fn clean_name(name: &str) -> String {
    let visible: String = name.chars()
        .filter(|&c| !c.is_control() && !matches!(c, '\u{061c}' | '\u{200b}'..='\u{200f}' | '\u{2028}'..='\u{202e}' | '\u{2060}' | '\u{2066}'..='\u{2069}' | '\u{feff}'))
        .collect();
    visible.trim().chars().take(MAX_NAME_CHARS).collect::<String>().trim_end().into()
}

#[cfg(test)]
pub mod tests {
    use super::*;
    use crate::core::CORE_LOCK;
    use std::{fs, path::PathBuf, sync::Mutex, time::{SystemTime, UNIX_EPOCH}};

    // Test-only store location so integration tests never touch the user's configuration.
    pub static DIRECTORY: Mutex<Option<String>> = Mutex::new(None);

    extern "C" {
        fn nekokem_generate_keypair(public: *const c_char, private: *const c_char, password: *const u8, length: usize) -> c_int;
        fn desktop_private_directory(path: *const c_char) -> c_int;
    }

    pub struct Directory(pub PathBuf);
    impl Directory {
        pub fn new() -> Self {
            let path = std::env::temp_dir().join(format!("nekokem-contacts-{}-{}", std::process::id(),
                SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()));
            let name = CString::new(path.to_str().unwrap()).unwrap();
            assert_eq!(unsafe { desktop_private_directory(name.as_ptr()) }, 1);
            Self(path)
        }
        pub fn file(&self, name: &str) -> String { self.0.join(name).to_str().unwrap().into() }
        pub fn private(&self, name: &str) -> Self {
            let path = self.0.join(name);
            let cpath = CString::new(path.to_str().unwrap()).unwrap();
            assert_eq!(unsafe { desktop_private_directory(cpath.as_ptr()) }, 1);
            Self(path)
        }
    }
    impl Drop for Directory { fn drop(&mut self) { let _ = fs::remove_dir_all(&self.0); } }

    /// Generates a real Core key pair and returns (public path, private path, fingerprint).
    pub fn keypair(directory: &Directory, name: &str, password: &str) -> (String, String, String) {
        let public = directory.file(&format!("{name}.public.key"));
        let private = directory.file(&format!("{name}.private.key.enc"));
        let (cpublic, cprivate) = (CString::new(public.as_str()).unwrap(), CString::new(private.as_str()).unwrap());
        assert_eq!(unsafe { nekokem_generate_keypair(cpublic.as_ptr(), cprivate.as_ptr(), password.as_ptr(), password.len()) }, 1);
        let fingerprint = core::fingerprint(&cpublic).unwrap();
        (public, private, fingerprint)
    }

    pub fn core_lock() -> std::sync::MutexGuard<'static, ()> { CORE_LOCK.lock().unwrap_or_else(|error| error.into_inner()) }

    fn store(directory: &Directory) -> Store { Store { directory: directory.0.to_str().unwrap().into() } }
    fn code<T>(result: Result<T, Failure>) -> &'static str { result.err().expect("operation must fail").code }
    fn record_names(directory: &Directory) -> Vec<String> {
        let mut names: Vec<String> = fs::read_dir(&directory.0).unwrap()
            .map(|entry| entry.unwrap().file_name().into_string().unwrap()).collect();
        names.sort();
        names
    }
    fn rewrite(store: &Store, id: &str, json: &str) {
        let path = CString::new(store.file(id)).unwrap();
        assert_eq!(unsafe { desktop_write_private(path.as_ptr(), json.as_ptr(), json.len()) }, 1);
    }

    #[test]
    fn source_names_drop_controls_separators_bidi_and_zero_width_characters() {
        assert_eq!(clean_name(" a\u{0085}\u{2028}\u{2029}\u{061c}\u{200b}\u{200c}\u{200d}\u{200e}\u{200f}\u{202a}\u{202e}\u{2060}\u{2066}\u{2069}\u{feff}b.pub "),"ab.pub");
        assert_eq!(clean_name(&"猫".repeat(129)).chars().count(),128);
    }

    #[test]
    fn save_list_edit_delete_and_reopen_with_core_normalization() {
        let _core = core_lock();
        let keys = Directory::new();
        let contacts = keys.private("contacts");
        let store = store(&contacts);
        let (alice, _, alice_fingerprint) = keypair(&keys, "alice", "public-test-alice");
        let (bob, _, bob_fingerprint) = keypair(&keys, "bob", "public-test-bob");
        assert_eq!(store.list().unwrap().contacts, vec![]);

        // Leading text is accepted by Core's PEM reader but never stored.
        let annotated = keys.file("annotated \u{202e}yek\u{061c}.pem");
        fs::write(&annotated, format!("Alice's key from a trusted channel\n{}", fs::read_to_string(&alice).unwrap())).unwrap();
        let saved = store.save(Source::Path(&annotated), "  Alice 中文 😀\tlaptop  ").unwrap();
        assert_eq!(saved.fingerprint, alice_fingerprint);
        assert_eq!(saved.id, alice_fingerprint.replace(':', "").to_lowercase());
        assert_eq!(saved.note, "Alice 中文 😀\tlaptop");
        assert_eq!(saved.name, "annotated yek.pem");
        let record = store.read(&saved.id).unwrap();
        assert!(!record.public_key.contains("trusted channel"));
        assert_eq!(record.public_key.matches("-----BEGIN PUBLIC KEY-----").count(), 2);
        assert_eq!(record_names(&contacts), vec![format!("{}.json", saved.id)]);

        let duplicate = store.save(Source::Path(&alice), "other note").err().unwrap();
        assert_eq!((duplicate.code, duplicate.contact.as_deref()), ("contact-exists", Some(saved.id.as_str())));
        assert_eq!(store.read(&saved.id).unwrap().note, "Alice 中文 😀\tlaptop");

        let pasted = store.save(Source::Text(&fs::read_to_string(&bob).unwrap()), "").unwrap();
        assert_eq!((pasted.fingerprint.as_str(), pasted.name.as_str(), pasted.note.as_str()), (bob_fingerprint.as_str(), "", ""));
        // Labels sort by note, then source name; the pasted key has neither.
        let listed = store.list().unwrap();
        assert_eq!(listed.unreadable, 0);
        assert_eq!(listed.contacts, vec![pasted.clone(), saved.clone()]);

        let edited = store.update_note(&pasted.id, " Bob ").unwrap();
        assert_eq!(edited.note, "Bob");
        assert_eq!(store.update_note(&pasted.id, "").unwrap().note, "");
        store.update_note(&pasted.id, "Bob desktop").unwrap();
        // A new store instance (as after restarting the app) reads the same records.
        let reopened = Store { directory: store.directory.clone() };
        assert_eq!(reopened.list().unwrap().contacts.iter().map(|c| c.note.as_str()).collect::<Vec<_>>(),
                   vec!["Alice 中文 😀\tlaptop", "Bob desktop"]);

        reopened.delete(&pasted.id).unwrap();
        assert_eq!(code(reopened.delete(&pasted.id)), "contact-missing");
        assert_eq!(code(reopened.update_note(&pasted.id, "note")), "contact-missing");
        assert_eq!(code(reopened.stage(&pasted.id)), "contact-missing");
        assert_eq!(reopened.list().unwrap().contacts, vec![saved]);
        assert_eq!(record_names(&contacts).len(), 1);
    }

    #[test]
    fn invalid_keys_notes_and_identifiers_are_refused_without_records() {
        let _core = core_lock();
        let keys = Directory::new();
        let contacts = keys.private("contacts");
        let store = store(&contacts);
        let (alice, private, _) = keypair(&keys, "alice", "public-test-alice");
        assert_eq!(code(store.save(Source::Path(&alice), &"n".repeat(MAX_NOTE_CHARS + 1))), "note-limit");
        assert_eq!(code(store.save(Source::Path(&alice), "bell\u{7}")), "note-limit");
        assert!(store.save(Source::Path(&alice), &"中".repeat(MAX_NOTE_CHARS)).is_ok());
        fs::write(keys.file("text.key"), "not a public key\n").unwrap();
        assert_eq!(code(store.save(Source::Path(&keys.file("text.key")), "")), "public-key-invalid");
        assert_eq!(code(store.save(Source::Path(&private), "")), "public-key-invalid");
        assert_eq!(code(store.save(Source::Path(&keys.file("missing.key")), "")), "public-key-invalid");
        assert_eq!(code(store.save(Source::Text("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"), "")), "public-key-invalid");
        // A truncated second component fails Core's two-component validation.
        let text = fs::read_to_string(&alice).unwrap();
        assert_eq!(code(store.save(Source::Text(&text[..text.len() - 40]), "")), "public-key-invalid");
        assert_eq!(code(store.save(Source::Text(""), "")), "key-limit");
        for id in ["", "../escape", "ABCDEF", &"a".repeat(63), &"g".repeat(64)] {
            assert_eq!(code(store.update_note(id, "")), "invalid-request");
            assert_eq!(code(store.delete(id)), "invalid-request");
            assert_eq!(code(store.stage(id)), "invalid-request");
        }
        assert_eq!(record_names(&contacts).len(), 1);
    }

    #[test]
    fn damaged_or_substituted_records_fail_closed_and_can_be_reimported() {
        let _core = core_lock();
        let keys = Directory::new();
        let contacts = keys.private("contacts");
        let store = store(&contacts);
        let (alice, _, alice_fingerprint) = keypair(&keys, "alice", "public-test-alice");
        let (bob, _, _) = keypair(&keys, "bob", "public-test-bob");
        let saved = store.save(Source::Path(&alice), "Alice").unwrap();
        let bob_record = store.read(&store.save(Source::Path(&bob), "Bob").unwrap().id).unwrap();
        let (mut staged, fingerprint) = store.stage(&saved.id).unwrap();
        assert_eq!(fingerprint, alice_fingerprint);
        assert_eq!(fs::read_to_string(staged.path().unwrap().to_str().unwrap()).unwrap(), store.read(&saved.id).unwrap().public_key);
        assert!(staged.close());

        // Bob's valid key under Alice's identity is detected by Core's fingerprint.
        let original = fs::read_to_string(store.file(&saved.id)).unwrap();
        let mut substituted: serde_json::Value = serde_json::from_str(&original).unwrap();
        substituted["publicKey"] = bob_record.public_key.clone().into();
        rewrite(&store, &saved.id, &substituted.to_string());
        assert_eq!(code(store.stage(&saved.id)), "contact-mismatch");
        // The listing does not run Core, so the substituted entry is still shown;
        // an explicit re-import of the real key must replace it, not report a duplicate.
        assert_eq!(store.list().unwrap().contacts.len(), 2);
        let repaired = store.save(Source::Path(&alice), "Alice repaired").unwrap();
        assert_eq!((repaired.id.as_str(), repaired.note.as_str()), (saved.id.as_str(), "Alice repaired"));
        assert_eq!(store.stage(&saved.id).unwrap().1, alice_fingerprint);
        let duplicate = store.save(Source::Path(&alice), "ignored").err().unwrap();
        assert_eq!((duplicate.code, duplicate.contact.as_deref()), ("contact-exists", Some(saved.id.as_str())));

        let mut renamed: serde_json::Value = serde_json::from_str(&original).unwrap();
        renamed["fingerprint"] = bob_record.fingerprint.clone().into();
        let mut unknown: serde_json::Value = serde_json::from_str(&original).unwrap();
        unknown["fallback"] = "default.key".into();
        let mut version: serde_json::Value = serde_json::from_str(&original).unwrap();
        version["version"] = 2.into();
        let mut control: serde_json::Value = serde_json::from_str(&original).unwrap();
        control["name"] = "bad\u{1b}name".into();
        for damaged in [renamed.to_string(), unknown.to_string(), version.to_string(), control.to_string(),
                        "{".into(), original.replace("BEGIN PUBLIC KEY", "BEGIN PRIVATE KEY")] {
            rewrite(&store, &saved.id, &damaged);
            assert_eq!(code(store.stage(&saved.id)), "contact-invalid");
            assert_eq!(code(store.update_note(&saved.id, "x")), "contact-invalid");
            let listed = store.list().unwrap();
            assert_eq!((listed.contacts.len(), listed.unreadable), (1, 1));
        }

        // An explicit re-import of the same key repairs the damaged record.
        let repaired = store.save(Source::Path(&alice), "Alice again").unwrap();
        assert_eq!((repaired.id.as_str(), repaired.note.as_str()), (saved.id.as_str(), "Alice again"));
        assert!(store.stage(&saved.id).is_ok());
        assert_eq!(store.list().unwrap().unreadable, 0);

        // Damaged records can still be deleted; foreign names are ignored.
        rewrite(&store, &saved.id, "{");
        fs::write(contacts.file("notes.txt"), "ignored").unwrap();
        store.delete(&saved.id).unwrap();
        let listed = store.list().unwrap();
        assert_eq!((listed.contacts.len(), listed.unreadable), (1, 0));
    }

    #[cfg(unix)]
    #[test]
    fn posix_records_reject_links_and_unsafe_permissions() {
        use std::os::unix::fs::{symlink, MetadataExt, PermissionsExt};
        let _core = core_lock();
        let keys = Directory::new();
        let contacts = keys.private("contacts");
        let store = store(&contacts);
        let (alice, _, _) = keypair(&keys, "alice", "public-test-alice");
        let saved = store.save(Source::Path(&alice), "Alice").unwrap();
        let record = store.file(&saved.id);
        assert_eq!(fs::metadata(&record).unwrap().mode() & 0o777, 0o600);
        assert_eq!(fs::metadata(&contacts.0).unwrap().mode() & 0o777, 0o700);

        fs::set_permissions(&record, fs::Permissions::from_mode(0o644)).unwrap();
        assert_eq!(code(store.stage(&saved.id)), "contact-invalid");
        fs::set_permissions(&record, fs::Permissions::from_mode(0o600)).unwrap();
        assert!(store.stage(&saved.id).is_ok());

        let outside = keys.file("outside.json");
        fs::rename(&record, &outside).unwrap();
        symlink(&outside, &record).unwrap();
        assert_eq!(code(store.stage(&saved.id)), "contact-invalid");
        // Deleting removes the link itself and never follows it.
        store.delete(&saved.id).unwrap();
        assert!(fs::metadata(&outside).is_ok());
        assert_eq!(code(store.stage(&saved.id)), "contact-missing");

        fs::rename(&outside, &record).unwrap();
        assert!(store.stage(&saved.id).is_ok());
        // SELinux may forbid hard links on some systems; only test when allowed.
        if fs::hard_link(&record, keys.file("record-link")).is_ok() {
            assert_eq!(code(store.stage(&saved.id)), "contact-invalid");
            fs::remove_file(keys.file("record-link")).unwrap();
        }

        // Removal re-checks the directory itself, independently of Store::open().
        fs::set_permissions(&contacts.0, fs::Permissions::from_mode(0o755)).unwrap();
        assert_eq!(code(store.delete(&saved.id)), "contact-storage");
        fs::set_permissions(&contacts.0, fs::Permissions::from_mode(0o700)).unwrap();
        store.delete(&saved.id).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn posix_contacts_directory_follows_language_preference_location() {
        use std::os::unix::fs::PermissionsExt;
        let _core = core_lock();
        let home = Directory::new();
        struct Environment(Vec<(&'static str,Option<std::ffi::OsString>)>);
        impl Drop for Environment {fn drop(&mut self){for (name,value) in &self.0 {match value {Some(value)=>std::env::set_var(name,value),None=>std::env::remove_var(name)}}}}
        let environment=Environment(["XDG_CONFIG_HOME","HOME"].iter().map(|&name|(name,std::env::var_os(name))).collect());
        std::env::set_var("HOME", &home.0);
        std::env::set_var("XDG_CONFIG_HOME", "relative/ignored");
        let opened = Store::open().map(|store| store.directory);
        let xdg = home.0.join("xdg");
        std::env::set_var("XDG_CONFIG_HOME", &xdg);
        let configured = Store::open().map(|store| store.directory);
        fs::set_permissions(xdg.join("nekokem/contacts"), fs::Permissions::from_mode(0o755)).unwrap();
        let unsafe_contacts = Store::open().map(|store| store.directory);
        fs::set_permissions(xdg.join("nekokem/contacts"), fs::Permissions::from_mode(0o700)).unwrap();
        fs::set_permissions(xdg.join("nekokem"), fs::Permissions::from_mode(0o755)).unwrap();
        let unsafe_parent = Store::open().map(|store| store.directory);
        drop(environment);
        assert_eq!(opened.unwrap(), home.file(".config/nekokem/contacts"));
        assert_eq!(fs::metadata(home.0.join(".config/nekokem")).unwrap().permissions().mode() & 0o777, 0o700);
        assert_eq!(configured.unwrap(), format!("{}/nekokem/contacts", xdg.display()));
        assert_eq!(fs::metadata(xdg.join("nekokem/contacts")).unwrap().permissions().mode() & 0o777, 0o700);
        assert_eq!(code(unsafe_contacts), "contact-storage");
        assert_eq!(code(unsafe_parent), "contact-storage");
    }

    #[cfg(windows)]
    #[test]
    fn windows_contacts_directory_is_private_under_local_app_data() {
        let _core = core_lock();
        // CI uses a disposable account; remove only the directories this test created.
        let base = PathBuf::from(std::env::var_os("LOCALAPPDATA").unwrap()).join("NekoKEM");
        let expected = base.join("contacts");
        let existed = (base.exists(), expected.exists());
        // Store::open() succeeds only after Core verifies both owner-only directories.
        let opened = Store::open().map(|store| store.directory);
        let listed = opened.as_ref().ok().map(|directory| Store { directory: directory.clone() }.list().map(|list| list.unreadable));
        if !existed.1 { let _ = fs::remove_dir(&expected); }
        if !existed.0 { let _ = fs::remove_dir(&base); }
        assert_eq!(std::path::Path::new(&opened.unwrap()), expected.as_path());
        assert_eq!(listed.unwrap().unwrap(), 0);
    }
}
