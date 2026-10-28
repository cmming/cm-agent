const {test} = require('node:test');
const assert = require('node:assert/strict');
const {File} = require('node:buffer');
const {webcrypto, createHash} = require('node:crypto');
const {createNativeWorkspaceImportState, formatWorkspaceImportFailure} = require('../../main/resources/META-INF/resources/assets/console-core.js');

const capabilities = {enabled: true, runtimeAvailable: true, allowedTypes: ['.md', '.txt', '.json', '.csv'], maxFiles: 4, maxFileBytes: 16384, maxTotalBytes: 32768, maxCandidates: 1000, maxDepth: 16, enumerationTimeoutMillis: 5000};
const folder = (files, name = '合成目录') => ({kind: 'directory', name, async *entries() {
    for (const file of files) yield [file.name, {kind: 'file', getFile: async () => file}];
}});
function file(name, content, relative = '') {
    const value = new File([content], name); Object.defineProperty(value, 'webkitRelativePath', {value: relative});
    let reads = 0; const slice = value.slice.bind(value); value.slice = (...args) => { reads++; return slice(...args); }; value.reads = () => reads;
    return value;
}
class DirectoryInput extends EventTarget {
    webkitdirectory = true; value = ''; files = []; clicks = 0;
    click() { this.clicks++; }
}
function setup({handle, input, caps = capabilities, intercept, crypto = webcrypto} = {}) {
    let epoch = 1, context = {agent: 'agent', conversation: 'conversation', editable: true, bindingVersion: 4}, batch = null;
    const calls = [], host = {crypto, setTimeout, clearTimeout}; if (handle) host.showDirectoryPicker = handle;
    const api = {request: async (path, options) => {
        calls.push({path, options, bytes: options?.body instanceof Uint8Array ? Uint8Array.from(options.body) : null});
        if (intercept) { const custom = await intercept(path, options, batch); if (custom !== undefined) return custom; }
        if (path.endsWith('/capabilities')) return caps;
        if (options?.method === 'POST' && path.endsWith('/workspace-imports')) {
            const manifest = JSON.parse(options.body); batch = {...manifest, id: 'batch', state: 'DRAFT', version: 1, bindable: false, files: manifest.files.map((file, index) => ({...file, id: `file-${index}`, uploaded: false}))}; return structuredClone(batch);
        }
        if (options?.method === 'POST' && path.includes('/files/')) {
            const id = path.split('/files/')[1].split('?')[0]; batch.files.find(file => file.id === id).uploaded = true; batch.version++; return structuredClone(batch);
        }
        if (options?.method === 'POST' && path.endsWith('/complete')) { batch.state = 'READY'; batch.bindable = true; batch.version++; return structuredClone(batch); }
        if (options?.method === 'DELETE') { batch.state = 'REVOKED'; batch.version++; return structuredClone(batch); }
        if (!options?.method && batch) return structuredClone(batch);
        throw new Error('合成未找到');
    }};
    const model = createNativeWorkspaceImportState({api, host, directoryInput: input, getEpoch: () => epoch, getContext: () => context});
    model.activate(context.agent, context.conversation);
    return {model, calls, host, context: value => {context = {...context, ...value};}, epoch: () => {epoch++;}, batch: () => batch};
}
const settled = () => new Promise(resolve => setImmediate(resolve));

test('真实点击栈同步调用只读系统选择器，选中与显式预览之前无正文或manifest网络', async () => {
    const a = file('a.txt', '合成A'), b = file('b.md', '合成B'); let calls = 0;
    const t = setup({handle: () => { calls++; return Promise.resolve(folder([a, b])); }}); await settled();
    const picked = t.model.pick(); assert.equal(calls, 1); await picked;
    assert.equal(t.model.value().selected.size, 0); assert.equal(t.model.value().canConfirm, false); assert.equal(a.reads(), 0); assert.equal(b.reads(), 0);
    await t.model.preview('b.md'); assert.equal(b.reads(), 1); assert.equal(t.calls.length, 1);
    t.model.select('a.txt', true); const result = await t.model.confirm(); assert.equal(result.batch.state, 'READY');
    const create = t.calls.find(call => call.options?.method === 'POST' && call.path.endsWith('/workspace-imports'));
    const manifest = JSON.parse(create.options.body); assert.equal(manifest.confirmed, true); assert.equal(manifest.files.length, 1);
    assert.equal(manifest.files[0].relativePath, 'a.txt'); assert.equal(manifest.files[0].sha256, createHash('sha256').update('合成A').digest('hex'));
    assert.equal(b.reads(), 1); assert.deepEqual([...t.calls.find(call => call.bytes).bytes], [...new TextEncoder().encode('合成A')]);
});

test('目录input降级同样同步打开系统通道，去掉一个根且不把fakepath发给Server', async () => {
    const input = new DirectoryInput(), t = setup({input}); await settled();
    const pending = t.model.pick(); assert.equal(input.clicks, 1);
    input.files = [file('a.txt', 'A', '根目录/sub/a.txt')]; input.dispatchEvent(new Event('change')); await pending;
    assert.equal(t.model.value().name, '根目录'); assert.equal(t.model.value().candidates[0].path, 'sub/a.txt');
    assert.equal(t.calls.length, 1); t.model.select('sub/a.txt', true); await t.model.confirm();
    assert.equal(JSON.parse(t.calls.find(call => call.options?.method === 'POST').options.body).files[0].relativePath, 'sub/a.txt');
});

test('取消系统选择不自动弹input，两个通道均缺失时明确提示', async () => {
    const input = new DirectoryInput(); const canceled = setup({input, handle: () => Promise.reject(Object.assign(new Error(), {name: 'AbortError'}))}); await settled(); await canceled.model.pick();
    assert.equal(input.clicks, 0); assert.match(canceled.model.value().message, /已取消/); assert.equal(canceled.calls.length, 1);
    const unsupported = setup(); await settled(); await unsupported.model.pick(); assert.match(unsupported.model.value().message, /不支持文件夹/); assert.equal(unsupported.calls.length, 1);
});

test('枚举超界拒绝整个选择，候选超上传预算则要求取消勾选不截断', async () => {
    const files = Array.from({length: 5}, (_, index) => file(`${index}.txt`, 'a'));
    const limited = setup({caps: {...capabilities, maxCandidates: 1}, handle: async () => folder(files)}); await settled(); await limited.model.pick();
    assert.equal(limited.model.value().candidates.length, 0); assert.match(limited.model.value().message, /未采用部分结果/);
    const t = setup({handle: async () => folder(files)}); await settled(); await t.model.pick(); files.forEach(value => t.model.select(value.name, true));
    assert.equal(t.model.budget().count, 5); assert.equal(t.model.value().canConfirm, false); assert.equal(await t.model.confirm(), null); assert.equal(t.calls.length, 1);
});

test('敏感路径和非法类型明确排除，普通单文件与多根FileList不能冒充目录', async () => {
    const input = new DirectoryInput(), t = setup({input}); await settled();
    let pending = t.model.pick(); input.files = [file('a.txt', 'a')]; input.dispatchEvent(new Event('change')); await pending; assert.match(t.model.value().message, /普通单文件框/);
    pending = t.model.pick(); input.files = [file('a.txt', 'a', '根/a.txt'), file('b.txt', 'b', '另一根/b.txt')]; input.dispatchEvent(new Event('change')); await pending; assert.match(t.model.value().message, /多个选择根/);
    pending = t.model.pick(); input.files = [file('password.txt', 'a', '根/password.txt'), file('x.exe', 'x', '根/x.exe'), file('a.txt', 'a', '根/a.txt')]; input.dispatchEvent(new Event('change')); await pending;
    assert.equal(t.model.value().candidates.length, 1); assert.equal(t.model.value().excluded.length, 2); assert.equal(t.calls.length, 1);
});

test('退出、切会话或取消后迟到的系统窗口与摘要结果均不串台', async () => {
    let select, digest;
    const t = setup({handle: () => new Promise(resolve => {select = resolve;})}); await settled(); const pending = t.model.pick();
    t.context({conversation: 'new'}); t.model.activate('agent', 'new'); select(folder([file('a.txt', 'a')])); await pending;
    assert.equal(t.model.value().candidates.length, 0);
    const late = setup({handle: async () => folder([file('a.txt', 'a')]), crypto: {getRandomValues: bytes => webcrypto.getRandomValues(bytes), subtle: {digest: () => new Promise(resolve => {digest = resolve;})}}});
    await settled(); await late.model.pick(); late.model.select('a.txt', true); const upload = late.model.confirm(); await settled(); late.epoch(); late.model.reset(); digest(new ArrayBuffer(32)); await upload;
    assert.equal(late.model.value().candidates.length, 0); assert.equal(late.calls.filter(call => call.options).length, 0);
});

test('未知创建结果先GET同一关联且不再次POST，无法确认时保留核对入口', async () => {
    let createCount = 0, getCount = 0;
    const t = setup({handle: async () => folder([file('a.txt', 'a')]), intercept: async (path, options) => {
        if (options?.method === 'POST') { createCount++; throw new Error('合成连接中断'); }
        if (path.includes('/by-request/')) { getCount++; throw Object.assign(new Error('合成未找到'), {status: 404}); }
    }}); await settled(); await t.model.pick(); t.model.select('a.txt', true); await t.model.confirm();
    assert.equal(createCount, 1); assert.equal(getCount, 1); assert.equal(t.model.value().phase, 'UNKNOWN');
    await t.model.check(); assert.equal(createCount, 1); assert.equal(getCount, 2);
});

test('打开后关闭弹层不取消独立的能力预加载，显式刷新可恢复能力失败', async () => {
    let resolve;
    const t = setup({handle: async () => folder([file('a.txt', 'a')]), intercept: async path => path.endsWith('/capabilities') ? new Promise(done => {resolve = done;}) : undefined});
    t.model.discard(); resolve(capabilities); await settled(); assert.equal(t.model.enabled(), true);
    await t.model.pick(); assert.equal(t.model.value().candidates.length, 1);
    t.model.refresh(); assert.equal(t.model.value().candidates.length, 0); resolve(capabilities); await settled(); assert.equal(t.model.enabled(), true);
});

test('Agent已切换但下个网络读取尚未开始时，旧系统窗口仍不可采用', async () => {
    let resolve; const t = setup({handle: () => new Promise(done => {resolve = done;})}); await settled();
    const pending = t.model.pick(); t.context({agent: 'new-agent'}); resolve(folder([file('a.txt', 'a')])); await pending;
    assert.equal(t.model.value().candidates.length, 0); assert.equal(t.calls.filter(call => call.options?.method).length, 0);
});

test('输入读取使用slice硬界，非法UTF8或NUL不上传，失败包含后端编号', async () => {
    const a = file('a.txt', new Uint8Array([0xff])); const t = setup({handle: async () => folder([a])}); await settled(); await t.model.pick(); t.model.select('a.txt', true); await t.model.confirm();
    assert.equal(t.calls.filter(call => call.options).length, 0); assert.match(t.model.value().message, /UTF-8/);
    assert.match(formatWorkspaceImportFailure(Object.assign(new Error('权限不足'), {code: 'FORBIDDEN', errorId: 'E-SAME'})), /FORBIDDEN.*E-SAME/);
});
