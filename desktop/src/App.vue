<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import * as bridge from './bridge';
import { languageNames,translate,errorCode,type Language,type Message } from './i18n';
const operation=ref<bridge.Operation>('encrypt');
const language=ref<Language>('en');const selectedLanguage=ref('system');const version=ref('3.2.0');
const busy=ref(false),loaded=ref(false),cancelling=ref(false),jobId=ref('');
const result=ref<bridge.Outcome|null>(null);const failure=ref<Message|null>(null);
const progress=ref<bridge.Progress|null>(null);
const form=reactive({input:'',output:'',keyPath:'',publicPath:'',privatePath:'',password:'',confirmation:'',keyText:'',paste:false});
const t=(key:Message)=>translate(key,language.value);
const tabs:bridge.Operation[]=['keygen','encrypt','decrypt','fingerprint'];
const description=computed(()=>t((operation.value+'Help') as Message));
const percent=computed(()=>progress.value&&progress.value.total>0?Math.min(100,Math.floor(progress.value.processed/progress.value.total*100)):null);
const passwordRequired=computed(()=>operation.value==='keygen'||operation.value==='decrypt');
const status=computed(()=>cancelling.value?t('cancelling'):busy.value?t('working'):t('ready'));
let unlisten:(()=>void)|undefined;
function clearSecrets(){form.password='';form.confirmation='';form.keyText='';}
function switchTab(value:bridge.Operation){if(busy.value)return;clearSecrets();form.output='';result.value=null;failure.value=null;progress.value=null;operation.value=value;}
async function browse(field:'input'|'output'|'keyPath'|'publicPath'|'privatePath'){
 if(busy.value)return;
 try{let value:string|null;
  if(['output','publicPath','privatePath'].includes(field)){
   const filename=form.input.split(/[\\/]/).pop()||'file';
   const defaultPath=field==='publicPath'?'public.key':field==='privatePath'?'private.key.enc':operation.value==='encrypt'?filename+'.nkem':filename.replace(/\.nkem$/,'');
   value=await bridge.chooseSave(defaultPath);
  }else value=await bridge.chooseOpen();
  if(value)form[field]=value;
 }catch(error){failure.value=errorCode(error);}
}
async function changeLanguage(){
 try{language.value=await bridge.setLanguage(selectedLanguage.value) as Language;document.documentElement.lang=language.value;}
 catch(error){failure.value=errorCode(error);}
}
function validate():Message|null{
 const bytes=(value:string)=>new TextEncoder().encode(value).length;
 if(bytes(form.password)>1024||bytes(form.confirmation)>1024)return 'password-limit';
 if(operation.value==='keygen'){
  if(!form.publicPath||!form.privatePath)return 'invalid-path';
  if(!form.password)return 'password-empty';
  if(form.password!==form.confirmation)return 'password-mismatch';
 }else{
  if(form.paste){if(!form.keyText||bytes(form.keyText)>1048576)return 'key-limit';}
  else if(!form.keyPath)return 'invalid-path';
  if(operation.value!=='fingerprint'&&(!form.input||!form.output))return 'invalid-path';
 }
 return null;
}
async function start(){
 if(busy.value||!loaded.value)return;
 failure.value=validate();result.value=null;progress.value=null;
 if(failure.value)return;
 jobId.value=crypto.randomUUID();busy.value=true;cancelling.value=false;
 const request:bridge.Request={id:jobId.value,kind:operation.value,...form};
 // The native layer owns zeroizing secrets. Do not retain passwords in the form.
 clearSecrets();
 try{result.value=await bridge.runOperation(request);}
 catch(error){failure.value=errorCode(error);}
 finally{request.password='';request.confirmation='';request.keyText='';busy.value=false;cancelling.value=false;}
}
async function cancel(){try{if(await bridge.cancelOperation(jobId.value))cancelling.value=true;}catch(error){failure.value=errorCode(error);}}
async function exit(){try{await bridge.closeApp();}catch(error){failure.value=errorCode(error);}}
onMounted(async()=>{
 try{
  unlisten=await bridge.onProgress(value=>{if(busy.value&&value.id===jobId.value)progress.value=value;});
  const settings=await bridge.getSettings();language.value=settings.language as Language;selectedLanguage.value=settings.selection;version.value=settings.version;loaded.value=true;document.documentElement.lang=language.value;
 }catch(error){failure.value=errorCode(error);}
});
onUnmounted(()=>{unlisten?.();clearSecrets();});
</script>

<template>
 <div class="shell">
  <aside class="sidebar">
   <a class="brand" href="#" @click.prevent><img src="/icon.png" alt=""><span>NekoKEM<small>v{{version}}</small></span></a>
   <nav :aria-label="t('local')">
    <button v-for="(tab,index) in tabs" :key="tab" :class="{active:operation===tab}" :disabled="busy" @click="switchTab(tab)"><span class="nav-number">0{{index+1}}</span>{{t(tab)}}</button>
    <button :disabled="busy" @click="exit"><span class="nav-number">05</span>{{t('exit')}}</button>
   </nav>
   <div class="sidebar-bottom"><span class="local-dot"></span><strong>{{t('local')}}</strong><p>{{t('localHint')}}</p></div>
  </aside>
  <main>
   <header><span class="eyebrow">NEKOKEM · DESKTOP</span><label class="language">{{t('language')}}<select v-model="selectedLanguage" :disabled="busy||!loaded" @change="changeLanguage"><option v-for="(name,tag) in languageNames" :key="tag" :value="tag">{{tag==='system'?t('system'):name}}</option></select></label></header>
   <section class="page-title"><h1>{{t(operation)}}</h1><p>{{description}}</p></section>
   <form class="card" @submit.prevent="start">
    <div class="card-heading"><span class="step-label">{{t('subtitle')}}</span><span class="status"><i :class="{running:busy}"></i>{{status}}</span></div>
    <fieldset :disabled="busy||!loaded">
     <template v-if="operation==='keygen'">
      <label class="field">{{t('publicPath')}}<div class="path-input"><input v-model="form.publicPath" name="publicPath" spellcheck="false" autocomplete="off"><button type="button" @click="browse('publicPath')">{{t('browse')}}</button></div></label>
      <label class="field">{{t('privatePath')}}<div class="path-input"><input v-model="form.privatePath" name="privatePath" spellcheck="false" autocomplete="off"><button type="button" @click="browse('privatePath')">{{t('browse')}}</button></div></label>
     </template>
     <template v-else>
      <div class="key-source"><label><input v-model="form.paste" type="radio" :value="false">{{t('path')}}</label><label><input v-model="form.paste" type="radio" :value="true">{{t('paste')}}</label></div>
      <label v-if="!form.paste" class="field">{{t('key')}}<div class="path-input"><input v-model="form.keyPath" name="keyPath" spellcheck="false" autocomplete="off"><button type="button" @click="browse('keyPath')">{{t('browse')}}</button></div></label>
      <label v-else class="field">{{t('paste')}}<textarea v-model="form.keyText" :class="{'key-secret':operation==='decrypt'}" name="keyText" rows="5" spellcheck="false" autocomplete="off" :placeholder="t('pasteHint')"></textarea></label>
      <template v-if="operation!=='fingerprint'">
       <label class="field">{{t('input')}}<div class="path-input"><input v-model="form.input" name="input" spellcheck="false" autocomplete="off"><button type="button" @click="browse('input')">{{t('browse')}}</button></div></label>
       <label class="field">{{t('output')}}<div class="path-input"><input v-model="form.output" name="output" spellcheck="false" autocomplete="off"><button type="button" @click="browse('output')">{{t('browse')}}</button></div></label>
      </template>
     </template>
     <div v-if="passwordRequired" class="password-fields">
      <label class="field">{{t('password')}}<input v-model="form.password" type="password" name="password" maxlength="1024" autocomplete="off" spellcheck="false"></label>
      <label v-if="operation==='keygen'" class="field">{{t('confirmation')}}<input v-model="form.confirmation" type="password" name="confirmation" maxlength="1024" autocomplete="off" spellcheck="false"></label>
     </div>
     <p v-if="passwordRequired" class="hint">{{t('passwordHint')}}</p>
    </fieldset>
    <div v-if="busy" class="progress-block" role="status"><div><span>{{progress?t('progress'):t('preparing')}}</span><strong v-if="percent!==null">{{percent}}%</strong></div><progress :value="percent??undefined" max="100" :aria-label="t('progress')"></progress></div>
    <div v-if="failure" class="notice" :class="{neutral:failure==='cancelled'}" role="alert">{{t(failure)}}</div>
    <div v-if="result" class="notice success" role="status"><strong>{{t('success')}}</strong><p v-if="result.output" class="result-path">{{result.output}}</p><textarea v-if="result.fingerprint" class="fingerprint" :value="result.fingerprint" readonly rows="3" :aria-label="t('fingerprint')"></textarea></div>
    <footer class="actions"><span class="format">X448 + ML-KEM-1024 · NKEM v3</span><button v-if="busy&&['encrypt','decrypt'].includes(operation)" class="secondary" type="button" :disabled="cancelling" @click="cancel">{{t('cancel')}}</button><button class="primary" type="submit" :disabled="busy||!loaded">{{t(operation)}}<span aria-hidden="true">↗</span></button></footer>
   </form>
   <section class="storage-note"><span aria-hidden="true">◇</span><div><strong>{{t('storage')}}</strong><p>{{t('storageHint')}}</p></div></section>
  </main>
 </div>
</template>
