const test = require("node:test");
const assert = require("node:assert/strict");
const projects = require("../../main/resources/META-INF/resources/console/v2/assets/client-projects.js");

test("非安全上下文仍生成 UUID v4 请求编号", () => {
    const id = projects.createRequestId({getRandomValues(bytes) {
        for (let index = 0; index < bytes.length; index++) bytes[index] = index;
        return bytes;
    }});
    assert.equal(id, "00010203-0405-4607-8809-0a0b0c0d0e0f");
    assert.equal(projects.createRequestId({randomUUID: () => "preferred"}), "preferred");
    assert.throws(() => projects.createRequestId({}), /HTTPS/);
});

test("设备任务只轮询原请求直到成功", async () => {
    const calls = [];
    const task = {requestId: "request-1", expiresAt: "2099-01-01T00:00:00Z"};
    const result = await projects.waitForTask({
        task,
        request: async path => {
            calls.push(path);
            return calls.length === 1
                ? {requestId: "request-1", state: "DISPATCHED"}
                : {requestId: "request-1", state: "SUCCEEDED", output: {items: []}};
        },
        statusPath: id => `/status/${id}`,
        host: {setTimeout(resolve) { resolve(); }}
    });
    assert.deepEqual(calls, ["/status/request-1", "/status/request-1"]);
    assert.deepEqual(result.output, {items: []});
});

test("设备任务未知或失效时明确失败且不重发", async () => {
    let calls = 0;
    await assert.rejects(projects.waitForTask({
        task: {requestId: "request-1", expiresAt: "2099-01-01T00:00:00Z"},
        request: async () => {
            calls++;
            return {requestId: "request-1", state: "UNKNOWN", error: "结果未知"};
        },
        statusPath: id => `/status/${id}`,
        host: {setTimeout(resolve) { resolve(); }}
    }), /结果未知/);
    assert.equal(calls, 1);
});

test("文件变更只有版本摘要完整且脱敏不掩盖差异时才可允许", () => {
    const reference = {operationId: "00000000-0000-4000-8000-000000000001",
        version: 1, digest: "a".repeat(64)};
    const preview = {...reference, displayExact: true,
        changes: [{kind: "REPLACE_FILE", path: "README.md", before: "旧", after: "新"}]};
    const development = {matchesPreview: (ref, value) => ref.digest === value.digest,
        completePreview: value => value.changes?.length === 1};
    assert.equal(projects.canApproveChange(reference, preview, development), true);
    assert.equal(projects.canApproveChange(reference,
        {...preview, displayExact: false}, development), false);
    assert.equal(projects.canApproveChange(reference,
        {...preview, digest: "b".repeat(64)}, development), false);
    assert.equal(projects.canApproveChange(reference,
        {...preview, changes: []}, development), false);
});

test("固定工具链命令只在完整精确预览和同一提案时可允许", () => {
    const reference = {operationId: "00000000-0000-4000-8000-000000000002",
        version: 2, digest: "b".repeat(64)};
    const command = {commandId: reference.operationId, version: 2,
        planDigest: reference.digest, displayExact: true, preview: {
            toolchainId: "java", displayName: "Java", executable: "java.exe",
            executableHash: "c".repeat(64), prefixArguments: ["-version"], arguments: [],
            relativeDirectory: "", environmentKeys: ["PATH"],
            environmentOverrides: {},
            environmentDigest: "d".repeat(64), buildFiles: ["pom.xml"],
            timeoutSeconds: 60, maxOutputBytes: 65536
        }};
    const development = {matchesPreview: (ref, value) => ref.digest === value.digest};
    assert.equal(projects.canApproveCommand(reference, command, development), true);
    assert.equal(projects.canApproveCommand(reference,
        {...command, displayExact: false}, development), false);
    assert.equal(projects.canApproveCommand(reference,
        {...command, preview: {...command.preview, executableHash: "invalid"}}, development), false);
});

test("能力关闭时只探测固定入口且不加载设备或文件", async () => {
    const document = dom();
    const calls = [];
    const page = projects.createClientProjectPage({
        document, host: host(), getSessionEpoch: () => 1,
        api: {request: async (url, options) => {
            calls.push([url, options]);
            return {enabled: false};
        }}
    });
    await page.mount();
    assert.deepEqual(calls.map(([url]) => url), ["/api/client-projects/capabilities"]);
    assert.equal(calls[0][1].headers["X-CM-Agent-Client-Project"], "1");
    assert.equal(document.getElementById("projectSourceClient").hidden, true);
    assert.equal(document.getElementById("clientProjectAddBtn").disabled, true);
    page.dispose();
});

function dom() {
    class Node {
        constructor() {
            this.children = []; this.events = {}; this.hidden = false; this.disabled = false;
            this.dataset = {}; this.value = ""; this.checked = false; this.className = "";
            this.attributes = {}; this.classList = {toggle() {}};
        }
        set textContent(value) { this.content = value; this.children = []; }
        get textContent() { return this.content || ""; }
        append(...nodes) { this.children.push(...nodes); }
        replaceChildren(...nodes) { this.children = nodes; this.content = ""; }
        addEventListener(name, callback) { this.events[name] = callback; }
        setAttribute(name, value) { this.attributes[name] = value; }
        focus() {}
    }
    const nodes = new Map();
    return {
        getElementById(id) {
            if (!nodes.has(id)) nodes.set(id, new Node());
            return nodes.get(id);
        },
        createElement() { return new Node(); }
    };
}

function host() {
    return {crypto: {randomUUID: () => "request-1"},
        setTimeout(resolve) { resolve(); }, clearTimeout() {}};
}
