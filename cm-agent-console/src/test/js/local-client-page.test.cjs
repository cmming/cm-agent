const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const app = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");
const start = app.indexOf("function localClientPlatformLabel(");
const end = app.indexOf("function renderChatLocalClientOptions(");
const actionsSource = app.slice(start, end);

function node(tag, props = {}) {
    return {
        tag, children: [], dataset: {}, hidden: false, disabled: false, textContent: props.text || "",
        className: props.className || "", value: "", title: "", isConnected: true,
        classList: {toggle() {}},
        setAttribute(name, value) { this[name] = value; },
        append(...items) { this.children.push(...items); },
        replaceChildren(...items) { this.children = items; this.textContent = ""; },
        addEventListener(type, listener) { this[type] = listener; },
        focus(options) { this.focusCalls = (this.focusCalls || 0) + 1; this.focusOptions = options; }
    };
}

function all(item) {
    return [item, ...(item.children || []).flatMap(all)];
}

function harness({request = async () => null} = {}) {
    const nodes = new Map();
    const requests = [], statuses = [];
    const state = {
        localClientDevices: [],
        selectedLocalClientDeviceId: "",
        localClientRevokeArmed: ""
    };
    function get(id) {
        if (!nodes.has(id)) nodes.set(id, node("div"));
        return nodes.get(id);
    }
    const document = {
        getElementById: get,
        querySelector(selector) {
            if (selector !== ".local-client-revoke-zone .button") return null;
            return all(get("localClientDeviceDetail")).find(item => item.className === "button danger") || null;
        }
    };
    let epoch = 1, revision = 0;
    const context = {
        state,
        core: {formatDateTime: value => value || "—"},
        $: get,
        element: node,
        emptyState: text => node("p", {text}),
        document,
        sessionEpoch: {capture: () => epoch, isCurrent: value => value === epoch},
        localClientLoadRevision: {issue: () => ++revision, isCurrent: value => value === revision},
        setStatus(target, message, tone) {
            statuses.push({id: target?.id || "", message, tone});
            if (target) {
                target.textContent = message;
                target.dataset.tone = tone;
            }
        },
        withSubmitState: async (button, action) => action(),
        api: {request: async (url, options) => {
            requests.push({url, options});
            return request(url, options);
        }},
        renderChatLocalClientOptions() {}
    };
    vm.runInNewContext(`${actionsSource};globalThis.actions={parseLocalClientPairPackage,renderLocalClientPairPreview,confirmLocalClientPair,loadLocalClientDevices,renderLocalClientDevices,renderLocalClientDeviceDetail,revokeLocalClientDevice};`, context);
    return {state, nodes, get, document, requests, statuses, actions: context.actions};
}

const pairingPackage = {
    requestId: "11111111-1111-4111-8111-111111111111",
    nonce: "bm9uY2U=",
    deviceName: "工作站",
    platform: "WINDOWS",
    publicKey: "cHVibGljLWtleQ==",
    proof: "cHJvb2Y="
};

function device(overrides = {}) {
    return {
        deviceId: "device-1", displayName: "工作站", platform: "WINDOWS", state: "ACTIVE", version: 3,
        createdAt: "2026-11-04T08:00:00Z", updatedAt: "2026-11-04T08:01:00Z", ...overrides
    };
}

test("配对包只接受完整字段和已知平台，预览不创建请求", () => {
    const flow = harness();
    flow.get("localClientPairPackage").value = JSON.stringify(pairingPackage);
    const preview = flow.actions.renderLocalClientPairPreview(false);
    assert.equal(preview.deviceName, "工作站");
    assert.equal(flow.get("localClientPairDeviceName").textContent, "工作站");
    assert.equal(flow.get("localClientPairPlatform").textContent, "Windows");
    assert.equal(flow.get("localClientPairPreview").hidden, false);
    assert.equal(flow.requests.length, 0);
    for (const invalid of ["{", JSON.stringify({...pairingPackage, proof: ""}),
        JSON.stringify({...pairingPackage, platform: "SOLARIS"})]) {
        flow.get("localClientPairPackage").value = invalid;
        assert.throws(() => flow.actions.parseLocalClientPairPackage());
    }
});

test("浏览器确认只报告服务端已确认，等待客户端最终落盘并刷新设备", async () => {
    const flow = harness({request: async url => {
        if (url === "/api/local-client/pairings") return {pairingId: "pair-1"};
        if (url.endsWith("/pairings/pair-1/confirm")) return {state: "CONFIRMED", deviceId: "device-1"};
        if (url === "/api/local-client/devices") return [device()];
        throw new Error(url);
    }});
    flow.get("localClientPairPackage").value = JSON.stringify(pairingPackage);
    await flow.actions.confirmLocalClientPair();
    assert.deepEqual(flow.requests.map(item => item.url), [
        "/api/local-client/pairings",
        "/api/local-client/pairings/pair-1/confirm",
        "/api/local-client/devices"
    ]);
    const message = flow.get("localClientPairStatus").textContent;
    assert.match(message, /服务端已确认/);
    assert.match(message, /本机客户端/);
    assert.doesNotMatch(message, /设备已连接/);
    assert.equal(flow.get("localClientPairPackage").value, "");
    assert.equal(flow.get("localClientDeviceCount").textContent, "共 1 台");
});

test("设备列表保留已撤销记录并展示服务端字段，不推断在线状态", () => {
    const flow = harness();
    flow.state.localClientDevices = [device(), device({deviceId: "device-2", displayName: "旧设备", state: "REVOKED", version: 4})];
    flow.state.selectedLocalClientDeviceId = "device-1";
    flow.actions.renderLocalClientDevices();
    assert.equal(flow.get("localClientDeviceCount").textContent, "共 2 台");
    assert.equal(flow.get("localClientDeviceList").children.length, 2);
    const buttons = all(flow.get("localClientDeviceList")).filter(item => item.className === "local-client-device-row");
    assert.equal(buttons[0]["aria-pressed"], "true");
    assert.equal(buttons[1]["aria-pressed"], "false");
    assert.equal(buttons[0]["aria-controls"], "localClientDeviceDetail");
    const listText = all(flow.get("localClientDeviceList")).map(item => item.textContent).join(" ");
    assert.match(listText, /已撤销/);
    const detailText = all(flow.get("localClientDeviceDetail")).map(item => item.textContent).join(" ");
    for (const text of ["Windows", "device-1", "2026-11-04T08:00:00Z", "3", "2026-11-04T08:01:00Z"]) {
        assert.match(detailText, new RegExp(text));
    }
    assert.match(detailText, /只显示服务端配对状态/);
});

test("键盘选择设备和二次撤销后焦点移回重建后的控件", async () => {
    const flow = harness();
    flow.state.localClientDevices = [device(), device({deviceId: "device-2", displayName: "第二台设备"})];
    flow.state.selectedLocalClientDeviceId = "device-1";
    flow.actions.renderLocalClientDevices();
    flow.get("localClientDeviceList").children[1].children[0].click();
    const selected = all(flow.get("localClientDeviceList")).filter(item => item.className === "local-client-device-row")[1];
    assert.equal(selected["aria-pressed"], "true");
    assert.equal(selected.focusCalls, 1);
    assert.equal(selected.focusOptions.preventScroll, true);

    await flow.actions.revokeLocalClientDevice(device({deviceId: "device-2", displayName: "第二台设备"}));
    const revoke = flow.document.querySelector(".local-client-revoke-zone .button");
    assert.equal(revoke.textContent, "再次点击确认撤销");
    assert.equal(revoke.focusCalls, 1);
});

test("设备读取失败在页面与实时状态中公布可读原因", async () => {
    const flow = harness({request: async () => { throw new Error("设备服务暂不可用"); }});
    await flow.actions.loadLocalClientDevices();
    assert.match(flow.get("localClientDeviceStatus").textContent, /无法读取本机客户端：设备服务暂不可用/);
    assert.match(all(flow.get("localClientDeviceList")).map(item => item.textContent).join(" "), /设备服务暂不可用/);
});

test("撤销需要二次点击，按读取版本提交且成功后刷新", async () => {
    const flow = harness({request: async (url, options) => {
        if (url.endsWith("/devices/device-1/revoke")) return {...device(), state: "REVOKED", version: 4};
        if (url === "/api/local-client/devices") return [{...device(), state: "REVOKED", version: 4}];
        throw new Error(url);
    }});
    flow.state.localClientDevices = [device()];
    flow.state.selectedLocalClientDeviceId = "device-1";
    flow.actions.renderLocalClientDevices();
    const active = device();
    await flow.actions.revokeLocalClientDevice(active);
    assert.equal(flow.requests.length, 0);
    assert.match(flow.get("globalStatus").textContent, /再次点击确认/);
    await flow.actions.revokeLocalClientDevice(active);
    assert.equal(flow.requests.length, 2);
    assert.equal(flow.requests[0].url, "/api/local-client/devices/device-1/revoke");
    assert.deepEqual(JSON.parse(flow.requests[0].options.body), {expectedVersion: 3});
    assert.match(flow.get("globalStatus").textContent, /设备已撤销/);
    const refreshed = all(flow.get("localClientDeviceList")).find(item => item.className === "local-client-device-row");
    assert.equal(refreshed.focusCalls, 1);
});

test("撤销版本冲突或网络未知只提交一次，刷新核对且不自动重试", async () => {
    for (const error of [Object.assign(new Error("设备状态已变化"), {status: 409}), new Error("网络中断")]) {
        const flow = harness({request: async url => {
            if (url.endsWith("/devices/device-1/revoke")) throw error;
            if (url === "/api/local-client/devices") return [device()];
            throw new Error(url);
        }});
        flow.state.localClientDevices = [device()];
        flow.state.selectedLocalClientDeviceId = "device-1";
        flow.actions.renderLocalClientDevices();
        await flow.actions.revokeLocalClientDevice(device());
        await flow.actions.revokeLocalClientDevice(device());
        assert.equal(flow.requests.filter(item => item.url.endsWith("/revoke")).length, 1);
        assert.match(flow.get("globalStatus").textContent, error.status === 409 ? /状态已变化/ : /结果尚未确认/);
    }
});

test("页面文案和脚本引用区分系统健康与设备在线", () => {
    const page = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/console/v2/local-client.html"), "utf8");
    assert.match(page, /控制台服务已连接/);
    assert.match(page, /不代表设备在线/);
    assert.match(page, /已登记设备/);
    assert.match(page, /共 0 台/);
    assert.doesNotMatch(page, /系统运行正常/);
    assert.doesNotMatch(page, /project-development\.js/);
    assert.doesNotMatch(page, /local-projects\.js/);
    assert.doesNotMatch(page, /skills\.js/);
});


test("首次连接指引包含 fat JAR 构建、配对落盘和单轮授权边界", () => {
    const page = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/console/v2/local-client.html"), "utf8");
    assert.match(page, /mvn -pl cm-agent-local-client -am -DskipTests package/);
    assert.match(page, /cm-agent-local-client-0\.1\.0-SNAPSHOT-all\.jar pair --server https:\/\/服务端地址 --name 设备名称/);
    assert.match(page, /java -jar cm-agent-local-client-0\.1\.0-SNAPSHOT-all\.jar run/);
    assert.match(page, /目标电脑只需 JDK 21/);
    assert.doesNotMatch(page, /target\/classes/);
    assert.doesNotMatch(page, /dependency:copy-dependencies/);
    assert.doesNotMatch(page, /LocalClientApplication pair/);
    assert.match(page, /回到客户端终端等待“配对完成”/);
    assert.match(page, /配对只登记设备，不授予文件、联网或命令操作权限/);
    assert.match(page, /每次运行仍需在会话中选择设备、能力和确认方式/);
    assert.match(page, /href="\/console\/v2\/chat.html"/);
    assert.doesNotMatch(page, /浏览器所在电脑/);
});
