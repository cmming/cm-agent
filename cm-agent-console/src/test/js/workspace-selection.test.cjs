const {test} = require('node:test');
const assert = require('node:assert/strict');
const {createWorkspaceSelectionState} = require('../../main/resources/META-INF/resources/assets/console-core.js');
const view = (name = '甲目录') => ({selection: {mode: 'SELECTED', workspaceId: 'a'}, displayName: name, version: 4, editable: true, available: true});
const catalog = {enabled: true, items: [{id: 'a', name: '甲目录'}]};
test('自动建会话参数保留显式 NONE，关闭编辑不改变状态', async () => {
    const model = createWorkspaceSelectionState({api: {request: async () => catalog}, getEpoch: () => 1});
    await model.load('agent'); await model.save({mode: 'NONE', workspaceId: null});
    assert.deepEqual(model.payload(), {workspaceSelection: {mode: 'NONE', workspaceId: null}});
    assert.equal(model.ready(), true);
});
test('已存在会话的条件写入使用后端版本且只调用一次', async () => {
    const calls = [], api = {request: async (url, options) => {
        calls.push({url, options}); return options ? {...view(), version: 5} : url.endsWith('/workspaces') ? catalog : view();
    }};
    const model = createWorkspaceSelectionState({api, getEpoch: () => 1});
    await model.load('agent', 'conversation'); await model.save({mode: 'SELECTED', workspaceId: 'a'});
    const writes = calls.filter(call => call.options);
    assert.equal(writes.length, 1);
    assert.deepEqual(JSON.parse(writes[0].options.body), {workspaceSelection: {mode: 'SELECTED', workspaceId: 'a'}, expectedVersion: 4});
});
test('切换会话时慢响应不能覆盖新选择', async () => {
    let resolve;
    const api = {request: async url => url.endsWith('/workspaces') ? catalog : url.includes('/old/')
        ? new Promise(done => { resolve = done; }) : view('乙目录')};
    const model = createWorkspaceSelectionState({api, getEpoch: () => 1});
    const old = model.load('agent', 'old'); await model.load('agent', 'new'); resolve(view('旧目录')); await old;
    assert.equal(model.value().view.displayName, '乙目录');
});
test('退出后慢响应不恢复上一个主体目录', async () => {
    let epoch = 1, resolve;
    const model = createWorkspaceSelectionState({api: {request: () => new Promise(done => { resolve = done; })}, getEpoch: () => epoch});
    const load = model.load('agent'); epoch++; model.reset(); resolve(catalog); await load;
    assert.equal(model.ready(), false); assert.equal(model.value().view, null);
});
test('网络错误或版本冲突阻止发送且写入不自动重试', async () => {
    let writes = 0;
    const model = createWorkspaceSelectionState({api: {request: async (url, options) => {
        if (options) { writes++; throw new Error('工作区选择已变化 E-409'); }
        return url.endsWith('/workspaces') ? catalog : view();
    }}, getEpoch: () => 1});
    await model.load('agent', 'conversation'); await assert.rejects(model.save({mode: 'NONE', workspaceId: null}), /E-409/);
    assert.equal(writes, 1); assert.equal(model.ready(), false); assert.match(model.value().error, /重新读取/);
});
test('已锁定会话不可写，目录失效不可发送', async () => {
    const model = createWorkspaceSelectionState({api: {request: async url => url.endsWith('/workspaces') ? catalog : {...view(), editable: false, available: false}}, getEpoch: () => 1});
    await model.load('agent', 'conversation');
    assert.equal(model.ready(), false);
    await assert.rejects(model.save({mode: 'NONE', workspaceId: null}), /新建会话/);
});

test('IMPORTED必须由READY批次证明且未知来源不能显示为默认映射', async () => {
    let writes = 0;
    const model = createWorkspaceSelectionState({api: {request: async (url, options) => { if (options) writes++; return catalog; }}, getEpoch: () => 1});
    await model.load('agent');
    const original = model.payload();
    await assert.rejects(model.save({mode: 'IMPORTED', workspaceId: 'import-id'}), /尚未就绪/);
    await assert.rejects(model.save({mode: 'FUTURE', workspaceId: 'import-id'}), /尚未开放/);
    assert.equal(writes, 0); assert.deepEqual(model.payload(), original);
});

test('读取不支持来源时保留原身份并关闭发送而不回退DEFAULT', async () => {
    const imported = {...view('未来快照'), selection: {mode: 'FUTURE', workspaceId: 'import-id'}};
    const model = createWorkspaceSelectionState({api: {request: async url => url.endsWith('/workspaces') ? catalog : imported}, getEpoch: () => 1});
    await model.load('agent', 'conversation');
    assert.equal(model.ready(), false); assert.match(model.value().error, /尚未开放/);
    assert.deepEqual(model.payload().workspaceSelection, imported.selection);
    assert.equal(model.value().view.displayName, '未来快照');
});

test('已持久绑定IMPORTED刷新后保留身份及后端可用性和首发锁定', async () => {
    const imported = {...view('本机快照'), selection: {mode: 'IMPORTED', workspaceId: 'import-id'}, editable: false};
    const model = createWorkspaceSelectionState({api: {request: async url => url.endsWith('/workspaces') ? catalog : imported}, getEpoch: () => 1});
    await model.load('agent', 'conversation'); assert.equal(model.ready(), true);
    assert.deepEqual(model.payload().workspaceSelection, imported.selection);
    await assert.rejects(model.save(imported.selection, {id: 'import-id', state: 'READY', bindable: true}), /新建会话/);
});

test('导入条件保存捕获取消后迟到响应，保留原显示并要求GET核对', async () => {
    let resolve, active = true;
    const model = createWorkspaceSelectionState({api: {request: async (url, options) => options ? new Promise(done => {resolve = done;}) : url.endsWith('/workspaces') ? catalog : view()}, getEpoch: () => 1});
    await model.load('agent', 'conversation');
    const save = model.save({mode: 'IMPORTED', workspaceId: 'i'}, {id: 'i', state: 'READY', bindable: true}, 4, () => active);
    active = false; resolve({...view('本机'), selection: {mode: 'IMPORTED', workspaceId: 'i'}, version: 5});
    assert.equal(await save, false); assert.equal(model.payload().workspaceSelection.mode, 'SELECTED');
    assert.equal(model.ready(), false); assert.match(model.value().error, /重新读取/);
});
