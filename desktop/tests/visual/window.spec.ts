import { expect, test, type Page } from '@playwright/test';
import { translate, type Language } from '../../src/i18n';

interface Harness {
 progress(processed:number,total:number,id?:string):void;
 complete(fingerprint?:string,extra?:Record<string,unknown>):void;
 fail(code:string):void;
 resolveDialog(path:string|null):void;
}
async function harness(page:Page,action:'complete'|'fail'|'progress'|'resolveDialog',value?:string|number,total?:number,id?:string,extra?:Record<string,unknown>){
 await page.evaluate(({action,value,total,id,extra})=>{
  const desktop=(window as unknown as {desktop:Harness}).desktop;
  if(action==='progress')desktop.progress(value as number,total!,id);
  else if(action==='fail')desktop.fail(value as string);
  else if(action==='resolveDialog')desktop.resolveDialog(value as string|null);
  else desktop.complete(value as string|undefined,extra);
 },{action,value,total,id,extra});
}
// Encryption signs by default with the sender's identity key and password.
async function signWith(page:Page){
 await page.locator('[name=signKeyPath]').fill('C:\\Local\\private.key.enc');
 await page.locator('[name=password]').fill('public-test-password');
}
async function fillEncryption(page:Page){
 await page.locator('[name=keyPath]').fill('C:\\Local\\public.key');
 await page.locator('[name=input]').fill('C:\\Local\\report.txt');
 await page.locator('[name=output]').fill('C:\\Local\\report.txt.nkem');
 await signWith(page);
 await page.locator('.primary').click();
}
// The language picker sits in Settings; this leaves the Settings page open.
async function setLanguage(page:Page,tag:string){
 await page.locator('.operation-tab').nth(5).click();
 await page.locator('[name=language]').selectOption(tag);
}
async function noHorizontalOverflow(page:Page){
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
 if(await page.locator('form.card').count()===0)return;
 const card=await page.locator('form.card').boundingBox();
 const button=await page.locator('.primary').boundingBox();
 expect(card).not.toBeNull();expect(button).not.toBeNull();
 expect(button!.x+button!.width).toBeLessThanOrEqual(card!.x+card!.width);
}
test.beforeEach(async({page})=>{
 await page.addInitScript(()=>{
  const callbacks:Record<number,(value:unknown)=>void>={};let next=1;
  let progressHandler=0,request:{id:string;output:string}|null=null;
  let finish:((value:unknown)=>void)|null=null,fail:((value:unknown)=>void)|null=null;
  let choose:((path:string|null)=>void)|null=null;
  const fingerprint=(byte:string)=>Array(32).fill(byte).join(':');
  let contacts=[
   {id:'a'.repeat(64),fingerprint:fingerprint('A1'),name:'alice-public.key',note:'Alice · 工作电脑',canVerify:true,legacyFingerprint:fingerprint('A0')},
   {id:'b'.repeat(64),fingerprint:fingerprint('B2'),name:'',note:'',canVerify:false},
  ];
  Object.assign(window,{__TAURI_INTERNALS__:{
   transformCallback(callback:(value:unknown)=>void){const id=next++;callbacks[id]=callback;return id;},
   unregisterCallback(id:number){delete callbacks[id];},
   invoke(command:string,args:Record<string,unknown>){
    if(command==='get_settings')return Promise.resolve({language:'zh-CN',selection:'system',version:'5.0.0'});
    if(command==='set_language')return Promise.resolve(args.language==='system'?'zh-CN':args.language);
    if(command==='plugin:event|listen'){progressHandler=args.handler as number;return Promise.resolve(1);}
    if(command==='plugin:event|unlisten')return Promise.resolve(null);
    if(command==='run_operation'){
     const value=args.request as {id:string;output:string};request={id:value.id,output:value.output};
     Object.assign(window,{lastRequest:value});
     return new Promise((resolve,reject)=>{finish=resolve;fail=reject;});
    }
    if(command==='cancel_operation')return Promise.resolve(true);
    if(command==='update_preferences')return Promise.resolve((window as unknown as {updatePreferences?:unknown}).updatePreferences??{auto:true,lastCheck:Date.now(),ignored:''});
    if(command==='save_update_preferences'){Object.assign(window,{updatePreferences:args.preferences});return Promise.resolve(null);}
    if(command==='check_update')return Promise.resolve((window as unknown as {updateStatus?:unknown}).updateStatus??{status:'current'});
    if(command==='open_release_page'){Object.assign(window,{openedRelease:args.version});return Promise.resolve(null);}
    if(command==='list_contacts')return Promise.resolve({contacts:contacts.map(contact=>({...contact})),unreadable:0});
    if(command==='save_contact'){
     const value=args.contact as {keyPath:string;note:string;confirmUpgrade?:string};
     // An identity key for the legacy contact B waits for the fingerprint check.
     if(value.keyPath.includes('upgrade')){
      if(value.confirmUpgrade!==fingerprint('E5'))return Promise.reject({code:'upgrade-unconfirmed',contact:'b'.repeat(64),fingerprint:fingerprint('E5')});
      const upgraded={id:'e'.repeat(64),fingerprint:fingerprint('E5'),name:'',note:'',canVerify:true,legacyFingerprint:fingerprint('B2')};
      contacts=[...contacts.filter(contact=>contact.id!=='b'.repeat(64)),upgraded];return Promise.resolve(upgraded);
     }
     const contact={id:'c'.repeat(64),fingerprint:fingerprint('C3'),name:value.keyPath.split(/[\\/]/).pop()!,note:value.note.trim(),canVerify:true};
     contacts=[...contacts,contact];return Promise.resolve(contact);
    }
    if(command==='update_contact_note'){
     contacts=contacts.map(contact=>contact.id===args.id?{...contact,note:(args.note as string).trim()}:contact);
     return Promise.resolve(contacts.find(contact=>contact.id===args.id));
    }
    if(command==='delete_contact'){contacts=contacts.filter(contact=>contact.id!==args.id);return Promise.resolve(null);}
    if(command==='plugin:dialog|open'||command==='plugin:dialog|save')return new Promise(resolve=>{choose=resolve;});
    return Promise.reject({code:'internal'});
   },
  },__TAURI_EVENT_PLUGIN_INTERNALS__:{unregisterListener(){}},desktop:{
   progress(processed:number,total:number,id?:string){callbacks[progressHandler]?.({event:'operation-progress',id:1,payload:{id:id??request?.id,processed,total}});},
   complete(fingerprint?:string,extra?:Record<string,unknown>){finish?.({output:request?.output??null,fingerprint:fingerprint??null,format:5,...extra});},
   fail(code:string){fail?.({code});},
   resolveDialog(path:string|null){choose?.(path);},
  }});
 });
 await page.goto('/');await expect(page.getByRole('heading',{name:'加密文件',exact:true})).toBeVisible();
});

test('five languages across all six page layouts',async({page})=>{
 for(const tag of ['zh-CN','zh-TW','en','ja','ko'] as Language[]){
  await setLanguage(page,tag);
  for(const [index,operation] of ['keygen','encrypt','decrypt','fingerprint','contacts','settings'].entries()){
   await page.locator('.operation-tab').nth(index).click();
   await expect(page.getByRole('heading',{name:translate(operation as 'keygen'|'encrypt'|'decrypt'|'fingerprint'|'contacts'|'settings',tag),exact:true,level:1})).toBeVisible();
   await noHorizontalOverflow(page);
  }
  await page.locator('.operation-tab').nth(4).click();
  await expect(page.locator('.contact')).toHaveCount(2);
  await expect(page.locator('.contact-label').last()).toHaveText(translate('pastedKey',tag));
  await page.screenshot({path:`test-results/desktop-contacts-${tag}.png`,fullPage:true,animations:'disabled'});
  await page.locator('.operation-tab').nth(1).click();
  await page.screenshot({path:`test-results/desktop-${tag}.png`,fullPage:true,animations:'disabled'});
 }
});

test('minimum native window fits all five languages and pasted-key layouts',async({page})=>{
 await page.setViewportSize({width:760,height:620});
 for(const tag of ['zh-CN','zh-TW','en','ja','ko'] as Language[]){
  await setLanguage(page,tag);
  for(let index=0;index<6;index++){
   await page.locator('.operation-tab').nth(index).click();await noHorizontalOverflow(page);
   if(index<5){await page.getByRole('radio').nth(index===0?2:1).check();await noHorizontalOverflow(page);}
  }
  await page.locator('[name=unsigned]').click();await expect(page.getByRole('alertdialog')).toBeVisible();await noHorizontalOverflow(page);
  await page.locator('.unsigned-cancel').click();
  await page.locator('.operation-tab').nth(0).click();await page.getByRole('radio').nth(0).check();
  await page.locator('.operation-tab').nth(4).click();
  await page.locator('.edit-note').first().click();await noHorizontalOverflow(page);
  await page.locator('.contact-actions.editing .text-button').click();
  await page.locator('.delete-contact').first().click();await noHorizontalOverflow(page);
  await page.locator('.contact-actions.confirming .text-button').click();
  await page.locator('.operation-tab').nth(1).click();await page.getByRole('radio').nth(2).check();
  await page.locator('[name=contacts]').first().check();
  await expect(page.locator('.recipient-option code').first()).toHaveText(Array(32).fill('A1').join(':'));
  await expect(page.locator('.contact-preview code')).toBeVisible();await noHorizontalOverflow(page);
  await page.locator('[name=contacts]').nth(1).check();
  await expect(page.locator('.multi-hint')).toBeVisible();await noHorizontalOverflow(page);
  await page.locator('.operation-tab').nth(4).click();
  await expect(page.locator('.selection-bar')).toBeVisible();await noHorizontalOverflow(page);
  await page.locator('.clear-selection').click();await expect(page.locator('.selection-bar')).toHaveCount(0);
 }
 await page.screenshot({path:'test-results/desktop-minimum-contact-selected.png',fullPage:true,animations:'disabled'});
 await setLanguage(page,'zh-CN');await page.locator('.operation-tab').nth(2).click();
 await page.screenshot({path:'test-results/desktop-minimum-decrypt.png',fullPage:true,animations:'disabled'});
});

test('the sidebar hides from the toolbar and remembers it',async({page})=>{
 await setLanguage(page,'en');
 const toggle=page.getByRole('button',{name:'Collapse sidebar'});
 await toggle.click();
 await expect(page.locator('.sidebar')).toHaveClass(/collapsed/);
 await expect(page.locator('.sidebar')).toBeHidden();
 await expect(page.getByRole('heading',{name:'Settings',level:1})).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
 await page.screenshot({path:'test-results/desktop-sidebar-collapsed.png',fullPage:true,animations:'disabled'});
 await page.reload();
 await expect(page.locator('.sidebar')).toHaveClass(/collapsed/);
 await page.locator('.sidebar-toggle').click();
 await expect(page.locator('.sidebar')).not.toHaveClass(/collapsed/);
});

test('keyboard navigation and error focus remain visible',async({page})=>{
 await setLanguage(page,'en');
 const first=page.locator('.operation-tab').first();await first.focus();
 await first.press('ArrowDown');await expect(page.locator('.operation-tab').nth(1)).toBeFocused();
 await page.keyboard.press('End');await expect(page.locator('.operation-tab').nth(5)).toBeFocused();
 await expect(page.getByRole('heading',{name:'Settings',level:1})).toBeVisible();
 await page.keyboard.press('Home');await expect(first).toBeFocused();
 await page.locator('.primary').focus();await page.keyboard.press('Enter');
 await expect(page.locator('[name=publicPath]')).toBeFocused();
 await expect(page.locator('[name=publicPath]')).toHaveAttribute('aria-invalid','true');
 await expect(page.getByRole('alert')).toContainText('required file paths');
 expect(await page.locator('[name=publicPath]').evaluate(element=>getComputedStyle(element).outlineWidth)).toBe('2px');
 await page.screenshot({path:'test-results/desktop-keyboard-error.png',fullPage:true,animations:'disabled'});
});

test('animated operation switches discard secret DOM before the next form appears',async({page})=>{
 await page.locator('.operation-tab').nth(2).click();await page.getByRole('radio').nth(1).check();
 await page.locator('[name=keyText]').fill('private-pem-test-fixture');
 await page.locator('[name=password]').fill('test-password');
 expect(await page.locator('[name=keyText]').evaluate(element=>getComputedStyle(element).getPropertyValue('-webkit-text-security'))).toBe('disc');
 await page.evaluate(()=>Object.assign(window,{removedKey:document.querySelector('[name=keyText]'),removedPassword:document.querySelector('[name=password]')}));
 await page.locator('.operation-tab').first().click();
 expect(await page.evaluate(()=>{
  const old=window as unknown as {removedKey:HTMLTextAreaElement;removedPassword:HTMLInputElement};
  return [old.removedKey.value,old.removedPassword.value,old.removedKey.isConnected,old.removedPassword.isConnected];
 })).toEqual(['','',false,false]);
 await expect(page.locator('.form-content')).toHaveCount(1);
 await expect(page.locator('[name=password]')).toHaveValue('');
});

test('real progress, safe cancellation and completion states',async({page})=>{
 await fillEncryption(page);
 await expect(page.getByRole('progressbar')).not.toHaveAttribute('aria-valuenow');
 await expect(page.locator('.status')).toContainText('正在处理');
 await harness(page,'progress',80,100,'old-job');
 await expect(page.getByRole('progressbar')).not.toHaveAttribute('aria-valuenow');
 await harness(page,'progress',48,100);
 await expect(page.getByRole('progressbar')).toHaveAttribute('aria-valuenow','48');
 await page.screenshot({path:'test-results/desktop-progress.png',fullPage:true,animations:'disabled'});
 await page.locator('.secondary').click();await expect(page.locator('.secondary')).toBeDisabled();
 await expect(page.locator('.status')).toContainText('正在安全取消');
 await expect(page.locator('.operation-tab').first()).toBeDisabled();
 await harness(page,'fail','cancelled');await expect(page.getByRole('alert')).toContainText('未提交新的输出');
 await expect(page.locator('.primary')).toBeEnabled();await expect(page.getByRole('progressbar')).toHaveCount(0);
 await fillEncryption(page);await harness(page,'complete');
 await expect(page.locator('.notice.success')).toContainText('已加密并签名（NKEM v5）');
 await expect(page.locator('.notice.success')).toContainText('report.txt.nkem');
 await expect(page.locator('.spinner')).toHaveCount(0);
 await page.screenshot({path:'test-results/desktop-completed.png',fullPage:true,animations:'disabled'});
});

test('reduced motion disables entry, progress and spinner animations',async({page})=>{
 await page.emulateMedia({reducedMotion:'reduce'});
 await page.locator('.operation-tab').first().click();
 expect(await page.locator('.page-title').evaluate(element=>getComputedStyle(element).animationName)).toBe('none');
 expect(await page.locator('.form-content').evaluate(element=>getComputedStyle(element).animationName)).toBe('none');
 await page.locator('.operation-tab').nth(1).click();await fillEncryption(page);
 expect(await page.locator('.spinner').evaluate(element=>getComputedStyle(element).animationName)).toBe('none');
 expect(await page.locator('.progress-fill').evaluate(element=>getComputedStyle(element).animationName)).toBe('none');
 expect(await page.locator('.progress-fill').evaluate(element=>getComputedStyle(element).transitionDuration)).toBe('0s');
 await harness(page,'progress',50,100);await expect(page.getByRole('progressbar')).toHaveAttribute('aria-valuenow','50');
 await harness(page,'complete');await expect(page.locator('.notice.success')).toBeVisible();
});

test('normal motion is enabled only during entry or active processing',async({page})=>{
 await page.emulateMedia({reducedMotion:'no-preference'});
 await page.locator('.operation-tab').first().click();
 expect(await page.locator('.page-title').evaluate(element=>getComputedStyle(element).animationName)).toBe('enter-page');
 await page.locator('.operation-tab').nth(1).click();await fillEncryption(page);
 expect(await page.locator('.spinner').evaluate(element=>getComputedStyle(element).animationName)).toBe('spin');
 expect(await page.locator('.progress-fill').evaluate(element=>getComputedStyle(element).animationName)).toBe('progress-scan');
 await harness(page,'complete');
 await expect.poll(()=>page.evaluate(()=>document.getAnimations().filter(animation=>animation.playState==='running').length)).toBe(0);
});

test('native dialog keeps controls locked until a selection returns',async({page})=>{
 await page.locator('.path-input button').first().click();
 await expect(page.locator('.status')).toContainText('正在选择文件');
 await expect(page.locator('.operation-tab').first()).toBeDisabled();await expect(page.locator('.primary')).toBeDisabled();
 await harness(page,'resolveDialog','C:\\Local\\public.key');
 await expect(page.locator('[name=keyPath]')).toHaveValue('C:\\Local\\public.key');
 await expect(page.locator('.operation-tab').first()).toBeEnabled();await expect(page.locator('.primary')).toBeEnabled();
});

test('Windows high contrast keeps navigation, focus and progress readable',async({page})=>{
 await page.emulateMedia({forcedColors:'active'});
 await page.locator('.operation-tab').nth(1).focus();
 expect(await page.locator('.operation-tab').nth(1).evaluate(element=>getComputedStyle(element).outlineStyle)).toBe('solid');
 await fillEncryption(page);await harness(page,'progress',50,100);
 await expect(page.getByRole('progressbar')).toHaveAttribute('aria-valuenow','50');
 expect(await page.locator('.progress-track').evaluate(element=>getComputedStyle(element).borderTopWidth)).toBe('1px');
 await page.screenshot({path:'test-results/desktop-high-contrast.png',fullPage:true,animations:'disabled'});
});

test('public-key contacts are saved, edited, chosen explicitly and deleted',async({page})=>{
 await setLanguage(page,'en');
 await page.locator('.operation-tab').nth(4).click();
 await page.locator('[name=contactKeyPath]').fill('C:\\Local\\carol-public.key');
 // Note limits count Unicode code points; the browser must not cut emoji at 512 UTF-16 units.
 await page.locator('[name=note]').fill('🐈'.repeat(512));
 await expect(page.locator('[name=note]')).toHaveValue('🐈'.repeat(512));
 await page.locator('[name=note]').fill('  Carol desktop  ');
 await page.locator('.primary').click();
 await expect(page.locator('form .notice.success')).toContainText('Public key saved');
 await expect(page.locator('.contact.highlighted .contact-label')).toHaveText('Carol desktop');
 await expect(page.locator('.contact.highlighted .contact-source')).toContainText('carol-public.key');
 await page.screenshot({path:'test-results/desktop-contacts-saved.png',fullPage:true,animations:'disabled'});

 const carol=page.locator('.contact.highlighted');
 await carol.locator('.edit-note').click();
 await expect(page.locator('[name=noteDraft]')).toBeFocused();
 await page.locator('[name=noteDraft]').fill('🐈'.repeat(512));
 await expect(page.locator('[name=noteDraft]')).toHaveValue('🐈'.repeat(512));
 await page.locator('[name=noteDraft]').fill('Carol laptop');await page.keyboard.press('Enter');
 await expect(page.locator('.contact-list .notice.success')).toContainText('Note saved');
 await expect(page.locator('.contact.highlighted .contact-label')).toHaveText('Carol laptop');

 await page.locator('.contact.highlighted .use-contact').click();
 await expect(page.getByRole('heading',{name:'Encrypt file',level:1})).toBeVisible();
 await expect(page.getByRole('radio',{name:'Saved contacts'})).toBeChecked();
 await expect(page.locator('.recipient-option').filter({hasText:'Carol laptop'}).locator('input')).toBeChecked();
 await expect(page.locator('[name=contacts]:checked')).toHaveCount(1);
 await expect(page.locator('.contact-preview code')).toHaveText(Array(32).fill('C3').join(':'));
 await page.locator('[name=input]').fill('C:\\Local\\report.txt');await page.locator('[name=output]').fill('C:\\Local\\report.txt.nkem');
 await signWith(page);await page.locator('.primary').click();
 const request=await page.evaluate(()=>(window as unknown as {lastRequest:Record<string,unknown>}).lastRequest);
 expect([request.kind,request.contacts,request.keyPath,request.keyText,request.paste]).toEqual(['encrypt',['c'.repeat(64)],'','',false]);
 await harness(page,'complete',Array(32).fill('C3').join(':'));
 await expect(page.locator('.notice.success')).toContainText('Recipient: Carol laptop');
 await page.screenshot({path:'test-results/desktop-contact-encrypted.png',fullPage:true,animations:'disabled'});

 await page.locator('.operation-tab').nth(4).click();
 await page.locator('.contact').filter({hasText:'Carol laptop'}).locator('.delete-contact').click();
 await expect(page.locator('.contact-actions.confirming')).toContainText('Files already encrypted are not affected');
 await page.locator('.danger').click();
 await expect(page.locator('.contact-list .notice.success')).toContainText('Contact deleted');
 await expect(page.locator('.contact')).toHaveCount(2);
 // The deleted selection is cleared on the encryption page instead of being replaced.
 await page.locator('.operation-tab').nth(1).click();
 await expect(page.locator('[name=contacts]:checked')).toHaveCount(0);
 await page.locator('[name=input]').fill('C:\\Local\\report.txt');await page.locator('[name=output]').fill('C:\\Local\\report.txt.nkem');
 await signWith(page);await page.locator('.primary').click();
 await expect(page.getByRole('alert')).toHaveText('Choose at least one saved contact.');
 await expect(page.locator('[name=contacts]').first()).toBeFocused();
});

test('several contacts selected on the contacts page share one encrypted file',async({page})=>{
 await setLanguage(page,'en');
 await page.locator('.operation-tab').nth(4).click();
 await expect(page.locator('.selection-bar')).toHaveCount(0);
 await page.getByRole('checkbox',{name:'Select · Pasted public key'}).check();
 await page.getByRole('checkbox',{name:'Select · Alice · 工作电脑'}).check();
 await expect(page.locator('.selection-count')).toHaveText('2 selected');
 await expect(page.locator('.contact.selected')).toHaveCount(2);
 await page.screenshot({path:'test-results/desktop-contacts-multi-select.png',fullPage:true,animations:'disabled'});
 await page.locator('.encrypt-selected').click();
 await expect(page.getByRole('heading',{name:'Encrypt file',level:1})).toBeVisible();
 await expect(page.getByRole('radio',{name:'Saved contacts'})).toBeChecked();
 await expect(page.locator('[name=contacts]:checked')).toHaveCount(2);
 await expect(page.locator('.recipient-heading .count')).toHaveText('2 / 64');
 await expect(page.locator('.multi-hint')).toContainText('Each selected recipient can decrypt');
 await expect(page.locator('.format')).toContainText('NKEM v5');
 await page.locator('[name=input]').fill('C:\\Local\\report.txt');await page.locator('[name=output]').fill('C:\\Local\\report.txt.nkem');
 await signWith(page);await noHorizontalOverflow(page);
 await page.locator('.primary').click();
 const request=await page.evaluate(()=>(window as unknown as {lastRequest:Record<string,unknown>}).lastRequest);
 // Selection order is kept and no other key source is sent.
 expect([request.kind,request.contacts,request.keyPath,request.keyText,request.paste]).toEqual(['encrypt',['b'.repeat(64),'a'.repeat(64)],'','',false]);
 await harness(page,'complete');
 await expect(page.locator('.notice.success')).toContainText('Recipients: Pasted public key, Alice · 工作电脑');
 await page.screenshot({path:'test-results/desktop-multi-recipient-encrypted.png',fullPage:true,animations:'disabled'});
 // One recipient shows the full fingerprint; a signed file stays NKEM v5.
 await page.locator('[name=contacts]').first().uncheck();
 await expect(page.locator('.format')).toContainText('NKEM v5');
 await expect(page.locator('.contact-preview code')).toHaveText(Array(32).fill('B2').join(':'));
});

test('upgrading a legacy contact waits for the new fingerprint check',async({page})=>{
 await page.locator('.operation-tab').nth(4).click();
 await page.locator('[name=contactKeyPath]').fill('C:\\Local\\bob-upgrade.key');
 await page.locator('.primary').click();
 const dialog=page.locator('.upgrade-confirm');
 await expect(dialog).toHaveAttribute('role','alertdialog');
 await expect(dialog).toContainText('任何拥有旧密钥的人都能给它附上自己的签名密钥');
 await expect(page.locator('.upgrade-fingerprint')).toHaveValue(Array(32).fill('E5').join(':'));
 await expect(page.locator('.upgrade-cancel')).toBeFocused();
 await expect(page.locator('.upgrade-accept')).toBeDisabled();
 expect(await page.locator('#upgrade-title').evaluate(element=>getComputedStyle(element).color)).toBe('rgb(215, 0, 21)');
 await noHorizontalOverflow(page);
 await page.screenshot({path:'test-results/desktop-contact-upgrade-confirm.png',fullPage:true,animations:'disabled'});
 await page.locator('[name=upgradeChecked]').check();
 await page.locator('.upgrade-accept').click();
 await expect(dialog).toHaveCount(0);
 await expect(page.locator('form .notice.success')).toBeVisible();
 expect(await page.locator('.contact').count()).toBe(2);
});

test('signing settings warn in red and decryption reports the signer',async({page})=>{
 await setLanguage(page,'zh-CN');
 await page.locator('.operation-tab').nth(5).click();
 await page.locator('[name=unsigned]').click();
 const dialog=page.getByRole('alertdialog');
 await expect(dialog).toContainText('接收方无法确认文件来自你');
 await expect(page.locator('.unsigned-cancel')).toBeFocused();
 await expect(page.locator('[name=unsigned]')).not.toBeChecked();
 const red=await page.locator('#unsigned-title').evaluate(element=>getComputedStyle(element).color);
 expect(red).toBe('rgb(215, 0, 21)');
 expect(await page.locator('.unsigned-accept').evaluate(element=>getComputedStyle(element).backgroundColor)).toBe(red);
 await page.screenshot({path:'test-results/desktop-unsigned-confirm.png',fullPage:true,animations:'disabled'});
 await page.locator('.unsigned-accept').click();
 await expect(page.locator('[name=unsigned]')).toBeChecked();
 await expect(page.locator('#unsigned-hint')).toHaveText('接收方无法确认文件来自你');
 expect(await page.locator('#unsigned-hint').evaluate(element=>getComputedStyle(element).color)).toBe(red);
 await page.screenshot({path:'test-results/desktop-settings-unsigned.png',fullPage:true,animations:'disabled'});
 await page.locator('.operation-tab').nth(1).click();
 await expect(page.locator('.unsigned-warning')).toContainText('接收方无法确认文件来自你');
 await expect(page.locator('[name=signKeyPath]')).toHaveCount(0);
 await page.locator('[name=keyPath]').fill('C:\\Local\\public.key');
 await page.locator('[name=input]').fill('C:\\Local\\report.txt');await page.locator('[name=output]').fill('C:\\Local\\report.txt.nkem');
 await page.locator('.primary').click();await harness(page,'complete',undefined,undefined,undefined,{format:3});
 await expect(page.locator('.notice.success strong')).toHaveText('已加密 · 未签名');
 await page.screenshot({path:'test-results/desktop-encrypt-unsigned.png',fullPage:true,animations:'disabled'});
 await page.locator('.operation-tab').nth(5).click();await page.locator('[name=unsigned]').uncheck();
 await page.locator('.operation-tab').nth(1).click();
 await expect(page.locator('[name=signKeyPath]')).toBeVisible();
 await page.screenshot({path:'test-results/desktop-encrypt-signed.png',fullPage:true,animations:'disabled'});

 await page.locator('.operation-tab').nth(2).click();
 const decrypt=async(signature:Record<string,unknown>,format:number)=>{
  await page.locator('[name=keyPath]').fill('C:\\Local\\private.key.enc');
  await page.locator('[name=input]').fill('C:\\Local\\report.txt.nkem');await page.locator('[name=output]').fill('C:\\Local\\report.txt');
  await page.locator('[name=password]').fill('public-test-password');
  await page.locator('.primary').click();await harness(page,'complete',undefined,undefined,undefined,{format,signature});
 };
 await decrypt({status:'trusted',fingerprint:Array(32).fill('A1').join(':'),contact:'a'.repeat(64)},5);
 await expect(page.locator('.notice.signature')).toContainText('签名者: Alice · 工作电脑');
 await page.screenshot({path:'test-results/desktop-decrypt-trusted.png',fullPage:true,animations:'disabled'});
 await decrypt({status:'untrusted',fingerprint:Array(32).fill('D4').join(':'),signerKey:'-----BEGIN PUBLIC KEY-----\nfixture\n-----END PUBLIC KEY-----\n'},5);
 await expect(page.locator('.save-signer')).toBeVisible();await noHorizontalOverflow(page);
 await page.screenshot({path:'test-results/desktop-decrypt-untrusted.png',fullPage:true,animations:'disabled'});
 await decrypt({status:'unsigned'},3);
 await expect(page.locator('.notice.signature.unsigned')).toContainText('未签名');
 await page.screenshot({path:'test-results/desktop-decrypt-unsigned.png',fullPage:true,animations:'disabled'});
 await page.locator('.operation-tab').nth(4).click();
 await expect(page.locator('.contact-kind').first()).toContainText('身份密钥');
 await page.screenshot({path:'test-results/desktop-contacts-identity.png',fullPage:true,animations:'disabled'});
});

test('update check offers a newer release in all five languages and fits the minimum window',async({page})=>{
 await page.setViewportSize({width:760,height:620});
 await page.evaluate(()=>Object.assign(window,{updateStatus:{status:'available',version:'5.1.0'}}));
 for(const tag of ['zh-CN','zh-TW','en','ja','ko'] as Language[]){
  await setLanguage(page,tag);
  await page.locator('.operation-tab').nth(5).click();
  await expect(page.locator('[name=autoUpdate]')).toBeChecked();
  await page.locator('.check-update').click();
  const dialog=page.getByRole('dialog');
  await expect(dialog).toContainText(translate('updateAvailableTitle',tag).replace('{version}','5.1.0'));
  await expect(page.locator('.later-update')).toBeFocused();
  await noHorizontalOverflow(page);
  await page.screenshot({path:`test-results/desktop-update-${tag}.png`,fullPage:true,animations:'disabled'});
  await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);
 }
 await page.locator('.check-update').click();await page.locator('.open-release').click();
 expect(await page.evaluate(()=>(window as unknown as {openedRelease?:string}).openedRelease)).toBe('5.1.0');
 await page.evaluate(()=>Object.assign(window,{updateStatus:{status:'current'}}));
 await page.locator('.check-update').click();
 await expect(page.locator('.update-notice')).toHaveText(translate('upToDate','ko'));
 await page.screenshot({path:'test-results/desktop-update-settings.png',fullPage:true,animations:'disabled'});
});


test('a fingerprint is shown as a QR code an Android contact can scan',async({page})=>{
 const fingerprint=Array.from({length:32},(_,index)=>((index*37+11)&255).toString(16).toUpperCase().padStart(2,'0')).join(':');
 await page.locator('.operation-tab').nth(3).click();
 await page.locator('[name=keyPath]').fill('C:\\Local\\public.key');
 await page.locator('.primary').click();
 await harness(page,'complete',fingerprint);
 await page.locator('.show-qr').click();
 const dialog=page.getByRole('dialog');
 await expect(dialog).toContainText(translate('qrTitle','zh-CN'));
 await expect(dialog.locator('.qr-fingerprint span')).toHaveText([fingerprint.slice(0,47),fingerprint.slice(48)]);
 await expect(dialog.locator('.close-qr')).toBeFocused();
 const qr=await dialog.locator('.fingerprint-qr').boundingBox();
 expect(qr!.width).toBeGreaterThanOrEqual(200);
 await noHorizontalOverflow(page);
 await page.screenshot({path:'test-results/desktop-fingerprint-qr.png',fullPage:true,animations:'disabled'});
 await page.keyboard.press('Escape');
 await expect(page.getByRole('dialog')).toHaveCount(0);
 await page.setViewportSize({width:720,height:560});
 await page.locator('.show-qr').click();
 await noHorizontalOverflow(page);
 await page.screenshot({path:'test-results/desktop-minimum-fingerprint-qr.png',fullPage:true,animations:'disabled'});
});
