const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("../../main/resources/META-INF/resources/assets/console-core.js");
const artifacts = require("../../main/resources/META-INF/resources/console/v2/assets/skill-artifacts.js");

test("附件下载只使用当前会话并检查实际大小与编号", async () => {
    for (const actual of [3, 2, 4]) {
        let sent;
        const api = core.createApiClient({getToken: () => "test-token", onUnauthorized() {}, fetchImpl: async (url, options) => {
            sent = {url, options};
            return {ok: true, headers: new Headers({"Content-Length": "3", "X-Error-Id": "artifact-error", "Content-Type": "text/plain"}),
                body: new ReadableStream({start(controller) {controller.enqueue(new Uint8Array(actual)); controller.close();}})};
        }});
        if (actual === 3) assert.equal((await api.request("/api/skill-artifacts/a/content", {responseType: "blob"})).size, 3);
        else await assert.rejects(() => api.request("/api/skill-artifacts/a/content", {responseType: "blob"}), /artifact-error/);
        assert.equal(sent.url, "/api/skill-artifacts/a/content");
        assert.equal(sent.options.headers.get("Authorization"), "Bearer test-token");
        assert.equal(sent.options.credentials, "same-origin");
        assert.equal(sent.options.responseType, undefined);
    }
});
function fixture(api) {
    const nodes = [], revoked = [];let epoch = 1;
    const element = (tag) => ({tag, isConnected: true, dataset: {}, classList: {add() {}}, children: [],
        setAttribute() {}, removeAttribute() {}, append(...items) {this.children.push(...items);}, replaceChildren(...items) {this.children = items;},
        addEventListener(event, handler) {this[event] = handler;}, remove() {this.isConnected = false;}, click() {}, scrollIntoView() {this.revealed = true;}});
    const document = {createElement(tag) {const node = element(tag);nodes.push(node);return node;}, body: element("body")};
    const manager = artifacts.createManager({api, document, getSessionEpoch: () => epoch,
        urlApi: {createObjectURL() {return "blob:test";}, revokeObjectURL(url) {revoked.push(url);}}, schedule: () => 1, unschedule() {}});
    return {manager, container: element("section"), nodes, revoked, switchTenant() {epoch++;}};
}
test("重复点击只下载一次且销毁释放 Blob", async () => {
    let resolve, count = 0;
    const f = fixture({request: async (path) => path.endsWith("/content") ? (count++, await new Promise(r => {resolve = r;})) :
        {status: "READY", files: [{id: "a", filename: "中文.docx", mediaType: "docx", sizeBytes: 3, expiresAt: new Date().toISOString(), status: "READY"}]}});
    await f.manager.mount(f.container, "/list").ready;
    const button = f.nodes.find(n => n.tag === "button");const first = button.click();await button.click();assert.equal(count, 1);
    resolve(new Blob(["abc"]));await first;f.manager.dispose();assert.deepEqual(f.revoked, ["blob:test"]);
    assert.equal(f.nodes.find(n => n.tag === "a").download, "中文.docx");
});
test("换租户后迟到列表不进入新会话", async () => {
    let resolve;
    const f = fixture({request: () => new Promise(r => {resolve = r;})});
    const pending = f.manager.mount(f.container, "/list");f.switchTenant();resolve({status: "READY", files: [{id: "a", filename: "不应展示"}]});await pending.ready;
    assert.equal(f.nodes.some(n => n.tag === "button"), false);f.manager.dispose();
});
test("并发下载达到上限会提示且完成后可继续", async () => {
    const waiting = [];let count = 0;
    const f = fixture({request: async (path) => path.endsWith("/content") ? (count++, await new Promise(r => waiting.push(r))) :
        {status: "READY", files: ["a", "b", "c"].map(id => ({id, filename: id + ".txt", sizeBytes: 3, expiresAt: new Date().toISOString(), status: "READY"}))}});
    await f.manager.mount(f.container, "/list").ready;
    const buttons = f.nodes.filter(n => n.tag === "button");
    const first = buttons[0].click(), second = buttons[1].click();
    await buttons[2].click();assert.equal(count, 2);
    assert.match(f.nodes.find(n => n.tag === "p").textContent, /已有两个文件正在下载/);
    assert.equal(f.nodes.find(n => n.tag === "p").revealed, true);
    waiting[0](new Blob(["abc"]));await first;
    const third = buttons[2].click();assert.equal(count, 3);
    waiting[1](new Blob(["abc"]));waiting[2](new Blob(["abc"]));await Promise.all([second, third]);f.manager.dispose();
});
test("过期附件不提供下载且名称清理危险字符", async () => {
    const f = fixture({request: async () => ({status: "READY", files: [{id: "a", filename: "旧文件.txt", status: "EXPIRED"}]})});
    await f.manager.mount(f.container, "/list").ready;assert.equal(f.nodes.find(n => n.tag === "button").disabled, true);
    assert.equal(artifacts.safeFilename('../a\\b"\n.docx'), '.._a_b__.docx');f.manager.dispose();
});
