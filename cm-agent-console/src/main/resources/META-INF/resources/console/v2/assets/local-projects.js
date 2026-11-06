(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) module.exports = api;
    if (root) root.CmAgentLocalProjects = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";
    const boundary = {"X-CM-Agent-Local-Project": "1"};

    // randomUUID 只在安全上下文可用；HTTP 局域网访问时仍可使用 getRandomValues 生成 UUID v4。
    function createRequestId(crypto) {
        if (typeof crypto?.randomUUID === "function") return crypto.randomUUID();
        if (typeof crypto?.getRandomValues !== "function")
            throw new Error("当前浏览器无法生成安全的目录选择编号，请使用 HTTPS 或更新浏览器。");
        const bytes = crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        const hex = [...bytes].map(value => value.toString(16).padStart(2, "0")).join("");
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }

    // 目录票据只留在当前页面内存；网络未知时只查询原请求，不重复打开系统窗口或消费票据。
    function createSelection({request, randomId, changed = () => {}}) {
        let revision = 0, checkRevision = 0, current = {phase: "IDLE"};
        const publish = (value) => { current = value; changed({...current}); };
        const currentRevision = (stamp) => stamp === revision;
        async function check(stamp = revision) {
            if (!current.requestId || !currentRevision(stamp) || current.phase === "CREATING" || current.phase === "CREATE_UNKNOWN") return;
            const requestId = current.requestId;
            const queryStamp = ++checkRevision;
            try {
                const result = await request(`/api/projects/selections/${encodeURIComponent(requestId)}`);
                if (!currentRevision(stamp) || queryStamp !== checkRevision) return;
                if (result?.requestId !== requestId || !["SELECTING", "SELECTED", "CANCELLED", "FAILED"].includes(result.state))
                    throw new Error("目录选择状态无法核对，请重新查询原请求。");
                if (result.state === "SELECTED" && (!result.ticketId || !result.expiresAt))
                    throw new Error("目录授权不完整，请重新查询原请求。");
                publish({phase: result.state, requestId, ticketId: result.state === "SELECTED" ? result.ticketId : undefined,
                    expiresAt: result.expiresAt, errorId: result.errorId});
            } catch (error) {
                if (currentRevision(stamp) && queryStamp === checkRevision) publish({phase: "UNKNOWN", requestId, message: error.message});
            }
        }
        return {
            value: () => ({...current}),
            async start() {
                if (["SELECTING", "UNKNOWN", "SELECTED", "CREATING", "CREATE_UNKNOWN"].includes(current.phase)) return;
                const stamp = ++revision, requestId = randomId();
                publish({phase: "SELECTING", requestId});
                try { await request("/api/projects/selections", {method: "POST", body: JSON.stringify({requestId})}); }
                catch (error) {
                    if (currentRevision(stamp)) publish({phase: "UNKNOWN", requestId, message: error.message});
                    return;
                }
                await check(stamp);
            },
            check,
            async create(name) {
                if (current.phase !== "SELECTED") throw new Error("请先完成系统目录选择。");
                if (!name?.trim() || name.trim().length > 120) throw new Error("请输入1至120字的项目名称。");
                if (!Number.isFinite(Date.parse(current.expiresAt)) || Date.parse(current.expiresAt) <= Date.now()) {
                    publish({phase: "EXPIRED"});
                    throw new Error("目录授权已过期，请重新选择文件夹。");
                }
                const stamp = ++revision, {requestId, ticketId} = current;
                publish({phase: "CREATING", requestId});
                try {
                    const result = await request("/api/projects", {method: "POST", body: JSON.stringify({requestId, ticketId, name: name.trim()})});
                    if (!currentRevision(stamp)) return null;
                    publish({phase: "CREATED"});
                    return result;
                } catch (error) {
                    // 创建可能已经消费票据；即使HTTP返回失败也不猜测能否再次提交，先看项目列表。
                    if (currentRevision(stamp)) publish({phase: "CREATE_UNKNOWN", requestId, message: error.message});
                    throw error;
                }
            },
            dispose() { revision += 1; current = {phase: "IDLE"}; }
        };
    }

    function createProjectPage({api, document, host = globalThis, getSessionEpoch}) {
        const $ = id => document.getElementById(id);
        let active = true, epoch = getSessionEpoch(), capability = null, selected = null, items = [], path = "", cursor = "";
        let listRevision = 0, detailRevision = 0, treeRevision = 0, textRevision = 0, searchRevision = 0, pollTimer = null, polls = 0;
        let preview = null, development = null, installing = false, installUnknown = false;
        const archiveLocks = new Set();
        const controllers = new Set();
        const valid = () => active && epoch === getSessionEpoch();
        function switchPane(pane) {
            const grid = $("projectWorkingGrid"); if (!grid) return;
            grid.dataset.activePane = pane;
            $("projectPaneChat")?.setAttribute("aria-pressed", String(pane === "chat"));
            $("projectPaneFiles")?.setAttribute("aria-pressed", String(pane === "files"));
        }
        const text = (tag, content, className) => {
            const node = document.createElement(tag); node.textContent = content || "";
            if (className) node.className = className;
            return node;
        };
        const status = (id, message = "", tone = "neutral") => {
            const node = $(id); if (!node) return; node.textContent = message; node.dataset.tone = tone;
        };
        async function request(url, options = {}) {
            if (!valid()) throw new Error("项目工作区已离开，请返回项目页后重试。");
            const controller = new AbortController(); controllers.add(controller);
            try { return await api.request(url, {...options, headers: {...options.headers, ...boundary}, signal: controller.signal}); }
            finally { controllers.delete(controller); }
        }
        const selection = createSelection({request, randomId: () => createRequestId(host.crypto), changed: renderSelection});
        function stopPoll() { if (pollTimer) host.clearTimeout(pollTimer); pollTimer = null; }
        function schedulePoll() {
            stopPoll();
            if (polls++ < 120 && selection.value().phase === "SELECTING") pollTimer = host.setTimeout(() => selection.check(), 1000);
        }
        function renderSelection(value) {
            if (!valid()) return;
            stopPoll();
            $("projectChooseFolder").disabled = capability?.enabled !== true
                || ["SELECTING", "SELECTED", "UNKNOWN", "CREATING", "CREATE_UNKNOWN"].includes(value.phase);
            $("projectCreateBtn").disabled = value.phase !== "SELECTED";
            $("projectSelectionCheck").hidden = !["SELECTING", "UNKNOWN"].includes(value.phase);
            $("projectSelectionCheck").disabled = value.phase === "CREATING";
            const messages = {
                IDLE: "选择运行 CM Agent Server 的这台电脑上的文件夹。",
                SELECTING: "系统文件夹选择器已打开，请在桌面确认或取消。",
                SELECTED: "文件夹已授权，请填写名称并添加项目。授权仅在本页短时有效。",
                CANCELLED: "已取消选择，没有新增项目。",
                FAILED: `系统目录选择失败，请重试${value.errorId ? `（错误编号：${value.errorId}）` : ""}。`,
                UNKNOWN: `尚未确认选择结果，请核对原请求。${value.message || ""}`,
                EXPIRED: "目录授权已过期，请重新选择文件夹。",
                CREATING: "正在添加项目，请勿重复提交。",
                CREATED: "项目已添加。",
                CREATE_UNKNOWN: `添加结果尚未确认，请刷新项目列表核对。请勿重复提交。${value.message || ""}`
            };
            status("projectSelectionStatus", messages[value.phase] || "请核对选择状态。", ["FAILED", "UNKNOWN", "CREATE_UNKNOWN"].includes(value.phase) ? "error" : "neutral");
            if (value.phase === "SELECTING") schedulePoll();
        }
        function renderList(focusProjectId = "") {
            const list = $("localProjectList"); list.replaceChildren();
            let focusTarget = null;
            if (!items.length) {
                list.append(text("p", "尚无本地项目。选择文件夹后，项目会出现在这里。", "empty-state")); return;
            }
            items.forEach(item => {
                const button = text("button", "", "resource-item local-project-item"); button.type = "button";
                button.setAttribute("aria-pressed", String(selected?.id === item.id));
                button.classList.toggle("active", selected?.id === item.id);
                button.append(text("strong", item.name), text("span", item.state === "ARCHIVED" ? "已归档" : "本地项目 · 可浏览"));
                button.addEventListener("click", () => select(item.id, true)); list.append(button);
                if (item.id === focusProjectId) focusTarget = button;
            });
            focusTarget?.focus({preventScroll: true});
        }
        async function reload(append = false) {
            if (!capability?.enabled || !valid()) return;
            const stamp = ++listRevision;
            $("projectLoadMore").disabled = true;
            status("localProjectPageStatus", "正在读取项目列表…");
            const params = new URLSearchParams({limit: "30", includeArchived: String($("projectIncludeArchived")?.checked === true)});
            const last = items.at(-1);
            if (append && last) { params.set("beforeUpdatedAt", last.updatedAt); params.set("beforeId", last.id); }
            try {
                const page = await request(`/api/projects?${params}`);
                if (!valid() || stamp !== listRevision) return;
                if (!Array.isArray(page)) throw new Error("项目列表格式无法识别，请刷新后重试。");
                const old = append ? items : [];
                items = [...old, ...page.filter(next => !old.some(item => item.id === next.id))];
                renderList(); $("projectLoadMore").hidden = page.length < 30;
                status("localProjectPageStatus", "");
            } catch (error) { if (valid() && stamp === listRevision) status("localProjectPageStatus", error.message, "error"); }
            finally { if (valid() && stamp === listRevision) $("projectLoadMore").disabled = false; }
        }
        function clearPreview() {
            textRevision += 1; preview = null; $("projectPreviewContent").textContent = "";
            $("projectPreviewRegion").hidden = true; $("projectTextNext").hidden = true;
            if ($("projectReferenceFile")) $("projectReferenceFile").disabled = true;
        }
        async function select(id, restoreFocus = false) {
            if (!valid()) return;
            development?.dispose(); development = null;
            const stamp = ++detailRevision; treeRevision += 1; searchRevision += 1; clearPreview();
            $("projectSearchResults")?.replaceChildren();
            if ($("projectSearchQuery")) $("projectSearchQuery").value = "";
            status("projectSearchStatus", "");
            selected = null; $("projectDetailRegion").hidden = true; $("projectDetailEmpty").hidden = false;
            $("projectDetailEmpty").textContent = "正在读取项目…"; renderList(restoreFocus ? id : "");
            try {
                const project = await request(`/api/projects/${encodeURIComponent(id)}`);
                if (!valid() || stamp !== detailRevision) return;
                selected = project; path = "";
                const archived = project.state === "ARCHIVED";
                $("projectRenameInput").disabled = archived;
                if ($("projectManagement")) $("projectManagement").hidden = archived;
                if ($("projectRenameDetails")) $("projectRenameDetails").hidden = archived;
                if ($("projectArchiveControls")) $("projectArchiveControls").hidden = archived;
                $("projectFileList").replaceChildren();
                for (const control of ["projectTreeParent", "projectTreeRefresh", "projectTreeMore", "projectSearchQuery", "projectSearchContent", "projectSearchSubmit"]) $(control).disabled = archived;
                $("projectTreeMore").hidden = true;
                $("projectTreePath").textContent = archived ? "已归档项目" : "项目根目录";
                status("projectTreeStatus", archived ? "归档后仅查看会话、审批历史和操作记录，不再读取本地文件。" : ""); $("projectDetailEmpty").hidden = true; $("projectDetailRegion").hidden = false;
                if ($("projectPaneChat")) $("projectPaneChat").disabled = !capability?.conversations;
                switchPane(capability?.conversations ? "chat" : "files");
                archiveLocks.delete(project.id);
                if ($("projectArchiveBtn")) $("projectArchiveBtn").hidden = project.state !== "ACTIVE";
                if ($("projectArchiveBtn")) $("projectArchiveBtn").disabled = false;
                $("projectTitle").textContent = project.name; $("projectRenameInput").value = project.name;
                $("projectMetadata").textContent = `版本 ${project.version} · ${project.state === "ARCHIVED" ? "已归档" : "本地项目"}`;
                $("projectRenameBtn").disabled = project.state !== "ACTIVE";
                renderList(restoreFocus ? project.id : "");
                if (host.CmAgentProjectDevelopment && capability?.conversations) {
                    development = host.CmAgentProjectDevelopment.create({request, document, host, isCurrent: () => valid() && stamp === detailRevision});
                    await development.mount(project, capability);
                }
                if (valid() && stamp === detailRevision && !archived) await loadTree("");
            } catch (error) {
                if (valid() && stamp === detailRevision) $("projectDetailEmpty").textContent = error.message;
            }
        }
        async function rename(event) {
            event.preventDefault(); if (!valid() || !selected || selected.state !== "ACTIVE") return;
            const id = selected.id, stamp = detailRevision, name = $("projectRenameInput").value.trim();
            if (!name || name.length > 120) { status("projectDetailStatus", "请输入1至120字的项目名称。", "error"); return; }
            $("projectRenameBtn").disabled = true;
            try {
                const result = await request(`/api/projects/${encodeURIComponent(id)}`, {method: "PATCH", body: JSON.stringify({expectedVersion: selected.version, name})});
                if (!valid() || stamp !== detailRevision) return;
                selected = result; $("projectTitle").textContent = result.name;
                $("projectMetadata").textContent = `版本 ${result.version} · 本地项目`;
                items = items.map(item => item.id === id ? result : item); renderList(); status("projectDetailStatus", "项目名称已更新。");
            } catch (error) { if (valid() && stamp === detailRevision) status("projectDetailStatus", `${error.message} 请重新选择项目核对最新状态。`, "error"); }
            finally { if (valid() && stamp === detailRevision) $("projectRenameBtn").disabled = false; }
        }
        async function archive() {
            if (!valid() || !selected || selected.state !== "ACTIVE" || archiveLocks.has(selected.id)) return;
            const id = selected.id, stamp = detailRevision, version = selected.version;
            archiveLocks.add(id); $("projectArchiveBtn").disabled = true;
            status("projectDetailStatus", "正在归档项目登记，本地目录与历史会保留。");
            try {
                await request(`/api/projects/${encodeURIComponent(id)}/archive`, {method: "POST", body: JSON.stringify({expectedVersion: version})});
                if (!valid()) return;
                if (stamp === detailRevision && selected?.id === id) {
                    detailRevision++; treeRevision++; development?.dispose(); development = null; clearPreview(); selection.dispose();
                    selected = null; $("projectFileList").replaceChildren(); $("projectDetailRegion").hidden = true;
                    $("projectDetailEmpty").hidden = false; $("projectDetailEmpty").textContent = "项目已归档，本地目录与历史保留。勾选“包含已归档”可查看历史。";
                    $("projectAddPanel").hidden = true; renderSelection(selection.value()); $("projectListHeading")?.focus();
                }
                archiveLocks.delete(id); await reload();
            } catch (error) {
                if (!valid() || stamp !== detailRevision) return;
                if ([400, 403, 404, 409].includes(error.status)) archiveLocks.delete(id);
                status("projectDetailStatus", `${error.message}${archiveLocks.has(id) ? " 结果尚未确认，请重新选择项目核对，不会自动再次归档。" : ""}`, "error");
                $("projectArchiveBtn").disabled = archiveLocks.has(id);
            }
        }
        async function loadTree(directory, append = false) {
            if (!valid() || !selected || selected.state !== "ACTIVE") return;
            const projectId = selected.id, stamp = ++treeRevision;
            if (!append) { path = directory; cursor = ""; $("projectFileList").replaceChildren(); clearPreview(); }
            $("projectTreePath").textContent = path || "项目根目录";
            $("projectTreeParent").disabled = !path;
            $("projectTreeMore").disabled = true;
            status("projectTreeStatus", "正在读取目录…");
            const params = new URLSearchParams({path: directory, limit: "100"});
            if (append && cursor) params.set("cursor", cursor);
            try {
                const page = await request(`/api/projects/${encodeURIComponent(projectId)}/files?${params}`);
                if (!valid() || stamp !== treeRevision || selected?.id !== projectId) return;
                const list = $("projectFileList");
                if (!page.items?.length && !append) list.append(text("p", "这个目录没有可展示的条目。", "empty-state"));
                for (const item of page.items || []) {
                    const button = text("button", "", "project-file-row"); button.type = "button";
                    const directory = item.kind === "DIRECTORY", readable = item.readable === true && item.kind === "FILE";
                    button.disabled = !directory && !readable;
                    button.append(text("span", directory ? "目录" : item.kind === "BLOCKED" ? "受限" : "文件", "project-file-kind"),
                        text("span", item.name, "project-file-name"), text("span", directory ? "打开" : readable ? "预览" : "不可预览", "project-file-action"));
                    button.addEventListener("click", () => directory ? loadTree(item.path) : loadText(item.path)); list.append(button);
                }
                cursor = page.nextCursor || ""; $("projectTreeMore").hidden = !cursor;
                status("projectTreeStatus", "");
            } catch (error) { if (valid() && stamp === treeRevision) status("projectTreeStatus", error.message, "error"); }
            finally { if (valid() && stamp === treeRevision) $("projectTreeMore").disabled = false; }
        }
        async function loadText(file, offset = 0) {
            if (!valid() || !selected || selected.state !== "ACTIVE") return;
            const projectId = selected.id, stamp = ++textRevision, previous = preview;
            preview = null; $("projectPreviewContent").textContent = ""; $("projectTextNext").hidden = true;
            if ($("projectReferenceFile")) $("projectReferenceFile").disabled = true;
            $("projectPreviewRegion").hidden = false; $("projectPreviewTitle").textContent = file;
            status("projectPreviewStatus", "正在读取脱敏文本…");
            try {
                const params = new URLSearchParams({path: file, offset: String(offset), maxBytes: "65536"});
                const chunk = await request(`/api/projects/${encodeURIComponent(projectId)}/text?${params}`);
                if (!valid() || stamp !== textRevision || selected?.id !== projectId) return;
                if (offset > 0 && previous?.sha256 !== chunk.sha256) throw new Error("文件已变化，请重新打开文件，从头读取。");
                preview = chunk; $("projectPreviewContent").textContent = chunk.content || "";
                if ($("projectReferenceFile")) $("projectReferenceFile").disabled = false;
                $("projectTextNext").hidden = !chunk.hasMore;
                status("projectPreviewStatus", `${chunk.redacted ? "内容已脱敏 · " : ""}当前片段 ${chunk.offset}–${chunk.nextOffset} / ${chunk.totalBytes} 字节`);
            } catch (error) { if (valid() && stamp === textRevision) status("projectPreviewStatus", error.message, "error"); }
        }
        async function search(event) {
            event.preventDefault();
            if (!valid() || selected?.state !== "ACTIVE") return;
            const query = $("projectSearchQuery").value.trim();
            if (!query) { status("projectSearchStatus", "请输入文件名或正文关键词。", "error"); return; }
            const projectId = selected.id, stamp = ++searchRevision;
            $("projectSearchResults").replaceChildren(); $("projectSearchSubmit").disabled = true;
            status("projectSearchStatus", "正在搜索允许访问的项目文件…");
            try {
                const params = new URLSearchParams({query, content: String($("projectSearchContent").checked === true)});
                const result = await request(`/api/projects/${encodeURIComponent(projectId)}/search?${params}`);
                if (!valid() || stamp !== searchRevision || selected?.id !== projectId) return;
                if (!Array.isArray(result.matches)) throw new Error("搜索结果格式无效，请重试。");
                for (const item of result.matches) {
                    const entry = text("button", "", "project-file-row"); entry.type = "button";
                    entry.append(text("span", item.contentMatch ? "正文" : "路径", "project-file-kind"), text("span", item.path, "project-file-name"), text("span", "预览", "project-file-action"));
                    entry.addEventListener("click", () => { if (valid() && stamp === searchRevision && selected?.id === projectId) return loadText(item.path); });
                    $("projectSearchResults").append(entry);
                    if (item.excerpt) $("projectSearchResults").append(text("p", item.excerpt, "field-help"));
                }
                status("projectSearchStatus", `找到 ${result.matches.length} 项 · 扫描 ${result.scanned} 项 · 跳过 ${result.skipped} 项（含受限或不可读文件）。${result.partial ? "搜索达到时间或数量预算，结果不完整；请缩小关键词。" : ""}`);
            } catch (error) { if (valid() && stamp === searchRevision) status("projectSearchStatus", error.message, "error"); }
            finally { if (valid() && stamp === searchRevision && selected?.state === "ACTIVE") $("projectSearchSubmit").disabled = false; }
        }
        async function create(event) {
            event.preventDefault();
            if (!valid()) return;
            try {
                const project = await selection.create($("projectNameInput").value);
                if (!valid() || !project) return;
                $("projectAddPanel").hidden = true; $("projectNameInput").value = "";
                await reload(); if (valid()) await select(project.id, true);
            } catch (error) { if (valid()) status("projectSelectionStatus", error.message + " 请核对列表中的项目。", "error"); }
        }
        async function mount() {
            $("projectPaneChat")?.addEventListener("click", () => { if(valid() && capability?.conversations) switchPane("chat"); });
            $("projectPaneFiles")?.addEventListener("click", () => { if(valid()) switchPane("files"); });
            $("projectArchiveBtn")?.addEventListener("click", archive);
            $("projectSearchForm")?.addEventListener("submit", search);
            $("projectReferenceFile")?.addEventListener("click", () => {
                if (!valid() || !preview || selected?.state !== "ACTIVE") return;
                try {
                    if (!development) throw new Error("请先选择或新建项目会话，再引用文件。");
                    development.referenceFile({path: preview.path, sha256: preview.sha256, offset: preview.offset, nextOffset: preview.nextOffset});
                    switchPane("chat"); $("projectChatInput")?.focus();
                    status("projectPreviewStatus", "文件版本引用已加入项目会话输入框，尚未发送。");
                } catch(error) { status("projectPreviewStatus", error.message, "error"); }
            });
            $("projectInstallTools")?.addEventListener("click", async () => {
                if (!valid() || !capability?.conversations || installing || installUnknown) return;
                const button = $("projectInstallTools");
                installing = true; button.disabled = true;
                status("localProjectPageStatus", "正在登记固定项目工具目录；只登记定义，不向 Agent 自动授权。");
                try {
                    await request("/api/projects/tools", {method: "POST"});
                    if (!valid()) return;
                    button.textContent = "固定项目工具目录已登记";
                    status("localProjectPageStatus", "固定项目工具目录已登记；新建项目会话时仍只为所选 Agent 补齐缺失授权。");
                } catch (error) {
                    if (!valid()) return;
                    if ([400, 403, 404, 409].includes(error.status)) {
                        button.disabled = false;
                        status("localProjectPageStatus", `${error.message} 请核对权限或当前目录后重试。`, "error");
                    } else {
                        // 网络未知时不自动重放登记；刷新页面后重新核对，避免把已知失败误判为可重试。
                        installUnknown = true;
                        button.disabled = true;
                        status("localProjectPageStatus", `${error.message} 登记结果尚未确认，请刷新页面并到工具治理页核对，不会自动重复提交。`, "error");
                    }
                } finally {
                    installing = false;
                }
            });
            $("projectAddBtn").addEventListener("click", () => { $("projectAddPanel").hidden = false; $("projectNameInput").focus(); });
            $("projectAddClose").addEventListener("click", () => { $("projectAddPanel").hidden = true; });
            $("projectChooseFolder").addEventListener("click", () => { polls = 0; selection.start(); });
            $("projectSelectionCheck").addEventListener("click", () => selection.check());
            $("projectCreateForm").addEventListener("submit", create);
            $("projectRefresh").addEventListener("click", () => reload());
            $("projectIncludeArchived")?.addEventListener("change", () => reload(false));
            $("projectLoadMore").addEventListener("click", () => reload(true));
            $("projectRenameForm").addEventListener("submit", rename);
            $("projectTreeParent").addEventListener("click", () => loadTree(path.split("/").slice(0, -1).join("/")));
            $("projectTreeRefresh").addEventListener("click", () => loadTree(path));
            $("projectTreeMore").addEventListener("click", () => loadTree(path, true));
            $("projectTextNext").addEventListener("click", () => preview && loadText(preview.path, preview.nextOffset));
            status("localProjectPageStatus", "正在核对本机项目能力…");
            try {
                const result = await request("/api/projects/capabilities");
                if (!valid()) return;
                capability = result; $("projectAddBtn").disabled = result.enabled !== true;
                if ($("projectInstallTools")) $("projectInstallTools").hidden = result.conversations !== true;
                $("projectRefresh").disabled = result.enabled !== true;
                if ($("projectIncludeArchived")) $("projectIncludeArchived").disabled = result.enabled !== true;
                $("projectCapabilityNote").textContent = result.enabled === true
                    ? result.conversations ? "开发会话使用当前本地项目。新建会话会自动准备固定 project_* 工具；每轮可选择请求批准、帮我自动批准或完全访问。" : "当前可添加项目、浏览目录和预览文本。开发会话由服务端能力另行开放。"
                    : "当前服务未开放本地项目。请在运行 CM Agent Server 的电脑上配置并启用后重试。";
                renderSelection(selection.value());
                if (result.enabled === true) await reload();
                else { status("localProjectPageStatus", "本地项目暂不可用。"); $("localProjectList").replaceChildren(text("p", "当前环境未开放本地项目。", "empty-state")); }
            } catch (error) { if (valid()) status("localProjectPageStatus", error.message, "error"); }
        }
        return {mount, dispose() {
            active = false; listRevision++; detailRevision++; treeRevision++; textRevision++; searchRevision++; stopPoll(); selection.dispose();
            development?.dispose(); development = null;
            archiveLocks.clear();
            controllers.forEach(controller => controller.abort()); controllers.clear();
            // 项目文本不可进入缓存/持久化；离页立即清空本实例持有的正文和已选票据。
            preview = null; selected = null; items = [];
            $("projectSearchResults")?.replaceChildren();
            if ($("projectSearchQuery")) $("projectSearchQuery").value = "";
        }};
    }
    return {createRequestId, createSelection, createProjectPage};
});
