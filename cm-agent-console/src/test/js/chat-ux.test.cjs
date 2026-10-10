"use strict";
const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

// 执行 app.js 的真实控制器；替换浏览器宿主以验证滚动、剪贴板和未知写入结果，不调用模型或工具。
const app = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");
const source = app.slice(app.indexOf("function createChatUxController("), app.indexOf("async function initializeChatPage("));
const context = {};
vm.runInNewContext(`${source};this.actions={createChatUxController,shouldSubmitChatInput,chatConversationGroups,chatMessageText};`, context);
const actions = context.actions;

function harness({version = "v2", clipboard = async () => {}} = {}) {
    let key = "agent/one", epoch = 0, observer;
    const nodes = new Map();
    function node(id) {
        const listeners = {};
        const result = {value: "", hidden: true, disabled: false, style: {}, dataset: {}, scrollTop: 0,
            clientHeight: 300, scrollHeight: 1000, isConnected: true,
            getBoundingClientRect: () => ({top: 100}), querySelectorAll: () => [],
            addEventListener: (event, callback) => { listeners[event] = callback; },
            emit: (event, data) => listeners[event]?.(data), requestSubmit() { result.submits++; }, submits: 0};
        nodes.set(id, result);
        return result;
    }
    ["chatConversationSearch", "chatMessageList", "chatLatestBtn", "chatInput", "chatForm", "chatFormStatus"].forEach(node);
    const document = {body: {dataset: {consoleVersion: version}}, getElementById: id => nodes.get(id)};
    const window = {navigator: {clipboard: {writeText: clipboard}}, MutationObserver: class {
        constructor(callback) { observer = callback; } observe() {} disconnect() { observer = null; }
    }};
    const controller = actions.createChatUxController({document, window, getKey: () => key, getEpoch: () => epoch});
    controller.bind();
    return {controller, nodes, document, setKey: value => { key = value; }, expire: () => { epoch++; }, mutate: () => observer?.()};
}

test("输入法组合、229、重复回车与禁用状态均不能发送，Shift保留换行", () => {
    const event = {key: "Enter"};
    assert.equal(actions.shouldSubmitChatInput(event, false, false, "你好"), true);
    for (const variant of [{shiftKey:true}, {ctrlKey:true}, {metaKey:true}, {altKey:true}, {repeat:true}, {isComposing:true}, {keyCode:229}]) {
        assert.equal(actions.shouldSubmitChatInput({...event, ...variant}, false, false, "你好"), false);
    }
    assert.equal(actions.shouldSubmitChatInput(event, true, false, "你好"), false);
    assert.equal(actions.shouldSubmitChatInput(event, false, true, "你好"), false);
    assert.equal(actions.shouldSubmitChatInput(event, false, false, " \n "), false);
});

test("compositionend后的确认回车不提交，后续独立回车仅提交一次", () => {
    const h = harness(), input = h.nodes.get("chatInput"), form = h.nodes.get("chatForm");
    input.value = "中文";
    input.emit("compositionstart");
    input.emit("compositionend");
    input.emit("keydown", {key:"Enter", preventDefault() { throw new Error("不得拦截选词确认"); }});
    assert.equal(form.submits, 0);
    input.emit("keyup");
    let prevented = 0;
    input.emit("keydown", {key:"Enter", preventDefault() { prevented++; }});
    assert.equal(prevented, 1); assert.equal(form.submits, 1);
});

test("v1控制器不绑定新快捷键也不干预原滚动", () => {
    const h = harness({version:"v1"});
    h.nodes.get("chatInput").value = "旧版";
    h.nodes.get("chatInput").emit("keydown", {key:"Enter"});
    assert.equal(h.nodes.get("chatForm").submits, 0);
    assert.equal(h.controller.scroll(), false);
});

test("上翻暂停delta和异步文件跟随，回到最新恢复", () => {
    const h = harness(), list = h.nodes.get("chatMessageList");
    h.controller.restoreReading(h.controller.captureReading());
    list.scrollTop = 100; list.emit("scroll");
    list.scrollHeight = 1400; h.mutate(); h.controller.scroll();
    assert.equal(list.scrollTop, 100);
    assert.equal(h.nodes.get("chatLatestBtn").hidden, false);
    h.nodes.get("chatLatestBtn").emit("click");
    assert.equal(list.scrollTop, 1400);
    assert.equal(h.nodes.get("chatLatestBtn").hidden, true);
});

test("同会话终态重绘保持消息锚点，新会话定位最新", () => {
    const h = harness(), list = h.nodes.get("chatMessageList");
    let top = 400;
    const anchor = {dataset: {messageKey:"message"}, getBoundingClientRect: () => ({top:100 + top - list.scrollTop, bottom:500 + top - list.scrollTop})};
    list.querySelectorAll = () => [anchor];
    h.controller.restoreReading(h.controller.captureReading());
    list.scrollTop = 350; list.emit("scroll");
    const saved = h.controller.captureReading();
    top += 200;
    h.controller.restoreReading(saved);
    assert.equal(list.scrollTop, 550);
    h.setKey("agent/two");
    h.controller.restoreReading(h.controller.captureReading());
    assert.equal(list.scrollTop, list.scrollHeight);
});

test("本地标题过滤仅处理给定列表，时间分组和原数据保持", () => {
    const items = [{id:"a", title:"Java 文档", updatedAt:"2026-10-10T12:00:00"},
        {id:"b", title:"工具审批", updatedAt:"2026-10-09T12:00:00"},
        {id:"c", title:"java 工具", updatedAt:"2026-10-01T12:00:00"}];
    const groups = actions.chatConversationGroups(items, " JAVA ", new Date("2026-10-10T14:00:00"));
    assert.deepEqual(Array.from(groups, ([label, rows]) => [label, Array.from(rows, item => item.id)]), [["今天",["a"]],["更早",["c"]]]);
    assert.equal(items.length, 3);
    assert.equal(actions.chatConversationGroups(items, "无匹配").length, 0);
});

test("正文复制排除思考工具与审批，剪贴板拒绝不暴露原异常也不假成功", async () => {
    const text = actions.chatMessageText({contentBlocks:[{type:"THINKING",text:"思考"},{type:"TEXT",text:"正文"},{type:"TOOL_USE",text:"参数"},{type:"TEXT",text:"结论"}]});
    assert.equal(text, "正文\n\n结论");
    let copied;
    const h = harness({clipboard:async value => { copied = value; }});
    const button = {isConnected:true}, status = {dataset:{}};
    await h.controller.copyText(text, button, status);
    assert.equal(copied, text); assert.equal(status.textContent, "已复制"); assert.equal(button.disabled, false);
    const denied = harness({clipboard:async () => { throw new Error("内部地址及模拟secret"); }});
    await denied.controller.copyText(text, button, status);
    assert.equal(status.dataset.tone, "error"); assert.match(status.textContent, /手动选择/);
    assert.doesNotMatch(status.textContent, /secret|内部地址/);
});

test("退出或切换后迟到剪贴板结果不更新新上下文", async () => {
    let resolve;
    const h = harness({clipboard:() => new Promise(done => { resolve=done; })});
    const button = {isConnected:true}, status = {dataset:{}, textContent:"原状态"};
    const pending = h.controller.copyText("正文", button, status);
    h.expire(); resolve(); await pending;
    assert.equal(status.textContent, "原状态");
});

test("网络未知保留并锁定，GET未见新消息不得证明拒绝，同文旧消息不能误确认", () => {
    const h = harness(), message = {id:"old", role:"USER", contentBlocks:[{type:"TEXT",text:"请求"}]};
    h.controller.reconcile([message], null);
    h.controller.beginSubmission("请求"); h.controller.failSubmission(new Error("断网"));
    h.controller.reconcile([message], null);
    assert.equal(h.controller.isUnknown(), true);
    assert.equal(h.nodes.get("chatInput").value, "请求");
    h.controller.reconcile([message], {id:"other-client-run",status:"RUNNING"});
    assert.equal(h.controller.isUnknown(), true);
    h.controller.reconcile([message, {...message,id:"new"}], null);
    assert.equal(h.controller.isUnknown(), false);
    assert.equal(h.nodes.get("chatInput").value, "");
    assert.match(h.nodes.get("chatFormStatus").textContent, /已核对：请求已写入/);
});

test("明确400拒绝保留可编辑输入，started接受后失败不复原已发送请求", () => {
    const h = harness();
    h.controller.beginSubmission("无效请求"); h.controller.failSubmission({status:400});
    assert.equal(h.controller.isUnknown(), false);
    assert.equal(h.nodes.get("chatInput").value, "无效请求");
    h.controller.beginSubmission("已接收"); h.controller.acceptSubmission(); h.controller.failSubmission({status:500});
    assert.equal(h.nodes.get("chatInput").value, ""); assert.equal(h.controller.isUnknown(), false);
});

test("草稿与未知状态按Agent会话隔离，退出清理且不写持久存储", () => {
    const h = harness();
    h.nodes.get("chatInput").value="会话一"; h.controller.rememberDraft();
    h.controller.beginSubmission("会话一"); h.controller.failSubmission({status:500});
    h.nodes.get("chatFormStatus").textContent = "上一轮已完成";
    h.setKey("agent/two"); h.controller.activate();
    assert.equal(h.nodes.get("chatInput").value, ""); assert.equal(h.controller.isUnknown(), false);
    assert.equal(h.nodes.get("chatFormStatus").textContent, "");
    h.setKey("agent/one"); h.controller.activate();
    assert.equal(h.nodes.get("chatInput").value, "会话一"); assert.equal(h.controller.isUnknown(), true);
    assert.match(h.nodes.get("chatFormStatus").textContent, /请核对本轮状态/);
    assert.equal(h.nodes.get("chatFormStatus").dataset.tone, "error");
    h.controller.reset(); h.controller.activate();
    assert.equal(h.nodes.get("chatInput").value, ""); assert.equal(h.controller.isUnknown(), false);
    assert.doesNotMatch(source, /localStorage|sessionStorage/);
});
