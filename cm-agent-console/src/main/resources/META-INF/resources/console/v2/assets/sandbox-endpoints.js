(function (root, factory) {
    const exports = factory();
    if (typeof module === "object" && module.exports) module.exports = exports;
    if (root) root.CmAgentSandboxEndpoints = exports;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";
    const base = "/api/skill-sandbox-endpoints";
    function canSetDefault(endpoint) {
        return Boolean(endpoint && endpoint.enabled && endpoint.probeStatus === "PASSED"
            && endpoint.probeRevision === endpoint.revision);
    }
    function errorMessage(error) {
        const message = error.message || "沙箱操作失败，请刷新后重试。";
        const details = [error.code && !message.includes(error.code) ? "错误码：" + error.code : "",
            error.errorId && !message.includes(error.errorId) ? "错误编号：" + error.errorId : ""].filter(Boolean);
        return message + (details.length ? "（" + details.join("，") + "）" : "");
    }
    // 凭据省略表示保留；表单从不读取、缓存或回填已保存的材料。
    function buildPayload(values, endpoint, credentials) {
        const local = values.mode === "LOCAL";
        const payload = {displayName: values.displayName.trim(), backend: "docker", mode: values.mode,
            host: local ? "" : values.host.trim(), port: local ? 0 : Number(values.port),
            username: values.mode === "SSH" ? values.username.trim() : "",
            enabled: Boolean(values.enabled), revision: endpoint?.revision || 0};
        if (values.mode === "SSH") payload.sshAuthType = values.sshAuthType || endpoint?.sshAuthType || "KEY";
        if (!local && credentials) payload.credentials = {...credentials};
        return payload;
    }
    function createSandboxPage({api, getSessionEpoch, getPermissions, document}) {
        let mounted = false, listing = null, selected = null, busy = false, revision = 0;
        const byId = (id) => document.getElementById(id);
        const allowed = (name) => getPermissions().includes("sandbox:" + name);
        const writable = () => allowed("write") && Boolean(listing?.enabled && listing?.credentialKeyConfigured);
        const node = (tag, content, className) => {
            const result = document.createElement(tag);
            if (content != null) result.textContent = content;
            if (className) result.className = className;
            return result;
        };
        const button = (label, handler, className = "button ghost compact") => {
            const result = node("button", label, className); result.type = "button";
            result.addEventListener("click", handler); return result;
        };
        function status(message, tone = "neutral") {
            const target = byId("sandboxPageStatus"); target.textContent = message; target.dataset.tone = tone;
        }
        function clearSecrets() {
            ["privateKey", "knownHosts", "caCertificate", "clientCertificate", "password"].forEach((key) => {
                const field = byId("sandbox-" + key); if (field) field.value = "";
            });
        }
        function drawList() {
            const list = byId("sandboxEndpointList"); list.replaceChildren();
            if (!listing.items.length) list.append(node("p", "尚未配置端点。新建、保存并测试连接后，可设为租户默认端点。", "empty-state"));
            listing.items.forEach((endpoint) => {
                const item = button("", () => edit(endpoint), "resource-item sandbox-resource-item");
                item.setAttribute("aria-pressed", String(selected?.id === endpoint.id));
                item.append(node("strong", endpoint.displayName),
                    node("span", endpoint.mode + (endpoint.defaultEndpoint ? " · 默认端点" : "")),
                    node("span", endpoint.enabled ? (canSetDefault(endpoint) ? "当前版本已通过连接测试" : "等待连接测试") : "已停用"));
                list.append(item);
            });
        }
        function edit(endpoint) {
            if (busy) return;
            selected = endpoint || null; clearSecrets(); drawList();
            const detail = byId("sandboxEndpointDetail"); detail.replaceChildren();
            detail.append(node("h2", endpoint ? endpoint.displayName : "新建沙箱端点"),
                node("p", "保存 → 测试连接 → 设为默认。默认切换只影响后续技能执行。", "field-help"));
            const form = node("form"); form.id = "sandboxEndpointForm";
            function field(key, label, type, value, required = false) {
                const wrapper = node("div", null, "sandbox-field" + (type === "checkbox" ? " sandbox-checkbox-field" : "")), id = "sandbox-" + key;
                const labelNode = node("label", label); labelNode.htmlFor = id;
                const input = node(type === "textarea" ? "textarea" : "input");
                input.id = id; input.name = key;
                if (type !== "textarea") input.type = type;
                input.value = value ?? ""; input.required = required;
                if (type === "textarea") { input.rows = 4; input.spellcheck = false; input.autocomplete = "off"; input.maxLength = 131072; }
                input.disabled = !writable() || busy;
                wrapper.append(labelNode, input); form.append(wrapper); return wrapper;
            }
            field("displayName", "端点名称", "text", endpoint?.displayName, true);
            const modeWrap = node("div", null, "sandbox-field");
            const label = node("label", "连接方式"); label.htmlFor = "sandbox-mode";
            const mode = node("select"); mode.id = "sandbox-mode"; mode.disabled = !writable();
            [["LOCAL", "本地 Docker"], ["SSH", "远程 Docker · SSH"], ["TLS", "远程 Docker · 双向 TLS"]].forEach(([value, title]) => {
                const option = node("option", title); option.value = value; mode.append(option);
            });
            mode.value = endpoint?.mode || "LOCAL"; modeWrap.append(label, mode); form.append(modeWrap);
            const authWrap = node("div", null, "sandbox-field");
            const authLabel = node("label", "SSH 认证方式"); authLabel.htmlFor = "sandbox-sshAuthType";
            const auth = node("select"); auth.id = "sandbox-sshAuthType"; auth.name = "sshAuthType";
            [["KEY", "私钥"], ["PASSWORD", "账号密码"]].forEach(([value, title]) => {
                const option = node("option", title); option.value = value; auth.append(option);
            });
            auth.value = endpoint?.sshAuthType || "KEY"; auth.disabled = !allowed("credential:write") || !writable();
            authWrap.append(authLabel, auth); form.append(authWrap);
            const host = field("host", "允许范围内的主机名或 IP", "text", endpoint?.host);
            const port = field("port", "连接端口", "number", endpoint?.port || 22);
            port.querySelector("input").min = "1"; port.querySelector("input").max = "65535";
            const user = field("username", "SSH 用户", "text", endpoint?.username);
            const enabled = field("enabled", "启用此端点", "checkbox", ""); enabled.querySelector("input").checked = endpoint?.enabled ?? true;
            const replace = field("replaceCredential", endpoint?.hasCredential ? "替换凭据（留空保留现有材料）" : "配置连接凭据", "checkbox", "");
            replace.querySelector("input").checked = !endpoint || !endpoint.hasCredential;
            const key = field("privateKey", "私钥（TLS 使用 PKCS#8 PEM）", "textarea", "");
            const password = field("password", "SSH 密码（只写，留空不代表清除）", "password", "");
            password.querySelector("input").autocomplete = "new-password";
            password.querySelector("input").maxLength = 4096;
            const known = field("knownHosts", "SSH 主机信任 · known_hosts", "textarea", "");
            const ca = field("caCertificate", "TLS CA 证书", "textarea", "");
            const client = field("clientCertificate", "TLS 客户端证书链", "textarea", "");
            [replace, key, known, ca, client, password].forEach((wrapper) => wrapper.querySelector("input,textarea").disabled = !allowed("credential:write") || !writable());
            function protocolChanged() {
                const remote = mode.value !== "LOCAL", ssh = mode.value === "SSH";
                const usesPassword = ssh && auth.value === "PASSWORD";
                // 切换协议或认证类型必须提交新完整材料，不能让旧密文被另一协议解释。
                const changed = endpoint && (mode.value !== endpoint.mode || (ssh && auth.value !== (endpoint.sshAuthType || "KEY")));
                if (changed) replace.querySelector("input").checked = true;
                replace.querySelector("input").disabled = !allowed("credential:write") || !writable() || Boolean(changed);
                authWrap.hidden = !ssh;
                host.hidden = port.hidden = !remote; user.hidden = !ssh;
                replace.hidden = !remote;
                const credentialsNeeded = remote && replace.querySelector("input").checked;
                key.hidden = !credentialsNeeded || usesPassword; password.hidden = !credentialsNeeded || !usesPassword;
                known.hidden = !credentialsNeeded || !ssh;
                ca.hidden = client.hidden = !credentialsNeeded || ssh;
                [host, port].forEach((wrapper) => wrapper.querySelector("input").required = remote);
                user.querySelector("input").required = ssh;
                key.querySelector("textarea").required = credentialsNeeded && !usesPassword;
                password.querySelector("input").required = credentialsNeeded && usesPassword;
                known.querySelector("textarea").required = credentialsNeeded && ssh;
                ca.querySelector("textarea").required = client.querySelector("textarea").required = credentialsNeeded && !ssh;
            }
            mode.addEventListener("change", () => {
                clearSecrets(); if (mode.value !== endpoint?.mode) replace.querySelector("input").checked = true;
                if (mode.value !== "SSH") auth.value = "KEY";
                port.querySelector("input").value = mode.value === "SSH" ? 22 : 2376;
                protocolChanged();
            });
            auth.addEventListener("change", () => {
                clearSecrets(); replace.querySelector("input").checked = true; protocolChanged();
            });
            replace.querySelector("input").addEventListener("change", () => { clearSecrets(); protocolChanged(); });
            protocolChanged();
            const save = node("button", "保存配置", "button primary"); save.type = "submit"; save.disabled = !writable();
            form.append(node("p", "凭据加密保存，仅返回配置状态。远程主机须由部署者加入允许范围；此处不能放宽执行限额。", "field-help"), save);
            form.addEventListener("submit", (event) => {
                event.preventDefault();
                const value = (name) => form.querySelector('[name="' + name + '"]').value;
                const credentials = replace.querySelector("input").checked && mode.value !== "LOCAL"
                    ? {privateKey: mode.value === "SSH" && auth.value === "PASSWORD" ? "" : value("privateKey"), knownHosts: mode.value === "SSH" ? value("knownHosts") : "",
                        password: mode.value === "SSH" && auth.value === "PASSWORD" ? value("password") : "",
                        caCertificate: mode.value === "TLS" ? value("caCertificate") : "", clientCertificate: mode.value === "TLS" ? value("clientCertificate") : ""} : null;
                const payload = buildPayload({displayName: value("displayName"), mode: mode.value, sshAuthType: auth.value, host: value("host"),
                    port: value("port"), username: value("username"), enabled: enabled.querySelector("input").checked}, endpoint, credentials);
                // 提交后立即清空认证输入；失败也不在页面或浏览器存储中保留。
                clearSecrets();
                mutate(endpoint ? "/" + endpoint.id : "", endpoint ? "PUT" : "POST", payload, "配置已保存，请测试当前版本连接。");
            });
            detail.append(form);
            if (endpoint) {
                detail.append(node("p", "配置版本 " + endpoint.revision + " · 凭据版本 " + endpoint.credentialVersion
                    + " · " + (canSetDefault(endpoint) ? "连接测试通过" : endpoint.probeStatus === "FAILED" ? "连接测试失败" : "尚未通过当前版本连接测试"), "field-help"));
                const actions = node("div", null, "sandbox-actions");
                const probe = button("测试连接", () => mutate("/" + endpoint.id + "/probe", "POST", {revision: endpoint.revision}, "连接测试通过。"));
                probe.disabled = !allowed("test");
                const makeDefault = button("设为默认", () => mutate("/default", "PUT", {endpointId: endpoint.id, revision: endpoint.revision}, "已切换租户默认端点。"));
                makeDefault.disabled = !allowed("write") || !canSetDefault(endpoint) || endpoint.defaultEndpoint;
                const remove = button("删除端点", () => mutate("/" + endpoint.id + "?revision=" + endpoint.revision, "DELETE", null, "端点已删除。"));
                remove.disabled = !allowed("delete") || endpoint.defaultEndpoint;
                actions.append(probe, makeDefault, remove);
                if (endpoint.defaultEndpoint) {
                    const clear = button("取消默认", () => mutate("/default", "PUT", {endpointId: null, revision: 0}, "已恢复部署默认连接。"));
                    clear.disabled = !allowed("write"); actions.append(clear);
                }
                detail.append(actions);
            }
        }
        async function reload() {
            const ticket = ++revision, session = getSessionEpoch();
            try {
                const result = await api.request(base);
                if (ticket !== revision || session !== getSessionEpoch()) return;
                listing = result; byId("sandboxCreateBtn").disabled = !allowed("write") || !result.enabled || !result.credentialKeyConfigured;
                byId("sandboxAvailability").textContent = !result.enabled ? "部署尚未启用技能沙箱。"
                    : !result.credentialKeyConfigured ? "端点管理需要部署者配置凭据主密钥。" : "端点与默认选择仅属于当前租户。";
                const current = selected && listing.items.find((entry) => entry.id === selected.id);
                drawList(); edit(current || listing.items[0] || null);
            } catch (error) { if (ticket === revision && session === getSessionEpoch()) status(errorMessage(error), "error"); }
        }
        async function mutate(path, method, body, message) {
            if (busy) return; busy = true; const session = getSessionEpoch();
            const controls = [...byId("sandboxEndpointDetail").querySelectorAll("button,input,select,textarea")]
                .map((control) => ({control, disabled: control.disabled}));
            controls.forEach(({control}) => control.disabled = true);
            let succeeded = false;
            status("正在处理…");
            try {
                const result = await api.request(base + path, {method, ...(body ? {body: JSON.stringify(body)} : {})});
                if (session !== getSessionEpoch()) return;
                if (result?.id) selected = result;
                succeeded = true;
                status(message, "success");
            } catch (error) { if (session === getSessionEpoch()) status(errorMessage(error), "error"); }
            finally {
                busy = false;
                if (session === getSessionEpoch()) {
                    const saving = (method === "POST" && path === "") || (method === "PUT" && path !== "/default");
                    if (!succeeded && saving) controls.forEach(({control, disabled}) => control.disabled = disabled);
                    else await reload();
                }
            }
        }
        function mount() {
            if (mounted) return; mounted = true;
            const page = byId("skillsPage"), skills = page.querySelector(".skills-management-grid");
            skills.id = "sandboxSkillPanel"; skills.setAttribute("role", "tabpanel");
            const tabs = node("div", null, "sandbox-tabs"); tabs.setAttribute("role", "tablist"); tabs.setAttribute("aria-label", "技能管理视图");
            const workspace = node("div", null, "management-grid sandbox-management-grid"); workspace.id = "sandboxEndpointPanel"; workspace.hidden = true; workspace.setAttribute("role", "tabpanel");
            const listPanel = node("article", null, "panel sandbox-list-panel");
            const heading = node("div", null, "panel-heading"); heading.append(node("h2", "沙箱端点"));
            const create = button("新建端点", () => edit(null)); create.id = "sandboxCreateBtn";
            const refresh = button("刷新", reload);
            heading.append(create, refresh);
            const list = node("div", null, "resource-list"); list.id = "sandboxEndpointList";
            const availability = node("p", "", "field-help"); availability.id = "sandboxAvailability";
            listPanel.append(heading, availability, list);
            const detail = node("article", null, "panel"); detail.id = "sandboxEndpointDetail";
            const messages = node("p", "", "form-status"); messages.id = "sandboxPageStatus"; messages.setAttribute("aria-live", "polite");
            workspace.append(listPanel, detail, messages);
            const tabButtons = [];
            [["技能与发布", skills], ["沙箱端点", workspace]].forEach(([title, panel], index) => {
                const tab = button(title, () => {
                    if (busy) return;
                    clearSecrets();
                    tabButtons.forEach((entry, position) => entry.setAttribute("aria-selected", String(position === index)));
                    skills.hidden = index !== 0; workspace.hidden = index !== 1;
                    if (index === 1) {
                        if (!allowed("read")) { status("当前账号缺少沙箱端点读取权限。", "error"); create.disabled = true; return; }
                        reload();
                    }
                });
                tab.id = "sandboxTab" + index; tab.setAttribute("role", "tab"); tab.setAttribute("aria-selected", String(index === 0));
                tab.setAttribute("aria-controls", panel.id); panel.setAttribute("aria-labelledby", tab.id); tabButtons.push(tab); tabs.append(tab);
            });
            skills.before(tabs); skills.after(workspace);
        }
        return {mount, reload};
    }
    return {buildPayload, canSetDefault, createSandboxPage, errorMessage};
});
