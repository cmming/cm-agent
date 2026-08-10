const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const core = require("../../main/resources/META-INF/resources/assets/console-core.js");

test("优先显示结构化接口错误", () => {
    assert.equal(core.formatError(403, {message: "没有权限"}, ""), "请求失败(403)：没有权限执行此操作。");
    assert.equal(core.formatError(404, {detail: "不存在"}, ""), "请求失败(404)：请求的资源不存在或已不可用。");
    assert.equal(
        core.formatError(503, {code: "PERSISTENCE_UNAVAILABLE", message: "数据服务暂不可用", errorId: "req-20260810"}, ""),
        "请求失败(503)：数据服务暂不可用（错误码：PERSISTENCE_UNAVAILABLE，错误编号：req-20260810）"
    );
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

test("旧token和旧会话代际的迟到401只拒绝原请求且不注销新会话", async () => {
    let token = "old-token";
    let epoch = 1;
    let unauthorizedCount = 0;
    let respond;
    const api = core.createApiClient({
        fetchImpl: () => new Promise((resolve) => { respond = () => resolve(response(401, {message: "旧令牌失效"})); }),
        getToken: () => token,
        getSessionEpoch: () => epoch,
        onUnauthorized: () => {
            unauthorizedCount += 1;
            token = "";
        }
    });

    const oldRequest = api.request("/api/tools/local-examples");
    token = "new-token";
    epoch = 2;
    respond();

    await assert.rejects(() => oldRequest, /未登录或令牌已失效/);
    assert.equal(unauthorizedCount, 0);
    assert.equal(token, "new-token");
});

test("当前token和当前会话代际的401仍只触发一次注销", async () => {
    let token = "current-token";
    let epoch = 3;
    let unauthorizedCount = 0;
    const api = core.createApiClient({
        fetchImpl: async () => response(401, {message: "令牌失效"}),
        getToken: () => token,
        getSessionEpoch: () => epoch,
        onUnauthorized: () => { unauthorizedCount += 1; }
    });

    await assert.rejects(() => api.request("/api/auth/me"), /未登录或令牌已失效/);
    assert.equal(unauthorizedCount, 1);
});

test("同一token但新会话代际的迟到401不得注销", async () => {
    const token = "reused-token";
    let epoch = 4;
    let unauthorizedCount = 0;
    let respond;
    const api = core.createApiClient({
        fetchImpl: () => new Promise((resolve) => { respond = () => resolve(response(401, {message: "旧请求失效"})); }),
        getToken: () => token,
        getSessionEpoch: () => epoch,
        onUnauthorized: () => { unauthorizedCount += 1; }
    });

    const oldRequest = api.request("/api/tools");
    epoch = 5;
    respond();

    await assert.rejects(() => oldRequest, /未登录或令牌已失效/);
    assert.equal(unauthorizedCount, 0);
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
    assert.equal(core.canDebugTool({type: "HTTP", riskLevel: "LOW", name: "orders", runtimeReady: true}, ""), true);
    assert.equal(core.canDebugTool({type: "LOCAL", riskLevel: "HIGH", name: "dangerous-tool", runtimeReady: true}, "dangerous-tool"), true);
    assert.equal(core.canDebugTool({type: "HTTP", riskLevel: "HIGH", name: "dangerous-tool", runtimeReady: true}, "Dangerous-tool"), false);
    assert.equal(core.canDebugTool({type: "MCP", riskLevel: "LOW", name: "remote-tool", runtimeReady: true}, ""), false);
});

test("LOCAL 工具只有运行时就绪后才能调试", () => {
    assert.equal(core.canDebugTool({
        type: "LOCAL", riskLevel: "LOW", name: "echo", runtimeReady: false
    }, ""), false);
    assert.equal(core.canDebugTool({
        type: "LOCAL", riskLevel: "LOW", name: "echo", runtimeReady: true
    }, ""), true);
});

test("内置示例安装路径编码 key 且示例输入格式化", () => {
    assert.equal(
        core.buildLocalExampleInstallPath("echo/add"),
        "/api/tools/local-examples/echo%2Fadd"
    );
    assert.equal(
        core.formatJsonInput({left: 0.1, right: 0.2}),
        "{\n  \"left\": 0.1,\n  \"right\": 0.2\n}"
    );
});

test("HTTP 工具请求拒绝 Secret 非引用和无效超时", () => {
    const base = {
        name: "orders", description: "订单查询", riskLevel: "LOW", mcpPublished: false,
        method: "GET", urlTemplate: "https://api.example.test/orders",
        parameters: [{id: "id", parentId: "", name: "id", dataType: "STRING", requestLocation: "QUERY"}],
        secretHeadersText: '{"Authorization":"actual secret value"}', timeoutMillis: "50"
    };
    assert.throws(() => core.buildHttpToolPayload(base), /Secret 引用/);
    assert.throws(() => core.buildHttpToolPayload({...base, secretHeadersText: '{"Authorization":"secret/integration/api-key"}'}), /超时时间/);
});

test("构建 HTTP 工具更新载荷时只提交最新参数定义", () => {
    const payload = core.buildToolUpdatePayload({id: "tool-1", type: "HTTP", name: "orders"}, {
        name: "orders-v2",
        description: "订单查询（新版）",
        riskLevel: "HIGH",
        enabled: false,
        mcpPublished: true,
        method: "PUT",
        urlTemplate: "https://api.example.test/orders/{id}",
        parameters: [{id: "id", parentId: "", name: "id", dataType: "STRING", requestLocation: "PATH", required: true}],
        secretHeadersText: '{"Authorization":"secret/integration/token"}',
        timeoutMillis: "2000"
    });

    assert.deepEqual(payload, {
        name: "orders-v2",
        description: "订单查询（新版）",
        type: "HTTP",
        riskLevel: "HIGH",
        enabled: false,
        mcpPublished: true,
        httpConfig: {
            method: "PUT",
            urlTemplate: "https://api.example.test/orders/{id}",
            parameters: [{
                id: "id", parentId: null, name: "id", dataType: "STRING", requestLocation: "PATH",
                description: "", required: true
            }],
            secretHeaders: {Authorization: "secret/integration/token"},
            timeoutMillis: 2000
        }
    });
});

test("构建 LOCAL 工具更新载荷时拒绝改名", () => {
    const localTool = {id: "tool-2", type: "LOCAL", name: "echo"};
    const fields = {name: "echo", description: "本地回显", riskLevel: "LOW", enabled: true, mcpPublished: true};

    assert.throws(() => core.buildToolUpdatePayload(localTool, {...fields, name: "renamed"}), /LOCAL/);
    assert.deepEqual(core.buildToolUpdatePayload(localTool, fields), {
        name: "echo",
        description: "本地回显",
        type: "LOCAL",
        riskLevel: "LOW",
        enabled: true,
        mcpPublished: true
    });
});

test("工具更新、删除和解除关联路径会编码资源标识", () => {
    assert.equal(core.buildToolUpdatePath("tool/id"), "/api/tools/tool%2Fid");
    assert.equal(core.buildToolDeletePath("tool/id"), "/api/tools/tool%2Fid");
    assert.equal(core.buildToolGrantDeletePath("tool", "agent/id"), "/api/tools/tool/grants/agent%2Fid");
});

test("解除关联完成后只刷新仍被选中的原 Agent", () => {
    assert.equal(core.shouldReloadRevokedAgent("agent-a", "agent-a"), true);
    assert.equal(core.shouldReloadRevokedAgent("agent-b", "agent-a"), false);

    const app = fs.readFileSync(
        path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"),
        "utf8"
    );
    assert.match(app, /core\.shouldReloadRevokedAgent\(state\.selectedAgentId, agent\.id\)/);
});

test("工具保存完成后只重置仍在编辑同一工具的表单", () => {
    assert.equal(core.shouldResetSavedToolForm("tool-a", "tool-a"), true);
    assert.equal(core.shouldResetSavedToolForm("tool-b", "tool-a"), false);

    const app = fs.readFileSync(
        path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"),
        "utf8"
    );
    assert.match(app, /core\.shouldResetSavedToolForm\(state\.editingToolId, editingTool\.id\)/);
});

test("仅将明确的 409 工具删除响应识别为关联冲突", () => {
    assert.equal(core.isToolDeleteConflict({
        status: 409,
        message: "请求失败(409)：工具仍被 Agent 关联，请先解除关联后再删除"
    }), true);
    assert.equal(core.isToolDeleteConflict({
        status: 409,
        message: "请求失败(409)：工具已有调用历史，为保留运行记录不能删除"
    }), false);
    assert.equal(core.isToolDeleteConflict({status: 409}), false);
    assert.equal(core.isToolDeleteConflict({status: 400}), false);
    assert.equal(core.isToolDeleteConflict(new Error("请求失败(409)：工具仍被 Agent 关联")), false);
});

test("HTTP 地址模板使用文本输入以支持路径参数占位符", () => {
    const html = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/index.html"), "utf8");

    assert.match(html, /id="httpUrlTemplate" type="text"/);
    assert.doesNotMatch(html, /id="httpUrlTemplate" type="url"/);
});

test("工具调试失败会显示具体原因和可检索错误编号", () => {
    assert.equal(core.formatToolDebugFailure({
        errorMessage: "HTTP 服务返回非成功状态",
        statusCode: 503,
        errorId: "call-20260810"
    }), "HTTP 服务返回非成功状态（HTTP 503，错误编号：call-20260810）");
    assert.equal(core.formatToolDebugFailure({errorMessage: "HTTP 请求超时"}), "HTTP 请求超时");
    assert.equal(core.formatToolDebugFailure({}), "工具执行失败");

    const app = fs.readFileSync(
        path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"),
        "utf8"
    );
    assert.match(app, /core\.formatToolDebugFailure\(result\)/);
    assert.match(app, /\["错误原因", result\?\.errorMessage\]/);
    assert.match(app, /\["错误码", result\?\.errorCode\]/);
    assert.match(app, /\["错误编号", result\?\.errorId\]/);
});

test("无参数 HTTP 工具允许提交空 parameters 数组", () => {
    const payload = core.buildHttpToolPayload({
        name: "tool-list",
        description: "获取工具列表",
        riskLevel: "LOW",
        mcpPublished: false,
        method: "GET",
        urlTemplate: "https://api.example.test/tools",
        parameters: [],
        secretHeadersText: "{}",
        timeoutMillis: "1000"
    });

    assert.deepEqual(payload.httpConfig.parameters, []);
});

test("HTTP Tool 表单提供树形录入、扁平提交和完整示例入口", () => {
    const resources = path.join(__dirname, "../../main/resources/META-INF/resources");
    const html = fs.readFileSync(path.join(resources, "index.html"), "utf8");
    const app = fs.readFileSync(path.join(resources, "assets/app.js"), "utf8");

    [
        "httpMethodHelp", "httpUrlTemplateHelp", "httpParameterEditor",
        "httpParameterList", "addHttpParameterBtn", "httpSecretHeadersHelp", "httpTimeoutMillisHelp"
    ].forEach((id) => assert.match(html, new RegExp(`id="${id}"`)));
    assert.match(html, /id="fillHttpExampleBtn"/);
    assert.match(html, /页面按树形结构录入参数/);
    assert.match(html, /添加顶层参数/);
    assert.match(html, /OBJECT 或 ARRAY 节点内添加子参数/);
    assert.match(html, /根数组/);
    assert.match(html, /secret\/integration\/orders-token/);
    assert.doesNotMatch(html, /httpLegacyConfigFields|httpInputSchema|httpParameterMappings/);
    assert.doesNotMatch(app, /editingLegacyHttpConfig|prepareHttpParameterMappingsForEdit/);
    assert.match(app, /function fillHttpToolExample\(\)/);
    assert.match(app, /function addHttpParameter\(/);
    assert.match(app, /function addHttpParameterChild\(/);
    assert.match(app, /function renderHttpParameterTree\(/);
    assert.match(app, /function collectHttpParameters\(/);
    assert.match(app, /parameterChildNodes\(card\)\.forEach\(\(child\) => visit\(child, value\.id\)\)/);
    assert.doesNotMatch(app, /data\.parameterField = "parentId"/);
    assert.match(app, /\$\("fillHttpExampleBtn"\)\.addEventListener\("click", fillHttpToolExample\)/);
    assert.match(app, /填入示例会覆盖当前 HTTP 配置/);
});

test("新 HTTP 参数定义直接生成请求载荷并支持根数组", () => {
    const payload = core.buildHttpToolPayload({
        name: "batch-create",
        description: "批量创建",
        riskLevel: "LOW",
        mcpPublished: false,
        method: "POST",
        urlTemplate: "https://api.example.test/orders/{shopId}",
        parameters: [
            {
                id: "shopId", parentId: null, name: "shopId", dataType: "STRING",
                requestLocation: "PATH", description: "门店编号", required: true,
                exampleValueText: "\"shop-1\""
            },
            {
                id: "payload", parentId: null, name: "payload", dataType: "ARRAY",
                requestLocation: "BODY_ROOT", description: "订单列表", required: true,
                minItems: 1
            },
            {
                id: "payloadItem", parentId: "payload", name: "", dataType: "OBJECT",
                requestLocation: "", description: "单个订单", required: false
            },
            {
                id: "p1", parentId: "payloadItem", name: "p1", dataType: "STRING",
                requestLocation: "", description: "业务字段", required: true,
                defaultValueText: "\"v1\""
            }
        ],
        secretHeadersText: "{}",
        timeoutMillis: "3000"
    });

    assert.equal(payload.httpConfig.inputSchema, undefined);
    assert.equal(payload.httpConfig.parameterMappings, undefined);
    assert.equal(payload.httpConfig.parameters.length, 4);
    assert.equal(payload.httpConfig.parameters[1].requestLocation, "BODY_ROOT");
    assert.equal(payload.httpConfig.parameters[2].name, null);
    assert.equal(payload.httpConfig.parameters[3].defaultValue, "v1");
});

test("新 HTTP 参数定义在提交前拒绝无元素数组和重复字段", () => {
    const fields = {
        name: "invalid", description: "非法参数树", riskLevel: "LOW", method: "POST",
        urlTemplate: "https://api.example.test/items", secretHeadersText: "{}", timeoutMillis: "1000"
    };
    assert.throws(() => core.buildHttpToolPayload({...fields, parameters: [
        {id: "payload", name: "payload", dataType: "ARRAY", requestLocation: "BODY_ROOT", required: true}
    ]}), /必须有且只有一个元素节点/);
    assert.throws(() => core.buildHttpToolPayload({...fields, parameters: [
        {id: "a", name: "same", dataType: "STRING", requestLocation: "BODY", required: false},
        {id: "b", name: "same", dataType: "STRING", requestLocation: "BODY", required: false}
    ]}), /字段名称不能重复/);
});

test("控制台和中文文档提供工具编辑、删除与 Agent 解除关联入口", () => {
    const resources = path.join(__dirname, "../../main/resources/META-INF/resources");
    const html = fs.readFileSync(path.join(resources, "index.html"), "utf8");
    const app = fs.readFileSync(path.join(resources, "assets/app.js"), "utf8");
    const readme = fs.readFileSync(path.join(__dirname, "../../../../README.md"), "utf8");
    const releaseNotes = fs.readFileSync(path.join(__dirname, "../../../../docs/release-notes.md"), "utf8");

    assert.match(html, /id="cancelToolEditBtn"/);
    assert.match(app, /state\.editingToolId/);
    assert.match(app, /core\.buildToolUpdatePath/);
    assert.match(app, /core\.buildToolDeletePath/);
    assert.match(app, /core\.buildToolGrantDeletePath/);
    assert.match(app, /window\.confirm/);
    assert.match(app, /请先到 Agent 详情解除关联/);
    assert.doesNotMatch(readme, /不提供编辑、删除/);
    assert.match(releaseNotes, /工具编辑、删除与 Agent 解除关联/);
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

test("不同工具写入按完成顺序协调最终刷新", () => {
    const revisions = core.createLoadRevisionGate();
    const bReload = revisions.completeWrite();
    const aReload = revisions.completeWrite();

    assert.equal(revisions.isCurrent(bReload), false);
    assert.equal(revisions.isCurrent(aReload), true);
});

test("旧会话提交结束不得取得当前会话共享按钮的收尾权", () => {
    const guard = core.createSubmitStateGuard();
    const button = {};
    const oldTicket = guard.begin(button, 1);

    guard.invalidate(button);
    const currentTicket = guard.begin(button, 2);

    assert.equal(guard.finish(oldTicket, 2), false);
    assert.equal(guard.finish(currentTicket, 2), true);

    const script = fs.readFileSync(
        path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"),
        "utf8"
    );
    assert.match(script, /const submitStateGuard = core\.createSubmitStateGuard\(\)/);
    assert.match(script, /if \(!submitStateGuard\.finish\(ticket, sessionEpoch\.capture\(\)\)\) return;/);
    assert.match(script, /submitStateGuard\.invalidateAll\(\)/);
});

test("交错工具刷新只允许已应用的最新操作写成功提示", async () => {
    const revisions = core.createLoadRevisionGate();
    const successMessages = [];
    let finishOld;
    let finishLatest;
    const oldResponse = new Promise((resolve) => { finishOld = resolve; });
    const latestResponse = new Promise((resolve) => { finishLatest = resolve; });

    const oldRevision = revisions.completeWrite();
    const oldOperation = oldResponse.then(() => {
        const toolsReloaded = revisions.isCurrent(oldRevision);
        if (toolsReloaded) successMessages.push("旧操作成功");
    });
    const latestRevision = revisions.completeWrite();
    const latestOperation = latestResponse.then(() => {
        const toolsReloaded = revisions.isCurrent(latestRevision);
        if (toolsReloaded) successMessages.push("最新操作成功");
    });

    finishLatest();
    await latestOperation;
    finishOld();
    await oldOperation;

    assert.deepEqual(successMessages, ["最新操作成功"]);

    const script = fs.readFileSync(
        path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"),
        "utf8"
    );
    assert.equal((script.match(/const toolsReloaded = await loadTools/g) || []).length, 2);
    assert.match(script, /if \(!toolsReloaded \|\| !sessionEpoch\.isCurrent\(operationSession\)\) return;/);
    assert.match(script, /const \[agentReloaded, toolsReloaded\] = await Promise\.all/);
    assert.match(script, /if \(!agentReloaded \|\| !toolsReloaded \|\| !sessionEpoch\.isCurrent\(operationSession\)\) return;/);
});

test("不同内置示例交错完成时各自目录刷新仍然有效", () => {
    const revisions = core.createKeyedLoadRevisionGate();

    revisions.invalidate("echo");
    const echoReload = revisions.completeWrite("echo");
    revisions.invalidate("add");
    const addReload = revisions.completeWrite("add");

    assert.equal(revisions.isCurrent("echo", echoReload), true);
    assert.equal(revisions.isCurrent("add", addReload), true);
});

test("会话切换会阻止旧安装请求在乱序完成后继续刷新或写入界面", async () => {
    const sessions = core.createSessionEpochGate();
    const oldSession = sessions.capture();
    let reloads = 0;
    let uiWrites = 0;
    let complete;
    const delayedInstall = new Promise((resolve) => { complete = resolve; });

    const oldRequest = delayedInstall.then(() => {
        if (!sessions.isCurrent(oldSession)) return;
        reloads += 1;
        uiWrites += 1;
    });
    sessions.invalidate();
    const newSession = sessions.capture();
    complete();
    await oldRequest;

    assert.equal(sessions.isCurrent(oldSession), false);
    assert.equal(sessions.isCurrent(newSession), true);
    assert.equal(reloads, 0);
    assert.equal(uiWrites, 0);
});

test("内置示例安装在写入和后续刷新前都应用会话代际门控", () => {
    const script = fs.readFileSync(
        path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"),
        "utf8"
    );

    assert.match(script, /const sessionEpoch = core\.createSessionEpochGate\(\)/);
    assert.match(script, /if \(!sessionEpoch\.isCurrent\(installSession\)\) return;/);
    assert.match(script, /loadLocalExamples\(reloadRevision, true, installSession\)/);
    assert.match(script, /loadTools\(undefined, installSession\)/);
});

test("HTTP 与 LOCAL 工具都提供 MCP 发布管理入口", () => {
    const script = fs.readFileSync(
        path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"),
        "utf8"
    );

    assert.match(script, /tool\.type === "HTTP" \|\| tool\.type === "LOCAL"/);
});

function response(status, body) {
    return {
        ok: status >= 200 && status < 300,
        status,
        headers: {get: () => "application/json"},
        text: async () => JSON.stringify(body)
    };
}
