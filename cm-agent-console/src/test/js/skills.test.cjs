const assert = require("node:assert/strict");
const path = require("node:path");
const test = require("node:test");
require("../../main/resources/META-INF/resources/assets/console-core.js");

const skills = require(path.resolve(
    __dirname,
    "../../main/resources/META-INF/resources/console/v2/assets/skills.js"
));

test("迟到的技能详情响应不能覆盖当前选择", () => {
    let session = 1;
    const scope = skills.createRequestScope(() => session);
    const skillA = scope.issue("detail");
    const skillB = scope.issue("detail");

    assert.equal(scope.isCurrent(skillA), false);
    assert.equal(scope.isCurrent(skillB), true);

    session = 2;
    assert.equal(scope.isCurrent(skillB), false);
});

test("只有同一候选和映射修订的已通过试运行才能开启发布", () => {
    const trial = {
        status: "PASSED",
        qualifiesRelease: true,
        versionId: "candidate-v2",
        mappingRevision: 7
    };

    assert.equal(skills.canPublishTrial(trial, "candidate-v2", 7), true);
    assert.equal(skills.canPublishTrial({...trial, status: "NOT_TRIGGERED"}, "candidate-v2", 7), false);
    assert.equal(skills.canPublishTrial(trial, "candidate-v3", 7), false);
    assert.equal(skills.canPublishTrial(trial, "candidate-v2", 8), false);
});

test("从预检接口的 check 字段读取结构预检状态", () => {
    assert.equal(skills.preflightStatus({check: {status: "PASSED"}, items: []}), "PASSED");
    assert.equal(skills.preflightStatus({check: {status: "FAILED"}, items: []}), "FAILED");
    assert.equal(skills.preflightStatus({summary: {status: "PASSED"}}), "未知");
});

test("试运行 Agent 选择仅包含当前租户已启用且具有标识的 Agent", () => {
    const enabled = {id: "agent-1", name: "分析 Agent", enabled: true};
    assert.deepEqual(skills.eligibleTrialAgents([
        enabled,
        {id: "agent-2", name: "已停用 Agent", enabled: false},
        {name: "缺少标识", enabled: true},
        null
    ]), [enabled]);
    assert.deepEqual(skills.eligibleTrialAgents(null), []);
});

test("试运行预览将运行和发布状态映射为可读中文", () => {
    assert.equal(skills.runStatusLabel("WAITING_APPROVAL"), "等待审批");
    assert.equal(skills.runStatusLabel("SUCCEEDED"), "成功");
    assert.equal(skills.trialStatusLabel("NOT_TRIGGERED"), "运行完成，但未读取目标技能");
});

test("TEST 恢复只查询原 Run 和审批，不自动重放决定", async () => {
    const calls = [];
    const trial = {runId: "run", status: "FAILED", preview: {status: "DENIED", errorMessage: "工具审批已过期"}};
    const approval = {status: "EXPIRED", canDecide: false};
    const loaded = await skills.loadTrialState(async (url, options) => {
        calls.push({url, options});
        return url.endsWith("/current") ? approval : trial;
    }, "skill", "run");
    assert.deepEqual(loaded, {trial, approval});
    assert.deepEqual(calls, [
        {url: "/api/skills/skill/trials/run", options: undefined},
        {url: "/api/skills/skill/trials/run/approvals/current", options: undefined}
    ]);
});

// 简化 DOM 仅承载事件和文本，回归真实页面编排；布局验收由浏览器单独执行。
function trialPageHarness({expired = false, failQuery = false, previous = false, terminalApprovalStatus = "EXPIRED", startStatuses = []} = {}) {
    class Node {
        constructor(tag) { this.tag = tag; this.children = []; this.dataset = {}; this.listeners = {}; this.value = ""; this.className = ""; this.textContent = ""; this.classList = {add() {}, remove() {}, toggle() {}}; }
        append(...nodes) { this.children.push(...nodes); }
        replaceChildren(...nodes) { this.children = nodes; }
        setAttribute() {}
        addEventListener(name, callback) { this.listeners[name] = callback; }
        querySelectorAll(selector) { return this.all().filter((node) => selector === "button" ? node.tag === "button" : node.className.split(" ").includes(selector.slice(1))); }
        all() { return [this, ...this.children.flatMap((node) => node.all())]; }
        async fire(name) { if (!this.disabled) await this.listeners[name]?.({preventDefault() {}}); }
    }
    const ids = new Map();
    const document = {createElement: (tag) => new Node(tag), getElementById: (id) => {
        if (!ids.has(id)) ids.set(id, new Node("div"));
        return ids.get(id);
    }};
    const stored = new Map(previous ? [["cm-agent.skill-trial.skill", "run"]] : []);
    const oldWindow = global.window;
    global.window = {sessionStorage: {getItem: (key) => stored.get(key), setItem: (key, value) => stored.set(key, value), removeItem: (key) => stored.delete(key)}};
    const requests = [];
    let terminal = expired;
    const approval = () => ({approvalId: "approval", runId: "run", status: terminal ? terminalApprovalStatus : "PENDING", canDecide: !terminal, version: 1, items: [{itemId: "item", toolName: "测试工具"}], expiresAt: "2026-09-30T12:00:00Z"});
    const trial = () => ({runId: "run", status: terminal ? "FAILED" : "WAITING_APPROVAL", preview: {status: terminal ? "DENIED" : "WAITING_APPROVAL", errorMessage: terminal ? "工具审批已过期" : ""}});
    const api = {request: async (url, options) => {
        requests.push({url, options});
        if (url === "/api/skills?page=0&size=50") return {items: [{id: "skill"}]};
        if (url === "/api/skills/skill") return {summary: {candidateVersionId: "candidate"}, resources: []};
        if (url.endsWith("/dependencies")) return {revision: 1, items: []};
        if (url.endsWith("/versions")) return [];
        if (url === "/api/agents") return [{id: "agent", enabled: true}];
        if (url.endsWith("/decisions")) { terminal = true; throw new Error("审批已过期（错误编号：expiry-test）"); }
        if (url.endsWith("/trials") && options?.method === "POST") {
            const status = startStatuses.shift();
            return status ? {...trial(), status, qualifiesRelease: status === "PASSED", versionId: "candidate", mappingRevision: 1} : trial();
        }
        if (failQuery && terminal) throw new Error("查询暂时不可用");
        if (url.endsWith("/current")) return approval();
        if (url.endsWith("/trials/run")) return trial();
        throw new Error(`未配置测试接口：${url}`);
    }};
    const page = skills.createSkillPage({api, document, getSessionEpoch: () => 1, getPermissions: () => ["skill:write", "agent:read", "agent:run"]});
    const flush = () => new Promise((resolve) => setImmediate(resolve));
    return {requests, stored, document, page, flush, find: (text) => document.getElementById("skillDetail").all().find((node) => node.textContent === text), nodes: () => document.getElementById("skillDetail").all(), restore: () => { global.window = oldWindow; }};
}

async function openTrialPage(flow) {
    await flow.page.reload();
    await flow.document.getElementById("skillList").children[0].fire("click");
    await flow.flush();
}

async function startWaitingTrial(flow) {
    await openTrialPage(flow);
    const nodes = flow.nodes();
    nodes.find((node) => node.tag === "select").value = "agent";
    nodes.find((node) => node.tag === "textarea").value = "测试";
    await nodes.find((node) => node.tag === "form").fire("submit");
}

test("TEST 过期冲突后废弃旧按钮并查询权威终态和原因", async () => {
    const flow = trialPageHarness();
    try {
        await startWaitingTrial(flow);
        const oldSubmit = flow.find("提交审批决定");
        flow.nodes().find((node) => node.className === "inline-select").value = "APPROVE";
        await oldSubmit.fire("click");
        assert.equal(oldSubmit.disabled, true);
        assert.equal(flow.find("提交审批决定"), undefined);
        assert.ok(flow.find("高风险工具审批 · 审批已过期"));
        assert.ok(flow.find("工具审批已过期"));
        assert.ok(flow.nodes().some((node) => /expiry-test/.test(node.textContent)));
        assert.equal(flow.nodes().find((node) => node.className === "inline-select").disabled, true);
        await oldSubmit.fire("click");
        assert.equal(flow.requests.filter((call) => call.url.endsWith("/decisions")).length, 1);
    } finally { flow.restore(); }
});

test("TEST 响应丢失后权威审批已批准时不重放决定", async () => {
    const flow = trialPageHarness({terminalApprovalStatus: "APPROVED"});
    try {
        await startWaitingTrial(flow);
        flow.nodes().find((node) => node.className === "inline-select").value = "APPROVE";
        await flow.find("提交审批决定").fire("click");
        assert.ok(flow.find("高风险工具审批 · 已允许本次调用"));
        assert.equal(flow.find("提交审批决定"), undefined);
        assert.equal(flow.requests.filter((call) => call.url.endsWith("/decisions")).length, 1);
        assert.ok(flow.nodes().some((node) => /实际执行结果/.test(node.textContent)));
    } finally { flow.restore(); }
});

test("TEST 决定断线且查询失败持续锁定，刷新只 GET 并保留恢复标识", async () => {
    const flow = trialPageHarness({failQuery: true});
    try {
        await startWaitingTrial(flow);
        flow.nodes().find((node) => node.className === "inline-select").value = "APPROVE";
        await flow.find("提交审批决定").fire("click");
        assert.equal(flow.find("提交审批决定"), undefined);
        assert.ok(flow.nodes().some((node) => /expiry-test.*尚未确认结果/.test(node.textContent)));
        const before = flow.requests.length;
        await flow.find("刷新试运行状态").fire("click");
        assert.ok(flow.requests.slice(before).every((call) => !call.options));
        assert.equal(flow.stored.get("cm-agent.skill-trial.skill"), "run");
        assert.equal(flow.requests.filter((call) => call.options?.method === "POST").length, 2);
    } finally { flow.restore(); }
});

test("TEST 页面重载从保存 Run 查询过期状态，不重复 TEST 或审批", async () => {
    const flow = trialPageHarness({expired: true, previous: true});
    try {
        await openTrialPage(flow);
        await flow.flush();
        assert.ok(flow.find("工具审批已过期"));
        assert.ok(flow.find("高风险工具审批 · 审批已过期"));
        assert.equal(flow.find("提交审批决定"), undefined);
        assert.ok(flow.requests.every((call) => !call.options));
        assert.equal(flow.stored.size, 0);
    } finally { flow.restore(); }
});

for (const nextStatus of ["NOT_TRIGGERED", "WAITING_APPROVAL"]) {
    test(`新 TEST ${nextStatus} 不保留上一轮 PASSED 发布入口`, async () => {
        const flow = trialPageHarness({startStatuses: ["PASSED", nextStatus]});
        try {
            await startWaitingTrial(flow);
            assert.ok(flow.find("发布当前候选"));
            await flow.nodes().find((node) => node.tag === "form").fire("submit");
            assert.equal(flow.find("发布当前候选"), undefined);
            assert.equal(flow.requests.filter((call) => call.url.endsWith("/releases")).length, 0);
        } finally { flow.restore(); }
    });
}
