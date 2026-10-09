const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");

// 执行真实导航函数，仅替换浏览器和网络边界；覆盖 body 替换前后的组件生命周期。
function navigationHarness() {
    function functionSource(name) {
        const result = source.match(new RegExp(`(?:async )?function ${name}\\([^)]*\\) \\{[\\s\\S]*?\\n    \\}`));
        assert.ok(result, `导航函数不存在：${name}`);
        return result[0];
    }
    const views = [], events = [];
    const state = {};
    const invalidate = {invalidate() {}, invalidateAll() {}};
    const context = {
        artifactManager: {dispose() { events.push("dispose-artifacts"); }},
        state, pageNavigationRevision: 0, currentPage: "overviewPage", sessionEpoch: invalidate,
        window: {fetch: async (url) => ({ok: true, text: async () => url}), scrollTo() {}, history: {pushState() {}, replaceState() {}}},
        document: {body: {dataset: {}, replaceChildren() { events.push("replace-body"); }}, importNode: (node) => node},
        pageIdForPath: (url) => url.includes("skills") ? "skillsPage" : "toolsPage",
        DOMParser: class { parseFromString(url) { return {body: {dataset: {consoleVersion: "v2", page: context.pageIdForPath(url)}, children: [{tagName: "MAIN"}]}, title: url}; } },
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
        "modelConfigDetailRevision", "modelCatalogDiscoveryRevision", "localExampleLoadRevision", "localExampleInstallRevision"]) context[key] = invalidate;
    vm.createContext(context);
    vm.runInContext(functionSource("disposeSkillPages") + "\n" + functionSource("loadMultiPage"), context);
    return {context, state, views, events, navigate: (url) => context.loadMultiPage(url)};
}

test("技能页离开后释放双组件，重复进入为新 DOM 创建新实例", async () => {
    const flow = navigationHarness();
    await flow.navigate("/console/v2/skills.html");
    const firstSkill = flow.state.skillsPage, firstSandbox = flow.state.sandboxPage;
    flow.events.length = 0;
    await flow.navigate("/console/v2/tools.html");
    assert.equal(firstSkill.disposed, true);
    assert.equal(firstSandbox.disposed, true);
    assert.deepEqual(flow.events, ["dispose-artifacts", "dispose-skillsPage", "dispose-sandboxPage", "replace-body"]);
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
});

test("导航读取失败保留当前技能工作区，不提前释放组件", async () => {
    const flow = navigationHarness();
    await flow.navigate("/console/v2/skills.html");
    const skill = flow.state.skillsPage;
    flow.context.window.fetch = async () => ({ok: false, status: 503});
    await assert.rejects(flow.navigate("/console/v2/tools.html"), /页面加载失败/);
    assert.equal(flow.state.skillsPage, skill);
    assert.equal(skill.disposed, false);
});
