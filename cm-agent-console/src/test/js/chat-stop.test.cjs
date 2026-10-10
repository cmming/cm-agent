"use strict";
const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const core = require("../../main/resources/META-INF/resources/assets/console-core.js");

// 执行真实控制函数，只替换 DOM、计时器和网络，确保不会因重试或迟到响应重放写入。
function harness(request) {
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");
    const source = app.slice(app.indexOf("function setActiveChatRun("), app.indexOf('let streamingMarkdownOutput = "";'));
    const state = {selectedAgentId: "agent", selectedConversationId: "conversation", activeChatRun: null,
        chatCancelPending: false, chatLoading: false};
    const requests = [];
    const timers = new Map();
    const statuses = [];
    let id = 0, session = 0, loads = 0;
    const context = {state, chatRunTimer: null, window: {
        setTimeout(fn) { timers.set(++id, fn); return id; }, clearTimeout(id) { timers.delete(id); }
    }, sessionEpoch: {capture: () => session, isCurrent: value => value === session},
        api: {request: async (url, options) => { requests.push({url, options}); return request(url, options); }},
        updateChatSendAvailability() {}, $: () => ({}), setStatus: (_, message) => statuses.push(message),
        loadChatMessages: async () => { loads++; state.activeChatRun = null; state.chatLoading = false; }};
    vm.runInNewContext(`${source};globalThis.actions={setActiveChatRun,stopChatRun};`, context);
    context.actions.setActiveChatRun({id: "run", status: "RUNNING"});
    return {state, requests, statuses, timers, actions: context.actions, loads: () => loads,
        logout: () => { session++; }};
}

test("停止202不冒充完成且重复点击只写入一次随后仅查询", async () => {
    let resolve;
    const flow = harness((_, options) => options?.method === "POST"
        ? new Promise(done => { resolve = done; }) : {id: "run", status: "CANCELLED"});
    const first = flow.actions.stopChatRun();
    await flow.actions.stopChatRun();
    assert.equal(flow.requests.length, 1);
    resolve({id: "run", status: "CANCELLING"});
    await first;
    assert.equal(flow.state.activeChatRun.status, "CANCELLING");
    assert.equal(flow.loads(), 0);
    await flow.actions.stopChatRun();
    assert.equal(flow.requests.length, 1);
    await [...flow.timers.values()][0]();
    assert.equal(flow.requests.length, 2);
    assert.equal(flow.requests[1].options, undefined);
    assert.equal(flow.loads(), 1);
});

test("停止网络失败保持锁定且不得自动重发写入", async () => {
    const flow = harness(async () => { throw new Error("网络不可用，错误编号：stop-error"); });
    await flow.actions.stopChatRun();
    assert.equal(flow.state.chatLoading, true);
    assert.equal(flow.state.activeChatRun.status, "RUNNING");
    assert.equal(flow.requests.filter(item => item.options?.method === "POST").length, 1);
    assert.match(flow.statuses.at(-1), /stop-error.*不会自动重试停止/);
});

test("停止响应在换会话或退出登录后不能改写新页面", async () => {
    for (const logout of [false, true]) {
        let resolve;
        const flow = harness(() => new Promise(done => { resolve = done; }));
        const pending = flow.actions.stopChatRun();
        if (logout) flow.logout(); else flow.state.selectedConversationId = "another";
        flow.state.activeChatRun = {id: "other-run", status: "RUNNING"};
        resolve({id: "run", status: "CANCELLED"});
        await pending;
        assert.equal(flow.state.activeChatRun.id, "other-run");
        assert.equal(flow.loads(), 0);
        assert.equal(flow.statuses.length, 0);
    }
});

test("等待审批停止成功刷新消息审批历史而不恢复原Run", async () => {
    const flow = harness(async () => ({id: "run", status: "CANCELLED"}));
    flow.actions.setActiveChatRun({id: "run", status: "WAITING_APPROVAL"});
    assert.equal(flow.timers.size, 0);
    await flow.actions.stopChatRun();
    assert.equal(flow.loads(), 1);
    assert.equal(flow.state.activeChatRun, null);
    assert.equal(flow.requests.length, 1);
    assert.ok(flow.requests[0].url.endsWith("/cancel"));
});

test("停止状态中文展示不把用户停止标成运行失败", () => {
    assert.equal(core.statusMeta("CANCELLING").label, "停止中");
    assert.equal(core.statusMeta("CANCELLED").label, "已停止");
});
