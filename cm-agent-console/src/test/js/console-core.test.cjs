const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const core = require("../../main/resources/META-INF/resources/assets/console-core.js");

test("v2 使用 Cookie 恢复刷新且只在当前页面内存保留令牌", () => {
    const resources = path.join(__dirname, "../../main/resources/META-INF/resources");
    const app = fs.readFileSync(path.join(resources, "assets/app.js"), "utf8");
    const login = fs.readFileSync(path.join(resources, "console/v2/login.html"), "utf8");

    assert.match(app, /window\.fetch\("\/api\/auth\/logout", \{method: "POST", credentials: "same-origin"\}\)/);
    assert.match(app, /state\.currentUser = await api\.request\("\/api\/auth\/me"\)/);
    assert.match(app, /state\.token = accessToken/);
    assert.match(app, /loadMultiPage/);
    assert.match(app, /button\[data-page\]/);
    assert.doesNotMatch(app, /document\.querySelectorAll\("\[data-page\]"\)/);
    assert.doesNotMatch(app, /sessionStorage|localStorage|CmAgentConsoleSession/);
    assert.doesNotMatch(login, /session\.js|sessionStorage|localStorage/);
});

test("优先显示结构化接口错误", () => {
    assert.equal(core.formatError(403, {message: "没有权限"}, ""), "请求失败(403)：没有权限执行此操作。");
    assert.equal(core.formatError(404, {detail: "不存在"}, ""), "请求失败(404)：请求的资源不存在或已不可用。");
    assert.equal(
        core.formatError(503, {code: "PERSISTENCE_UNAVAILABLE", message: "数据服务暂不可用", errorId: "req-20260810"}, ""),
        "请求失败(503)：数据服务暂不可用（错误码：PERSISTENCE_UNAVAILABLE，错误编号：req-20260810）"
    );
});

test("会话聊天页复用既有会话流和多页面导航", () => {
    const resources = path.join(__dirname, "../../main/resources/META-INF/resources");
    const app = fs.readFileSync(path.join(resources, "assets/app.js"), "utf8");
    const chat = fs.readFileSync(path.join(resources, "console/v2/chat.html"), "utf8");
    const overview = fs.readFileSync(path.join(resources, "console/v2/overview.html"), "utf8");

    assert.match(chat, /data-page="chatPage"/);
    assert.match(chat, /id="chatConversationList"/);
    assert.match(chat, /id="chatMessageList"/);
    assert.match(chat, /id="chatApprovalRegion"/);
    assert.match(chat, /id="chatForm"/);
    assert.match(app, /chatPage: "\/console\/v2\/chat\.html"/);
    assert.match(app, /function sendChatMessage\(\)/);
    assert.match(app, /messages\/stream/);
    assert.match(app, /function renderChatMessages\(messages\)/);
    assert.match(app, /function createChatExecutionTrace\(blocks\)/);
    assert.match(app, /function renderChatProgress\(streamMessage, progress\)/);
    assert.match(app, /event\.type === "progress"/);
    assert.match(app, /event\.type === "approval-required"/);
    assert.match(app, /function renderChatApprovals\(\)/);
    assert.match(app, /function submitApprovalDecision\(approval, form\)/);
    assert.match(app, /pendingApprovals: new Map\(\)/);
    assert.match(app, /function updateApprovalSubmitAvailability\(approval, decisions, submit, summary, submitting\)/);
    assert.match(app, /event\.type === "approval-decision"/);
    assert.match(app, /className: "chat-approval-decisions"/);
    assert.match(app, /text: item\.inputSummary/);
    assert.doesNotMatch(app, /innerHTML\s*=\s*item\.inputSummary/);
    assert.match(app, /\["THINKING", "TOOL_USE", "TOOL_RESULT"\]/);
    assert.match(app, /调用入参（已脱敏）/);
    assert.match(app, /返回值（已脱敏）/);
    assert.match(app, /progress\.input/);
    assert.match(app, /progress\.output/);
    assert.match(app, /renderToolCallPayload/);
    assert.match(overview, /href="\/console\/v2\/chat\.html"/);
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

test("请求自动附加 Bearer 令牌并显式携带同源 Cookie", async () => {
    let authorization = "";
    let credentials = "";
    const api = core.createApiClient({
        fetchImpl: async (_path, options) => {
            authorization = options.headers.get("Authorization");
            credentials = options.credentials;
            return response(200, []);
        },
        getToken: () => "memory-only-token",
        onUnauthorized: () => {}
    });

    await api.request("/api/agents");
    assert.equal(authorization, "Bearer memory-only-token");
    assert.equal(credentials, "same-origin");
});

test("multipart 上传由浏览器设置边界并保留同源认证", async () => {
    let received;
    const api = core.createApiClient({
        fetchImpl: async (_path, options) => {
            received = options;
            return response(201, {id: "skill-1"});
        },
        getToken: () => "test-token",
        onUnauthorized: () => assert.fail("上传不能触发退出")
    });
    const body = new FormData();
    body.append("file", new Blob(["test"], {type: "application/zip"}), "test.zip");

    await api.request("/api/skills", {method: "POST", headers: {"Content-Type": "application/json"}, body});

    assert.equal(received.headers.has("Content-Type"), false);
    assert.equal(received.headers.get("Authorization"), "Bearer test-token");
    assert.equal(received.credentials, "same-origin");
    assert.equal(received.body, body);
});

test("结构化失败保留错误码和错误编号但不保存原始响应", async () => {
    const api = core.createApiClient({
        fetchImpl: async () => response(503, {
            code: "SKILL_LOAD_FAILED", errorId: "err-1", message: "技能内容读取失败", internal: "不应保留"
        }),
        getToken: () => "test-token",
        onUnauthorized: () => {}
    });

    await assert.rejects(() => api.request("/api/skills"), (error) => {
        assert.equal(error.status, 503);
        assert.equal(error.code, "SKILL_LOAD_FAILED");
        assert.equal(error.errorId, "err-1");
        assert.equal(Object.hasOwn(error, "body"), false);
        return true;
    });
});

test("日期和运行状态转换为可读中文", () => {
    assert.equal(core.formatDateTime(""), "—");
    assert.deepEqual(core.statusMeta("SUCCEEDED"), {label: "成功", tone: "success"});
    assert.deepEqual(core.statusMeta("RUNNING"), {label: "运行中", tone: "warning"});
    assert.deepEqual(core.statusMeta("WAITING_APPROVAL"), {label: "等待审批", tone: "warning"});
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
    const localTool = {id: "tool-2", type: "LOCAL", name: "echo", mcpPublished: true};
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

test("LOCAL 编辑忽略表单发布切换并保留已有发布状态", () => {
    for (const published of [false, true]) {
        const tool = {id: "tool-local", type: "LOCAL", name: "echo", mcpPublished: published};
        const payload = core.buildToolFormPayload(tool, {
            name: "echo", description: "更新描述", riskLevel: "LOW", enabled: true,
            mcpPublished: !published
        });
        assert.equal(payload.mcpPublished, published);
        assert.equal(payload.description, "更新描述");
    }
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
    const v2Tools = fs.readFileSync(path.join(resources, "console/v2/tools.html"), "utf8");
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
    assert.match(v2Tools, /id="fillHttpExampleBtn"/);
    assert.match(v2Tools, /id="httpParameterList"/);
    assert.doesNotMatch(html, /httpLegacyConfigFields|httpInputSchema|httpParameterMappings/);
    assert.doesNotMatch(app, /editingLegacyHttpConfig|prepareHttpParameterMappingsForEdit/);
    assert.match(app, /function fillHttpToolExample\(\)/);
    assert.match(app, /function addHttpParameter\(/);
    assert.match(app, /function addHttpParameterChild\(/);
    assert.match(app, /function renderHttpParameterTree\(/);
    assert.match(app, /function collectHttpParameters\(/);
    assert.match(app, /parameterChildNodes\(card\)\.forEach\(\(child\) => visit\(child, value\.id\)\)/);
    assert.doesNotMatch(app, /data\.parameterField = "parentId"/);
    assert.match(app, /bind\("fillHttpExampleBtn", "click", fillHttpToolExample\)/);
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

test("审批提交覆盖单项、多项和混合决定且丢弃不可信附加字段", () => {
    const view = {status: "PENDING", canDecide: true, version: 2, items: [{itemId: "a"}, {itemId: "b"}]};
    const payload = core.buildApprovalDecisionPayload(view, [
        {itemId: "a", decision: "APPROVE", tenant: "不可信租户", input: "不得发送"},
        {itemId: "b", decision: "DENY"}
    ]);
    assert.deepEqual(payload, {expectedVersion: 2, decisions: [
        {itemId: "a", decision: "APPROVE"}, {itemId: "b", decision: "DENY"}
    ]});
    assert.deepEqual(core.buildApprovalDecisionPayload({...view, items: [{itemId: "a"}]}, [
        {itemId: "a", decision: "APPROVE"}
    ]).decisions, [{itemId: "a", decision: "APPROVE"}]);
    assert.equal(core.buildApprovalDecisionPayload(view, view.items.map((item) => ({
        itemId: item.itemId, decision: "APPROVE"
    }))).decisions.length, 2);
});

test("审批缺项、重复、外来明细和非法决定必须在发送前拒绝", () => {
    const view = {status: "PENDING", canDecide: true, version: 0, items: [{itemId: "a"}, {itemId: "b"}]};
    assert.throws(() => core.buildApprovalDecisionPayload(view, [{itemId: "a", decision: "APPROVE"}]), /每个工具/);
    for (const invalid of [
        [{itemId: "a", decision: "APPROVE"}, {itemId: "a", decision: "DENY"}],
        [{itemId: "a", decision: "APPROVE"}, {itemId: "other", decision: "DENY"}],
        [{itemId: "a", decision: "APPROVE"}, {itemId: "b", decision: "ALLOW_ALWAYS"}]
    ]) assert.throws(() => core.buildApprovalDecisionPayload(view, invalid), /明细/);
});

test("只读及过期或已处理审批不可再次提交", () => {
    const view = {status: "PENDING", canDecide: false, version: 0, items: [{itemId: "a"}]};
    for (const change of [{}, {canDecide: true, status: "EXPIRED"}, {canDecide: true, status: "APPROVED"}]) {
        assert.throws(() => core.buildApprovalDecisionPayload({...view, ...change}, [
            {itemId: "a", decision: "APPROVE"}
        ]), /不可提交/);
    }
});

test("审批和恢复SSE支持跨分片中文与连续事件且按顺序交付", async () => {
    const events = [];
    const frames = [
        ["approval-required", {approvalId: "a", status: "PENDING"}],
        ["approval-decision", {approvalId: "a", status: "APPROVED", version: 1}],
        ["progress", {content: "执行中"}], ["delta", {delta: "完成"}], ["completed", {run: {status: "SUCCEEDED"}}]
    ];
    const bytes = new TextEncoder().encode(frames.map(([type, data]) =>
        `event: ${type}\r\ndata: ${JSON.stringify(data)}\r\n\r\n`).join(""));
    const api = core.createApiClient({getToken: () => "", onUnauthorized: () => {},
        fetchImpl: async () => ({ok: true, body: new ReadableStream({start(controller) {
            for (let index = 0; index < bytes.length; index += 2) controller.enqueue(bytes.slice(index, index + 2));
            controller.close();
        }})})});
    await api.stream("/api/approvals/a/decision/stream", {method: "POST"}, (event) => events.push(event));
    assert.deepEqual(events, frames.map(([type, data]) => ({type, data})));
});

test("审批冲突与过期保留HTTP状态及错误编号供权威查询", async () => {
    for (const [status, code, message] of [[409, "TOOL_APPROVAL_CONFLICT", "审批已处理"], [410, "TOOL_APPROVAL_EXPIRED", "审批已过期"]]) {
        const api = core.createApiClient({getToken: () => "", onUnauthorized: () => {},
            fetchImpl: async () => response(status, {code, message, errorId: "approval-error"})});
        await assert.rejects(() => api.stream("/api/approval", {method: "POST"}, () => {}), (error) => {
            assert.equal(error.status, status);
            assert.match(error.message, /approval-error/);
            assert.ok(error.message.includes(code));
            return true;
        });
    }
});

test("会话审批查询拒绝旧响应且网络中断后必须读取权威详情", () => {
    const gate = core.createLoadRevisionGate();
    const firstConversation = gate.issue();
    const secondConversation = gate.issue();
    assert.equal(gate.isCurrent(firstConversation), false);
    assert.equal(gate.isCurrent(secondConversation), true);
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");
    assert.match(app, /chatLoadRevision\.isCurrent\(revision\)/);
    assert.match(app, /if \(state\.approvalSubmissions\.has\(approval\.approvalId\)\) return/);
    assert.match(app, /refreshApprovalState\(approval, isCurrent\)/);
    assert.match(app, /state\.chatLoading = true/);
    assert.match(app, /决定已经被服务端接受，请勿重复提交/);
    assert.match(app, /canDecide: false/);
});

test("审批选择汇总不预选且按钮明确区分允许与拒绝的结果", () => {
    const items = [{itemId: "a"}, {itemId: "b"}];
    assert.deepEqual(core.summarizeApprovalChoices(items, []), {
        total: 2, selected: 0, approved: 0, denied: 0, ready: false, submitLabel: "请先完成全部选择"
    });
    assert.equal(core.summarizeApprovalChoices(items, [{itemId: "a", decision: "APPROVE"}]).ready, false);
    const mixed = core.summarizeApprovalChoices(items, [{itemId: "a", decision: "APPROVE"}, {itemId: "b", decision: "DENY"}]);
    assert.equal(mixed.ready, true);
    assert.equal(mixed.submitLabel, "提交决定：允许 1 项，拒绝 1 项");
    assert.equal(core.summarizeApprovalChoices([items[0]], [{itemId: "a", decision: "APPROVE"}]).submitLabel, "允许本次调用并继续");
    assert.equal(core.summarizeApprovalChoices([items[0]], [{itemId: "a", decision: "DENY"}]).submitLabel, "拒绝本次调用并结束");
    assert.equal(core.summarizeApprovalChoices(items, items.map(({itemId}) => ({itemId, decision: "DENY"}))).submitLabel, "拒绝全部 2 项并结束");
});

test("选择汇总拒绝空明细重复明细外来明细与非法决定", () => {
    assert.equal(core.summarizeApprovalChoices([], []).ready, false);
    assert.equal(core.summarizeApprovalChoices([{itemId: "a"}, {itemId: "a"}], [{itemId: "a", decision: "APPROVE"}]).ready, false);
    for (const decisions of [
        [{itemId: "a", decision: "APPROVE"}, {itemId: "a", decision: "DENY"}],
        [{itemId: "a", decision: "APPROVE"}, {itemId: "foreign", decision: "DENY"}],
        [{itemId: "a", decision: "ALLOW_ALWAYS"}], [null]
    ]) assert.equal(core.summarizeApprovalChoices([{itemId: "a"}], decisions).ready, false);
});

test("提交恢复查询与未知结果都不可重新决定且不冒充运行成功", () => {
    const pending = {status: "PENDING", canDecide: true};
    assert.equal(core.approvalUiState(pending).editable, true);
    for (const phase of ["SUBMITTING", "RESUMING", "CHECKING", "UNKNOWN"]) {
        assert.equal(core.approvalUiState(pending, phase).editable, false);
    }
    assert.equal(core.approvalUiState(pending, "SUBMITTING").label, "正在提交决定");
    assert.equal(core.approvalUiState({...pending, status: "APPROVED"}, "RESUMING").label, "决定已接受");
    assert.match(core.approvalUiState({...pending, status: "APPROVED"}).message, /实际执行结果/);
    assert.match(core.approvalUiState(pending, "UNKNOWN").message, /不能再次提交/);
});

test("只读提示不把过期或他人发起误断言为账号缺少权限", () => {
    const readonly = core.approvalUiState({status: "PENDING", canDecide: false});
    assert.equal(readonly.editable, false);
    assert.match(readonly.message, /发起本次运行/);
    assert.match(readonly.message, /有效期/);
    assert.doesNotMatch(readonly.message, /当前账号没有审批权限/);
    for (const status of ["APPROVED", "PARTIALLY_APPROVED", "DENIED", "EXPIRED", "CANCELLED"]) {
        assert.equal(core.approvalUiState({status, canDecide: true}).editable, false);
        assert.equal(core.approvalUiState({status}).terminal, true);
    }
});

function approvalDraftFixture() {
    return {approvalId: "request", agentId: "agent", conversationId: "conversation", version: 1,
        status: "PENDING", canDecide: true, items: [{itemId: "item", toolId: "tool", toolCallId: "call", inputSummary: "测试参数"}]};
}

test("审批草稿只保存明确选择且读写防御性复制", () => {
    const store = core.createApprovalDraftStore();
    const approval = approvalDraftFixture();
    assert.deepEqual(store.read(approval), []);
    const submitted = [{itemId: "item", decision: "APPROVE", input: "不得缓存"}];
    store.write(approval, submitted);
    submitted[0].decision = "DENY";
    assert.deepEqual(store.read(approval), [{itemId: "item", decision: "APPROVE"}]);
    store.read(approval)[0].decision = "DENY";
    assert.equal(store.read(approval)[0].decision, "APPROVE");
    store.remove(approval.approvalId);
    assert.deepEqual(store.read(approval), []);
});

test("审批草稿在版本调用参数所属会话或权限变化时失效", () => {
    const original = approvalDraftFixture();
    const variants = [
        {...original, version: 2}, {...original, agentId: "another"}, {...original, conversationId: "another"},
        {...original, canDecide: false}, {...original, status: "APPROVED"},
        ...["itemId", "toolId", "toolCallId", "inputSummary"].map((field) => ({...original, items: [{...original.items[0], [field]: "changed"}]}))
    ];
    for (const variant of variants) {
        const store = core.createApprovalDraftStore();
        store.write(original, [{itemId: "item", decision: "APPROVE"}]);
        assert.deepEqual(store.read(variant), []);
        assert.deepEqual(store.read(original), []);
    }
});

test("审批草稿不会记住永久允许且退出清理后不再回填", () => {
    const store = core.createApprovalDraftStore();
    const approval = approvalDraftFixture();
    store.write(approval, [{itemId: "item", decision: "ALLOW_ALWAYS"}, {itemId: "foreign", decision: "APPROVE"}]);
    assert.deepEqual(store.read(approval), []);
    store.write(approval, [{itemId: "item", decision: "DENY"}]);
    store.clear();
    assert.deepEqual(store.read(approval), []);
});

test("人工确认界面明确批量选择非提交并保留纯文本与焦点合同", () => {
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");
    assert.match(app, /if \(items\.length > 1\)/);
    assert.match(app, /全部选为允许/);
    assert.match(app, /批量按钮仅更改选择，不会提交/);
    assert.match(app, /调用参数（已脱敏，仅供核对，不可编辑）/);
    assert.match(app, /text: item\.inputSummary/);
    assert.match(app, /details\.open = !ui\.terminal/);
    assert.match(app, /status\.tabIndex = -1/);
    assert.match(app, /"alert" : "status"/);
    assert.match(app, /approvalDrafts\.clear\(\)/);
    assert.doesNotMatch(app, /\.innerHTML|localStorage|sessionStorage/);
});

test("状态刷新入口只查询且未知结果保持关闭", () => {
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");
    const start = app.indexOf("async function refreshApprovalFromCard(");
    const end = app.indexOf("async function refreshApprovalState(", start);
    const refresh = app.slice(start, end);
    assert.match(refresh, /refreshApprovalState\(approval, isCurrent\)/);
    assert.match(refresh, /state\.chatLoading = true/);
    assert.match(refresh, /phase: "UNKNOWN"/);
    assert.match(refresh, /sessionEpoch\.isCurrent\(session\)/);
    assert.doesNotMatch(refresh, /method: "POST"|api\.stream|submitApprovalDecision\(/);
    assert.match(app, /if \(!state\.pendingApprovals\.has\(approval\.approvalId\)\) setStatus\(\$\("chatFormStatus"\), error\.message, "error"\)/);
});

// 在隔离上下文中执行真实审批编排函数，只替换网络和 DOM 边界；不需要启动服务或调用真实工具。
function approvalFlowHarness({stream, refresh} = {}) {
    const vm = require("node:vm");
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");
    const begin = app.indexOf("async function submitApprovalDecision(");
    const end = app.indexOf("async function refreshApprovalState(", begin);
    assert.ok(begin > 0 && end > begin);
    const approval = approvalDraftFixture();
    const state = {selectedAgentId: approval.agentId, selectedConversationId: approval.conversationId,
        pendingApprovals: new Map([[approval.approvalId, approval]]), approvalSubmissions: new Set(),
        approvalFeedback: new Map(), approvalHistory: [], chatLoading: false};
    const requests = [];
    const phases = [];
    let queries = 0;
    let session = 0;
    const context = {
        state, core, approvalDrafts: core.createApprovalDraftStore(),
        sessionEpoch: {capture: () => session, isCurrent: (value) => session === value},
        collectApprovalChoices: () => [{itemId: "item", decision: "APPROVE"}],
        renderChatApprovals: () => phases.push(state.approvalFeedback.get(approval.approvalId)?.phase),
        $: () => ({focus() {}}), setStatus() {}, renderMarkdown() {}, renderChatProgress() {},
        appendChatStreamingMessage: () => ({article: {remove() {}}, output: {}}),
        loadConversations: async () => {},
        refreshApprovalState: async () => {
            queries += 1;
            if (refresh) return refresh(state, approval);
            state.pendingApprovals.set(approval.approvalId, {...approval, status: "APPROVED", canDecide: false});
            state.chatLoading = false;
        },
        streamEventError: (data) => new Error(`${data.message} ${data.code} ${data.errorId}`),
        api: {stream: async (url, options, emit) => {
            requests.push({url, options});
            if (stream) return stream(emit);
            emit({type: "approval-decision", data: {status: "APPROVED", version: 2}});
            emit({type: "completed", data: {}});
        }}
    };
    vm.runInNewContext(`${app.slice(begin, end)}; globalThis.actions = {submitApprovalDecision, refreshApprovalFromCard};`, context);
    return {approval, state, requests, phases, actions: context.actions,
        queryCount: () => queries, changeSession: () => { session += 1; }};
}

test("真实审批编排在提交和接受后切换阶段并保持原协议载荷", async () => {
    const flow = approvalFlowHarness();
    await flow.actions.submitApprovalDecision(flow.approval, {});
    assert.equal(flow.requests.length, 1);
    assert.deepEqual(JSON.parse(flow.requests[0].options.body), {
        expectedVersion: 1, decisions: [{itemId: "item", decision: "APPROVE"}]
    });
    assert.ok(flow.phases.includes("SUBMITTING"));
    assert.ok(flow.phases.includes("RESUMING"));
    assert.equal(flow.phases.at(-1), "DONE");
    assert.equal(flow.state.approvalSubmissions.size, 0);
});

test("真实审批编排双击只发一次决定请求", async () => {
    let finish;
    const pending = new Promise((resolve) => { finish = resolve; });
    const flow = approvalFlowHarness({stream: async (emit) => {
        await pending;
        emit({type: "approval-decision", data: {status: "APPROVED", version: 2}});
        emit({type: "completed", data: {}});
    }});
    const first = flow.actions.submitApprovalDecision(flow.approval, {});
    await flow.actions.submitApprovalDecision(flow.approval, {});
    assert.equal(flow.requests.length, 1);
    finish();
    await first;
});

test("网络结果不确定且权威查询失败时实际关闭决定和会话发送", async () => {
    const flow = approvalFlowHarness({stream: async () => { throw new Error("网络中断，错误编号：network-test"); },
        refresh: async () => { throw new Error("状态查询失败"); }});
    await assert.rejects(() => flow.actions.submitApprovalDecision(flow.approval, {}), /network-test.*无法确认审批状态/);
    assert.equal(flow.state.pendingApprovals.get(flow.approval.approvalId).canDecide, false);
    assert.equal(flow.state.approvalFeedback.get(flow.approval.approvalId).phase, "UNKNOWN");
    assert.equal(flow.state.chatLoading, true);
    assert.equal(flow.requests.length, 1);
    assert.equal(flow.queryCount(), 1);
});

test("决定接受后恢复错误保留错误码编号且不会再次提交", async () => {
    const flow = approvalFlowHarness({stream: async (emit) => {
        emit({type: "approval-decision", data: {status: "APPROVED", version: 2}});
        emit({type: "error", data: {message: "模型暂时不可用", code: "MODEL_UNAVAILABLE", errorId: "resume-test"}});
    }});
    await assert.rejects(() => flow.actions.submitApprovalDecision(flow.approval, {}), /MODEL_UNAVAILABLE resume-test.*决定已经被服务端接受，请勿重复提交/);
    assert.equal(flow.state.pendingApprovals.get(flow.approval.approvalId).canDecide, false);
    assert.equal(flow.requests.length, 1);
    assert.equal(flow.queryCount(), 1);
});

test("卡片刷新实际只查询状态并在成功后解除未知提示", async () => {
    const flow = approvalFlowHarness();
    flow.state.approvalFeedback.set(flow.approval.approvalId, {phase: "UNKNOWN"});
    flow.state.chatLoading = true;
    await flow.actions.refreshApprovalFromCard(flow.approval);
    assert.equal(flow.requests.length, 0);
    assert.equal(flow.queryCount(), 1);
    assert.ok(flow.phases.includes("CHECKING"));
    assert.equal(flow.state.approvalFeedback.size, 0);
    assert.equal(flow.state.chatLoading, false);
});

test("旧登录会话的审批返回不会覆盖新会话卡片或启动查询", async () => {
    let finish;
    const pending = new Promise((resolve) => { finish = resolve; });
    const flow = approvalFlowHarness({stream: async (emit) => {
        await pending;
        emit({type: "approval-decision", data: {status: "APPROVED", version: 2}});
        emit({type: "completed", data: {}});
    }});
    const first = flow.actions.submitApprovalDecision(flow.approval, {});
    flow.changeSession();
    flow.state.approvalFeedback.clear();
    finish();
    await first;
    assert.equal(flow.state.approvalFeedback.size, 0);
    assert.equal(flow.state.pendingApprovals.get(flow.approval.approvalId).status, "PENDING");
    assert.equal(flow.queryCount(), 0);
});

function historyFixture(id, changes = {}) {
    return {...approvalDraftFixture(), approvalId: id, runId: "run", status: "APPROVED", canDecide: false,
        decidedAt: "2026-09-03T04:00:00Z", version: 2,
        items: [{itemId: `${id}-item`, toolName: "测试工具", toolCallId: `${id}-call`, decision: "APPROVE", inputSummary: "脱敏参数"}], ...changes};
}

test("历史按审批编号去重而不是覆盖同一运行的多轮确认", () => {
    const first = historyFixture("first", {decidedAt: "2026-09-03T03:00:00Z"});
    const second = historyFixture("second", {status: "PARTIALLY_APPROVED", items: [
        {itemId: "one", decision: "APPROVE"}, {itemId: "two", decision: "DENY"}]} );
    const history = core.mergeApprovalHistory([first], [second, {...first, canDecide: true}], "agent", "conversation");
    assert.deepEqual(history.map((item) => item.approvalId), ["second", "first"]);
    assert.ok(history.every((item) => item.canDecide === false));
    const group = core.groupApprovalHistory(history)[0];
    assert.equal(group.runId, "run");
    assert.deepEqual(group.items.map((item) => item.approvalId), ["first", "second"]);
    assert.deepEqual(group.items[1].items.map((item) => item.decision), ["APPROVE", "DENY"]);
});

test("历史合并过滤其他会话Agent待办与非法时间并保留更高版本", () => {
    const current = historyFixture("same", {version: 3, status: "DENIED"});
    const history = core.mergeApprovalHistory([current], [historyFixture("same"),
        historyFixture("foreign", {agentId: "other"}), historyFixture("foreign2", {conversationId: "other"}),
        historyFixture("pending", {status: "PENDING"}), historyFixture("bad", {decidedAt: "bad"}), null], "agent", "conversation");
    assert.equal(history.length, 1);
    assert.equal(history[0].status, "DENIED");
    const reloaded = core.mergeApprovalHistory([], JSON.parse(JSON.stringify(history)), "agent", "conversation");
    assert.deepEqual(reloaded, history);
});

// 仅模拟标准 DOM 边界；加载、分组、消息关联、错误重试使用实际 app.js 函数。
function historyFlowHarness(request) {
    const vm = require("node:vm");
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");
    function node(tag, options = {}) {
        const result = {tag, className: options.className || "", textContent: options.text || "", dataset: {}, children: [],
            append(...children) { for (const child of children) { child.parent = this; this.children.push(child); } },
            replaceChildren(...children) { this.children = []; this.append(...children); },
            remove() { this.parent.children = this.parent.children.filter((child) => child !== this); },
            querySelectorAll(selector) {
                const [classPart] = selector.split("[");
                return this.children.flatMap((child) => [child, ...child.all()]).filter((child) =>
                    child.className.split(" ").includes(classPart.slice(1))
                    && (!selector.includes("[open]") || child.open)
                    && (!selector.includes("[data-run-id]") || child.dataset.runId));
            },
            all() { return this.children.flatMap((child) => [child, ...child.all()]); }};
        result.classList = {add(name) { result.className += ` ${name}`; }};
        return result;
    }
    const root = node("main");
    const ids = Object.fromEntries(["chatApprovalHistoryRegion", "chatApprovalHistoryList", "chatMessageList",
        "chatApprovalHistorySummary", "chatApprovalHistoryStatus", "loadMoreApprovalHistoryBtn", "refreshApprovalHistoryBtn", "chatFormStatus"]
        .map((id) => [id, node("div")]));
    root.append(...Object.values(ids));
    const state = {selectedAgentId: "agent", selectedConversationId: "conversation", approvalHistory: [],
        approvalHistoryCursor: "", approvalHistoryBusy: false, approvalHistoryError: "", pendingApprovals: new Map()};
    const requests = [];
    let session = 0;
    const context = {state, core, document: root, element: node, $: (id) => ids[id],
        shortIdentifier: (id) => id.slice(0, 8), setStatus: (target, message) => { target.textContent = message; },
        sessionEpoch: {capture: () => session, isCurrent: (value) => value === session},
        chatLoadRevision: core.createLoadRevisionGate(), approvalHistoryRevision: core.createLoadRevisionGate(),
        renderChatApprovals() {}, renderChatMessages(messages) {
            ids.chatMessageList.replaceChildren(...messages.map((message) => {
                const item = node("article", {className: "chat-message"});
                item.dataset.runId = message.runId;
                return item;
            }));
        },
        api: {request: async (url, options) => { requests.push({url, options}); return request(url, options); }}
    };
    const loading = app.slice(app.indexOf("async function loadChatMessages("), app.indexOf("function renderChatApprovals("));
    const history = app.slice(app.indexOf("async function refreshApprovalState("), app.indexOf("function streamEventError("));
    vm.runInNewContext(`${loading}\n${history}\nglobalThis.actions = {loadChatMessages, refreshApprovalState, resetApprovalHistory, loadApprovalHistory, renderApprovalHistory, createApprovalHistoryGroup};`, context);
    return {state, requests, ids, node, actions: context.actions, changeSession: () => { session += 1; }};
}

test("实际会话加载在重新进入时恢复历史并保留待审批入口", async () => {
    const entries = [historyFixture("one"), historyFixture("two", {decidedAt: "2026-09-03T05:00:00Z"})];
    const flow = historyFlowHarness(async (url) => {
        if (url.includes("/messages?")) return {items: [{runId: "run"}]};
        if (url.includes("status=PENDING")) return {items: [approvalDraftFixture()]};
        return {items: entries, nextCursor: null};
    });
    await flow.actions.loadChatMessages("agent", "conversation");
    assert.equal(flow.state.pendingApprovals.size, 1);
    assert.equal(flow.state.approvalHistory.length, 2);
    assert.equal(flow.ids.chatMessageList.querySelectorAll(".chat-approval-history-inline").length, 1);
    flow.actions.resetApprovalHistory();
    await flow.actions.loadChatMessages("agent", "conversation");
    assert.equal(flow.state.approvalHistory.length, 2);
    assert.equal(flow.requests.length, 6);
    assert.ok(flow.requests.every(({options}) => !options?.method || options.method === "GET"));
});

test("实际分页保留多轮记录且无消息锚点的运行进入历史区", async () => {
    const flow = historyFlowHarness(async (url) => url.includes("cursor=")
        ? {items: [historyFixture("old", {runId: "old-run", decidedAt: "2026-09-02T00:00:00Z"})]}
        : {items: [historyFixture("latest")], nextCursor: "opaque+/cursor"});
    const anchor = flow.node("article", {className: "chat-message"});
    anchor.dataset.runId = "run";
    flow.ids.chatMessageList.append(anchor);
    await flow.actions.loadApprovalHistory();
    anchor.children[0].open = true;
    await flow.actions.loadApprovalHistory(true);
    assert.equal(flow.state.approvalHistory.length, 2);
    assert.match(flow.requests[1].url, /cursor=opaque%2B%2Fcursor/);
    assert.equal(anchor.children.length, 1);
    assert.equal(anchor.children[0].open, true);
    assert.equal(flow.ids.chatApprovalHistoryList.children[0].dataset.runId, "old-run");
    assert.match(flow.ids.chatApprovalHistorySummary.textContent, /1 条位于对应消息下方.*另有 1 条/);
    assert.equal(flow.ids.loadMoreApprovalHistoryBtn.hidden, true);
});

test("提交后权威查询将终态放入历史而不是覆盖下一轮待审批", async () => {
    const decided = historyFixture("done");
    const next = {...approvalDraftFixture(), approvalId: "next"};
    const flow = historyFlowHarness(async (url) => {
        if (url.endsWith("/approvals/done")) return decided;
        if (url.includes("status=PENDING")) return {items: [next]};
        if (url.includes("/history?")) return {items: [historyFixture("earlier")]};
        return {items: [{runId: "run"}]};
    });
    await flow.actions.refreshApprovalState(decided, () => true);
    assert.equal(flow.state.pendingApprovals.has("done"), false);
    assert.equal(flow.state.pendingApprovals.has("next"), true);
    assert.deepEqual(Array.from(flow.state.approvalHistory, (item) => item.approvalId).sort(), ["done", "earlier"]);
    assert.equal(flow.requests.length, 4);
});

test("历史失败不伪装空数据并保留分页游标和错误编号供重试", async () => {
    let failed = true;
    const flow = historyFlowHarness(async () => {
        if (failed) throw new Error("暂时不可用（错误码：PERSISTENCE_UNAVAILABLE）（错误编号：history-error）");
        return {items: [historyFixture("old", {decidedAt: "2026-09-02T00:00:00Z"})]};
    });
    flow.state.approvalHistory = [historyFixture("first")];
    flow.state.approvalHistoryCursor = "cursor";
    await flow.actions.loadApprovalHistory(true);
    assert.equal(flow.state.approvalHistory.length, 1);
    assert.equal(flow.state.approvalHistoryCursor, "cursor");
    assert.match(flow.ids.chatApprovalHistoryStatus.textContent, /PERSISTENCE_UNAVAILABLE.*history-error/);
    assert.equal(flow.ids.refreshApprovalHistoryBtn.textContent, "重试加载历史");
    failed = false;
    await flow.actions.loadApprovalHistory(true);
    assert.equal(flow.state.approvalHistory.length, 2);
    assert.equal(flow.state.approvalHistoryError, "");
});

test("首屏历史失败独立显示错误而不丢失已读取的消息和待办", async () => {
    const flow = historyFlowHarness(async (url) => {
        if (url.includes("/messages?")) return {items: [{runId: "run"}]};
        if (url.includes("status=PENDING")) return {items: [approvalDraftFixture()]};
        throw new Error("历史不可用，错误编号：history-first");
    });
    assert.equal(await flow.actions.loadChatMessages("agent", "conversation"), true);
    assert.equal(flow.state.chatLoading, false);
    assert.equal(flow.state.pendingApprovals.size, 1);
    assert.equal(flow.ids.chatMessageList.children.length, 1);
    assert.match(flow.ids.chatApprovalHistorySummary.textContent, /不表示没有历史/);
    assert.match(flow.ids.chatApprovalHistoryStatus.textContent, /history-first/);
});

test("迟到历史响应在切换会话或退出登录后不写回新页面", async () => {
    for (const logout of [false, true]) {
        let resolve;
        const flow = historyFlowHarness(() => new Promise((done) => { resolve = done; }));
        const loading = flow.actions.loadApprovalHistory();
        if (logout) flow.changeSession();
        else flow.state.selectedConversationId = "another";
        flow.actions.resetApprovalHistory();
        resolve({items: [historyFixture("late")], nextCursor: "old"});
        await loading;
        assert.equal(flow.state.approvalHistory.length, 0);
        assert.equal(flow.state.approvalHistoryCursor, "");
        assert.equal(flow.state.approvalHistoryBusy, false);
    }
});

test("历史详情逐项只读展示且HTML样参数始终作为纯文本", () => {
    const flow = historyFlowHarness(async () => ({}));
    const dangerous = "<img src=x onerror=alert(1)>";
    const detail = flow.actions.createApprovalHistoryGroup({runId: "run", items: [historyFixture("readonly", {
        canDecide: true, requestedByDisplayName: "发起人", decidedByDisplayName: "审批人",
        items: [{toolName: dangerous, inputSummary: dangerous, decision: "DENY"},
            {toolName: "允许项", inputSummary: "已脱敏", decision: "APPROVE"}]
    })]});
    const nodes = [detail, ...detail.all()];
    assert.ok(nodes.every((item) => !["form", "input", "button", "img"].includes(item.tag)));
    assert.ok(nodes.some((item) => item.tag === "pre" && item.textContent === dangerous));
    assert.ok(nodes.some((item) => item.textContent.includes("已拒绝")));
    assert.ok(nodes.some((item) => item.textContent.includes("已允许")));
    assert.ok(nodes.some((item) => item.textContent.includes("审批允许不等于工具执行成功")));
});
