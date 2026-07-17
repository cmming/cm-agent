const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("../../main/resources/META-INF/resources/assets/console-core.js");

test("优先显示结构化接口错误", () => {
    assert.equal(core.formatError(403, {message: "没有权限"}, ""), "请求失败(403)：没有权限");
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

function response(status, body) {
    return {
        ok: status >= 200 && status < 300,
        status,
        headers: {get: () => "application/json"},
        text: async () => JSON.stringify(body)
    };
}
