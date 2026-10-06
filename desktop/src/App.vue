<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, onUnmounted, reactive, ref } from 'vue';
import * as bridge from './bridge';
import AppIcon from './components/AppIcon.vue';
import { languageNames, translate, errorCode, type Language, type Message } from './i18n';

type View = bridge.Operation | 'contacts';
type KeySource = 'path' | 'paste' | 'contact';
const operation = ref<View>('encrypt');
const language = ref<Language>('en');
const selectedLanguage = ref('system');
const version = ref('3.3.1');
const busy = ref(false), loaded = ref(false), cancelling = ref(false), jobId = ref('');
const cancelPending = ref(false);
const result = ref<bridge.Outcome | null>(null);
const successMessage = ref<Message>('success');
const failure = ref<Message | null>(null);
// Contact list actions report beside the list; everything else beside the form.
const feedbackArea = ref<'form' | 'list'>('form');
const progress = ref<bridge.Progress | null>(null);
const form = reactive({ input: '', output: '', keyPath: '', publicPath: '', privatePath: '', password: '', confirmation: '', keyText: '', source: 'path' as KeySource, contact: '' });
const contactForm = reactive({ keyPath: '', keyText: '', paste: false, note: '' });
type Field = Exclude<keyof typeof form, 'source'> | 'contactKeyPath' | 'contactKeyText' | 'note' | 'noteDraft';
type PathField = 'input' | 'output' | 'keyPath' | 'publicPath' | 'privatePath' | 'contactKeyPath';
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
const recipient = ref<bridge.Contact | null>(null);
const locked = computed(() => busy.value || contactBusy.value || choosing.value !== null);
const t = (key: Message) => translate(key, language.value);
const tabs: View[] = ['keygen', 'encrypt', 'decrypt', 'fingerprint', 'contacts'];
const description = computed(() => t((operation.value + 'Help') as Message));
const percent = computed(() => progress.value && progress.value.total > 0 ? Math.max(0, Math.min(100, Math.floor(progress.value.processed / progress.value.total * 100))) : null);
const passwordRequired = computed(() => operation.value === 'keygen' || operation.value === 'decrypt');
const selectedContact = computed(() => contacts.value.find(contact => contact.id === form.contact) ?? null);
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
  recipient.value = null;
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
  if ((value === 'decrypt' || value === 'fingerprint') && form.source === 'contact') form.source = 'path';
  operation.value = value;
  if (value === 'encrypt' || value === 'contacts') void refreshContacts();
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
    if (['output', 'publicPath', 'privatePath'].includes(field)) {
      const filename = form.input.split(/[\\/]/).pop() || 'file';
      const defaultPath = field === 'publicPath' ? 'public.key' : field === 'privatePath' ? 'private.key.enc' : operation.value === 'encrypt' ? filename + '.nkem' : filename.replace(/\.nkem$/, '');
      value = await bridge.chooseSave(defaultPath);
    } else value = await bridge.chooseOpen();
    if (value) {
      if (field === 'contactKeyPath') contactForm.keyPath = value;
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
  if (operation.value === 'keygen') {
    if (!form.publicPath) return invalid('invalid-path', 'publicPath');
    if (!form.privatePath) return invalid('invalid-path', 'privatePath');
    if (!form.password) return invalid('password-empty', 'password');
    if (form.password !== form.confirmation) return invalid('password-mismatch', 'confirmation');
  } else {
    if (form.source === 'paste') {
      if (!form.keyText || bytes(form.keyText) > 1048576) return invalid('key-limit', 'keyText');
    } else if (form.source === 'contact') {
      // An explicit, currently listed contact is required; never pick one implicitly.
      if (!form.contact) return invalid('contact-required', 'contact');
      if (!selectedContact.value) return invalid('contact-missing', 'contact');
    } else if (!form.keyPath) return invalid('invalid-path', 'keyPath');
    if (operation.value !== 'fingerprint') {
      if (!form.input) return invalid('invalid-path', 'input');
      if (!form.output) return invalid('invalid-path', 'output');
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
  // A selection that disappeared is cleared and reported, never replaced.
  if (form.contact && !busy.value && !selectedContact.value) {
    form.contact = '';
    if (operation.value === 'encrypt' && form.source === 'contact' && !failure.value) failure.value = invalid('contact-missing', 'contact');
  }
}
async function start() {
  if (locked.value || !loaded.value) return;
  if (operation.value === 'contacts') return saveContact();
  resetFeedback();
  failure.value = validate();
  if (failure.value) return focusInvalid();
  const contact = form.source === 'contact' && operation.value === 'encrypt' ? selectedContact.value : null;
  jobId.value = crypto.randomUUID();
  busy.value = true;
  cancelling.value = false;
  const request: bridge.Request = {
    id: jobId.value, kind: operation.value, input: form.input, output: form.output,
    keyPath: form.source === 'path' ? form.keyPath : '', publicPath: form.publicPath, privatePath: form.privatePath,
    password: form.password, confirmation: form.confirmation, keyText: form.source === 'paste' ? form.keyText : '',
    paste: form.source === 'paste', contact: contact?.id ?? '',
  };
  // The native layer owns zeroizing secrets. Do not retain them in the form.
  clearSecrets();
  let unavailable = false;
  try {
    result.value = await bridge.runOperation(request);
    recipient.value = contact;
  } catch (error) {
    failure.value = errorCode(error);
    // Require an explicit new choice after a contact could not be used.
    unavailable = contact !== null && contactFailures.includes(failure.value);
    if (unavailable) { form.contact = ''; invalidField.value = 'contact'; }
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
  form.contact = contact.id;
  form.source = 'contact';
  switchTab('encrypt');
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
        <button class="exit-button" :disabled="locked || !loaded" @click="exit"><AppIcon name="exit" /><span class="nav-label">{{ t('exit') }}</span><span class="nav-number" aria-hidden="true">06</span></button>
      </nav>
      <div class="sidebar-bottom"><span class="local-dot" aria-hidden="true"></span><strong>{{ t('local') }}</strong><p>{{ t('localHint') }}</p></div>
    </aside>
    <main>
      <header><span class="eyebrow">NEKOKEM · DESKTOP</span><label class="language">{{ t('language') }}<select v-model="selectedLanguage" :disabled="locked || !loaded" @change="changeLanguage"><option v-for="(name, tag) in languageNames" :key="tag" :value="tag">{{ tag === 'system' ? t('system') : name }}</option></select></label></header>
      <section :key="operation" class="page-title enter-page"><div class="title-icon"><AppIcon :name="operation" /></div><div><h1>{{ t(operation) }}</h1><p>{{ description }}</p></div></section>
      <form ref="operationForm" class="card" :class="{ 'is-busy': busy }" novalidate @submit.prevent="start" @input="editField">
        <div class="card-heading"><span class="step-label">{{ t('subtitle') }}</span><span class="status" :class="{ 'is-working': locked, 'is-complete': result }"><AppIcon v-if="result && !locked" name="check" /><i v-else :class="{ running: locked }" aria-hidden="true"></i>{{ status }}</span></div>
        <fieldset :disabled="locked || !loaded">
          <!-- Only animate the new form's entry; never keep an outgoing secret-bearing form. -->
          <div :key="operation" class="form-content enter-form">
            <template v-if="operation === 'keygen'">
              <label class="field">{{ t('publicPath') }}<div class="path-input"><input v-model="form.publicPath" v-bind="fieldAttrs('publicPath')" name="publicPath" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('publicPath')}`" @click="browse('publicPath')">{{ choosing === 'publicPath' ? t('choosing') : t('browse') }}</button></div></label>
              <label class="field">{{ t('privatePath') }}<div class="path-input"><input v-model="form.privatePath" v-bind="fieldAttrs('privatePath')" name="privatePath" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('privatePath')}`" @click="browse('privatePath')">{{ choosing === 'privatePath' ? t('choosing') : t('browse') }}</button></div></label>
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
                <label class="field">{{ t('recipient') }}<select v-model="form.contact" v-bind="fieldAttrs('contact')" name="contact"><option value="" disabled>{{ t('chooseContact') }}</option><option v-for="contact in contacts" :key="contact.id" :value="contact.id">{{ label(contact) }} · {{ contact.fingerprint.slice(0, 11) }}…</option></select></label>
                <div v-if="selectedContact" class="contact-preview"><span>{{ t('fingerprint') }}</span><code>{{ selectedContact.fingerprint }}</code></div>
                <p v-else-if="contactsFailure" class="hint warning">{{ t(contactsFailure) }}</p>
                <p v-else-if="contactsLoaded && !contacts.length" class="hint">{{ t('noContacts') }}<button type="button" class="link-button" @click="switchTab('contacts')">{{ t('manageContacts') }}</button></p>
              </div>
              <template v-if="operation !== 'fingerprint'">
                <label class="field">{{ t('input') }}<div class="path-input"><input v-model="form.input" v-bind="fieldAttrs('input')" name="input" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('input')}`" @click="browse('input')">{{ choosing === 'input' ? t('choosing') : t('browse') }}</button></div></label>
                <label class="field">{{ t('output') }}<div class="path-input"><input v-model="form.output" v-bind="fieldAttrs('output')" name="output" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('output')}`" @click="browse('output')">{{ choosing === 'output' ? t('choosing') : t('browse') }}</button></div></label>
              </template>
            </template>
            <div v-if="passwordRequired" class="password-fields">
              <label class="field">{{ t('password') }}<input v-model="form.password" v-bind="fieldAttrs('password')" type="password" name="password" maxlength="1024" autocomplete="off" spellcheck="false"></label>
              <label v-if="operation === 'keygen'" class="field">{{ t('confirmation') }}<input v-model="form.confirmation" v-bind="fieldAttrs('confirmation')" type="password" name="confirmation" maxlength="1024" autocomplete="off" spellcheck="false"></label>
            </div>
            <p v-if="passwordRequired" id="password-hint" class="hint">{{ t('passwordHint') }}</p>
          </div>
        </fieldset>
        <div v-if="busy" class="progress-block enter-feedback" role="status"><div class="progress-label"><span>{{ cancelling ? t('cancelling') : progress ? t('progress') : t('preparing') }}</span><strong v-if="percent !== null">{{ percent }}%</strong></div><div class="progress-track" role="progressbar" :aria-label="t('progress')" :aria-valuenow="percent ?? undefined" aria-valuemin="0" aria-valuemax="100" :aria-valuetext="percent === null ? t('preparing') : undefined"><div class="progress-fill" :class="{ indeterminate: percent === null }" :style="{ width: `${percent ?? 32}%` }"></div></div></div>
        <template v-if="feedbackArea === 'form'">
          <div v-if="failure" id="operation-error" class="notice enter-feedback" :class="{ neutral: failure === 'cancelled' }" role="alert"><AppIcon :name="failure === 'cancelled' ? 'close' : 'alert'" /><span>{{ t(failure) }}</span></div>
          <div v-if="result" class="notice success enter-feedback" role="status"><AppIcon name="check" /><div><strong>{{ t(successMessage) }}</strong><p v-if="result.output" class="result-path">{{ result.output }}</p><p v-if="recipient" class="result-path">{{ t('recipient') }}: {{ label(recipient) }}</p><textarea v-if="result.fingerprint" class="fingerprint" :value="result.fingerprint" readonly rows="3" :aria-label="t('fingerprint')"></textarea></div></div>
        </template>
        <footer class="actions"><span class="format"><span class="format-dot" aria-hidden="true"></span>X448 + ML-KEM-1024<span>NKEM v3</span></span><button v-if="busy && ['encrypt', 'decrypt'].includes(operation)" class="secondary" type="button" :disabled="cancelling || cancelPending" @click="cancel">{{ t('cancel') }}</button><button class="primary" type="submit" :disabled="locked || !loaded"><span class="spinner" v-if="busy || contactBusy" aria-hidden="true"></span>{{ busy || contactBusy ? t('working') : operation === 'contacts' ? t('saveContact') : t(operation) }}<AppIcon v-if="!busy && !contactBusy" name="arrow" /></button></footer>
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
        <ul :aria-label="t('savedContacts')">
          <li v-for="contact in contacts" :key="contact.id" class="contact" :class="{ highlighted: highlighted === contact.id, selected: form.contact === contact.id }" :data-contact="contact.id">
            <div class="contact-main">
              <strong class="contact-label">{{ label(contact) }}</strong>
              <span v-if="contact.note" class="contact-source">{{ t('sourceFile') }}: {{ contact.name || t('pastedKey') }}</span>
              <code class="contact-fingerprint" :aria-label="t('fingerprint')">{{ contact.fingerprint }}</code>
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
