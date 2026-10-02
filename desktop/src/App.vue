<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, onUnmounted, reactive, ref } from 'vue';
import * as bridge from './bridge';
import AppIcon from './components/AppIcon.vue';
import { languageNames, translate, errorCode, type Language, type Message } from './i18n';

const operation = ref<bridge.Operation>('encrypt');
const language = ref<Language>('en');
const selectedLanguage = ref('system');
const version = ref('3.2.0');
const busy = ref(false), loaded = ref(false), cancelling = ref(false), jobId = ref('');
const cancelPending = ref(false);
const result = ref<bridge.Outcome | null>(null);
const failure = ref<Message | null>(null);
const progress = ref<bridge.Progress | null>(null);
const form = reactive({ input: '', output: '', keyPath: '', publicPath: '', privatePath: '', password: '', confirmation: '', keyText: '', paste: false });
type Field = Exclude<keyof typeof form, 'paste'>;
type PathField = 'input' | 'output' | 'keyPath' | 'publicPath' | 'privatePath';
const invalidField = ref<Field | null>(null);
const choosing = ref<PathField | null>(null);
const operationForm = ref<HTMLFormElement | null>(null);
const navigation = ref<HTMLElement | null>(null);
const locked = computed(() => busy.value || choosing.value !== null);
const t = (key: Message) => translate(key, language.value);
const tabs: bridge.Operation[] = ['keygen', 'encrypt', 'decrypt', 'fingerprint'];
const description = computed(() => t((operation.value + 'Help') as Message));
const percent = computed(() => progress.value && progress.value.total > 0 ? Math.max(0, Math.min(100, Math.floor(progress.value.processed / progress.value.total * 100))) : null);
const passwordRequired = computed(() => operation.value === 'keygen' || operation.value === 'decrypt');
const status = computed(() => !loaded.value ? t('loading') : choosing.value ? t('choosing') : cancelling.value ? t('cancelling') : busy.value ? t('working') : result.value ? t('success') : t('ready'));
let unlisten: (() => void) | undefined;

function clearSecretField(field: 'password' | 'confirmation' | 'keyText') {
  form[field] = '';
  // Clear the live DOM before invoking native code or removing a keyed form.
  // Entry animations never retain an outgoing form or a secret-bearing node.
  const element = operationForm.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>(`[name="${field}"]`);
  if (element) element.value = '';
}
function clearSecrets() {
  clearSecretField('password');
  clearSecretField('confirmation');
  clearSecretField('keyText');
}
function resetFeedback() {
  result.value = null;
  failure.value = null;
  invalidField.value = null;
  progress.value = null;
}
function switchTab(value: bridge.Operation) {
  if (locked.value || !loaded.value || value === operation.value) return;
  clearSecrets();
  form.output = '';
  resetFeedback();
  operation.value = value;
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
function setKeySource(paste: boolean) {
  if (locked.value || !loaded.value || paste === form.paste) return;
  clearSecretField('keyText');
  form.paste = paste;
  resetFeedback();
}
function fieldAttrs(field: Field) {
  const invalid = invalidField.value === field;
  const describedBy = [field === 'password' || field === 'confirmation' ? 'password-hint' : '', invalid ? 'operation-error' : ''].filter(Boolean).join(' ');
  return { 'aria-invalid': invalid || undefined, 'aria-describedby': describedBy || undefined };
}
function editField(event: Event) {
  const field = (event.target as HTMLInputElement | HTMLTextAreaElement).name;
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
      form[field] = value;
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
function validate(): Message | null {
  invalidField.value = null;
  const bytes = (value: string) => new TextEncoder().encode(value).length;
  if (bytes(form.password) > 1024) return invalid('password-limit', 'password');
  if (bytes(form.confirmation) > 1024) return invalid('password-limit', 'confirmation');
  if (operation.value === 'keygen') {
    if (!form.publicPath) return invalid('invalid-path', 'publicPath');
    if (!form.privatePath) return invalid('invalid-path', 'privatePath');
    if (!form.password) return invalid('password-empty', 'password');
    if (form.password !== form.confirmation) return invalid('password-mismatch', 'confirmation');
  } else {
    if (form.paste) {
      if (!form.keyText || bytes(form.keyText) > 1048576) return invalid('key-limit', 'keyText');
    } else if (!form.keyPath) return invalid('invalid-path', 'keyPath');
    if (operation.value !== 'fingerprint') {
      if (!form.input) return invalid('invalid-path', 'input');
      if (!form.output) return invalid('invalid-path', 'output');
    }
  }
  return null;
}
async function start() {
  if (locked.value || !loaded.value) return;
  failure.value = validate();
  result.value = null;
  progress.value = null;
  if (failure.value) {
    await nextTick();
    operationForm.value?.querySelector<HTMLInputElement | HTMLTextAreaElement>(`[name="${invalidField.value}"]`)?.focus();
    return;
  }
  jobId.value = crypto.randomUUID();
  busy.value = true;
  cancelling.value = false;
  const request: bridge.Request = { id: jobId.value, kind: operation.value, ...form };
  // The native layer owns zeroizing secrets. Do not retain them in the form.
  clearSecrets();
  try { result.value = await bridge.runOperation(request); }
  catch (error) { failure.value = errorCode(error); }
  finally {
    request.password = ''; request.confirmation = ''; request.keyText = '';
    busy.value = false; cancelling.value = false;
  }
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
  } catch (error) { failure.value = errorCode(error); }
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
        <button class="exit-button" :disabled="locked || !loaded" @click="exit"><AppIcon name="exit" /><span class="nav-label">{{ t('exit') }}</span><span class="nav-number" aria-hidden="true">05</span></button>
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
            <template v-else>
              <div class="key-source" role="group" :aria-label="t('keySource')"><label :class="{ selected: !form.paste }"><input type="radio" name="keySource" :checked="!form.paste" @change="setKeySource(false)">{{ t('path') }}</label><label :class="{ selected: form.paste }"><input type="radio" name="keySource" :checked="form.paste" @change="setKeySource(true)">{{ t('paste') }}</label></div>
              <label v-if="!form.paste" class="field enter-field">{{ t('key') }}<div class="path-input"><input v-model="form.keyPath" v-bind="fieldAttrs('keyPath')" name="keyPath" spellcheck="false" autocomplete="off"><button type="button" :aria-label="`${t('browse')} ${t('key')}`" @click="browse('keyPath')">{{ choosing === 'keyPath' ? t('choosing') : t('browse') }}</button></div></label>
              <label v-else class="field enter-field">{{ t('paste') }}<textarea v-model="form.keyText" v-bind="fieldAttrs('keyText')" :class="{ 'key-secret': operation === 'decrypt' }" name="keyText" rows="4" spellcheck="false" autocomplete="off" :placeholder="t('pasteHint')"></textarea></label>
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
        <div v-if="failure" id="operation-error" class="notice enter-feedback" :class="{ neutral: failure === 'cancelled' }" role="alert"><AppIcon :name="failure === 'cancelled' ? 'close' : 'alert'" /><span>{{ t(failure) }}</span></div>
        <div v-if="result" class="notice success enter-feedback" role="status"><AppIcon name="check" /><div><strong>{{ t('success') }}</strong><p v-if="result.output" class="result-path">{{ result.output }}</p><textarea v-if="result.fingerprint" class="fingerprint" :value="result.fingerprint" readonly rows="3" :aria-label="t('fingerprint')"></textarea></div></div>
        <footer class="actions"><span class="format"><span class="format-dot" aria-hidden="true"></span>X448 + ML-KEM-1024<span>NKEM v3</span></span><button v-if="busy && ['encrypt', 'decrypt'].includes(operation)" class="secondary" type="button" :disabled="cancelling || cancelPending" @click="cancel">{{ t('cancel') }}</button><button class="primary" type="submit" :disabled="locked || !loaded"><span class="spinner" v-if="busy" aria-hidden="true"></span>{{ busy ? t('working') : t(operation) }}<AppIcon v-if="!busy" name="arrow" /></button></footer>
      </form>
      <section class="storage-note"><AppIcon name="shield" /><div><strong>{{ t('storage') }}</strong><p>{{ t('storageHint') }}</p></div></section>
    </main>
  </div>
</template>
