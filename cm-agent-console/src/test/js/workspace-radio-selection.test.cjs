const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/META-INF/resources/assets/app.js'), 'utf8');
const helper = source.match(/function element\(tagName, options = \{\}\) \{[\s\S]*?\n    \}/)[0];
const binding = source.match(/function bindWorkspaceChoice\(\) \{[\s\S]*?\n    \}/)[0];

// 执行真实element与选择器接线，避免替代helper接受name后掩盖真实属性丢失。
function fixture() {
    const nodes = new Map(), radios = [], saved = [];
    const node = (tag = '') => ({tag, value: '', disabled: false, dataset: {}, listeners: {}, children: [],
        addEventListener(event, fn) {this.listeners[event] = fn;}, append(...items) {this.children.push(...items);},
        replaceChildren(...items) {this.children = items;}, focus() {}, setAttribute() {},
        showModal() {this.open = true;}, close() {this.open = false; this.listeners.close?.();}});
    const $ = id => {if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id);};
    let selection = {mode: 'DEFAULT', workspaceId: null};
    const context = {$, document: {createElement(tag) {const value = node(tag); if (tag === 'input') radios.push(value); return value;}},
        state: {selectedAgentId: 'agent', selectedConversationId: 'conversation'}, sessionEpoch: {capture: () => 1},
        workspaceChoice: {value: () => ({agent: 'agent', conversation: 'conversation', view: {selection, editable: true},
            catalog: {enabled: true, items: [{id: 'product', name: '产品只读参考', directory: 'product-reference', available: true}]}}),
            async save(value) {saved.push({...value}); selection = {...value}; return true;}},
        nativeWorkspaceImport: {discard() {}, pick() {}, refresh() {}}, nativeWorkspaceSource: 'server',
        readyNativeImport: null, unknownNativeBinding: null, renderNativeWorkspaceImport() {}};
    vm.createContext(context); vm.runInContext(`${helper}\n${binding}`, context); context.bindWorkspaceChoice();
    const open = () => {radios.length = 0; $('chatWorkspaceChoose').listeners.click();};
    const choose = value => {const radio = radios.find(item => item.value === value); radio.checked = true; radio.listeners.change();};
    const submit = () => $('chatWorkspaceForm').listeners.submit({preventDefault() {}});
    return {$, radios, saved, open, choose, submit};
}

test('真实目录渲染为默认、不使用和登记项设置同名单选组', () => {
    const f = fixture(); f.open();
    assert.equal(f.radios.length, 3);
    assert.deepEqual(f.radios.map(radio => radio.name), ['workspace', 'workspace', 'workspace']);
    assert.deepEqual(f.radios.filter(radio => radio.checked).map(radio => radio.value), ['DEFAULT']);
});

test('切换不使用后保存NONE，登记目录ID不残留', async () => {
    const f = fixture(); f.open(); f.choose('product'); f.choose('NONE'); await f.submit();
    assert.deepEqual(f.saved, [{mode: 'NONE', workspaceId: null}]);
});

test('取消不保存草稿，再次打开恢复原选择', () => {
    const f = fixture(); f.open(); f.choose('product'); f.$('chatWorkspaceCancel').listeners.click(); f.open();
    assert.deepEqual(f.saved, []);
    assert.deepEqual(f.radios.filter(radio => radio.checked).map(radio => radio.value), ['DEFAULT']);
});

test('搜索重建选项保留草稿身份和单选分组', async () => {
    const f = fixture(); f.open(); f.choose('product'); f.radios.length = 0;
    f.$('chatWorkspaceSearch').value = '产品'; f.$('chatWorkspaceSearch').listeners.input();
    assert.deepEqual(f.radios.map(radio => radio.name), ['workspace', 'workspace', 'workspace']);
    assert.deepEqual(f.radios.filter(radio => radio.checked).map(radio => radio.value), ['product']);
    await f.submit(); assert.deepEqual(f.saved, [{mode: 'SELECTED', workspaceId: 'product'}]);
});
