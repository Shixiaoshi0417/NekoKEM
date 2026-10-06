import { expect, test, type Page } from '@playwright/test';
import { translate, type Language } from '../../src/i18n';

interface Harness {
 progress(processed:number,total:number,id?:string):void;
 complete(fingerprint?:string):void;
 fail(code:string):void;
 resolveDialog(path:string|null):void;
}
async function harness(page:Page,action:'complete'|'fail'|'progress'|'resolveDialog',value?:string|number,total?:number,id?:string){
 await page.evaluate(({action,value,total,id})=>{
  const desktop=(window as unknown as {desktop:Harness}).desktop;
  if(action==='progress')desktop.progress(value as number,total!,id);
  else if(action==='fail')desktop.fail(value as string);
  else if(action==='resolveDialog')desktop.resolveDialog(value as string|null);
  else desktop.complete(value as string|undefined);
 },{action,value,total,id});
}
async function fillEncryption(page:Page){
 await page.locator('[name=keyPath]').fill('C:\\Local\\public.key');
 await page.locator('[name=input]').fill('C:\\Local\\report.txt');
 await page.locator('[name=output]').fill('C:\\Local\\report.txt.nkem');
 await page.locator('.primary').click();
}
async function noHorizontalOverflow(page:Page){
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
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
   {id:'a'.repeat(64),fingerprint:fingerprint('A1'),name:'alice-public.key',note:'Alice · 工作电脑'},
   {id:'b'.repeat(64),fingerprint:fingerprint('B2'),name:'',note:''},
  ];
  Object.assign(window,{__TAURI_INTERNALS__:{
   transformCallback(callback:(value:unknown)=>void){const id=next++;callbacks[id]=callback;return id;},
   unregisterCallback(id:number){delete callbacks[id];},
   invoke(command:string,args:Record<string,unknown>){
    if(command==='get_settings')return Promise.resolve({language:'zh-CN',selection:'system',version:'4.0.0'});
    if(command==='set_language')return Promise.resolve(args.language==='system'?'zh-CN':args.language);
    if(command==='plugin:event|listen'){progressHandler=args.handler as number;return Promise.resolve(1);}
    if(command==='plugin:event|unlisten')return Promise.resolve(null);
    if(command==='run_operation'){
     const value=args.request as {id:string;output:string};request={id:value.id,output:value.output};
     Object.assign(window,{lastRequest:value});
     return new Promise((resolve,reject)=>{finish=resolve;fail=reject;});
    }
    if(command==='cancel_operation')return Promise.resolve(true);
    if(command==='list_contacts')return Promise.resolve({contacts:contacts.map(contact=>({...contact})),unreadable:0});
    if(command==='save_contact'){
     const value=args.contact as {keyPath:string;note:string};
     const contact={id:'c'.repeat(64),fingerprint:fingerprint('C3'),name:value.keyPath.split(/[\\/]/).pop()!,note:value.note.trim()};
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
   complete(fingerprint?:string){finish?.({output:request?.output??null,fingerprint:fingerprint??null});},
   fail(code:string){fail?.({code});},
   resolveDialog(path:string|null){choose?.(path);},
  }});
 });
 await page.goto('/');await expect(page.getByRole('heading',{name:'加密文件',exact:true})).toBeVisible();
});

test('five languages across all five page layouts',async({page})=>{
 for(const tag of ['zh-CN','zh-TW','en','ja','ko'] as Language[]){
  await page.locator('header select').selectOption(tag);
  for(const [index,operation] of ['keygen','encrypt','decrypt','fingerprint','contacts'].entries()){
   await page.locator('.operation-tab').nth(index).click();
   await expect(page.getByRole('heading',{name:translate(operation as 'keygen'|'encrypt'|'decrypt'|'fingerprint'|'contacts',tag),exact:true,level:1})).toBeVisible();
   await noHorizontalOverflow(page);
  }
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
  await page.locator('header select').selectOption(tag);
  for(let index=0;index<5;index++){
   await page.locator('.operation-tab').nth(index).click();await noHorizontalOverflow(page);
   if(index>0){await page.getByRole('radio').nth(1).check();await noHorizontalOverflow(page);}
  }
  await page.locator('.edit-note').first().click();await noHorizontalOverflow(page);
  await page.locator('.contact-actions.editing .text-button').click();
  await page.locator('.delete-contact').first().click();await noHorizontalOverflow(page);
  await page.locator('.contact-actions.confirming .text-button').click();
  await page.locator('.operation-tab').nth(1).click();await page.getByRole('radio').nth(2).check();
  await page.locator('[name=contacts]').first().check();
  await expect(page.locator('.contact-preview code')).toBeVisible();await noHorizontalOverflow(page);
  await page.locator('[name=contacts]').nth(1).check();
  await expect(page.locator('.multi-hint')).toBeVisible();await noHorizontalOverflow(page);
  await page.locator('.operation-tab').nth(4).click();
  await expect(page.locator('.selection-bar')).toBeVisible();await noHorizontalOverflow(page);
  await page.locator('.clear-selection').click();await expect(page.locator('.selection-bar')).toHaveCount(0);
 }
 await page.screenshot({path:'test-results/desktop-minimum-contact-selected.png',fullPage:true,animations:'disabled'});
 await page.locator('header select').selectOption('zh-CN');await page.locator('.operation-tab').nth(2).click();
 await page.screenshot({path:'test-results/desktop-minimum-decrypt.png',fullPage:true,animations:'disabled'});
});

test('keyboard navigation and error focus remain visible',async({page})=>{
 await page.locator('header select').selectOption('en');
 const first=page.locator('.operation-tab').first();await first.focus();
 await first.press('ArrowDown');await expect(page.locator('.operation-tab').nth(1)).toBeFocused();
 await page.keyboard.press('End');await expect(page.locator('.operation-tab').nth(4)).toBeFocused();
 await expect(page.getByRole('heading',{name:'Public-key contacts',level:1})).toBeVisible();
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
 await expect(page.locator('.notice.success')).toContainText('操作完成');
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
 await page.locator('header select').selectOption('en');
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
 await page.locator('.primary').click();
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
 await page.locator('.primary').click();
 await expect(page.getByRole('alert')).toHaveText('Choose at least one saved contact.');
 await expect(page.locator('[name=contacts]').first()).toBeFocused();
});

test('several contacts selected on the contacts page share one encrypted file',async({page})=>{
 await page.locator('header select').selectOption('en');
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
 await expect(page.locator('.format')).toContainText('NKEM v4');
 await page.locator('[name=input]').fill('C:\\Local\\report.txt');await page.locator('[name=output]').fill('C:\\Local\\report.txt.nkem');
 await noHorizontalOverflow(page);
 await page.locator('.primary').click();
 const request=await page.evaluate(()=>(window as unknown as {lastRequest:Record<string,unknown>}).lastRequest);
 // Selection order is kept and no other key source is sent.
 expect([request.kind,request.contacts,request.keyPath,request.keyText,request.paste]).toEqual(['encrypt',['b'.repeat(64),'a'.repeat(64)],'','',false]);
 await harness(page,'complete');
 await expect(page.locator('.notice.success')).toContainText('Recipients: Pasted public key, Alice · 工作电脑');
 await page.screenshot({path:'test-results/desktop-multi-recipient-encrypted.png',fullPage:true,animations:'disabled'});
 // Dropping to one recipient returns to NKEM v3 and shows the full fingerprint.
 await page.locator('[name=contacts]').first().uncheck();
 await expect(page.locator('.format')).toContainText('NKEM v3');
 await expect(page.locator('.contact-preview code')).toHaveText(Array(32).fill('B2').join(':'));
});
