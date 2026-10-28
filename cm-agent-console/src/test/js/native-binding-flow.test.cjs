const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const app = fs.readFileSync(path.join(__dirname, '../../main/resources/META-INF/resources/assets/app.js'), 'utf8');
const bindSource = app.match(/function bindWorkspaceChoice\(\) \{[\s\S]*?\n    \}/)[0];

// 执行真实接线，只替换DOM与HTTP边界；这里不声称验证操作系统窗口。
function flow({conversation = '', postFails = false, found = false, delay = false, putFails = false} = {}) {
    const nodes = new Map(), calls = []; let active = true, selected = {mode: 'DEFAULT', workspaceId: null}, finish;
    function node(id) {
        if (!nodes.has(id)) nodes.set(id, {id, disabled: false, value: '', dataset: {}, listeners: {}, open: true,
            addEventListener(name, fn) {this.listeners[name] = fn;}, close() {this.open = false; this.listeners.close?.();},
            focus() {}, setAttribute() {}, replaceChildren() {}, append() {}});
        return nodes.get(id);
    }
    const ready = {batch: {id: 'batch', state: 'READY', bindable: true, displayName: '合成目录'},
        context: {agent: 'agent', conversation, epoch: 1, bindingVersion: 4}, isCurrent: () => active};
    const state = {selectedAgentId: 'agent', selectedConversationId: conversation, conversations: [], chatBusy: false, chatLoading: false};
    const context = {state, window: {}, sessionEpoch: {capture: () => 1}, $: node,
        nativeWorkspaceSource: 'computer', readyNativeImport: ready, unknownNativeBinding: null,
        nativeWorkspaceImport: {value: () => ({phase: 'READY'}), markBinding() {node('chatWorkspaceSave').disabled = true;},
            discard() {active = false;}, bindingFailed(message) {context.message = message;}, check() {}, revoke() {}, pick() {}},
        workspaceChoice: {value: () => ({view: {selection: selected, editable: true, version: 4}, catalog: {items: []}}),
            async save() {calls.push('PUT'); if (putFails) throw new Error('合成PUT响应未知'); selected = {mode: 'IMPORTED', workspaceId: 'batch'}; return true;},
            async load() {calls.push('GET-WORKSPACE'); if (found) selected = {mode: 'IMPORTED', workspaceId: 'batch'};}},
        core: {formatWorkspaceImportFailure: error => error.message},
        api: {request: async (url, options) => {
            calls.push(options?.method || 'GET');
            if (options?.method === 'POST') {
                if (delay) return new Promise(resolve => {finish = () => resolve({id: 'created'});});
                if (postFails) throw new Error('合成POST响应未知');
                return {id: 'created'};
            }
            return url.includes('/workspace') ? {selection: {mode: 'IMPORTED', workspaceId: 'batch'}} : url.endsWith('/created') ? {id: 'created'} : {items: found ? [{id: 'created'}] : []};
        }},
        loadConversations: async () => {}, loadChatMessages: async () => {}, renderNativeWorkspaceImport() {}, renderChatConversations() {},
        element: () => node('generated'), createChatConversation: async () => {}, setStatus() {}}
    vm.createContext(context); vm.runInContext(bindSource, context); context.bindWorkspaceChoice();
    return {context, calls, node, finish: () => finish(), cancel: () => node('chatWorkspaceCancel').listeners.click(),
        submit: () => node('chatWorkspaceForm').listeners.submit({preventDefault() {}}), check: () => node('chatNativeCheck').listeners.click()};
}

test('新会话创建响应未知，只GET核对唯一importId匹配，不重复POST', async () => {
    const f = flow({postFails: true, found: true}); await f.submit();
    assert.equal(f.calls.filter(call => call === 'POST').length, 1); assert.equal(f.context.state.selectedConversationId, 'created');
    assert.equal(f.node('chatWorkspaceDialog').open, false);
});

test('无法确认创建结果时保留旧上下文，显式核对也不再次创建', async () => {
    const f = flow({postFails: true}); await f.submit();
    assert.equal(f.context.state.selectedConversationId, ''); assert.match(f.context.message, /不会重复创建/);
    await f.check(); assert.equal(f.calls.filter(call => call === 'POST').length, 1); assert.equal(f.context.state.selectedConversationId, '');
});

test('已存在会话PUT响应未知，经GET确认原绑定后完成而不重写', async () => {
    const f = flow({conversation: 'existing', putFails: true, found: true}); await f.submit();
    assert.equal(f.calls.filter(call => call === 'PUT').length, 1); assert.equal(f.calls.filter(call => call === 'GET-WORKSPACE').length, 1);
    assert.equal(f.node('chatWorkspaceDialog').open, false);
});

test('关闭选择界面后迟到的创建响应不切换活动会话', async () => {
    const f = flow({delay: true}); const pending = f.submit(); f.cancel(); f.finish(); await pending;
    assert.equal(f.context.state.selectedConversationId, ''); assert.equal(f.calls.filter(call => call === 'GET').length, 0);
});
