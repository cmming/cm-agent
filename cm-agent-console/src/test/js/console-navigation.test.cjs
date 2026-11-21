const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");

function versionConfiguration(version) {
    const header = source.slice(source.indexOf("const consoleVersion ="), source.indexOf("const HTTP_TOOL_FORM_EXAMPLE"));
    const context = {document: {body: {dataset: {consoleVersion: version, page: "overviewPage"}}}};
    vm.runInNewContext(`${header}\nthis.configuration = {consoleVersion, isMultiPage, consoleBasePath, multiPagePaths};`, context);
    return context.configuration;
}

test("只有显式V2或V3页面启用多页协议且路径固定在当前版本", () => {
    for (const version of ["v2", "v3"]) {
        const configuration = versionConfiguration(version);
        assert.equal(configuration.isMultiPage, true);
        assert.equal(configuration.multiPagePaths.chatPage, `/console/${version}/chat.html`);
        assert.equal(configuration.multiPagePaths.modelConfigsPage, `/console/${version}/model-configs.html`);
        assert.equal(Object.values(configuration.multiPagePaths).every(value => value.startsWith(`/console/${version}/`)), true);
    }
    for (const version of [undefined, "v1", "v4", "../external"]) {
        assert.equal(versionConfiguration(version).isMultiPage, false);
    }
});

test("每个 v2 入口都预载跨页导航所需样式和项目脚本", () => {
    const directory = path.join(__dirname, "../../main/resources/META-INF/resources/console/v2");
    const pages = ["login", "overview", "agents", "model-configs", "tools", "skills", "projects", "local-client", "chat", "runs", "audit"];
    const dependencies = ["project-development.js?v=1.2.2", "local-projects.js?v=1.3.2", "client-projects.js?v=1.2.3", "/assets/app.js?v=2.0.46"];
    for (const page of pages) {
        const html = fs.readFileSync(path.join(directory, `${page}.html`), "utf8");
        for (const stylesheet of ["local-projects.css?v=1.11.0", "local-client.css?v=1.0.4"]) {
            assert.equal(html.split(stylesheet).length - 1, 1, `${page} 缺少样式或重复引用：${stylesheet}`);
            assert.ok(html.indexOf(stylesheet) < html.indexOf("</head>"), `${page} 的样式必须位于 head`);
        }
        let previous = -1;
        for (const dependency of dependencies) {
            const position = html.indexOf(dependency);
            assert.ok(position > previous && html.indexOf(dependency, position + 1) === -1, `${page} 的脚本顺序或引用次数错误：${dependency}`);
            previous = position;
        }
    }
});

test("每个V3入口预载相同的外观和真实业务脚本以支持body导航", () => {
    const directory = path.join(__dirname, "../../main/resources/META-INF/resources/console/v3");
    const pages = ["login", "overview", "agents", "model-configs", "tools", "skills", "projects", "local-client", "chat", "runs", "audit"];
    const scripts = ["/assets/console-core.js", "/console/v2/assets/project-development.js",
        "/console/v2/assets/local-projects.js", "/console/v2/assets/client-projects.js",
        "/console/v3/assets/workspace.js", "/console/v3/assets/chat-workspace.js", "/assets/app.js"];
    let commonSources;
    for (const page of pages) {
        const html = fs.readFileSync(path.join(directory, `${page}.html`), "utf8");
        assert.match(html, /data-console-version="v3"/);
        const sources = Array.from(html.matchAll(/<script\s+src="([^"]+)"/g), match => match[1]);
        commonSources ||= sources;
        assert.deepEqual(sources, commonSources, `${page} 必须预载同一组脚本`);
        for (const script of scripts) {
            assert.equal(sources.filter(value => value.split("?")[0] === script).length, 1, `${page} 的 ${script} 应只加载一次`);
        }
        assert.ok(sources.findIndex(value => value.startsWith("/console/v3/assets/chat-workspace.js"))
            < sources.findIndex(value => value.startsWith("/assets/app.js")));
        for (const stylesheet of ["compatibility.css", "workspace.css", "chat-workspace.css"]) {
            const position = html.indexOf(`/console/v3/assets/${stylesheet}`);
            assert.ok(position >= 0 && position < html.indexOf("</head>"), `${page} 缺少 head 中的 ${stylesheet}`);
        }
    }
});

// 执行真实导航函数，仅替换浏览器和网络边界；覆盖 body 替换前后的组件生命周期。
function navigationHarness({version = "v2"} = {}) {
    function functionSource(name) {
        const result = source.match(new RegExp(`(?:async )?function ${name}\\([^)]*\\) \\{[\\s\\S]*?\\n    \\}`));
        assert.ok(result, `导航函数不存在：${name}`);
        return result[0];
    }
    const views = [], events = [], requests = [];
    const state = {};
    const invalidate = {invalidate() {}, invalidateAll() {}};
    const context = {
        ...versionConfiguration(version), URLSearchParams,
        workspaceChoice: {reset() { events.push("reset-workspace"); }},
        nativeWorkspaceImport: {reset() { events.push("reset-local-files"); }},
        artifactManager: {dispose() { events.push("dispose-artifacts"); }},
        chatUx: {reset() { events.push("reset-chat"); }},
        state, pageNavigationRevision: 0, currentPage: "overviewPage", sessionEpoch: invalidate,
        window: {fetch: async (url) => { requests.push(url); return {ok: true, text: async () => url}; },
            scrollTo() {}, location: {search: ""}, history: {pushState() {}, replaceState() {}},
            CmAgentV3: {dispose() { events.push("dispose-v3"); }}},
        document: {body: {dataset: {}, replaceChildren() { events.push("replace-body"); }}, importNode: (node) => node},
        responseVersion: version,
        DOMParser: class { parseFromString(url) { return {body: {dataset: {consoleVersion: context.responseVersion, page: context.pageIdForPath(url)}, children: [{tagName: "MAIN"}]}, title: url}; } },
        bindPageControls() {}, toggleHttpConfigFields() {},
        initializeMultiPage: async () => {
            if (context.currentPage !== "skillsPage") return;
            for (const key of ["skillsPage", "sandboxPage"]) {
                if (!state[key]) {
                    const view = {key, disposed: false, dispose() { this.disposed = true; events.push("dispose-" + key); }};
                    views.push(view); state[key] = view;
                }
            }
        }
    };
    for (const key of ["submitStateGuard", "toolLoadRevision", "agentDetailRevision", "modelConfigLoadRevision",
        "modelConfigDetailRevision", "modelCatalogDiscoveryRevision", "localClientLoadRevision",
        "localExampleLoadRevision", "localExampleInstallRevision"]) context[key] = invalidate;
    vm.createContext(context);
    vm.runInContext(["pageIdForPath", "disposeSkillPages", "loadMultiPage", "redirectAfterLogin", "loginPath"]
        .map(functionSource).join("\n"), context);
    return {context, state, views, events, requests, navigate: (url) => context.loadMultiPage(url)};
}

test("V3默认进入聊天且returnTo仅允许当前版本白名单", async () => {
    for (const returnTo of [null, "https://external.example/", "/console/v2/tools.html", "/console/v3/not-a-page.html"]) {
        const flow = navigationHarness({version: "v3"});
        flow.context.window.location.search = returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : "";
        await flow.context.redirectAfterLogin();
        assert.equal(flow.requests.at(-1), "/console/v3/chat.html");
    }
    const v3 = navigationHarness({version: "v3"});
    v3.context.window.location.search = "?returnTo=%2Fconsole%2Fv3%2Ftools.html";
    await v3.context.redirectAfterLogin();
    assert.equal(v3.requests.at(-1), "/console/v3/tools.html");
    const v2 = navigationHarness();
    await v2.context.redirectAfterLogin();
    assert.equal(v2.requests.at(-1), "/console/v2/overview.html");
});

test("V3导航替换前释放外观组件且不接受跨版本页面", async () => {
    const flow = navigationHarness({version: "v3"});
    await flow.navigate("/console/v3/skills.html");
    assert.ok(flow.events.indexOf("dispose-v3") < flow.events.indexOf("replace-body"));
    assert.equal(flow.context.document.body.dataset.consoleVersion, "v3");
    flow.context.currentPage = "toolsPage";
    assert.equal(flow.context.loginPath(), "/console/v3/login.html?returnTo=%2Fconsole%2Fv3%2Ftools.html");
    flow.context.currentPage = "login";
    assert.equal(flow.context.loginPath(), "/console/v3/login.html");
    await assert.rejects(flow.navigate("/console/v2/tools.html"), /目标控制台页面不受支持/);
    flow.context.responseVersion = "v2";
    flow.events.length = 0;
    await assert.rejects(flow.navigate("/console/v3/tools.html"), /页面内容与目标版本不匹配/);
    assert.equal(flow.events.length, 0);
});

test("技能页离开后释放双组件，重复进入为新 DOM 创建新实例", async () => {
    const flow = navigationHarness();
    await flow.navigate("/console/v2/skills.html");
    assert.ok(flow.events.indexOf("reset-local-files") < flow.events.indexOf("replace-body"));
    const firstSkill = flow.state.skillsPage, firstSandbox = flow.state.sandboxPage;
    flow.events.length = 0;
    await flow.navigate("/console/v2/tools.html");
    assert.equal(firstSkill.disposed, true);
    assert.equal(firstSandbox.disposed, true);
    assert.deepEqual(flow.events, ["reset-workspace", "reset-local-files", "dispose-artifacts", "dispose-skillsPage", "dispose-sandboxPage", "reset-chat", "replace-body"]);
    assert.equal(flow.state.chatBusy, false);
    assert.equal(flow.state.skillsPage, null);
    await flow.navigate("/console/v2/skills.html");
    assert.notEqual(flow.state.skillsPage, firstSkill);
    assert.notEqual(flow.state.sandboxPage, firstSandbox);
    assert.equal(flow.views.length, 4);
});

test("迟到导航响应不能释放较新页面已创建的技能组件", async () => {
    const flow = navigationHarness();
    let finish;
    flow.context.window.fetch = (url) => url.includes("tools")
        ? new Promise((resolve) => { finish = () => resolve({ok: true, text: async () => url}); })
        : Promise.resolve({ok: true, text: async () => url});
    const older = flow.navigate("/console/v2/tools.html");
    await flow.navigate("/console/v2/skills.html");
    const skill = flow.state.skillsPage;
    finish();
    assert.equal(await older, false);
    assert.equal(flow.state.skillsPage, skill);
    assert.equal(skill.disposed, false);
    assert.equal(flow.events.filter(event => event === "reset-chat").length, 1);
});

test("导航读取失败保留当前技能工作区，不提前释放组件", async () => {
    const flow = navigationHarness();
    await flow.navigate("/console/v2/skills.html");
    const skill = flow.state.skillsPage;
    flow.context.window.fetch = async () => ({ok: false, status: 503});
    flow.events.length = 0;
    await assert.rejects(flow.navigate("/console/v2/tools.html"), /页面加载失败/);
    assert.equal(flow.state.skillsPage, skill);
    assert.equal(skill.disposed, false);
    assert.equal(flow.events.includes("reset-chat"), false);
});
