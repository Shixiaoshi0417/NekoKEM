import { mount,flushPromises,enableAutoUnmount } from '@vue/test-utils';
import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest';
import App from '../src/App.vue';
import * as bridge from '../src/bridge';
import { translate } from '../src/i18n';
vi.mock('../src/bridge',()=>({getSettings:vi.fn(),setLanguage:vi.fn(),runOperation:vi.fn(),cancelOperation:vi.fn(),closeApp:vi.fn(),onProgress:vi.fn(),chooseOpen:vi.fn(),chooseSave:vi.fn()}));
enableAutoUnmount(afterEach);
beforeEach(()=>{vi.clearAllMocks();vi.mocked(bridge.getSettings).mockResolvedValue({language:'en',selection:'system',version:'3.2.0'});vi.mocked(bridge.onProgress).mockResolvedValue(()=>{});vi.mocked(bridge.runOperation).mockResolvedValue({output:'output.nkem',fingerprint:null});vi.mocked(bridge.chooseOpen).mockResolvedValue(null);vi.mocked(bridge.chooseSave).mockResolvedValue(null);vi.mocked(bridge.cancelOperation).mockResolvedValue(true);});
async function ready(){const app=mount(App,{attachTo:document.body});await flushPromises();return app;}
describe('Desktop operation boundaries',()=>{
 it('rejects mismatched keygen passwords before invoking native Core',async()=>{const app=await ready();await app.findAll('nav button')[0]!.trigger('click');await app.get('[name=publicPath]').setValue('public.key');await app.get('[name=privatePath]').setValue('private.key.enc');await app.get('[name=password]').setValue('secret-one');await app.get('[name=confirmation]').setValue('secret-two');await app.get('form').trigger('submit');expect(bridge.runOperation).not.toHaveBeenCalled();expect(app.get('[role=alert]').text()).toBe('Passwords do not match.');});
 it('clears password fields immediately and preserves cancellation failure semantics',async()=>{const app=await ready();await app.findAll('nav button')[2]!.trigger('click');await app.get('[name=keyPath]').setValue('private.key.enc');await app.get('[name=input]').setValue('input.nkem');await app.get('[name=output]').setValue('output');await app.get('[name=password]').setValue('public-test-password');vi.mocked(bridge.runOperation).mockRejectedValue({code:'cancelled'});await app.get('form').trigger('submit');expect((app.get('[name=password]').element as HTMLInputElement).value).toBe('');await flushPromises();expect(app.get('[role=alert]').text()).toContain('no new output');expect(app.text()).not.toContain('public-test-password');});
 it('rejects passwords exceeding the UTF-8 byte limit',async()=>{const app=await ready();await app.findAll('nav button')[0]!.trigger('click');await app.get('[name=publicPath]').setValue('public.key');await app.get('[name=privatePath]').setValue('private.key.enc');await app.get('[name=password]').setValue('中'.repeat(400));await app.get('[name=confirmation]').setValue('中'.repeat(400));await app.get('form').trigger('submit');expect(bridge.runOperation).not.toHaveBeenCalled();expect(app.get('[role=alert]').text()).toContain('1024 UTF-8 bytes');});
 it('locks navigation and ignores stale progress events during a job',async()=>{let callback:(value:bridge.Progress)=>void=()=>{};vi.mocked(bridge.onProgress).mockImplementation(async fn=>{callback=fn;return()=>{};});let finish:(result:bridge.Outcome)=>void=()=>{};vi.mocked(bridge.runOperation).mockReturnValue(new Promise(resolve=>{finish=resolve;}));const app=await ready();await app.get('[name=keyPath]').setValue('public.key');await app.get('[name=input]').setValue('plain');await app.get('[name=output]').setValue('out.nkem');await app.get('form').trigger('submit');const request=vi.mocked(bridge.runOperation).mock.calls[0]![0];callback({id:'stale-job',processed:50,total:100});await flushPromises();expect(app.text()).not.toContain('50%');expect(app.findAll('nav button').every(button=>button.attributes('disabled')!==undefined)).toBe(true);callback({id:request.id,processed:50,total:100});await flushPromises();expect(app.text()).toContain('50%');finish({output:'out.nkem',fingerprint:null});await flushPromises();expect(app.text()).toContain('Completed');});
 it('has a complete label for every supported language',()=>{for(const language of ['en','zh-CN','zh-TW','ja','ko'] as const){for(const key of ['keygen','encrypt','decrypt','fingerprint','exit','cancelled','core-error','navigation','keySource','loading','choosing'] as const)expect(translate(key,language).length).toBeGreaterThan(0);}});
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
  expect(app.get('h1').text()).toBe('Public key fingerprint');
  await buttons[3]!.trigger('keydown',{key:'ArrowDown'});
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
