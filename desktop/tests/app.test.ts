import { mount,flushPromises,enableAutoUnmount } from '@vue/test-utils';
import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest';
import App from '../src/App.vue';
import * as bridge from '../src/bridge';
import { catalog, translate } from '../src/i18n';
vi.mock('../src/bridge',()=>({getSettings:vi.fn(),setLanguage:vi.fn(),runOperation:vi.fn(),cancelOperation:vi.fn(),closeApp:vi.fn(),onProgress:vi.fn(),chooseOpen:vi.fn(),chooseSave:vi.fn(),listContacts:vi.fn(),saveContact:vi.fn(),updateContactNote:vi.fn(),deleteContact:vi.fn(),MAX_RECIPIENTS:64}));
enableAutoUnmount(afterEach);
beforeEach(()=>{vi.clearAllMocks();vi.mocked(bridge.getSettings).mockResolvedValue({language:'en',selection:'system',version:'4.0.1'});vi.mocked(bridge.onProgress).mockResolvedValue(()=>{});vi.mocked(bridge.runOperation).mockResolvedValue({output:'output.nkem',fingerprint:null,recipients:[]});vi.mocked(bridge.chooseOpen).mockResolvedValue(null);vi.mocked(bridge.chooseSave).mockResolvedValue(null);vi.mocked(bridge.cancelOperation).mockResolvedValue(true);vi.mocked(bridge.listContacts).mockResolvedValue({contacts:[],unreadable:0});});
async function ready(){const app=mount(App,{attachTo:document.body});await flushPromises();return app;}
describe('Desktop operation boundaries',()=>{
 it('rejects mismatched keygen passwords before invoking native Core',async()=>{const app=await ready();await app.findAll('nav button')[0]!.trigger('click');await app.get('[name=publicPath]').setValue('public.key');await app.get('[name=privatePath]').setValue('private.key.enc');await app.get('[name=password]').setValue('secret-one');await app.get('[name=confirmation]').setValue('secret-two');await app.get('form').trigger('submit');expect(bridge.runOperation).not.toHaveBeenCalled();expect(app.get('[role=alert]').text()).toBe('Passwords do not match.');});
 it('offers key replacement only after Core reports existing keys, for one confirmed run',async()=>{
  const app=await ready();await app.findAll('nav button')[0]!.trigger('click');
  const fill=async()=>{await app.get('[name=publicPath]').setValue('public.key');await app.get('[name=privatePath]').setValue('private.key.enc');await app.get('[name=password]').setValue('secret');await app.get('[name=confirmation]').setValue('secret');};
  const calls=()=>vi.mocked(bridge.runOperation).mock.calls.map(call=>call[0].replace);
  expect(app.find('[name=replace]').exists()).toBe(false);
  vi.mocked(bridge.runOperation).mockRejectedValueOnce({code:'key-exists'});
  await fill();await app.get('form').trigger('submit');await flushPromises();
  expect(calls()).toEqual([false]);
  expect(app.get('[role=alert]').text()).toContain('left unchanged');
  const box=app.get<HTMLInputElement>('[name=replace]');expect(box.element.checked).toBe(false);
  expect(app.get('button.primary').text()).toContain('Generate keys');
  await app.get('[name=password]').setValue('secret');await app.get('[name=confirmation]').setValue('secret');
  await box.setValue(true);
  expect(app.get('button.primary').text()).toContain('Replace keys');
  await app.get('form').trigger('submit');await flushPromises();
  expect(calls()).toEqual([false,true]);
  // The confirmation covers one run only.
  expect(app.find('[name=replace]').exists()).toBe(false);
  vi.mocked(bridge.runOperation).mockRejectedValueOnce({code:'key-exists'});
  await fill();await app.get('form').trigger('submit');await flushPromises();
  expect(app.find('[name=replace]').exists()).toBe(true);
  // Other paths withdraw the offer, and so does another page.
  await app.get('[name=publicPath]').setValue('other.key');await flushPromises();
  expect(app.find('[name=replace]').exists()).toBe(false);
  vi.mocked(bridge.runOperation).mockRejectedValueOnce({code:'key-exists'});
  await fill();await app.get('form').trigger('submit');await flushPromises();
  await app.findAll('nav button')[1]!.trigger('click');await app.findAll('nav button')[0]!.trigger('click');
  expect(app.find('[name=replace]').exists()).toBe(false);
  expect(calls()).toEqual([false,true,false,false]);
 });
 it('clears password fields immediately and preserves cancellation failure semantics',async()=>{const app=await ready();await app.findAll('nav button')[2]!.trigger('click');await app.get('[name=keyPath]').setValue('private.key.enc');await app.get('[name=input]').setValue('input.nkem');await app.get('[name=output]').setValue('output');await app.get('[name=password]').setValue('public-test-password');vi.mocked(bridge.runOperation).mockRejectedValue({code:'cancelled'});await app.get('form').trigger('submit');expect((app.get('[name=password]').element as HTMLInputElement).value).toBe('');await flushPromises();expect(app.get('[role=alert]').text()).toContain('no new output');expect(app.text()).not.toContain('public-test-password');});
 it('rejects passwords exceeding the UTF-8 byte limit',async()=>{const app=await ready();await app.findAll('nav button')[0]!.trigger('click');await app.get('[name=publicPath]').setValue('public.key');await app.get('[name=privatePath]').setValue('private.key.enc');await app.get('[name=password]').setValue('中'.repeat(400));await app.get('[name=confirmation]').setValue('中'.repeat(400));await app.get('form').trigger('submit');expect(bridge.runOperation).not.toHaveBeenCalled();expect(app.get('[role=alert]').text()).toContain('1024 UTF-8 bytes');});
 it('locks navigation and ignores stale progress events during a job',async()=>{let callback:(value:bridge.Progress)=>void=()=>{};vi.mocked(bridge.onProgress).mockImplementation(async fn=>{callback=fn;return()=>{};});let finish:(result:bridge.Outcome)=>void=()=>{};vi.mocked(bridge.runOperation).mockReturnValue(new Promise(resolve=>{finish=resolve;}));const app=await ready();await app.get('[name=keyPath]').setValue('public.key');await app.get('[name=input]').setValue('plain');await app.get('[name=output]').setValue('out.nkem');await app.get('form').trigger('submit');const request=vi.mocked(bridge.runOperation).mock.calls[0]![0];callback({id:'stale-job',processed:50,total:100});await flushPromises();expect(app.text()).not.toContain('50%');expect(app.findAll('nav button').every(button=>button.attributes('disabled')!==undefined)).toBe(true);callback({id:request.id,processed:50,total:100});await flushPromises();expect(app.text()).toContain('50%');finish({output:'out.nkem',fingerprint:null});await flushPromises();expect(app.text()).toContain('Completed');});
 it('keeps every desktop message complete in all five languages',()=>{for(const [key,values] of Object.entries(catalog)){expect(values,key).toHaveLength(5);const placeholders=values.map(value=>(value.match(/\{\w+\}/g)??[]).sort().join());for(const value of values){expect(value.trim(),key).not.toBe('');}expect(new Set(placeholders).size,key).toBe(1);}});
 it('has a complete label for every supported language',()=>{for(const language of ['en','zh-CN','zh-TW','ja','ko'] as const){for(const key of ['keygen','encrypt','decrypt','fingerprint','exit','cancelled','core-error','navigation','keySource','loading','choosing','contacts','contactsHelp','savedContact','recipient','recipients','selectContact','selectedCount','encryptSelected','clearSelection','multiRecipientHint','recipient-limit','noContacts','note','noteHint','saveContact','useContact','editNote','saveNote','deleteContact','confirmDelete','deletePrompt','contactSaved','noteSaved','contactDeleted','contact-required','contact-missing','contact-invalid','contact-mismatch','contact-storage','contact-exists','contact-limit','public-key-invalid','note-limit','invalid-request','key-exists','output-is-key','replaceKeys','replaceKeysHint','replaceKeysAction'] as const)expect(translate(key,language).length).toBeGreaterThan(0);}});
});

describe('Desktop interaction and secret lifetime',()=>{
 it('focuses the missing field and clears its error when corrected',async()=>{
  const app=await ready();
  await app.get('form').trigger('submit');await flushPromises();
  const input=app.get('[name=keyPath]');
  expect(document.activeElement).toBe(input.element);
  expect(input.attributes('aria-invalid')).toBe('true');
  expect(input.attributes('aria-describedby')).toBe('operation-error');
  expect(bridge.runOperation).not.toHaveBeenCalled();
  await input.setValue('public.key');
  expect(app.find('[role=alert]').exists()).toBe(false);
  expect(input.attributes('aria-invalid')).toBeUndefined();
 });
 it('supports keyboard navigation without starting an operation',async()=>{
  const app=await ready();const buttons=app.findAll('.operation-tab');
  await buttons[1]!.trigger('keydown',{key:'ArrowDown'});
  expect(app.get('h1').text()).toBe('Decrypt file');
  expect(document.activeElement).toBe(buttons[2]!.element);
  expect(buttons[2]!.attributes('aria-current')).toBe('page');
  await buttons[2]!.trigger('keydown',{key:'Home'});
  expect(app.get('h1').text()).toBe('Generate keys');
  await buttons[0]!.trigger('keydown',{key:'End'});
  expect(app.get('h1').text()).toBe('Public-key contacts');
  expect(document.activeElement).toBe(buttons[4]!.element);
  await buttons[4]!.trigger('keydown',{key:'ArrowUp'});
  expect(app.get('h1').text()).toBe('Public key fingerprint');
  await buttons[3]!.trigger('keydown',{key:'ArrowDown'});
  expect(app.get('h1').text()).toBe('Public-key contacts');
  await buttons[4]!.trigger('keydown',{key:'ArrowDown'});
  expect(app.get('h1').text()).toBe('Generate keys');
  expect(bridge.runOperation).not.toHaveBeenCalled();
 });
 it('preserves the active form on a repeated click and scrubs removed secret nodes on a real switch',async()=>{
  const app=await ready();await app.findAll('.operation-tab')[2]!.trigger('click');
  await app.findAll('[name=keySource]')[1]!.setValue(true);
  await app.get('[name=keyText]').setValue('private-pem-test-fixture');
  await app.get('[name=password]').setValue('test-password');
  await app.get('[name=output]').setValue('output');
  const password=app.get('[name=password]').element as HTMLInputElement;
  const key=app.get('[name=keyText]').element as HTMLTextAreaElement;
  await app.findAll('.operation-tab')[2]!.trigger('click');
  expect(password.value).toBe('test-password');expect(key.value).toBe('private-pem-test-fixture');
  expect((app.get('[name=output]').element as HTMLInputElement).value).toBe('output');
  await app.findAll('.operation-tab')[0]!.trigger('click');
  expect(password.value).toBe('');expect(key.value).toBe('');
  expect(password.isConnected).toBe(false);expect(key.isConnected).toBe(false);
  expect(app.findAll('.form-content')).toHaveLength(1);
  expect(app.html()).not.toContain('private-pem-test-fixture');
 });
 it('discards pasted private-key text when its source is hidden',async()=>{
  const app=await ready();await app.findAll('.operation-tab')[2]!.trigger('click');
  await app.findAll('[name=keySource]')[1]!.setValue(true);
  await app.get('[name=keyText]').setValue('private-pem-test-fixture');
  const key=app.get('[name=keyText]').element as HTMLTextAreaElement;
  await app.findAll('[name=keySource]')[0]!.setValue(true);
  expect(key.value).toBe('');expect(key.isConnected).toBe(false);
  await app.findAll('[name=keySource]')[1]!.setValue(true);
  expect((app.get('[name=keyText]').element as HTMLTextAreaElement).value).toBe('');
 });
 it('scrubs visible secrets before the native invoke and releases request secrets after completion',async()=>{
  const app=await ready();await app.findAll('.operation-tab')[2]!.trigger('click');
  await app.findAll('[name=keySource]')[1]!.setValue(true);
  await app.get('[name=keyText]').setValue('private-pem-test-fixture');
  await app.get('[name=password]').setValue('test-password');
  await app.get('[name=input]').setValue('input.nkem');await app.get('[name=output]').setValue('output');
  const password=app.get('[name=password]').element as HTMLInputElement;
  const key=app.get('[name=keyText]').element as HTMLTextAreaElement;
  let finish!:(value:bridge.Outcome)=>void;
  vi.mocked(bridge.runOperation).mockImplementation(request=>{
   expect(password.value).toBe('');expect(key.value).toBe('');
   expect(request.password).toBe('test-password');expect(request.keyText).toBe('private-pem-test-fixture');
   return new Promise(resolve=>{finish=resolve;});
  });
  await app.get('form').trigger('submit');await app.get('form').trigger('submit');
  expect(bridge.runOperation).toHaveBeenCalledTimes(1);
  const request=vi.mocked(bridge.runOperation).mock.calls[0]![0];
  finish({output:'output',fingerprint:null});await flushPromises();
  expect(request.password).toBe('');expect(request.keyText).toBe('');expect(request.confirmation).toBe('');
 });
 it('locks navigation and submission while a native path dialog is open',async()=>{
  let choose!:(path:string|null)=>void;
  vi.mocked(bridge.chooseOpen).mockReturnValue(new Promise(resolve=>{choose=resolve;}));
  const app=await ready();await app.get('.path-input button').trigger('click');
  expect(app.findAll('nav button').every(button=>button.attributes('disabled')!==undefined)).toBe(true);
  expect(app.get('.primary').attributes('disabled')).toBeDefined();
  await app.findAll('.operation-tab')[1]!.trigger('keydown',{key:'ArrowDown'});
  await app.get('form').trigger('submit');
  expect(app.get('h1').text()).toBe('Encrypt file');expect(bridge.runOperation).not.toHaveBeenCalled();
  choose('chosen-public.key');await flushPromises();
  expect((app.get('[name=keyPath]').element as HTMLInputElement).value).toBe('chosen-public.key');
  expect(app.get('.primary').attributes('disabled')).toBeUndefined();
 });
 it('prevents duplicate cancellation requests and waits for native cleanup',async()=>{
  let fail!:(error:{code:string})=>void,accept!:(value:boolean)=>void;
  vi.mocked(bridge.runOperation).mockReturnValue(new Promise((_resolve,reject)=>{fail=reject;}));
  vi.mocked(bridge.cancelOperation).mockReturnValue(new Promise(resolve=>{accept=resolve;}));
  const app=await ready();
  await app.get('[name=keyPath]').setValue('public.key');await app.get('[name=input]').setValue('plain');await app.get('[name=output]').setValue('output.nkem');
  await app.get('form').trigger('submit');await app.get('.secondary').trigger('click');
  await app.get('.secondary').trigger('click');
  expect(bridge.cancelOperation).toHaveBeenCalledTimes(1);
  expect(app.get('.secondary').attributes('disabled')).toBeDefined();
  accept(true);await flushPromises();
  expect(app.text()).toContain('Cancelling safely');
  expect(app.get('.primary').attributes('disabled')).toBeDefined();
  fail({code:'cancelled'});await flushPromises();
  expect(app.get('[role=alert]').text()).toContain('no new output was committed');
  expect(app.find('[role=progressbar]').exists()).toBe(false);
  expect(app.get('.primary').attributes('disabled')).toBeUndefined();
 });
 it('scrubs secret DOM values on unmount',async()=>{
  const app=await ready();await app.findAll('.operation-tab')[0]!.trigger('click');
  await app.get('[name=password]').setValue('test-password');await app.get('[name=confirmation]').setValue('test-password');
  const password=app.get('[name=password]').element as HTMLInputElement;
  const confirmation=app.get('[name=confirmation]').element as HTMLInputElement;
  app.unmount();expect(password.value).toBe('');expect(confirmation.value).toBe('');
 });
});

const alice:bridge.Contact={id:'a'.repeat(64),fingerprint:Array(32).fill('AA').join(':'),name:'alice.pub',note:'Alice'};
const bob:bridge.Contact={id:'b'.repeat(64),fingerprint:Array(32).fill('BB').join(':'),name:'bob.pub',note:''};
async function contactsPage(app:Awaited<ReturnType<typeof ready>>){await app.findAll('.operation-tab')[4]!.trigger('click');await flushPromises();}
async function chooseContactSource(app:Awaited<ReturnType<typeof ready>>){await app.findAll('[name=keySource]')[2]!.setValue(true);}

describe('Desktop public-key contacts',()=>{
 it('saves a public key with a trimmed note, edits the note and deletes only after confirmation',async()=>{
  let saved:bridge.Contact[]=[];
  vi.mocked(bridge.listContacts).mockImplementation(async()=>({contacts:[...saved],unreadable:0}));
  vi.mocked(bridge.saveContact).mockImplementation(async request=>{saved=[{...alice,note:request.note.trim()}];return saved[0]!;});
  vi.mocked(bridge.updateContactNote).mockImplementation(async(id,note)=>{saved=[{...saved[0]!,note}];return saved[0]!;});
  vi.mocked(bridge.deleteContact).mockImplementation(async()=>{saved=[];});
  const app=await ready();await contactsPage(app);
  expect(app.get('.contact-list').text()).toContain('No public keys are saved yet.');
  await app.get('[name=contactKeyPath]').setValue('alice.pub');await app.get('[name=note]').setValue('  Alice  ');
  await app.get('form').trigger('submit');await flushPromises();
  expect(bridge.saveContact).toHaveBeenCalledWith({keyPath:'alice.pub',keyText:'',paste:false,note:'  Alice  '});
  expect(app.get('form .notice.success').text()).toContain('Public key saved');
  expect((app.get('form .fingerprint').element as HTMLTextAreaElement).value).toBe(alice.fingerprint);
  expect((app.get('[name=contactKeyPath]').element as HTMLInputElement).value).toBe('');
  expect(app.get('.contact.highlighted .contact-label').text()).toBe('Alice');
  expect(app.get('.contact-fingerprint').text()).toBe(alice.fingerprint);
  expect(app.get('.contact-source').text()).toContain('alice.pub');

  await app.get('.edit-note').trigger('click');await flushPromises();
  const draft=app.get('[name=noteDraft]');
  expect((draft.element as HTMLInputElement).value).toBe('Alice');expect(document.activeElement).toBe(draft.element);
  await draft.setValue('Alice laptop');await app.get('.contact-actions.editing .secondary').trigger('click');await flushPromises();
  expect(bridge.updateContactNote).toHaveBeenCalledWith(alice.id,'Alice laptop');
  expect(app.get('.contact-label').text()).toBe('Alice laptop');
  expect(app.get('.contact-list .notice.success').text()).toContain('Note saved');

  await app.get('.delete-contact').trigger('click');
  expect(bridge.deleteContact).not.toHaveBeenCalled();
  expect(app.get('.contact-actions.confirming').text()).toContain('Files already encrypted are not affected');
  await app.get('.contact-actions.confirming .text-button').trigger('click');
  expect(app.find('.contact-actions.confirming').exists()).toBe(false);
  await app.get('.delete-contact').trigger('click');await app.get('.danger').trigger('click');await flushPromises();
  expect(bridge.deleteContact).toHaveBeenCalledWith(alice.id);
  expect(app.findAll('.contact')).toHaveLength(0);
  expect(app.get('.contact-list .notice.success').text()).toContain('Contact deleted');
 });
 it('validates notes and key sources before invoking native code',async()=>{
  const app=await ready();await contactsPage(app);
  await app.get('form').trigger('submit');await flushPromises();
  expect(document.activeElement).toBe(app.get('[name=contactKeyPath]').element);
  await app.get('[name=contactKeyPath]').setValue('alice.pub');await app.get('[name=note]').setValue('😀'.repeat(513));
  await app.get('form').trigger('submit');await flushPromises();
  expect(app.get('[role=alert]').text()).toContain('512 characters');
  expect(app.get('[name=note]').attributes('aria-invalid')).toBe('true');
  expect(bridge.saveContact).not.toHaveBeenCalled();
  // The limit counts code points: 512 emoji are accepted, and no UTF-16 maxlength cuts them.
  expect(app.get('[name=note]').attributes('maxlength')).toBeUndefined();
  vi.mocked(bridge.saveContact).mockResolvedValue({...alice,note:'😀'.repeat(512)});
  await app.get('[name=note]').setValue('😀'.repeat(512));
  await app.get('form').trigger('submit');await flushPromises();
  expect(bridge.saveContact).toHaveBeenCalledWith({keyPath:'alice.pub',keyText:'',paste:false,note:'😀'.repeat(512)});
 });
 it('scrubs pasted contact text before the native call and when its source is hidden',async()=>{
  const app=await ready();await contactsPage(app);
  await app.findAll('[name=contactKeySource]')[1]!.setValue(true);
  await app.get('[name=contactKeyText]').setValue('-----BEGIN PUBLIC KEY-----fixture');
  const text=app.get('[name=contactKeyText]').element as HTMLTextAreaElement;
  await app.findAll('[name=contactKeySource]')[0]!.setValue(true);
  expect(text.value).toBe('');expect(text.isConnected).toBe(false);
  await app.findAll('[name=contactKeySource]')[1]!.setValue(true);
  const pasted=app.get('[name=contactKeyText]');await pasted.setValue('-----BEGIN PUBLIC KEY-----fixture');
  vi.mocked(bridge.saveContact).mockImplementation(async request=>{
   expect((pasted.element as HTMLTextAreaElement).value).toBe('');
   expect(request).toEqual({keyPath:'',keyText:'-----BEGIN PUBLIC KEY-----fixture',paste:true,note:''});
   throw {code:'public-key-invalid'};
  });
  await app.get('form').trigger('submit');await flushPromises();
  expect(app.get('[role=alert]').text()).toContain('Not a valid NekoKEM public key');
  expect(app.get('[name=contactKeyText]').attributes('aria-invalid')).toBe('true');
  expect(vi.mocked(bridge.saveContact).mock.calls[0]![0].keyText).toBe('');
  expect(app.html()).not.toContain('fixture');
 });
 it('points a duplicate import at the existing contact instead of replacing it',async()=>{
  vi.mocked(bridge.listContacts).mockResolvedValue({contacts:[alice,bob],unreadable:0});
  vi.mocked(bridge.saveContact).mockRejectedValue({code:'contact-exists',contact:bob.id});
  const app=await ready();await contactsPage(app);
  await app.get('[name=contactKeyPath]').setValue('bob-copy.pub');await app.get('form').trigger('submit');await flushPromises();
  expect(app.get('[role=alert]').text()).toContain('already saved');
  expect(app.get('.contact.highlighted').attributes('data-contact')).toBe(bob.id);
  expect(app.get('.contact.highlighted .contact-label').text()).toBe('bob.pub');
 });
 it('encrypts only for explicitly selected contacts without sending another key source',async()=>{
  vi.mocked(bridge.listContacts).mockResolvedValue({contacts:[alice,bob],unreadable:0});
  vi.mocked(bridge.runOperation).mockResolvedValue({output:'out.nkem',fingerprint:bob.fingerprint,recipients:[bob.fingerprint]});
  const app=await ready();
  await app.get('[name=keyPath]').setValue('default-public.key');
  await chooseContactSource(app);
  expect(app.find('[name=keyPath]').exists()).toBe(false);
  await app.get('[name=input]').setValue('plain');await app.get('[name=output]').setValue('out.nkem');
  await app.get('form').trigger('submit');await flushPromises();
  expect(bridge.runOperation).not.toHaveBeenCalled();
  expect(app.get('[role=alert]').text()).toBe('Choose at least one saved contact.');
  const boxes=app.findAll('[name=contacts]');
  expect(document.activeElement).toBe(boxes[0]!.element);expect(app.get('.recipient-options').attributes('aria-invalid')).toBe('true');
  expect(app.findAll('.recipient-label').map(item=>item.text())).toEqual(['Alice','bob.pub']);
  expect(app.findAll('.recipient-option code').map(item=>item.text())).toEqual(['AA:AA:AA:AA…','BB:BB:BB:BB…']);
  await boxes[1]!.setValue(true);
  expect(app.find('[role=alert]').exists()).toBe(false);
  expect(app.get('.contact-preview code').text()).toBe(bob.fingerprint);
  expect(app.get('.format').text()).toContain('NKEM v3');
  await app.get('form').trigger('submit');await flushPromises();
  const request=vi.mocked(bridge.runOperation).mock.calls[0]![0];
  expect([request.kind,request.contacts,request.keyPath,request.keyText,request.paste]).toEqual(['encrypt',[bob.id],'','',false]);
  expect(app.get('.notice.success').text()).toContain('Recipient: bob.pub');
  expect((app.get('.notice.success .fingerprint').element as HTMLTextAreaElement).value).toBe(bob.fingerprint);
 });
 it('reports a missing or damaged contact, names it and requires a new explicit choice',async()=>{
  vi.mocked(bridge.listContacts).mockResolvedValue({contacts:[alice,bob],unreadable:0});
  vi.mocked(bridge.runOperation).mockRejectedValueOnce({code:'contact-invalid',contact:alice.id});
  const app=await ready();await chooseContactSource(app);
  await app.findAll('[name=contacts]')[0]!.setValue(true);await app.get('[name=input]').setValue('plain');await app.get('[name=output]').setValue('out.nkem');
  await app.get('form').trigger('submit');await flushPromises();
  expect(app.get('[role=alert]').text()).toContain('damaged or has unsafe permissions');
  expect(app.get('.failed-contact').text()).toBe('Recipient: Alice · AA:AA:AA:AA…');
  expect(app.findAll('[name=contacts]').map(box=>(box.element as HTMLInputElement).checked)).toEqual([false,false]);
  expect(app.get('.recipient-options').attributes('aria-invalid')).toBe('true');
  expect(bridge.listContacts).toHaveBeenCalledTimes(2);
  await app.get('form').trigger('submit');await flushPromises();
  expect(bridge.runOperation).toHaveBeenCalledTimes(1);
  expect(app.get('[role=alert]').text()).toBe('Choose at least one saved contact.');

  // A contact deleted elsewhere disappears from the next refresh; it is never replaced.
  await app.findAll('[name=contacts]')[0]!.setValue(true);
  vi.mocked(bridge.listContacts).mockResolvedValue({contacts:[bob],unreadable:0});
  await app.findAll('.operation-tab')[0]!.trigger('click');await app.findAll('.operation-tab')[1]!.trigger('click');await flushPromises();
  expect(app.get('[role=alert]').text()).toContain('no longer exists');
  expect(app.findAll('[name=contacts]').map(box=>(box.element as HTMLInputElement).checked)).toEqual([false]);
  expect(bridge.runOperation).toHaveBeenCalledTimes(1);
 });
 it('selects several contacts on the contacts page and encrypts one file that each of them decrypts',async()=>{
  const carol:bridge.Contact={id:'c'.repeat(64),fingerprint:Array(32).fill('CC').join(':'),name:'carol.pub',note:''};
  vi.mocked(bridge.listContacts).mockResolvedValue({contacts:[alice,bob,carol],unreadable:0});
  vi.mocked(bridge.runOperation).mockResolvedValue({output:'out.nkem',fingerprint:null,recipients:[carol.fingerprint,alice.fingerprint]});
  const app=await ready();await contactsPage(app);
  expect(app.find('.selection-bar').exists()).toBe(false);
  const select=(index:number)=>app.findAll('.contact-select input')[index]!.setValue(true);
  await select(2);await select(0);
  expect(app.get('.selection-count').text()).toBe('2 selected');
  expect(app.findAll('.contact.selected').map(item=>item.attributes('data-contact'))).toEqual([alice.id,carol.id]);
  expect(app.get('.contact-select input').attributes('aria-label')).toBe('Select · Alice');
  await app.get('.encrypt-selected').trigger('click');await flushPromises();
  expect(app.get('h1').text()).toBe('Encrypt file');
  expect((app.findAll('[name=keySource]')[2]!.element as HTMLInputElement).checked).toBe(true);
  expect(app.findAll('[name=contacts]').map(box=>(box.element as HTMLInputElement).checked)).toEqual([true,false,true]);
  expect(app.get('.recipient-heading .count').text()).toBe('2 / 64');
  expect(app.get('.multi-hint').text()).toContain('Each selected recipient can decrypt');
  expect(app.find('.contact-preview').exists()).toBe(false);
  expect(app.get('.format').text()).toContain('NKEM v4');
  await app.get('[name=input]').setValue('plain');await app.get('[name=output]').setValue('out.nkem');
  await app.get('form').trigger('submit');await flushPromises();
  const request=vi.mocked(bridge.runOperation).mock.calls[0]![0];
  // Selection order is kept; no other key source is sent.
  expect([request.kind,request.contacts,request.keyPath,request.keyText,request.paste]).toEqual(['encrypt',[carol.id,alice.id],'','',false]);
  expect(app.get('.notice.success').text()).toContain('Recipients: carol.pub, Alice');
  expect(app.find('.notice.success .fingerprint').exists()).toBe(false);

  // A failure names the contact, deselects only it and encrypts nothing.
  vi.mocked(bridge.runOperation).mockRejectedValueOnce({code:'contact-missing',contact:carol.id});
  await app.get('form').trigger('submit');await flushPromises();
  expect(app.get('[role=alert]').text()).toContain('nothing was encrypted');
  expect(app.get('.failed-contact').text()).toContain('carol.pub');
  expect(app.findAll('[name=contacts]').map(box=>(box.element as HTMLInputElement).checked)).toEqual([true,false,false]);
  expect(bridge.runOperation).toHaveBeenCalledTimes(2);
  expect(app.get('.format').text()).toContain('NKEM v3');

  await contactsPage(app);
  expect(app.get('.selection-count').text()).toBe('1 selected');
  await app.get('.clear-selection').trigger('click');
  expect(app.find('.selection-bar').exists()).toBe(false);
  expect(app.findAll('.contact.selected')).toHaveLength(0);
 });
 it('caps the selection at 64 recipients',async()=>{
  const many:bridge.Contact[]=Array.from({length:65},(_,index)=>({id:index.toString(16).padStart(64,'0'),fingerprint:Array(32).fill('DD').join(':'),name:`key-${index}.pub`,note:''}));
  vi.mocked(bridge.listContacts).mockResolvedValue({contacts:many,unreadable:0});
  const app=await ready();await contactsPage(app);
  const boxes=app.findAll('.contact-select input');
  for(const box of boxes.slice(0,64))await box.setValue(true);
  expect(app.get('.selection-count').text()).toBe('64 selected');
  expect(app.get('.selection-limit').text()).toBe('Choose at most 64 recipients.');
  expect(boxes[64]!.attributes('disabled')).toBeDefined();
  expect(boxes[0]!.attributes('disabled')).toBeUndefined();
  await app.get('.encrypt-selected').trigger('click');await flushPromises();
  expect(app.findAll('[name=contacts]').filter(box=>(box.element as HTMLInputElement).checked)).toHaveLength(64);
  expect(app.findAll('[name=contacts]')[64]!.attributes('disabled')).toBeDefined();
  await app.findAll('[name=contacts]')[0]!.setValue(false);
  expect(app.findAll('[name=contacts]')[64]!.attributes('disabled')).toBeUndefined();
 });
 it('opens encryption from a contact and keeps the choice per operation',async()=>{
  vi.mocked(bridge.listContacts).mockResolvedValue({contacts:[alice,bob],unreadable:2});
  const app=await ready();await contactsPage(app);
  expect(app.get('.contact-list').text()).toContain('2 saved entries could not be read');
  expect(app.findAll('.contact-label').map(item=>item.text())).toEqual(['Alice','bob.pub']);
  // "Use for encryption" chooses exactly that one contact.
  await app.findAll('.contact-select input')[1]!.setValue(true);
  await app.findAll('.use-contact')[0]!.trigger('click');await flushPromises();
  expect(app.get('h1').text()).toBe('Encrypt file');
  expect((app.findAll('[name=keySource]')[2]!.element as HTMLInputElement).checked).toBe(true);
  expect(app.findAll('[name=contacts]').map(box=>(box.element as HTMLInputElement).checked)).toEqual([true,false]);
  // Decryption and fingerprints never offer saved contacts.
  await app.findAll('.operation-tab')[2]!.trigger('click');
  expect(app.findAll('[name=keySource]')).toHaveLength(2);
  expect(app.find('[name=keyPath]').exists()).toBe(true);
 });
 it('shows contact storage failures without offering any contact',async()=>{
  vi.mocked(bridge.listContacts).mockRejectedValue({code:'contact-storage'});
  const app=await ready();await chooseContactSource(app);
  expect(app.get('.contact-choice').text()).toContain('Cannot use the contacts folder');
  expect(app.findAll('[name=contacts]')).toHaveLength(0);
  await contactsPage(app);
  expect(app.get('.contact-list [role=alert]').text()).toContain('Cannot use the contacts folder');
 });
});
