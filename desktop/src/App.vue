<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import * as bridge from './bridge';
import AppIcon from './components/AppIcon.vue';
import { languageNames, translate, errorCode, type Language, type Message } from './i18n';

type View = 'keygen' | 'encrypt' | 'decrypt' | 'fingerprint' | 'contacts' | 'settings';
type KeySource = 'path' | 'paste' | 'contact';
// The key page generates identity keys, upgrades a 4.x key pair or exports
// the two-component public key that NekoKEM 4.x and earlier read.
type KeyMode = 'generate' | 'upgrade' | 'export';
// Settings are per-device conveniences. Missing or blocked storage means the
// safe defaults: sign every file and accept unsigned files with a notice.
const SETTINGS_KEY = 'nekokem.settings';
function loadSettings(): { unsigned: boolean; requireSignature: boolean; signKeyPath: string } {
  try {
    const value = JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}');
    return { unsigned: value.unsigned === true, requireSignature: value.requireSignature === true, signKeyPath: typeof value.signKeyPath === 'string' ? value.signKeyPath : '' };
  } catch { return { unsigned: false, requireSignature: false, signKeyPath: '' }; }
}
const operation = ref<View>('encrypt');
const language = ref<Language>('en');
const selectedLanguage = ref('system');
const version = ref('5.0.0');
const busy = ref(false), loaded = ref(false), cancelling = ref(false), jobId = ref('');
const cancelPending = ref(false);
const result = ref<bridge.Outcome | null>(null);
const successMessage = ref<Message>('success');
const failure = ref<Message | null>(null);
// Contact list actions report beside the list; everything else beside the form.
const feedbackArea = ref<'form' | 'list'>('form');
const progress = ref<bridge.Progress | null>(null);
// Saved-contact recipients, in selection order: shared by the Contacts and Encrypt pages.
const form = reactive({ input: '', output: '', keyPath: '', publicPath: '', privatePath: '', password: '', confirmation: '', keyText: '', source: 'path' as KeySource, contacts: [] as string[], replace: false, overwrite: false });
const contactForm = reactive({ keyPath: '', keyText: '', paste: false, note: '' });
const settings = reactive(loadSettings());
const signKeyPath = ref(settings.signKeyPath);
const keyMode = ref<KeyMode>('generate');
// Turning off signing needs an explicit, red-marked confirmation.
const confirmingUnsigned = ref(false);
const unsignedCancel = ref<HTMLButtonElement | null>(null);
function storeSettings() {
  settings.signKeyPath = signKeyPath.value;
  try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings)); } catch { /* the defaults apply next time */ }
}
watch(() => [settings.unsigned, settings.requireSignature], storeSettings);
async function toggleUnsigned(event: Event) {
  const box = event.target as HTMLInputElement;
  if (!box.checked) { settings.unsigned = false; confirmingUnsigned.value = false; return; }
  // The box stays off until the warning is confirmed.
  box.checked = false;
  confirmingUnsigned.value = true;
  await nextTick();
  unsignedCancel.value?.focus();
}
function confirmUnsigned(enable: boolean) {
  settings.unsigned = enable;
  confirmingUnsigned.value = false;
}
const signing = computed(() => operation.value === 'encrypt' && !settings.unsigned);
// The last decryption's signature report and the save-signer action beside it.
const signerSaved = ref(false);
// Replacing keys is offered only after Core reported existing keys at the
// chosen paths, must be ticked for each run and is withdrawn when they change.
const replaceOffered = ref(false);
function withdrawReplace() {
  replaceOffered.value = false;
  form.replace = false;
}
watch(() => [form.publicPath, form.privatePath], withdrawReplace);
const overwriteOffered = ref(false);
function withdrawOverwrite() { overwriteOffered.value = false; form.overwrite = false; }
watch(() => form.output, withdrawOverwrite);
type Field = Exclude<keyof typeof form, 'source'> | 'contactKeyPath' | 'contactKeyText' | 'note' | 'noteDraft' | 'signKeyPath';
type PathField = 'input' | 'output' | 'keyPath' | 'publicPath' | 'privatePath' | 'contactKeyPath' | 'signKeyPath';
const invalidField = ref<Field | null>(null);
const choosing = ref<PathField | null>(null);
const operationForm = ref<HTMLFormElement | null>(null);
const navigation = ref<HTMLElement | null>(null);
const contactList = ref<HTMLElement | null>(null);
const contacts = ref<bridge.Contact[]>([]);
const unreadable = ref(0);
const contactsFailure = ref<Message | null>(null);
const contactsLoaded = ref(false);
const contactBusy = ref(false);
const editing = ref<string | null>(null), noteDraft = ref('');
const confirming = ref<string | null>(null), highlighted = ref<string | null>(null);
const recipients = ref<bridge.Contact[]>([]);
// The saved contact that made the last encryption fail, named beside the error.
const failedContact = ref<bridge.Contact | null>(null);
const locked = computed(() => busy.value || contactBusy.value || choosing.value !== null);
const t = (key: Message) => translate(key, language.value);
const tabs: View[] = ['keygen', 'encrypt', 'decrypt', 'fingerprint', 'contacts', 'settings'];
const description = computed(() => t((operation.value === 'keygen' && keyMode.value !== 'generate' ? keyMode.value + 'Help' : operation.value + 'Help') as Message));
const kind = computed<bridge.Operation>(() => operation.value === 'keygen' ? (keyMode.value === 'upgrade' ? 'upgrade' : keyMode.value === 'export' ? 'export-legacy' : 'keygen') : operation.value as bridge.Operation);
const percent = computed(() => progress.value && progress.value.total > 0 ? Math.max(0, Math.min(100, Math.floor(progress.value.processed / progress.value.total * 100))) : null);
const passwordRequired = computed(() => (operation.value === 'keygen' && keyMode.value !== 'export') || operation.value === 'decrypt' || signing.value);
const selectedContacts = computed(() => form.contacts.flatMap(id => contacts.value.filter(contact => contact.id === id)));
const maxRecipients = bridge.MAX_RECIPIENTS;
const atRecipientLimit = computed(() => form.contacts.length >= maxRecipients);
const selectedText = computed(() => t('selectedCount').replace('{count}', String(form.contacts.length)));
// Signed files are NKEM v5. Unsigned output keeps v3 for one recipient and v4 for several.
const formatLabel = computed(() => operation.value === 'decrypt' ? 'NKEM v3 · v4 · v5'
  : operation.value === 'encrypt' && settings.unsigned ? (form.source === 'contact' && form.contacts.length > 1 ? 'NKEM v4' : 'NKEM v3') + ' · ' + t('unsignedMark')
  : operation.value === 'keygen' && keyMode.value === 'export' ? 'NekoKEM 4.x' : 'NKEM v5');
const algorithms = computed(() => signing.value || (operation.value === 'keygen' && keyMode.value !== 'export') ? 'X448 + ML-KEM-1024 · Ed448 + ML-DSA-87' : 'X448 + ML-KEM-1024');
const signerContact = computed(() => contacts.value.find(contact => contact.id === result.value?.signature?.contact) ?? null);
const status = computed(() => !loaded.value ? t('loading') : choosing.value ? t('choosing') : cancelling.value ? t('cancelling') : busy.value || contactBusy.value ? t('working') : result.value ? t('success') : t('ready'));
const unreadableText = computed(() => t('contactUnreadable').replace('{count}', String(unreadable.value)));
const contactFailures: Message[] = ['contact-missing', 'contact-invalid', 'contact-mismatch', 'contact-storage'];
let unlisten: (() => void) | undefined;

const label = (contact: bridge.Contact) => contact.note || contact.name || t('pastedKey');
const characters = (value: string) => [...value].length;
function clearField(name: string) {
  // Clear the live DOM before invoking native code or removing a keyed form.
  // Entry animations never retain an outgoing form or a secret-bearing node.
  const element = operationForm.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>(`[name="${name}"]`);
  if (element) element.value = '';
}
function clearSecretField(field: 'password' | 'confirmation' | 'keyText') {
  form[field] = '';
  clearField(field);
}
// Pasted contact text should be public, but is scrubbed like key text in case
// a private key was pasted by mistake.
function clearContactText() {
  contactForm.keyText = '';
  clearField('contactKeyText');
}
function clearSecrets() {
  clearSecretField('password');
  clearSecretField('confirmation');
  clearSecretField('keyText');
  clearContactText();
}
function resetFeedback() {
  result.value = null;
  failure.value = null;
  invalidField.value = null;
  progress.value = null;
  recipients.value = [];
  failedContact.value = null;
  signerSaved.value = false;
  feedbackArea.value = 'form';
  successMessage.value = 'success';
}
function switchTab(value: View) {
  if (locked.value || !loaded.value || value === operation.value) return;
  clearSecrets();
  form.output = '';
  resetFeedback();
  editing.value = null;
  confirming.value = null;
  highlighted.value = null;
  withdrawReplace();
  withdrawOverwrite();
  confirmingUnsigned.value = false;
  if ((value === 'decrypt' || value === 'fingerprint') && form.source === 'contact') form.source = 'path';
  operation.value = value;
  if (value === 'encrypt' || value === 'decrypt' || value === 'contacts') void refreshContacts();
}
function navigate(event: KeyboardEvent, index: number) {
  if (locked.value || !loaded.value) return;
  const target = event.key === 'ArrowDown' ? (index + 1) % tabs.length
    : event.key === 'ArrowUp' ? (index + tabs.length - 1) % tabs.length
    : event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : null;
  if (target === null) return;
  event.preventDefault();
  switchTab(tabs[target]!);
  navigation.value?.querySelectorAll<HTMLButtonElement>('.operation-tab')[target]?.focus();
}
function setKeySource(source: KeySource) {
  if (locked.value || !loaded.value || source === form.source) return;
  clearSecretField('keyText');
  form.source = source;
  resetFeedback();
}
function setKeyMode(mode: KeyMode) {
  if (locked.value || !loaded.value || mode === keyMode.value) return;
  clearSecretField('password');
  clearSecretField('confirmation');
  form.output = '';
  keyMode.value = mode;
  withdrawReplace();
  withdrawOverwrite();
  resetFeedback();
}
function setContactSource(paste: boolean) {
  if (locked.value || !loaded.value || paste === contactForm.paste) return;
  clearContactText();
  contactForm.paste = paste;
  resetFeedback();
}
function fieldAttrs(field: Field) {
  const invalid = invalidField.value === field;
  const error = field === 'noteDraft' ? 'contact-list-error' : 'operation-error';
  const describedBy = [field === 'password' || field === 'confirmation' ? 'password-hint' : '', field === 'note' ? 'note-hint' : '', invalid ? error : ''].filter(Boolean).join(' ');
  return { 'aria-invalid': invalid || undefined, 'aria-describedby': describedBy || undefined };
}
function editField(event: Event) {
  const field = (event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement).name;
  if (invalidField.value === field) {
    invalidField.value = null;
    failure.value = null;
  }
}
async function browse(field: PathField) {
  if (locked.value || !loaded.value) return;
  choosing.value = field;
  try {
    let value: string | null;
    // Upgrading changes existing key files, so it opens them.
    if (['output', 'publicPath', 'privatePath'].includes(field) && !(operation.value === 'keygen' && keyMode.value === 'upgrade')) {
      const filename = form.input.split(/[\\/]/).pop() || 'file';
      const defaultPath = field === 'publicPath' ? 'public.key' : field === 'privatePath' ? 'private.key.enc' : operation.value === 'keygen' ? 'legacy-public.key' : operation.value === 'encrypt' ? filename + '.nkem' : filename.replace(/\.nkem$/, '');
      value = await bridge.chooseSave(defaultPath);
    } else value = await bridge.chooseOpen();
    if (value) {
      if (field === 'contactKeyPath') contactForm.keyPath = value;
      else if (field === 'signKeyPath') signKeyPath.value = value;
      else form[field] = value;
      if (invalidField.value === field) { invalidField.value = null; failure.value = null; }
    }
  } catch (error) { failure.value = errorCode(error); }
  finally { choosing.value = null; }
}
async function changeLanguage() {
  try {
    language.value = await bridge.setLanguage(selectedLanguage.value) as Language;
    document.documentElement.lang = language.value;
  } catch (error) { failure.value = errorCode(error); }
}
function invalid(message: Message, field: Field): Message {
  invalidField.value = field;
  return message;
}
function invalidNote(note: string) {
  // Same rule as the native store: 512 code points, no control characters. Inputs
  // carry no HTML maxlength, which would count UTF-16 units and cut emoji at 256.
  return characters(note.trim()) > 512 || /[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/.test(note);
}
function validate(): Message | null {
  invalidField.value = null;
  const bytes = (value: string) => new TextEncoder().encode(value).length;
  if (operation.value === 'contacts') {
    if (contactForm.paste) {
      if (!contactForm.keyText || bytes(contactForm.keyText) > 1048576) return invalid('key-limit', 'contactKeyText');
    } else if (!contactForm.keyPath) return invalid('invalid-path', 'contactKeyPath');
    if (invalidNote(contactForm.note)) return invalid('note-limit', 'note');
    return null;
  }
  if (bytes(form.password) > 1024) return invalid('password-limit', 'password');
  if (bytes(form.confirmation) > 1024) return invalid('password-limit', 'confirmation');
  if (operation.value === 'keygen' && keyMode.value === 'export') {
    if (!form.keyPath) return invalid('invalid-path', 'keyPath');
    if (!form.output) return invalid('invalid-path', 'output');
  } else if (operation.value === 'keygen') {
    if (!form.publicPath) return invalid('invalid-path', 'publicPath');
    if (!form.privatePath) return invalid('invalid-path', 'privatePath');
    if (!form.password) return invalid('password-empty', 'password');
    if (keyMode.value === 'generate' && form.password !== form.confirmation) return invalid('password-mismatch', 'confirmation');
  } else {
    if (form.source === 'paste') {
      if (!form.keyText || bytes(form.keyText) > 1048576) return invalid('key-limit', 'keyText');
    } else if (form.source === 'contact') {
      // Explicit, currently listed contacts are required; never pick one implicitly.
      if (!form.contacts.length) return invalid('contact-required', 'contacts');
      if (form.contacts.length > maxRecipients) return invalid('recipient-limit', 'contacts');
      if (selectedContacts.value.length !== form.contacts.length) return invalid('contact-missing', 'contacts');
    } else if (!form.keyPath) return invalid('invalid-path', 'keyPath');
    if (operation.value !== 'fingerprint') {
      if (!form.input) return invalid('invalid-path', 'input');
      if (!form.output) return invalid('invalid-path', 'output');
    }
    // Signing is never skipped silently: it needs the sender's key and password.
    if (signing.value) {
      if (!signKeyPath.value) return invalid('sign-key-missing', 'signKeyPath');
      if (!form.password) return invalid('password-empty', 'password');
    }
  }
  return null;
}
async function focusInvalid() {
  await nextTick();
  const root = invalidField.value === 'noteDraft' ? contactList.value : operationForm.value;
  root?.querySelector<HTMLElement>(`[name="${invalidField.value}"]`)?.focus();
}
async function refreshContacts() {
  try {
    const list = await bridge.listContacts();
    contacts.value = list.contacts;
    unreadable.value = list.unreadable;
    contactsFailure.value = null;
  } catch (error) {
    contacts.value = [];
    unreadable.value = 0;
    contactsFailure.value = errorCode(error);
  } finally { contactsLoaded.value = true; }
  // A selected contact that disappeared is deselected and reported, never replaced.
  const listed = form.contacts.filter(id => contacts.value.some(contact => contact.id === id));
  if (listed.length !== form.contacts.length && !busy.value) {
    form.contacts = listed;
    if (operation.value === 'encrypt' && form.source === 'contact' && !failure.value) failure.value = invalid('contact-missing', 'contacts');
  }
}
async function start() {
  if (locked.value || !loaded.value) return;
  if (operation.value === 'contacts') return saveContact();
  if (operation.value === 'settings') return;
  resetFeedback();
  failure.value = validate();
  if (failure.value) return focusInvalid();
  const chosen = form.source === 'contact' && operation.value === 'encrypt' ? [...selectedContacts.value] : [];
  jobId.value = crypto.randomUUID();
  busy.value = true;
  cancelling.value = false;
  const exporting = kind.value === 'export-legacy', keys = kind.value === 'keygen' || kind.value === 'upgrade';
  const request: bridge.Request = {
    id: jobId.value, kind: kind.value, input: keys || exporting ? '' : form.input, output: keys ? '' : form.output,
    keyPath: exporting || (!keys && form.source === 'path') ? form.keyPath : '', publicPath: keys ? form.publicPath : '', privatePath: keys ? form.privatePath : '',
    password: passwordRequired.value ? form.password : '', confirmation: kind.value === 'keygen' ? form.confirmation : '',
    keyText: !keys && !exporting && form.source === 'paste' ? form.keyText : '',
    paste: !keys && !exporting && form.source === 'paste', contacts: chosen.map(contact => contact.id),
    replace: kind.value === 'keygen' && replaceOffered.value && form.replace,
    overwrite: ['encrypt', 'decrypt', 'export-legacy'].includes(kind.value) && overwriteOffered.value && form.overwrite,
    signKeyPath: signing.value ? signKeyPath.value : '', unsigned: operation.value === 'encrypt' && settings.unsigned,
    requireSignature: operation.value === 'decrypt' && settings.requireSignature,
  };
  if (signing.value) storeSettings();
  withdrawReplace();
  withdrawOverwrite();
  // The native layer owns zeroizing secrets. Do not retain them in the form.
  clearSecrets();
  let unavailable = false;
  try {
    result.value = await bridge.runOperation(request);
    recipients.value = chosen;
    successMessage.value = request.kind === 'encrypt' ? (result.value.format === 5 ? 'encryptedSigned' : 'encryptedUnsigned')
      : request.kind === 'upgrade' ? 'keyUpgraded' : request.kind === 'export-legacy' ? 'legacyExported' : 'success';
  } catch (error) {
    failure.value = errorCode(error);
    if (request.kind === 'keygen' && failure.value === 'key-exists') replaceOffered.value = true;
    if (['encrypt', 'decrypt', 'export-legacy'].includes(request.kind) && failure.value === 'output-exists') overwriteOffered.value = true;
    // Nothing was encrypted. Deselect the contact that could not be used and
    // require the user to start again with the recipients that remain.
    unavailable = chosen.length > 0 && contactFailures.includes(failure.value);
    if (unavailable) {
      const id = (error as { contact?: string })?.contact;
      failedContact.value = chosen.find(contact => contact.id === id) ?? null;
      form.contacts = failedContact.value ? form.contacts.filter(selected => selected !== id) : [];
      invalidField.value = 'contacts';
    }
  } finally {
    request.password = ''; request.confirmation = ''; request.keyText = '';
    busy.value = false; cancelling.value = false;
  }
  if (unavailable) await refreshContacts();
}
async function saveContact() {
  resetFeedback();
  highlighted.value = null;
  failure.value = validate();
  if (failure.value) return focusInvalid();
  const request: bridge.ContactImport = { keyPath: contactForm.paste ? '' : contactForm.keyPath, keyText: contactForm.paste ? contactForm.keyText : '', paste: contactForm.paste, note: contactForm.note };
  clearContactText();
  contactBusy.value = true;
  try {
    const contact = await bridge.saveContact(request);
    contactForm.keyPath = '';
    contactForm.note = '';
    successMessage.value = 'contactSaved';
    result.value = { output: null, fingerprint: contact.fingerprint };
    highlighted.value = contact.id;
  } catch (error) {
    failure.value = errorCode(error);
    highlighted.value = (error as { contact?: string })?.contact ?? null;
    if (failure.value === 'note-limit') invalidField.value = 'note';
    else if (['public-key-invalid', 'invalid-path', 'key-limit', 'key-line-limit'].includes(failure.value)) invalidField.value = contactForm.paste ? 'contactKeyText' : 'contactKeyPath';
  } finally {
    request.keyText = '';
    contactBusy.value = false;
  }
  await refreshContacts();
  await nextTick();
  contactList.value?.querySelector<HTMLElement>('.contact.highlighted')?.scrollIntoView?.({ block: 'nearest' });
}
// Saves the public key Core extracted from a valid signature. The user is
// asked to compare the fingerprint first; later files then show as trusted.
async function saveSigner() {
  const key = result.value?.signature?.signerKey;
  if (locked.value || !key) return;
  contactBusy.value = true;
  failure.value = null;
  try {
    const contact = await bridge.saveContact({ keyPath: '', keyText: key, paste: true, note: '' });
    signerSaved.value = true;
    highlighted.value = contact.id;
  } catch (error) {
    failure.value = errorCode(error);
    if (failure.value === 'contact-exists') { failure.value = null; signerSaved.value = true; }
  } finally { contactBusy.value = false; }
  await refreshContacts();
}
async function startEdit(contact: bridge.Contact) {
  if (locked.value) return;
  resetFeedback();
  confirming.value = null;
  editing.value = contact.id;
  noteDraft.value = contact.note;
  await nextTick();
  contactList.value?.querySelector<HTMLInputElement>('[name="noteDraft"]')?.focus();
}
async function saveNote(contact: bridge.Contact) {
  if (locked.value) return;
  resetFeedback();
  feedbackArea.value = 'list';
  if (invalidNote(noteDraft.value)) {
    failure.value = invalid('note-limit', 'noteDraft');
    return focusInvalid();
  }
  contactBusy.value = true;
  try {
    await bridge.updateContactNote(contact.id, noteDraft.value);
    editing.value = null;
    successMessage.value = 'noteSaved';
    result.value = { output: null, fingerprint: null };
    highlighted.value = contact.id;
  } catch (error) { failure.value = errorCode(error); }
  finally { contactBusy.value = false; }
  await refreshContacts();
}
function editNoteDraft() {
  if (invalidField.value === 'noteDraft') { invalidField.value = null; failure.value = null; }
}
function askDelete(contact: bridge.Contact) {
  if (locked.value) return;
  resetFeedback();
  editing.value = null;
  confirming.value = contact.id;
}
async function removeContact(contact: bridge.Contact) {
  if (locked.value) return;
  resetFeedback();
  feedbackArea.value = 'list';
  contactBusy.value = true;
  try {
    await bridge.deleteContact(contact.id);
    successMessage.value = 'contactDeleted';
    result.value = { output: null, fingerprint: null };
  } catch (error) { failure.value = errorCode(error); }
  finally {
    confirming.value = null;
    contactBusy.value = false;
  }
  await refreshContacts();
}
function useContact(contact: bridge.Contact) {
  if (locked.value) return;
  form.contacts = [contact.id];
  form.source = 'contact';
  switchTab('encrypt');
}
function toggleContact(contact: bridge.Contact) {
  if (locked.value) return;
  if (form.contacts.includes(contact.id)) form.contacts = form.contacts.filter(id => id !== contact.id);
  else if (!atRecipientLimit.value) form.contacts = [...form.contacts, contact.id];
  if (invalidField.value === 'contacts') { invalidField.value = null; failure.value = null; }
}
function encryptSelected() {
  if (locked.value || !form.contacts.length) return;
  form.source = 'contact';
  switchTab('encrypt');
}
function clearSelection() {
  if (locked.value) return;
  form.contacts = [];
}
async function cancel() {
  if (!busy.value || cancelling.value || cancelPending.value) return;
  const id = jobId.value;
  cancelPending.value = true;
  try { if (await bridge.cancelOperation(id) && busy.value && jobId.value === id) cancelling.value = true; }
  catch (error) { failure.value = errorCode(error); }
  finally { cancelPending.value = false; }
}
async function exit() {
  if (locked.value) return;
  try { await bridge.closeApp(); }
  catch (error) { failure.value = errorCode(error); }
}
onMounted(async () => {
  try {
    unlisten = await bridge.onProgress(value => { if (busy.value && value.id === jobId.value) progress.value = value; });
    const settings = await bridge.getSettings();
    language.value = settings.language as Language;
    selectedLanguage.value = settings.selection;
    version.value = settings.version;
    loaded.value = true;
    document.documentElement.lang = language.value;
  } catch (error) { failure.value = errorCode(error); return; }
  await refreshContacts();
});
onBeforeUnmount(clearSecrets);
onUnmounted(() => unlisten?.());
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand"><img src="/icon.png" alt=""><span>NekoKEM<small>v{{ version }}</small></span></div>
      <nav ref="navigation" :aria-label="t('navigation')">
        <button v-for="(tab, index) in tabs" :key="tab" class="operation-tab" :class="{ active: operation === tab }" :aria-current="operation === tab ? 'page' : undefined" :disabled="locked || !loaded" @click="switchTab(tab)" @keydown="navigate($event, index)">
          <AppIcon :name="tab" /><span class="nav-label">{{ t(tab) }}</span><span class="nav-number" aria-hidden="true">0{{ index + 1 }}</span>
        </button>
        <button class="exit-button" :disabled="locked || !loaded" @click="exit"><AppIcon name="exit" /><span class="nav-label">{{ t('exit') }}</span><span class="nav-number" aria-hidden="true">07</span></button>
      </nav>
      <div class="sidebar-bottom"><span class="local-dot" aria-hidden="true"></span><strong>{{ t('local') }}</strong><p>{{ t('localHint') }}</p></div>
    </aside>
    <main>
      <header><span class="eyebrow">NEKOKEM · DESKTOP</span><label class="language">{{ t('language') }}<select v-model="selectedLanguage" :disabled="locked || !loaded" @change="changeLanguage"><option v-for="(name, tag) in languageNames" :key="tag" :value="tag">{{ tag === 'system' ? t('system') : name }}</option></select></label></header>
      <section :key="operation" class="page-title enter-page"><div class="title-icon"><AppIcon :name="operation" /></div><div><h1>{{ t(operation) }}</h1><p>{{ description }}</p></div></section>
      <section v-if="operation === 'settings'" class="card settings enter-page" :aria-label="t('settings')">
        <div class="setting">
          <label class="setting-row"><input type="checkbox" name="unsigned" :checked="settings.unsigned" :disabled="confirmingUnsigned" aria-describedby="unsigned-hint" @change="toggleUnsigned"><span><strong>{{ t('unsignedSetting') }}</strong><span id="unsigned-hint" class="hint" :class="{ 'danger-text': settings.unsigned }">{{ settings.unsigned ? t('unsignedWarning') : t('unsignedSettingHint') }}</span></span></label>
          <div v-if="confirmingUnsigned" class="unsigned-confirm enter-feedback" role="alertdialog" aria-modal="false" aria-labelledby="unsigned-title" aria-describedby="unsigned-text" @keydown.esc.prevent="confirmUnsigned(false)">
            <strong id="unsigned-title" class="danger-text">{{ t('unsignedConfirmTitle') }}</strong>
            <p id="unsigned-text" class="danger-text">{{ t('unsignedConfirmText') }}</p>
            <div class="contact-actions"><button ref="unsignedCancel" type="button" class="secondary unsigned-cancel" @click="confirmUnsigned(false)">{{ t('cancel') }}</button><button type="button" class="danger unsigned-accept" @click="confirmUnsigned(true)">{{ t('unsignedConfirm') }}</button></div>
          </div>
        </div>
        <div class="setting">
          <label class="setting-row"><input v-model="settings.requireSignature" type="checkbox" name="requireSignature" aria-describedby="require-hint"><span><strong>{{ t('requireSignature') }}</strong><span id="require-hint" class="hint">{{ t('requireSignatureHint') }}</span></span></label>
        </div>
      </section>
      <form v-else ref="operationForm" class="card" :class="{ 'is-busy': busy }" novalidate @submit.prevent="start" @input="editField">
        <div class="card-heading"><span class="step-label">{{ t('subtitle') }}</span><span class="status" :class="{ 'is-working': locked, 'is-complete': result }"><AppIcon v-if="result && !locked" name="check" /><i v-else :class="{ running: locked }" aria-hidden="true"></i>{{ status }}</span></div>
        <fieldset :disabled="locked || !loaded">
          <!-- Only animate the new form's entry; never keep an outgoing secret-bearing form. -->
          <div :key="operation" class="form-content enter-form">
            <template v-if="operation === 'keygen'">
              <div class="key-source" role="group" :aria-label="t('keyMode')"><label v-for="mode in (['generate', 'upgrade', 'export'] as const)" :key="mode" :class="{ selected: keyMode === mode }"><input type="radio" name="keyMode" :checked="keyMode === mode" @change="setKeyMode(mode)">{{ t(mode === 'generate' ? 'keygen' : mode === 'upgrade' ? 'upgradeKey' : 'exportLegacy') }}</label></div>
            </template>
            <template v-if="operation === 'keygen' && keyMode === 'export'">
              <label class="field enter-field">{{ t('publicKey') }}<div class="path-input"><input v-model="form.keyPath" v-bind="fieldAttrs('keyPath')" name="keyPath" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('publicKey')}`" @click="browse('keyPath')">{{ choosing === 'keyPath' ? t('choosing') : t('browse') }}</button></div></label>
              <label class="field">{{ t('legacyOutput') }}<div class="path-input"><input v-model="form.output" v-bind="fieldAttrs('output')" name="output" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('legacyOutput')}`" @click="browse('output')">{{ choosing === 'output' ? t('choosing') : t('browse') }}</button></div></label>
            </template>
            <template v-else-if="operation === 'keygen'">
              <label class="field">{{ t(keyMode === 'upgrade' ? 'publicKey' : 'publicPath') }}<div class="path-input"><input v-model="form.publicPath" v-bind="fieldAttrs('publicPath')" name="publicPath" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('publicPath')}`" @click="browse('publicPath')">{{ choosing === 'publicPath' ? t('choosing') : t('browse') }}</button></div></label>
              <label class="field">{{ t(keyMode === 'upgrade' ? 'privateKey' : 'privatePath') }}<div class="path-input"><input v-model="form.privatePath" v-bind="fieldAttrs('privatePath')" name="privatePath" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t(keyMode === 'upgrade' ? 'privateKey' : 'privatePath')}`" @click="browse('privatePath')">{{ choosing === 'privatePath' ? t('choosing') : t('browse') }}</button></div></label>
              <div v-if="replaceOffered" class="replace-option enter-field">
                <label class="recipient-option" :class="{ selected: form.replace }"><input v-model="form.replace" type="checkbox" name="replace" aria-describedby="replace-hint">{{ t('replaceKeys') }}</label>
                <p id="replace-hint" class="hint warning">{{ t('replaceKeysHint') }}</p>
              </div>
            </template>
            <template v-else-if="operation === 'contacts'">
              <div class="key-source" role="group" :aria-label="t('keySource')"><label :class="{ selected: !contactForm.paste }"><input type="radio" name="contactKeySource" :checked="!contactForm.paste" @change="setContactSource(false)">{{ t('path') }}</label><label :class="{ selected: contactForm.paste }"><input type="radio" name="contactKeySource" :checked="contactForm.paste" @change="setContactSource(true)">{{ t('paste') }}</label></div>
              <label v-if="!contactForm.paste" class="field enter-field">{{ t('publicKey') }}<div class="path-input"><input v-model="contactForm.keyPath" v-bind="fieldAttrs('contactKeyPath')" name="contactKeyPath" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('publicKey')}`" @click="browse('contactKeyPath')">{{ choosing === 'contactKeyPath' ? t('choosing') : t('browse') }}</button></div></label>
              <label v-else class="field enter-field">{{ t('paste') }}<textarea v-model="contactForm.keyText" v-bind="fieldAttrs('contactKeyText')" name="contactKeyText" rows="4" spellcheck="false" autocomplete="off" :placeholder="t('pastePublicHint')"></textarea></label>
              <label class="field">{{ t('note') }}<input v-model="contactForm.note" v-bind="fieldAttrs('note')" name="note" spellcheck="false" autocomplete="off"></label>
              <p id="note-hint" class="hint">{{ t('noteHint') }}</p>
            </template>
            <template v-else>
              <div class="key-source" role="group" :aria-label="t('keySource')"><label :class="{ selected: form.source === 'path' }"><input type="radio" name="keySource" :checked="form.source === 'path'" @change="setKeySource('path')">{{ t('path') }}</label><label :class="{ selected: form.source === 'paste' }"><input type="radio" name="keySource" :checked="form.source === 'paste'" @change="setKeySource('paste')">{{ t('paste') }}</label><label v-if="operation === 'encrypt'" :class="{ selected: form.source === 'contact' }"><input type="radio" name="keySource" :checked="form.source === 'contact'" @change="setKeySource('contact')">{{ t('savedContact') }}</label></div>
              <label v-if="form.source === 'path'" class="field enter-field">{{ t('key') }}<div class="path-input"><input v-model="form.keyPath" v-bind="fieldAttrs('keyPath')" name="keyPath" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('key')}`" @click="browse('keyPath')">{{ choosing === 'keyPath' ? t('choosing') : t('browse') }}</button></div></label>
              <label v-else-if="form.source === 'paste'" class="field enter-field">{{ t('paste') }}<textarea v-model="form.keyText" v-bind="fieldAttrs('keyText')" :class="{ 'key-secret': operation === 'decrypt' }" name="keyText" rows="4" spellcheck="false" autocomplete="off" :placeholder="t('pasteHint')"></textarea></label>
              <div v-else class="contact-choice enter-field">
                <div class="recipient-heading"><span id="recipients-label">{{ t('recipients') }}</span><span class="count">{{ form.contacts.length }} / {{ maxRecipients }}</span></div>
                <div v-if="contacts.length" class="recipient-options" role="group" aria-labelledby="recipients-label" v-bind="fieldAttrs('contacts')">
                  <label v-for="contact in contacts" :key="contact.id" class="recipient-option" :class="{ selected: form.contacts.includes(contact.id) }"><input type="checkbox" name="contacts" :value="contact.id" :checked="form.contacts.includes(contact.id)" :disabled="!form.contacts.includes(contact.id) && atRecipientLimit" @change="toggleContact(contact)"><span class="recipient-label">{{ label(contact) }}</span><code>{{ contact.fingerprint }}</code></label>
                </div>
                <div v-if="selectedContacts.length === 1" class="contact-preview"><span>{{ t('fingerprint') }}</span><code>{{ selectedContacts[0]!.fingerprint }}</code></div>
                <p v-else-if="selectedContacts.length > 1" class="hint multi-hint">{{ t('multiRecipientHint') }}</p>
                <p v-if="atRecipientLimit" class="hint">{{ t('recipient-limit') }}</p>
                <p v-if="contactsFailure" class="hint warning">{{ t(contactsFailure) }}</p>
                <p v-else-if="contactsLoaded && !contacts.length" class="hint">{{ t('noContacts') }}<button type="button" class="link-button" @click="switchTab('contacts')">{{ t('manageContacts') }}</button></p>
              </div>
              <template v-if="operation !== 'fingerprint'">
                <label class="field">{{ t('input') }}<div class="path-input"><input v-model="form.input" v-bind="fieldAttrs('input')" name="input" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('input')}`" @click="browse('input')">{{ choosing === 'input' ? t('choosing') : t('browse') }}</button></div></label>
                <label class="field">{{ t('output') }}<div class="path-input"><input v-model="form.output" v-bind="fieldAttrs('output')" name="output" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('output')}`" @click="browse('output')">{{ choosing === 'output' ? t('choosing') : t('browse') }}</button></div></label>
              </template>
              <label v-if="signing" class="field enter-field">{{ t('signKey') }}<div class="path-input"><input v-model="signKeyPath" v-bind="fieldAttrs('signKeyPath')" name="signKeyPath" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('signKey')}`" @click="browse('signKeyPath')">{{ choosing === 'signKeyPath' ? t('choosing') : t('browse') }}</button></div></label>
              <p v-if="operation === 'encrypt' && settings.unsigned" class="hint danger-text unsigned-warning">{{ t('unsignedWarning') }}<button type="button" class="link-button" @click="switchTab('settings')">{{ t('settings') }}</button></p>
            </template>
            <div v-if="passwordRequired" class="password-fields">
              <label class="field">{{ t('password') }}<input v-model="form.password" v-bind="fieldAttrs('password')" type="password" name="password" maxlength="1024" autocomplete="off" spellcheck="false"></label>
              <label v-if="operation === 'keygen' && keyMode === 'generate'" class="field">{{ t('confirmation') }}<input v-model="form.confirmation" v-bind="fieldAttrs('confirmation')" type="password" name="confirmation" maxlength="1024" autocomplete="off" spellcheck="false"></label>
            </div>
            <p v-if="passwordRequired" id="password-hint" class="hint">{{ t(signing ? 'signPasswordHint' : keyMode === 'upgrade' && operation === 'keygen' ? 'upgradePasswordHint' : 'passwordHint') }}</p>
          </div>
        </fieldset>
        <div v-if="busy" class="progress-block enter-feedback" role="status"><div class="progress-label"><span>{{ cancelling ? t('cancelling') : progress ? t('progress') : t('preparing') }}</span><strong v-if="percent !== null">{{ percent }}%</strong></div><div class="progress-track" role="progressbar" :aria-label="t('progress')" :aria-valuenow="percent ?? undefined" aria-valuemin="0" aria-valuemax="100" :aria-valuetext="percent === null ? t('preparing') : undefined"><div class="progress-fill" :class="{ indeterminate: percent === null }" :style="{ width: `${percent ?? 32}%` }"></div></div></div>
        <template v-if="feedbackArea === 'form'">
          <div v-if="overwriteOffered" class="replace-option enter-field"><label class="recipient-option" :class="{ selected: form.overwrite }"><input v-model="form.overwrite" type="checkbox" name="overwrite" aria-describedby="overwrite-hint">{{ t('overwriteOutput') }}</label><p id="overwrite-hint" class="hint warning">{{ t('overwriteOutputHint') }}</p></div>
          <div v-if="failure" id="operation-error" class="notice enter-feedback" :class="{ neutral: failure === 'cancelled' }" role="alert"><AppIcon :name="failure === 'cancelled' ? 'close' : 'alert'" /><div><span>{{ t(failure) }}</span><p v-if="failedContact" class="result-path failed-contact">{{ t('recipient') }}: {{ label(failedContact) }} · {{ failedContact.fingerprint }}</p></div></div>
          <div v-if="result" class="notice success enter-feedback" role="status"><AppIcon name="check" /><div><strong :class="{ 'danger-text': successMessage === 'encryptedUnsigned' }">{{ t(successMessage) }}</strong><p v-if="result.output" class="result-path">{{ result.output }}</p><p v-if="recipients.length" class="result-path">{{ recipients.length > 1 ? t('recipients') : t('recipient') }}: {{ recipients.map(label).join(', ') }}</p><textarea v-if="result.fingerprint" class="fingerprint" :value="result.fingerprint" readonly rows="3" :aria-label="t('fingerprint')"></textarea>
            <template v-if="result.legacyFingerprint && result.legacyFingerprint !== result.fingerprint"><p class="result-path legacy-label">{{ t('legacyFingerprint') }}</p><textarea class="fingerprint legacy-fingerprint" :value="result.legacyFingerprint" readonly rows="3" :aria-label="t('legacyFingerprint')"></textarea></template>
            <p v-if="result.canVerify === false" class="hint warning legacy-key">{{ t('legacyKeyHint') }}</p></div></div>
          <div v-if="result?.signature" class="notice signature enter-feedback" :class="result.signature.status" role="status"><AppIcon :name="result.signature.status === 'unsigned' ? 'alert' : 'shield'" /><div>
            <strong>{{ result.signature.status === 'trusted' ? t('signatureTrusted') : result.signature.status === 'untrusted' ? t('signatureUntrusted') : t('signatureNone') }}</strong>
            <p v-if="result.signature.status === 'trusted'" class="result-path">{{ t('signer') }}: {{ signerContact ? label(signerContact) : t('savedContact') }}</p>
            <p v-else-if="result.signature.status === 'untrusted'" class="result-path">{{ t('signatureUntrustedHint') }}</p>
            <p v-else class="result-path">{{ t('signatureNoneHint') }}</p>
            <textarea v-if="result.signature.fingerprint" class="fingerprint signer-fingerprint" :value="result.signature.fingerprint" readonly rows="3" :aria-label="t('signerFingerprint')"></textarea>
            <p v-if="signerSaved" class="result-path signer-saved">{{ t('signerSaved') }}</p>
            <button v-else-if="result.signature.signerKey" type="button" class="secondary save-signer" :disabled="locked" @click="saveSigner">{{ t('saveSigner') }}</button>
          </div></div>
        </template>
        <footer class="actions"><span class="format"><span class="format-dot" aria-hidden="true"></span>{{ algorithms }}<span>{{ formatLabel }}</span></span><button v-if="busy && ['encrypt', 'decrypt'].includes(operation)" class="secondary" type="button" :disabled="cancelling || cancelPending" @click="cancel">{{ t('cancel') }}</button><button class="primary" type="submit" :disabled="locked || !loaded"><span class="spinner" v-if="busy || contactBusy" aria-hidden="true"></span>{{ busy || contactBusy ? t('working') : operation === 'contacts' ? t('saveContact') : operation === 'keygen' && keyMode === 'upgrade' ? t('upgradeKey') : operation === 'keygen' && keyMode === 'export' ? t('exportLegacy') : operation === 'keygen' && form.replace ? t('replaceKeysAction') : t(operation) }}<AppIcon v-if="!busy && !contactBusy" name="arrow" /></button></footer>
      </form>
      <section v-if="operation === 'contacts'" ref="contactList" class="card contact-list enter-page" aria-labelledby="saved-contacts-title">
        <h2 id="saved-contacts-title">{{ t('savedContacts') }}<span class="count">{{ contacts.length }}</span></h2>
        <p v-if="contactsFailure" class="notice" role="alert"><AppIcon name="alert" /><span>{{ t(contactsFailure) }}</span></p>
        <p v-if="unreadable" class="notice neutral" role="status"><AppIcon name="alert" /><span>{{ unreadableText }}</span></p>
        <template v-if="feedbackArea === 'list'">
          <div v-if="failure" id="contact-list-error" class="notice enter-feedback" role="alert"><AppIcon name="alert" /><span>{{ t(failure) }}</span></div>
          <div v-if="result" class="notice success enter-feedback" role="status"><AppIcon name="check" /><span>{{ t(successMessage) }}</span></div>
        </template>
        <p v-if="contactsLoaded && !contactsFailure && !contacts.length" class="hint empty">{{ t('noContacts') }}</p>
        <div v-if="form.contacts.length" class="selection-bar enter-feedback" role="group" :aria-label="selectedText">
          <span class="selection-count">{{ selectedText }}</span>
          <span v-if="atRecipientLimit" class="selection-limit">{{ t('recipient-limit') }}</span>
          <button type="button" class="text-button clear-selection" :disabled="locked" @click="clearSelection">{{ t('clearSelection') }}</button>
          <button type="button" class="secondary encrypt-selected" :disabled="locked" @click="encryptSelected">{{ t('encryptSelected') }}</button>
        </div>
        <ul :aria-label="t('savedContacts')">
          <li v-for="contact in contacts" :key="contact.id" class="contact" :class="{ highlighted: highlighted === contact.id, selected: form.contacts.includes(contact.id) }" :data-contact="contact.id">
            <label class="contact-select"><input type="checkbox" :checked="form.contacts.includes(contact.id)" :disabled="locked || (!form.contacts.includes(contact.id) && atRecipientLimit)" :aria-label="`${t('selectContact')} · ${label(contact)}`" @change="toggleContact(contact)"></label>
            <div class="contact-main">
              <strong class="contact-label">{{ label(contact) }}</strong>
              <span v-if="contact.note" class="contact-source">{{ t('sourceFile') }}: {{ contact.name || t('pastedKey') }}</span>
              <code class="contact-fingerprint" :aria-label="t('fingerprint')">{{ contact.fingerprint }}</code>
              <span class="contact-kind" :class="{ legacy: !contact.canVerify }">{{ contact.canVerify ? t('contactVerifies') : t('contactLegacy') }}</span>
            </div>
            <div v-if="editing === contact.id" class="contact-actions editing">
              <input v-model="noteDraft" v-bind="fieldAttrs('noteDraft')" name="noteDraft" spellcheck="false" autocomplete="off" :aria-label="`${t('note')} · ${label(contact)}`" :disabled="locked" @input="editNoteDraft" @keydown.enter.prevent="saveNote(contact)" @keydown.esc.prevent="editing = null">
              <button type="button" class="secondary" :disabled="locked" @click="saveNote(contact)">{{ t('saveNote') }}</button>
              <button type="button" class="text-button" :disabled="locked" @click="editing = null">{{ t('cancel') }}</button>
            </div>
            <div v-else-if="confirming === contact.id" class="contact-actions confirming" role="group" :aria-label="t('deletePrompt')">
              <span>{{ t('deletePrompt') }}</span>
              <button type="button" class="danger" :disabled="locked" @click="removeContact(contact)">{{ t('confirmDelete') }}</button>
              <button type="button" class="text-button" :disabled="locked" @click="confirming = null">{{ t('cancel') }}</button>
            </div>
            <div v-else class="contact-actions">
              <button type="button" class="secondary use-contact" :disabled="locked" :aria-label="`${t('useContact')} · ${label(contact)}`" @click="useContact(contact)">{{ t('useContact') }}</button>
              <button type="button" class="text-button edit-note" :disabled="locked" :aria-label="`${t('editNote')} · ${label(contact)}`" @click="startEdit(contact)">{{ t('editNote') }}</button>
              <button type="button" class="text-button delete-contact" :disabled="locked" :aria-label="`${t('deleteContact')} · ${label(contact)}`" @click="askDelete(contact)">{{ t('deleteContact') }}</button>
            </div>
          </li>
        </ul>
      </section>
      <section class="storage-note"><AppIcon name="shield" /><div><strong>{{ t('storage') }}</strong><p>{{ t('storageHint') }}</p></div></section>
    </main>
  </div>
</template>
