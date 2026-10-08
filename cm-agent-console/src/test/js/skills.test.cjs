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
function trialPageHarness({permissions = ["skill:write", "agent:read", "agent:run"], summary = {name: "sandbox-smoke-test", versionNo: 1, candidateVersionId: "candidate", publishedVersionId: "published"}, versionUpload, createUpload, expired = false, failQuery = false, previous = false, terminalApprovalStatus = "EXPIRED", startStatuses = []} = {}) {
    class Node {
        constructor(tag) { this.tag = tag; this.children = []; this.dataset = {}; this.listeners = {}; this.value = ""; this.files = []; this.className = ""; this.textContent = ""; this.classList = {add() {}, remove() {}, toggle() {}}; }
        append(...nodes) { this.children.push(...nodes); }
        replaceChildren(...nodes) { this.children = nodes; }
        setAttribute() {}
        addEventListener(name, callback) { this.listeners[name] = callback; }
        querySelectorAll(selector) { return this.all().filter((node) => selector === "input, button" ? ["input", "button"].includes(node.tag) : selector === "button" ? node.tag === "button" : node.className.split(" ").includes(selector.slice(1))); }
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
        if (url === "/api/skills/skill") return {summary: {...summary}, resources: []};
        if (url === "/api/skills" && options?.method === "POST") return createUpload ? createUpload(url, options) : {summary: {...summary, id: "skill"}};
        if (url.endsWith("/dependencies")) return {revision: 1, items: []};
        if (url.endsWith("/versions") && options?.method === "POST") return versionUpload ? versionUpload(url, options) : {summary};
        if (url.endsWith("/versions")) return [{versionId: "published", versionNo: 1, state: "CURRENT_PUBLISHED"}];
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
    const page = skills.createSkillPage({api, document, getSessionEpoch: () => 1, getPermissions: () => permissions});
    const flush = () => new Promise((resolve) => setImmediate(resolve));
    return {requests, stored, document, page, flush, find: (text) => document.getElementById("skillDetail").all().find((node) => node.textContent === text), nodes: () => document.getElementById("skillDetail").all(), restore: () => { global.window = oldWindow; }};
}

function chooseUpgradeFile(flow) {
    const form = flow.nodes().find((node) => node.className === "skill-version-upload-form");
    const input = form.all().find((node) => node.tag === "input");
    input.files = [new File(["测试 ZIP"], "sandbox-v2.zip", {type: "application/zip"})];
    return {form, input};
}

test("升级上传携带双指针并保持技能上下文，不发送旧单指针或发布请求", async () => {
    const summary = {id: "skill", name: "sandbox-smoke-test", versionNo: 1, candidateVersionId: null, publishedVersionId: "published"};
    const flow = trialPageHarness({summary, versionUpload: async () => {
        Object.assign(summary, {candidateVersionId: "candidate-v2", versionNo: 2});
        return {summary};
    }});
    try {
        await openTrialPage(flow);
        await chooseUpgradeFile(flow).form.fire("submit");
        const call = flow.requests.find((call) => call.options?.method === "POST");
        assert.equal(call.url, "/api/skills/skill/versions");
        assert.ok(call.options.body instanceof FormData);
        assert.equal(call.options.body.get("file").name, "sandbox-v2.zip");
        assert.equal(call.options.body.get("expectedCandidateVersionId"), null);
        assert.equal(call.options.body.get("expectedPublishedVersionId"), "published");
        assert.equal(call.options.body.get("expectedVersionId"), null);
        assert.match(flow.document.getElementById("skillPageStatus").textContent, /新候选已上传/);
        assert.ok(flow.find("v2"));
        assert.equal(flow.requests.filter((call) => call.options?.method === "POST").length, 1);
    } finally { flow.restore(); }
});

test("替换已有候选时发送其预期指针，相同内容不宣称新增版本", async () => {
    const flow = trialPageHarness();
    try {
        await openTrialPage(flow);
        await chooseUpgradeFile(flow).form.fire("submit");
        const call = flow.requests.find((call) => call.options?.method === "POST");
        assert.equal(call.options.body.get("expectedCandidateVersionId"), "candidate");
        assert.match(flow.document.getElementById("skillPageStatus").textContent, /未新增版本/);
    } finally { flow.restore(); }
});

test("候选冲突保留文件和错误编号，刷新只查询不重放上传", async () => {
    const flow = trialPageHarness({versionUpload: async () => {
        throw Object.assign(new Error("候选版本已变化（SKILL_CANDIDATE_CONFLICT，错误编号：upgrade-conflict）"), {code: "SKILL_CANDIDATE_CONFLICT"});
    }});
    try {
        await openTrialPage(flow);
        const {form, input} = chooseUpgradeFile(flow);
        await form.fire("submit");
        assert.equal(input.files[0].name, "sandbox-v2.zip");
        const result = form.all().find((node) => node.className === "form-status");
        assert.match(result.textContent, /upgrade-conflict.*刷新技能详情/);
        assert.equal(result.dataset.tone, "error");
        await flow.find("刷新技能详情").fire("click"); await flow.flush();
        assert.equal(flow.requests.filter((call) => call.options?.method === "POST").length, 1);
    } finally { flow.restore(); }
});

test("升级请求执行中拒绝重复提交，迟到结果不能覆盖刷新后的页面", async () => {
    let finish;
    const flow = trialPageHarness({versionUpload: () => new Promise((resolve) => { finish = resolve; })});
    try {
        await openTrialPage(flow);
        const {form, input} = chooseUpgradeFile(flow);
        const pending = form.fire("submit");
        assert.equal(input.disabled, true);
        assert.equal(flow.find("正在上传…").disabled, true);
        await form.fire("submit");
        await flow.page.reload();
        finish({summary: {candidateVersionId: "new"}}); await pending;
        assert.equal(flow.requests.filter((call) => call.options?.method === "POST").length, 1);
        assert.equal(flow.document.getElementById("skillPageStatus").textContent, "");
    } finally { flow.restore(); }
});

test("没有写权限时不展示升级入口，权限撤销后旧表单不能提交", async () => {
    const permissions = ["skill:read"];
    const flow = trialPageHarness({permissions});
    try {
        await openTrialPage(flow);
        assert.equal(flow.find("上传新版本"), undefined);
        permissions.push("skill:write");
        await openTrialPage(flow);
        const {form} = chooseUpgradeFile(flow);
        permissions.pop(); await form.fire("submit");
        assert.ok(flow.requests.every((call) => !call.options?.method));
    } finally { flow.restore(); }
});

test("升级缺少文件时给出就地提示且不发送请求", async () => {
    const flow = trialPageHarness();
    try {
        await openTrialPage(flow);
        await flow.nodes().find((node) => node.className === "skill-version-upload-form").fire("submit");
        assert.ok(flow.find("请选择新版本 ZIP 技能包。"));
        assert.ok(flow.requests.every((call) => !call.options?.method));
    } finally { flow.restore(); }
});

test("网络失败保留文件并提示先确认结果，不自动重试", async () => {
    const flow = trialPageHarness({versionUpload: async () => { throw new Error("网络连接失败，请稍后重试。"); }});
    try {
        await openTrialPage(flow);
        const {form, input} = chooseUpgradeFile(flow);
        await form.fire("submit");
        assert.equal(input.disabled, false);
        assert.ok(flow.nodes().some((node) => /先刷新详情确认上传结果/.test(node.textContent)));
        assert.equal(flow.requests.filter((call) => call.options?.method === "POST").length, 1);
    } finally { flow.restore(); }
});

test("技能版本状态显示中文且版本号使用真实 versionNo 字段", async () => {
    assert.equal(skills.versionStateLabel("CURRENT_PUBLISHED"), "当前正式版本");
    assert.equal(skills.versionStateLabel("PREVIOUSLY_PUBLISHED"), "曾发布，可回滚");
    assert.equal(skills.versionStateLabel("CANDIDATE"), "待验证候选");
    assert.equal(skills.versionStateLabel("UNPUBLISHED_HISTORY"), "未发布历史");
    const flow = trialPageHarness();
    try {
        await openTrialPage(flow);
        assert.ok(flow.find("v1"));
        assert.ok(flow.find("v1 · 当前正式版本"));
        assert.equal(flow.find("v—"), undefined);
    } finally { flow.restore(); }
});

test("新技能同名冲突引导升级且只发送创建请求", async () => {
    const flow = trialPageHarness({createUpload: async () => {
        throw Object.assign(new Error("同名技能已存在（SKILL_CONFLICT，错误编号：duplicate-test）"), {code: "SKILL_CONFLICT"});
    }});
    try {
        flow.page.mount(); await flow.flush();
        flow.document.getElementById("skillFile").files = [new File(["ZIP"], "same-name.zip")];
        await flow.document.getElementById("skillUploadForm").fire("submit");
        assert.match(flow.document.getElementById("skillPageStatus").textContent, /duplicate-test.*上传新版本/);
        assert.equal(flow.requests.filter((call) => call.options?.method === "POST").length, 1);
        assert.equal(flow.requests.at(-1).url, "/api/skills");
    } finally { flow.restore(); }
});

test("创建成功保留成功提示并自动选中导入的技能", async () => {
    const flow = trialPageHarness();
    try {
        flow.page.mount(); await flow.flush();
        flow.document.getElementById("skillFile").files = [new File(["ZIP"], "new.zip")];
        await flow.document.getElementById("skillUploadForm").fire("submit");
        assert.match(flow.document.getElementById("skillPageStatus").textContent, /技能已导入/);
        assert.ok(flow.find("sandbox-smoke-test"));
    } finally { flow.restore(); }
});

test("详情刷新后旧升级表单不能提交旧指针", async () => {
    const flow = trialPageHarness();
    try {
        await openTrialPage(flow);
        const {form} = chooseUpgradeFile(flow);
        await openTrialPage(flow);
        await form.fire("submit");
        assert.ok(flow.requests.every((call) => !call.options?.method));
    } finally { flow.restore(); }
});

test("刷新列表后恢复当前详情并允许新表单提交", async () => {
    const flow = trialPageHarness();
    try {
        flow.page.mount(); await flow.flush();
        await openTrialPage(flow);
        const oldForm = chooseUpgradeFile(flow).form;
        await flow.document.getElementById("refreshSkillsBtn").fire("click");
        const {form} = chooseUpgradeFile(flow);
        assert.notEqual(form, oldForm);
        await form.fire("submit");
        assert.equal(flow.requests.filter((call) => call.options?.method === "POST").length, 1);
    } finally { flow.restore(); }
});

test("已启用但未发布的候选不会显示为正式可用", async () => {
    const flow = trialPageHarness({summary: {name: "sandbox-smoke-test", enabled: true, versionNo: 1, candidateVersionId: "candidate", publishedVersionId: null}});
    try {
        await openTrialPage(flow);
        assert.ok(flow.find("已启用但尚未发布，暂不能用于正式运行"));
    } finally { flow.restore(); }
});

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
    await nodes.find((node) => node.className === "skill-trial-form").fire("submit");
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
            await flow.nodes().find((node) => node.className === "skill-trial-form").fire("submit");
            assert.equal(flow.find("发布当前候选"), undefined);
            assert.equal(flow.requests.filter((call) => call.url.endsWith("/releases")).length, 0);
        } finally { flow.restore(); }
    });
}
