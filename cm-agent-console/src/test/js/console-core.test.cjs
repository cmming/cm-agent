const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const core = require("../../main/resources/META-INF/resources/assets/console-core.js");

test("优先显示结构化接口错误", () => {
    assert.equal(core.formatError(403, {message: "没有权限"}, ""), "请求失败(403)：没有权限执行此操作。");
    assert.equal(core.formatError(404, {detail: "不存在"}, ""), "请求失败(404)：请求的资源不存在或已不可用。");
});

test("追加游标页并保留下一游标", () => {
    const result = core.appendCursorPage([{id: "1"}], {items: [{id: "2"}], nextCursor: "next"});
    assert.deepEqual(result, {items: [{id: "1"}, {id: "2"}], nextCursor: "next"});
});

test("401 会通知认证失效且不暴露响应体", async () => {
    let unauthorized = false;
    const api = core.createApiClient({
        fetchImpl: async () => response(401, {message: "令牌无效"}),
        getToken: () => "secret-token",
        onUnauthorized: () => { unauthorized = true; }
    });

    await assert.rejects(() => api.request("/api/auth/me"), /未登录或令牌已失效/);
    assert.equal(unauthorized, true);
});

test("请求自动附加 Bearer 令牌", async () => {
    let authorization = "";
    const api = core.createApiClient({
        fetchImpl: async (_path, options) => {
            authorization = options.headers.get("Authorization");
            return response(200, []);
        },
        getToken: () => "memory-only-token",
        onUnauthorized: () => {}
    });

    await api.request("/api/agents");
    assert.equal(authorization, "Bearer memory-only-token");
});

test("日期和运行状态转换为可读中文", () => {
    assert.equal(core.formatDateTime(""), "—");
    assert.deepEqual(core.statusMeta("SUCCEEDED"), {label: "成功", tone: "success"});
    assert.deepEqual(core.statusMeta("RUNNING"), {label: "运行中", tone: "warning"});
    assert.deepEqual(core.statusMeta("FAILED"), {label: "失败", tone: "error"});
});

test("游标请求路径会编码不安全字符", () => {
    assert.equal(
        core.buildCursorPath("/api/audit-events", 20, "a+b/="),
        "/api/audit-events?limit=20&cursor=a%2Bb%2F%3D"
    );
    assert.equal(core.buildCursorPath("/api/audit-events", 20, ""), "/api/audit-events?limit=20");
});

test("缺少分页条目时保留已有数据", () => {
    assert.deepEqual(core.appendCursorPage([{id: "1"}], {nextCursor: null}), {
        items: [{id: "1"}],
        nextCursor: ""
    });
});

test("解析 HTTP 配置 JSON 并在非法输入时给出中文提示", () => {
    assert.deepEqual(core.parseJsonField('{"type":"object"}', "输入 Schema"), {type: "object"});
    assert.throws(() => core.parseJsonField('{', "输入 Schema"), /输入 Schema必须是有效 JSON/);
});

test("仅 HTTP 和 LOCAL 工具允许调试，HIGH 必须完全匹配工具名称", () => {
    assert.equal(core.canDebugTool({type: "HTTP", riskLevel: "LOW", name: "orders"}, ""), true);
    assert.equal(core.canDebugTool({type: "LOCAL", riskLevel: "HIGH", name: "dangerous-tool"}, "dangerous-tool"), true);
    assert.equal(core.canDebugTool({type: "HTTP", riskLevel: "HIGH", name: "dangerous-tool"}, "Dangerous-tool"), false);
    assert.equal(core.canDebugTool({type: "MCP", riskLevel: "LOW", name: "remote-tool"}, ""), false);
});

test("构建 HTTP 工具请求体时保留 MCP 发布和 Secret 引用", () => {
    const payload = core.buildHttpToolPayload({
        name: "orders",
        description: "订单查询",
        riskLevel: "MEDIUM",
        mcpPublished: true,
        method: "POST",
        urlTemplate: "https://api.example.test/orders/{id}",
        inputSchemaText: '{"type":"object"}',
        parameterMappingsText: '[{"sourcePointer":"/id","location":"PATH","targetName":"id","required":true}]',
        secretHeadersText: '{"X-Api-Key":"secret/integration/api-key"}',
        timeoutMillis: "1000"
    });

    assert.deepEqual(payload, {
        name: "orders",
        description: "订单查询",
        type: "HTTP",
        riskLevel: "MEDIUM",
        mcpPublished: true,
        httpConfig: {
            method: "POST",
            urlTemplate: "https://api.example.test/orders/{id}",
            inputSchema: {type: "object"},
            parameterMappings: [{sourcePointer: "/id", location: "PATH", targetName: "id", required: true}],
            secretHeaders: {"X-Api-Key": "secret/integration/api-key"},
            timeoutMillis: 1000
        }
    });
});

test("HTTP 工具请求拒绝 Secret 非引用和无效超时", () => {
    const base = {
        name: "orders", description: "订单查询", riskLevel: "LOW", mcpPublished: false,
        method: "GET", urlTemplate: "https://api.example.test/orders", inputSchemaText: "{}",
        parameterMappingsText: "[]", secretHeadersText: '{"Authorization":"actual secret value"}', timeoutMillis: "50"
    };
    assert.throws(() => core.buildHttpToolPayload(base), /Secret 引用/);
    assert.throws(() => core.buildHttpToolPayload({...base, secretHeadersText: '{"Authorization":"secret/integration/api-key"}'}), /超时时间/);
});

test("HTTP 地址模板使用文本输入以支持路径参数占位符", () => {
    const html = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/index.html"), "utf8");

    assert.match(html, /id="httpUrlTemplate" type="text"/);
    assert.doesNotMatch(html, /id="httpUrlTemplate" type="url"/);
});

test("工具发布锁拒绝同一工具的重复操作并在释放后允许重试", () => {
    const lock = core.createToolPublicationLock();

    assert.equal(lock.tryAcquire("tool-1"), true);
    assert.equal(lock.tryAcquire("tool-1"), false);
    assert.equal(lock.tryAcquire("tool-2"), true);
    lock.release("tool-1");
    assert.equal(lock.tryAcquire("tool-1"), true);
});

test("工具加载版本会拒绝早到的旧响应", () => {
    const revisions = core.createLoadRevisionGate();
    const oldRequest = revisions.issue();
    revisions.invalidate();
    const latestRequest = revisions.issue();

    assert.equal(revisions.isCurrent(oldRequest), false);
    assert.equal(revisions.isCurrent(latestRequest), true);
});

function response(status, body) {
    return {
        ok: status >= 200 && status < 300,
        status,
        headers: {get: () => "application/json"},
        text: async () => JSON.stringify(body)
    };
}
