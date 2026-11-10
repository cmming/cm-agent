const test = require("node:test"), assert = require("node:assert/strict");
const development = require("../../main/resources/META-INF/resources/console/v2/assets/project-development.js");
const reference = {operationId: "11111111-1111-4111-8111-111111111111", version: 1, digest: "a".repeat(64)};
const summary = `项目操作 operationId=${reference.operationId} version=1 digest=${reference.digest}`;
test("审批引用必须精确匹配，缺少版本或摘要的预览失败关闭", () => {
    assert.deepEqual(development.operationReference(summary), reference);
    assert.deepEqual(development.operationReference(
        `项目命令 commandId=${reference.operationId} version=1 digest=${reference.digest}`),
        reference);
    for (const raw of [summary + "\n", summary + " extra", summary.replace("version=1", "version=0"), "operationId=x", ""]) assert.equal(development.operationReference(raw), null);
    assert.equal(development.matchesPreview(reference, {view: reference}), true);
    assert.equal(development.matchesPreview(reference, {...reference, digest: "b".repeat(64)}), false);
    assert.equal(development.matchesPreview(reference, {operationId: reference.operationId}), false);
    assert.equal(development.completePreview({view: reference, changes: [{}]}, false), false);
    assert.equal(development.completePreview({view: reference}, false), false);
    assert.equal(development.completePreview({...reference, executable: "java"}, true), false);
});
function dom() {
    class Element {
        constructor(tag) { this.tag = tag; this.children = []; this.events = {}; this.dataset = {}; this.value = ""; this.checked = false; this.disabled = false; }
        set textContent(value) { this.text = value; this.children = []; }
        get textContent() { return this.text; }
        append(...items) { this.children.push(...items); }
        replaceChildren(...items) { this.children = items; }
        addEventListener(event, callback) { this.events[event] = callback; }
        setAttribute() {}
        querySelectorAll() { return this.children.flatMap(item => [item, ...item.querySelectorAll()]).filter(item => ["select", "input", "button"].includes(item.tag)); }
        click() { return this.events.click?.(); }
    }
    const nodes = new Map();
    return {getElementById(id) { if (!nodes.has(id)) nodes.set(id, new Element("div")); return nodes.get(id); }, createElement: tag => new Element(tag)};
}
function all(element) { return [element, ...element.children.flatMap(all)]; }
async function setup({preview = {view: reference, changes: [{kind: "REPLACE", path: "README.md", before: "OLD_PRIVATE", after: "<script>NEW_PRIVATE</script>"}]}, previewRead = null, decisionFailure = false, command = false, recover = false, operationState = "SUCCEEDED", resolveFailure = false, archived = false, activeStatus = null, authorizationMode = "ASK_ON_RISK", scheduler = globalThis, cancelFailure = false, runRead = null, runLookupFailure = false, pendingApproval = true, newConversation = false} = {}) {
    const document = dom(), calls = [], conversation = {id: "conversation", agentId: "agent", title: "开发会话"};
    const labels = {"": "高风险前确认", ASK_ON_RISK: "高风险前确认", ASK_ON_EFFECT: "副作用前确认", FULL_ACCESS: "自动执行已授权操作"};
    Object.defineProperty(document.getElementById("projectAuthorizationMode"), "selectedOptions", {get() { return [{textContent: labels[this.value]}]; }});
    const approval = {approvalId: "approval", runId: "run", version: 2, status: "PENDING", canDecide: true,
        items: [{itemId: "item", toolName: command ? "project_run_command" : "project_apply_changes", inputSummary: summary}]};
    let live = true, runStatus = activeStatus;
    const page = development.create({document, host: scheduler, isCurrent: () => live, request: async (url, options) => {
        calls.push([url, options]);
        if (url.endsWith("/conversations/conversation/active-run")) { if (runLookupFailure) throw new Error("活动状态查询失败"); return runStatus ? {id: "active-run", status: runStatus} : null; }
        if (url.endsWith("/runs/active-run/authorization-mode")) return {mode: authorizationMode};
        if (url.endsWith("/runs/active-run/cancel")) { if (cancelFailure) throw new Error("停止响应丢失"); runStatus = "CANCELLING"; return {id: "active-run", status: runStatus}; }
        if (url.endsWith("/runs/active-run") && runRead) await runRead();
        if (url.endsWith("/runs/active-run")) return {id: "active-run", status: runStatus};
        if (url === "/api/agents") return [{id: "agent", enabled: true, name: "Agent"}];
        if (newConversation && url.endsWith("/conversations") && options?.method === "POST") return conversation;
        if (url.includes("conversations?")) return [conversation];
        if (url.includes("/messages?")) return {items: recover ? [{id: "saved", runId: "saved-message-run", contentBlocks: []}] : []};
        if (url.includes("/approvals/history?")) return {items: recover ? [{runId: "saved-history-run"}] : []};
        if (url.includes("/operations?")) return recover ? [{...reference, version: 7, kind: "COMMAND", state: operationState, notice: "<script>人工核对说明</script>"}] : [];
        if (url.endsWith("/resolve-unknown")) { if (resolveFailure) throw new Error("网络中断"); operationState = "CANCELLED"; return {}; }
        if (url.endsWith("/approvals")) return {items: pendingApproval ? [structuredClone(approval)] : []};
        if (url.endsWith("/changes/" + reference.operationId)) { if (previewRead) await previewRead(); return preview; }
        if (url.endsWith("/commands/" + reference.operationId + "?preview=true")) return preview;
        if (url.endsWith("/commands/" + reference.operationId)) return {state: "RUNNING", sequence: 1, outputAvailable: true, stdout: "PRIVATE_STDOUT", stderr: "", truncated: true};
        if (url.endsWith("/cancel")) throw new Error("停止结果未知");
        if (url.endsWith("/decision")) { if (decisionFailure) throw new Error("网络未知"); return {run: {status: "SUCCEEDED"}}; }
        if (url.endsWith("/messages") && options?.method === "POST") return {run: {status: "SUCCEEDED"}};
        throw new Error(url);
    }});
    await page.mount({id: "project", state: archived ? "ARCHIVED" : "ACTIVE"}, {conversations: true, changes: true, commands: true});
    await document.getElementById("projectConversationList").children[0].click();
    const parts = () => all(document.getElementById("projectApprovalItems"));
    return {document, calls, page, parts, setApproval(value) { Object.assign(approval, value); }, setRunStatus(value) { runStatus = value; }, stop() { live = false; page.dispose(); }};
}

test("相同审批多次轮询后原预览按钮仍可读取且已核对选择保持有效", async () => {
    const clock = timers(), flow = await setup({activeStatus: "WAITING_APPROVAL", scheduler: clock});
    const nodes = flow.parts(), preview = nodes.find(item => item.textContent === "读取精确预览");
    const reviewed = nodes.find(item => item.tag === "input"), choice = nodes.find(item => item.tag === "select");
    await clock.tick(); await clock.tick();
    assert.equal(flow.parts().find(item => item.textContent === "读取精确预览"), preview);
    await preview.click();
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/changes/" + reference.operationId)).length, 1);
    assert.equal(reviewed.disabled, false);
    reviewed.checked = true; reviewed.events.change(); choice.value = "APPROVE"; choice.events.change();
    await clock.tick();
    assert.equal(flow.parts().find(item => item.tag === "input"), reviewed); assert.equal(reviewed.checked, true);
    assert.equal(choice.value, "APPROVE");
    await nodes.find(item => item.textContent === "提交本次决定").click();
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/decision")).length, 1);
    assert.match(flow.document.getElementById("projectChatStatus").textContent, /审批决定已接受.*成功/); flow.stop();
});

test("审批版本变化后迟到预览与旧提交控件均失效且不能解锁新审批", async () => {
    let release;
    const flow = await setup({previewRead: () => new Promise(resolve => { release = resolve; })});
    const old = flow.parts(), oldReviewed = old.find(item => item.tag === "input");
    const pending = old.find(item => item.textContent === "读取精确预览").click();
    const oldChoice = old.find(item => item.tag === "select"); oldChoice.value = "DENY"; oldChoice.events.change();
    assert.equal(old.find(item => item.textContent === "提交本次决定").disabled, false);
    flow.setApproval({version: 3}); await flow.document.getElementById("projectChatRefresh").click();
    const currentReviewed = flow.parts().find(item => item.tag === "input");
    assert.notEqual(currentReviewed, oldReviewed);
    release(); await pending;
    assert.equal(oldReviewed.disabled, true); assert.equal(currentReviewed.disabled, true);
    assert.equal(flow.parts().some(item => item.tag === "pre" && item.textContent?.includes("NEW_PRIVATE")), false);
    await old.find(item => item.textContent === "提交本次决定").click();
    assert.equal(flow.calls.some(([url]) => url.endsWith("/decision")), false); flow.stop();
});
test("默认不批准，核对精确差异后才可允许且仅提交明细决定", async () => {
    const flow = await setup(); let nodes = flow.parts();
    const choice = nodes.find(item => item.tag === "select"), reviewed = nodes.find(item => item.tag === "input");
    const allow = choice.children.find(item => item.value === "APPROVE");
    assert.equal(choice.value, ""); assert.equal(allow.disabled, true);
    assert.equal(flow.document.getElementById("projectChatSend").disabled, true);
    await nodes.find(item => item.textContent === "读取精确预览").click();
    assert.equal(reviewed.disabled, false);
    const rendered = flow.parts();
    assert.ok(rendered.some(item => item.tag === "pre" && item.textContent === "<script>NEW_PRIVATE</script>"));
    assert.ok(rendered.some(item => item.tag === "h6" && item.textContent === "变更前"));
    assert.ok(rendered.some(item => item.tag === "h6" && item.textContent === "变更后"));
    assert.equal(rendered.some(item => item.tag === "strong" && item.textContent === "project_apply_changes"), false);
    reviewed.checked = true; reviewed.events.change(); assert.equal(allow.disabled, false);
    choice.value = "APPROVE"; choice.events.change();
    const submit = nodes.find(item => item.textContent === "提交本次决定"); assert.equal(submit.disabled, false);
    await submit.click();
    const writes = flow.calls.filter(([url]) => url.endsWith("/decision"));
    assert.equal(writes.length, 1);
    assert.deepEqual(JSON.parse(writes[0][1].body), {expectedVersion: 2, decisions: [{itemId: "item", decision: "APPROVE"}]});
    assert.doesNotMatch(writes[0][1].body, /PRIVATE|digest/); flow.stop();
});
test("预览摘要变化不能允许但拒绝仍可提交，断线不会重放", async () => {
    const flow = await setup({preview: {view: {...reference, version: 2}}, decisionFailure: true}), nodes = flow.parts();
    await nodes.find(item => item.textContent === "读取精确预览").click();
    assert.equal(nodes.find(item => item.tag === "input").disabled, true);
    const choice = nodes.find(item => item.tag === "select"); choice.value = "DENY"; choice.events.change();
    const submit = nodes.find(item => item.textContent === "提交本次决定"); await submit.click(); await submit.click();
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/decision")).length, 1);
    flow.stop();
});
test("离页清空精确正文，旧刷新和旧会话入口不能继续发请求", async () => {
    const flow = await setup(), nodes = flow.parts();
    await nodes.find(item => item.textContent === "读取精确预览").click();
    const oldConversation = flow.document.getElementById("projectConversationList").children[0];
    flow.stop(); const count = flow.calls.length;
    await oldConversation.click(); await flow.document.getElementById("projectChatRefresh").click();
    assert.equal(flow.calls.length, count);
    assert.equal(flow.document.getElementById("projectApprovalItems").children.length, 0);
});
test("命令先核对启动清单，输出留在专用区域，停止未知不重发", async () => {
    const flow = await setup({command: true, preview: {...reference, toolchainId: "java", executable: "java.exe", executableHash: "b".repeat(64),
        arguments: ["-version"], relativeDirectory: "", environment: {}, timeoutSeconds: 30, maxOutputBytes: 4096, buildHashes: {}}});
    await flow.parts().find(item => item.textContent === "读取精确预览").click();
    const previewNodes = flow.parts();
    assert.ok(previewNodes.some(item => item.tag === "dt" && item.textContent === "逻辑工具链"));
    assert.ok(previewNodes.some(item => item.tag === "li" && item.textContent === "-version"));
    assert.ok(previewNodes.some(item => item.tag === "summary" && item.textContent === "版本与定位信息"));
    const nodes = all(flow.document.getElementById("projectCommandResults"));
    const stop = nodes.find(item => item.textContent === "停止这次命令"); assert.equal(stop.disabled, true);
    await nodes.find(item => item.textContent === "刷新状态与输出").click(); assert.equal(stop.disabled, false);
    assert.ok(nodes.some(item => item.tag === "pre" && item.textContent?.includes("PRIVATE_STDOUT")));
    await stop.click(); await stop.click(); assert.equal(stop.disabled, true);
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/cancel")).length, 1);
    flow.stop(); assert.equal(flow.document.getElementById("projectCommandResults").children.length, 0);
});
test("刷新会话从服务端消息和审批历史恢复命令入口，只查询已知运行", async () => {
    const flow = await setup({recover: true});
    const reads = flow.calls.filter(([url]) => url.includes("/operations?"));
    assert.ok(reads.some(([url]) => url.includes("/runs/saved-message-run/")));
    assert.ok(reads.some(([url]) => url.includes("/runs/saved-history-run/")));
    assert.ok(all(flow.document.getElementById("projectCommandResults")).some(item => item.textContent === "读取精确命令清单"));
    assert.equal(flow.calls.filter(([, options]) => options?.method === "POST").length, 0);
    flow.stop();
});
test("文件引用只追加相对路径和版本，不含正文，不发送也不自动建会话", async () => {
    const flow = await setup(), input = flow.document.getElementById("projectChatInput"); input.value = "请检查这个文件";
    const count = flow.calls.length;
    flow.page.referenceFile({path: "src/Main.java", sha256: "c".repeat(64), offset: 0, nextOffset: 4096, content: "PRIVATE_FILE_BODY"});
    assert.match(input.value, /请检查这个文件/); assert.match(input.value, /expectedSha256/);
    assert.match(input.value, /src\/Main.java/); assert.match(input.value, /4096/); assert.doesNotMatch(input.value, /PRIVATE_FILE_BODY/);
    assert.equal(flow.calls.length, count);
    for (const path of ["../secret", "C:/private", "/private", "src/../private", "src\\private", "src\nprivate"]) {
        assert.throws(() => development.fileReference({path, sha256: "c".repeat(64), offset: 0, nextOffset: 1}));
    }
    flow.stop(); assert.throws(() => flow.page.referenceFile({}), /先选择或新建/);
});
test("UNKNOWN默认不确认，明确人工核对后只终结最新版本原意图，成功仅GET核对", async () => {
    const flow = await setup({recover: true, operationState: "UNKNOWN"});
    const nodes = all(flow.document.getElementById("projectCommandResults"));
    const check = nodes.find(item => item.tag === "input"), resolve = nodes.find(item => item.textContent === "终结原意图");
    assert.equal(check.checked, false); assert.equal(resolve.disabled, true);
    assert.ok(nodes.some(item => item.textContent === "<script>人工核对说明</script>"));
    await resolve.click(); assert.equal(flow.calls.some(([url]) => url.endsWith("/resolve-unknown")), false);
    check.checked = true; check.events.change(); await resolve.click(); await resolve.click();
    const writes = flow.calls.filter(([url]) => url.endsWith("/resolve-unknown")); assert.equal(writes.length, 1);
    assert.deepEqual(JSON.parse(writes[0][1].body), {expectedVersion: 7, acknowledgeExternalEffects: true});
    const refreshed = all(flow.document.getElementById("projectCommandResults"));
    assert.ok(refreshed.some(item => item.textContent === "原意图已终结"));
    assert.equal(all(flow.document.getElementById("projectCommandResults").children[0]).some(item => item.textContent === "终结原意图"), false);
    flow.stop();
});
test("人工收口网络未知不自动重复，刷新权威状态后重新人工确认", async () => {
    const flow = await setup({recover: true, operationState: "UNKNOWN", resolveFailure: true});
    const nodes = all(flow.document.getElementById("projectCommandResults"));
    const check = nodes.find(item => item.tag === "input"), resolve = nodes.find(item => item.textContent === "终结原意图");
    check.checked = true; check.events.change(); await resolve.click(); await resolve.click();
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/resolve-unknown")).length, 1);
    assert.ok(nodes.some(item => item.textContent?.includes("收口结果尚未确认")));
    await flow.document.getElementById("projectOperationsRefresh").click();
    const refreshed = all(flow.document.getElementById("projectCommandResults"));
    assert.equal(refreshed.find(item => item.tag === "input").checked, false);
    assert.equal(refreshed.find(item => item.textContent === "终结原意图").disabled, true);
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/resolve-unknown")).length, 1); flow.stop();
});


test("归档会话读取消息审批历史和操作元数据，禁用开发与副作用入口", async () => {
    const flow = await setup({archived: true, recover: true, operationState: "UNKNOWN"});
    assert.equal(flow.document.getElementById("projectChatForm").hidden, true);
    assert.equal(flow.document.getElementById("projectChatInput").disabled, true);
    assert.equal(flow.document.getElementById("projectNewConversation").hidden, true);
    assert.equal(flow.calls.some(([url]) => url === "/api/agents" || url.endsWith("/approvals")), false);
    assert.ok(flow.calls.some(([url]) => url.includes("/messages?")));
    assert.ok(flow.calls.some(([url]) => url.includes("/approvals/history?")));
    assert.ok(flow.calls.some(([url]) => url.includes("/operations?")));
    assert.ok(flow.parts().some(item => item.textContent === "审批历史（只读）"));
    const actions = all(flow.document.getElementById("projectCommandResults")).filter(item => item.tag === "button");
    assert.equal(actions.length, 0);
    const count = flow.calls.length;
    flow.document.getElementById("projectChatAgent").value = "agent";
    flow.document.getElementById("projectChatInput").value = "不应发送";
    await flow.document.getElementById("projectNewConversation").click();
    await flow.document.getElementById("projectChatForm").events.submit({preventDefault() {}});
    assert.throws(() => flow.page.referenceFile({}), /归档项目/);
    assert.equal(flow.calls.length, count);
    assert.equal(flow.calls.some(([, options]) => options?.method === "POST"), false); flow.stop();
});


function timers() {
    let next = 1; const pending = new Map();
    return {setTimeout(callback) { const id = next++; pending.set(id, callback); return id; }, clearTimeout(id) { pending.delete(id); },
        size: () => pending.size, async tick() { const entry = pending.entries().next().value; if (!entry) throw new Error("没有待执行轮询"); pending.delete(entry[0]); await entry[1](); }};
}
test("恢复活动运行后停止只投一次，轮询服务端终态后停止计时且审批预览不被相同响应清除", async () => {
    const clock = timers(), flow = await setup({activeStatus: "RUNNING", scheduler: clock});
    const stop = flow.document.getElementById("projectRunStop");
    assert.equal(stop.hidden, false); assert.equal(stop.disabled, false); assert.equal(clock.size(), 1);
    await flow.parts().find(item => item.textContent === "读取精确预览").click();
    const reviewed = flow.parts().find(item => item.tag === "input"); reviewed.checked = true; reviewed.events.change();
    await clock.tick();
    assert.equal(flow.parts().find(item => item.tag === "input"), reviewed); assert.equal(reviewed.checked, true);
    await stop.click(); await stop.click();
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/runs/active-run/cancel")).length, 1);
    assert.match(flow.document.getElementById("projectRunStatus").textContent, /正在停止/);
    assert.equal(stop.disabled, true); assert.equal(clock.size(), 1);
    flow.setRunStatus("CANCELLED"); await clock.tick();
    assert.equal(clock.size(), 0); assert.equal(stop.hidden, true);
    assert.ok(flow.calls.some(([url]) => url.endsWith("/runs/active-run"))); flow.stop();
});

test("活动运行轮询离页清除计时器，归档历史不探测运行也不启动轮询", async () => {
    const clock = timers(), flow = await setup({activeStatus: "RUNNING", scheduler: clock});
    assert.equal(clock.size(), 1); flow.stop(); assert.equal(clock.size(), 0);
    const history = await setup({archived: true, scheduler: clock});
    assert.equal(clock.size(), 0); assert.equal(history.calls.some(([url]) => url.endsWith("/active-run")), false); history.stop();
});


test("停止响应未知不会重发，重叠刷新只查询一批并在三次连续失败后暂停轮询", async () => {
    let unblock, fail = false;
    const clock = timers(), flow = await setup({activeStatus: "RUNNING", cancelFailure: true, scheduler: clock,
        runRead: async () => { if (fail) throw new Error("状态服务暂不可用"); await new Promise(resolve => { unblock = resolve; }); }});
    const tick = clock.tick();
    await Promise.resolve();
    const refresh = flow.document.getElementById("projectChatRefresh").click();
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/runs/active-run")).length, 1);
    unblock(); await Promise.all([tick, refresh]);
    fail = true;
    await flow.document.getElementById("projectRunStop").click(); await flow.document.getElementById("projectRunStop").click();
    assert.equal(flow.calls.filter(([url]) => url.endsWith("/runs/active-run/cancel")).length, 1);
    while (clock.size()) await clock.tick();
    assert.equal(clock.size(), 0); assert.match(flow.document.getElementById("projectChatStatus").textContent, /自动查询已暂停/);
    flow.stop();
});

test("运行中的命令经受控轮询刷新安全输出，停止页面后不再请求", async () => {
    const clock = timers(), flow = await setup({recover: true, operationState: "RUNNING", scheduler: clock});
    assert.equal(clock.size(), 1); await clock.tick();
    assert.ok(all(flow.document.getElementById("projectCommandResults")).some(item => item.tag === "pre" && item.textContent?.includes("PRIVATE_STDOUT")));
    assert.ok(flow.calls.some(([url]) => url.endsWith("/commands/" + reference.operationId)));
    flow.stop(); assert.equal(clock.size(), 0);
});


test("活动状态查询失败即使没有待审批仍禁止发送，不把未知结果当作空闲", async () => {
    const flow = await setup({runLookupFailure: true, pendingApproval: false});
    assert.equal(flow.document.getElementById("projectChatSend").disabled, true);
    flow.document.getElementById("projectChatInput").value = "不应派发";
    await flow.document.getElementById("projectChatForm").events.submit({preventDefault() {}});
    assert.equal(flow.calls.some(([, options]) => options?.method === "POST"), false); flow.stop();
});

test("项目消息提交本轮授权方式且活动运行期间锁定授权控件", async () => {
    const flow = await setup({pendingApproval: false});
    const mode = flow.document.getElementById("projectAuthorizationMode");
    assert.equal(mode.value, "");
    assert.match(flow.document.getElementById("projectAuthorizationHelp").textContent, /高风险/);
    mode.value = "FULL_ACCESS"; mode.events.change();
    assert.match(flow.document.getElementById("projectAuthorizationHelp").textContent, /不能撤销/);
    assert.equal(flow.document.getElementById("projectAuthorizationSelection").textContent, "自动执行已授权操作");
    mode.value = "ASK_ON_EFFECT"; mode.events.change();
    assert.equal(flow.document.getElementById("projectAuthorizationSelection").textContent, "副作用前确认");
    mode.value = "FULL_ACCESS"; mode.events.change();
    flow.document.getElementById("projectChatInput").value = "修改文件并运行测试";
    await flow.document.getElementById("projectChatForm").events.submit({preventDefault() {}});
    const write = flow.calls.find(([url, options]) => url.endsWith("/messages") && options?.method === "POST");
    assert.deepEqual(JSON.parse(write[1].body), {input: "修改文件并运行测试", projectAuthorizationMode: "FULL_ACCESS"});
    assert.equal(mode.disabled, false);
    flow.stop();
});

test("审批恢复按原Run快照还原授权方式而不是使用默认选项", async () => {
    const flow = await setup({activeStatus: "WAITING_APPROVAL", authorizationMode: "ASK_ON_EFFECT", pendingApproval: false});
    const mode = flow.document.getElementById("projectAuthorizationMode");
    assert.equal(mode.value, "ASK_ON_EFFECT");
    assert.equal(flow.document.getElementById("projectAuthorizationSelection").textContent, "副作用前确认");
    assert.match(flow.document.getElementById("projectAuthorizationHelp").textContent, /只读且非高风险/);
    assert.equal(mode.disabled, true);
    flow.stop();
});

test("新建项目会话提示服务端已自动准备固定项目工具", async () => {
    const flow = await setup({newConversation: true, pendingApproval: false});
    flow.document.getElementById("projectChatAgent").value = "agent";
    await flow.document.getElementById("projectNewConversation").click();
    assert.ok(flow.calls.some(([url, options]) => url.endsWith("/conversations") && options?.method === "POST"));
    assert.match(flow.document.getElementById("projectChatStatus").textContent, /固定 project_\* 工具已准备/);
    flow.stop();
});
