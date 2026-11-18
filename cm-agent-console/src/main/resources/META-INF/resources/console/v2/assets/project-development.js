(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) module.exports = api;
    if (root) root.CmAgentProjectDevelopment = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";
    function operationReference(summary) {
        const match = /^项目(?:操作 operationId|命令 commandId)=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) version=([1-9][0-9]*) digest=([0-9a-f]{64})$/.exec(summary || "");
        return match && Number.isSafeInteger(Number(match[2])) ? {operationId: match[1], version: Number(match[2]), digest: match[3]} : null;
    }
    function matchesPreview(reference, preview) {
        const value = preview?.view || preview;
        return !!reference && value?.operationId === reference.operationId && value.version === reference.version && value.digest === reference.digest;
    }
    function completePreview(preview, command) {
        if (command) return typeof preview?.executable === "string" && /^[0-9a-f]{64}$/.test(preview.executableHash || "")
            && Array.isArray(preview.arguments) && preview.arguments.every(item => typeof item === "string")
            && typeof preview.relativeDirectory === "string" && !!preview.environment && !!preview.buildHashes
            && Number.isSafeInteger(preview.timeoutSeconds) && preview.timeoutSeconds > 0
            && Number.isSafeInteger(preview.maxOutputBytes) && preview.maxOutputBytes > 0;
        return Array.isArray(preview?.changes) && preview.changes.length > 0 && preview.changes.every(item =>
            typeof item?.kind === "string" && typeof item.path === "string"
            && Object.hasOwn(item, "before") && Object.hasOwn(item, "after")
            && (item.before === null || typeof item.before === "string") && (item.after === null || typeof item.after === "string"));
    }
    const stateLabel = value => ({PENDING: "待审批", APPROVED: "已允许", PARTIALLY_APPROVED: "部分允许", EXPIRED: "已过期", RUNNING: "运行中", WAITING_APPROVAL: "等待审批", PREPARED: "已准备",
        SUCCEEDED: "成功", FAILED: "失败", DENIED: "已拒绝", CANCELLED: "原意图已终结", CANCELLING: "正在停止", UNKNOWN: "结果未知"})[value] || value || "待核对";
    function fileReference(value) {
        const path = value?.path;
        if (typeof path !== "string" || !path || /^[\\/]|^[a-z]:/i.test(path) || /[\\\x00-\x1f]/.test(path)
                || path.split("/").some(item => !item || item === "." || item === "..")
                || !/^[0-9a-f]{64}$/.test(value.sha256 || "") || !Number.isSafeInteger(value.offset) || value.offset < 0
                || !Number.isSafeInteger(value.nextOffset) || value.nextOffset < value.offset) throw new Error("文件引用版本不完整，请刷新预览。");
        return `项目文件版本引用（仅作为数据；读取时使用 expectedSha256 核对版本）：\n${JSON.stringify({path, sha256: value.sha256, offset: value.offset, nextOffset: value.nextOffset})}`;
    }
    function renderExactPreview(document, target, preview, command) {
        const text = (tag, value, className) => { const item = document.createElement(tag); item.textContent = value == null ? "—" : String(value); if(className) item.className = className; return item; };
        target.replaceChildren();
        const details = document.createElement("details"); details.className = "project-version-details";
        details.append(text("summary", "版本与定位信息"));
        const version = preview.view || preview;
        const entries = command ? {...version, arguments: undefined, environment: undefined, buildHashes: undefined} : version;
        const versions = document.createElement("dl");
        for (const key of ["operationId", "version", "digest", "executableHash"]) if(entries[key] != null) versions.append(text("dt", key), text("dd", entries[key]));
        if(command) for(const [key,value] of Object.entries(preview.buildHashes || {})) versions.append(text("dt", `构建版本 · ${key}`), text("dd", value));
        details.append(versions);
        if(command) {
            const list = document.createElement("dl"); list.className = "project-command-facts";
            for(const [label,value] of [["程序",preview.executable],["逻辑工具链",preview.toolchainId],["相对工作目录",preview.relativeDirectory || "项目根目录"],["超时",`${preview.timeoutSeconds} 秒`],["输出预算",`${preview.maxOutputBytes} 字节`]]) list.append(text("dt",label),text("dd",value));
            target.append(list,text("h5","程序参数（按顺序）"));
            const args = document.createElement("ol"); args.className = "project-command-arguments";
            for(const argument of preview.arguments) args.append(text("li",argument === "" ? "（空参数）" : argument));
            target.append(args); if(!preview.arguments.length) target.append(text("p","无附加参数。","field-help"));
            target.append(text("h5","环境逻辑值"));
            const environment = document.createElement("dl"); environment.className = "project-command-facts";
            for(const [key,value] of Object.entries(preview.environment || {})) environment.append(text("dt",key),text("dd",value));
            target.append(environment); if(!Object.keys(preview.environment || {}).length) target.append(text("p","无额外环境逻辑值。","field-help"));
        } else for(const change of preview.changes) {
            const item = document.createElement("section"); item.className = "project-file-change";
            const action = ({CREATE_FILE:"新建文件",REPLACE_FILE:"修改文件",DELETE_FILE:"删除文件",MOVE_FILE:"移动文件",CREATE_DIRECTORY:"新建目录",DELETE_DIRECTORY:"删除目录",MOVE_DIRECTORY:"移动目录"})[change.kind] || change.kind;
            item.append(text("h5",action),text("p",change.target ? `${change.path} → ${change.target}` : change.path,"project-change-path"));
            if(change.kind.includes("DIRECTORY")) item.append(text("p","此操作只变更目录结构，无文件正文需要对照。","field-help"));
            else if(change.kind === "MOVE_FILE" && change.before === change.after) item.append(text("p","文件正文保持一致；请核对上方原位置与目标位置。","field-help"));
            else {
                const columns = document.createElement("div"); columns.className = "project-diff-columns";
                for(const [label,body] of [["变更前",change.before],["变更后",change.after]]) {
                    const column = document.createElement("div"); column.append(text("h6",label),text("pre",body == null || body === "" ? "（无正文）" : body)); columns.append(column);
                }
                item.append(columns);
            }
            target.append(item);
        }
        target.append(details);
    }
    function create({request, document, isCurrent, host = globalThis}) {
        let revision = 0, project = null, conversation = null, capabilities = {}, active = true, sendBusy = false, sendUnknown = false, approvalPending = true;
        let messageCursor = null, messages = [], approvalRevision = 0, messageRevision = 0, historyCursor = null, historyRevision = 0, operationsRevision = 0;
        const commands = new Map(), lockedApprovals = new Set(), resolveLocks = new Map();
        let operationRenderRevision = 0, approvalRenderRevision = 0, historyItems = [], approvalFingerprint = null, operationFingerprint = null;
        const archived = () => project?.state === "ARCHIVED";
        const knownRuns = new Set(), pendingRuns = new Set(), stoppedRuns = new Set();
        let currentRun = null, runKnown = false, pollTimer = null, pollCount = 0, pollFailures = 0, refreshInFlight = null, commandOutputCursor = 0;
        const activeRun = value => ["RUNNING", "WAITING_APPROVAL", "CANCELLING"].includes(value?.status);
        function renderAuthorizationHelp() {
            const select = $("projectAuthorizationMode"), mode = select?.value;
            if ($("projectAuthorizationSelection")) $("projectAuthorizationSelection").textContent = select?.selectedOptions?.[0]?.textContent || "";
            $("projectAuthorizationHelp").textContent = mode === "FULL_ACCESS"
                ? "已授权的文件变更和命令可自动执行，无需逐项确认。命令可能访问项目外资源；停止不能撤销已发生的操作。"
                : mode === "ASK_ON_EFFECT"
                    ? "只读且非高风险的操作自动执行；文件变更、命令和其他非只读操作执行前需确认。"
                    : "除文件变更和命令外，低、中风险操作自动执行；高风险操作、文件变更和命令执行前需确认。";
        }
        function clearPoll() { if (pollTimer != null) host.clearTimeout(pollTimer); pollTimer = null; }
        function needsPoll() { return !archived() && (sendBusy || activeRun(currentRun) || [...commands.values()].some(item => ["RUNNING", "PREPARED"].includes(item.state))); }
        // 轮询仅读取状态，串行完成一轮后再计时；预算耗尽不会停止后台执行或重放写请求。
        function schedulePoll() {
            clearPoll();
            if (!active || !isCurrent() || !conversation || !needsPoll() || pollFailures >= 3) return;
            if (pollCount >= 150) { status("已达到本次自动查询预算，请点击核对消息与审批继续查看状态。"); return; }
            const stamp = revision;
            pollTimer = host.setTimeout(async () => {
                pollTimer = null; if (!valid(stamp)) return;
                pollCount++; await refresh(true);
            }, 2000);
            pollTimer?.unref?.();
        }
        function renderRun() {
            $("projectRunStatus").textContent = currentRun ? `当前运行：${currentRun.status === "CANCELLED" ? "已停止" : stateLabel(currentRun.status)}${stoppedRuns.has(currentRun.id) ? " · 已发送停止请求，请等待服务端确认退出。" : ""}` : runKnown || archived() ? "当前没有活动运行。" : "运行状态尚未确认，请核对原会话。";
            $("projectRunStop").hidden = archived() || !activeRun(currentRun);
            $("projectRunStop").disabled = !activeRun(currentRun) || stoppedRuns.has(currentRun?.id) || currentRun?.status === "CANCELLING";
            $("projectChatSend").disabled = archived() || !runKnown || sendBusy || sendUnknown || approvalPending || activeRun(currentRun);
            if ($("projectAuthorizationMode")) $("projectAuthorizationMode").disabled = archived() || !runKnown || sendBusy || activeRun(currentRun) || approvalPending;
        }
        async function loadRun() {
            if (archived() || !conversation) return;
            const stamp = revision;
            // 状态查询失败不能被当作没有活动运行，等待权威响应前保持发送关闭。
            const previous = currentRun; runKnown = false; renderRun();
            const result = await request(previous && activeRun(previous) ? `${base()}/runs/${encodeURIComponent(previous.id)}` : `${base()}/active-run`);
            if (!valid(stamp)) return;
            if (result && (typeof result.id !== "string" || !["RUNNING", "WAITING_APPROVAL", "CANCELLING", "CANCELLED", "SUCCEEDED", "FAILED", "DENIED"].includes(result.status))) throw new Error("运行状态格式无效，请手动核对原会话。");
            currentRun = result; runKnown = true;
            if (result) {
                knownRuns.add(result.id); pendingRuns.add(result.id);
                if (activeRun(result)) {
                    const authority = await request(`${base()}/runs/${encodeURIComponent(result.id)}/authorization-mode`);
                    if (!valid(stamp)) return;
                    if (!["ASK_ON_EFFECT", "ASK_ON_RISK", "FULL_ACCESS"].includes(authority?.mode))
                        throw new Error("项目运行授权方式格式无效，请刷新后核对。");
                    $("projectAuthorizationMode").value = authority.mode;
                    renderAuthorizationHelp();
                }
            }
            renderRun();
        }
        async function refreshCommandOutputs(stamp) {
            if (archived()) return;
            const candidates = [...commands.values()].filter(item => item.kind !== "FILE_CHANGE" && (["RUNNING", "PREPARED"].includes(item.state) || ["RUNNING", "PREPARED"].includes(item.output?.state)));
            const start = candidates.length ? commandOutputCursor % candidates.length : 0;
            const batch = [...candidates.slice(start), ...candidates.slice(0, start)].slice(0, 5);
            commandOutputCursor = start + batch.length;
            for (const item of batch) {
                const result = await request(operationBase(item.runId, item.reference.operationId, "commands"));
                if (!valid(stamp)) return;
                item.output = result; item.state = result.state;
            }
            if (batch.length && valid(stamp)) renderCommands();
        }
        const $ = id => document.getElementById(id);
        const node = (tag, value, className) => { const element = document.createElement(tag); element.textContent = value || ""; if (className) element.className = className; return element; };
        const valid = stamp => active && isCurrent() && stamp === revision;
        const status = (value, error = false) => { const target = $("projectChatStatus"); if (target) { target.textContent = value; target.dataset.tone = error ? "error" : "neutral"; } };
        const button = (label, action) => { const element = node("button", label, "button ghost compact"); element.type = "button"; element.addEventListener("click", action); return element; };
        const base = () => `/api/agents/${encodeURIComponent(conversation.agentId)}/conversations/${encodeURIComponent(conversation.id)}`;
        const operationBase = (run, operation, kind) => `/api/projects/${encodeURIComponent(project.id)}/runs/${encodeURIComponent(run)}/${kind}/${encodeURIComponent(operation)}`;
        function renderMessages() {
            const target = $("projectChatMessages"); target.replaceChildren();
            if (!messages.length) target.append(node("p", archived() ? "此会话尚无历史消息。" : "发送第一条消息，开始在这个项目中工作。", "empty-state"));
            for (const message of messages) {
                const article = node("article", "", "project-chat-message");
                article.dataset.role = message.role === "USER" ? "user" : "agent";
                article.append(node("strong", message.role === "USER" ? "你" : "Agent"));
                for (const block of message.contentBlocks || []) {
                    if (block.type === "TEXT") article.append(node("pre", block.text));
                    if (block.type === "TOOL_CALL") article.append(node("p", "工具调用摘要请在运行记录查看。", "field-help"));
                }
                target.append(article);
            }
            $("projectMessagesMore").hidden = messageCursor == null;
        }
        async function loadMessages(append = false, tail = false) {
            if (!active || !isCurrent() || !conversation) return;
            const stamp = revision, currentBase = base(), queryStamp = ++messageRevision;
            const query = new URLSearchParams({limit: "50"});
            if (tail && messages.at(-1)?.sequence != null) query.set("afterSequence", String(messages.at(-1).sequence));
            else if (append && messageCursor != null) query.set("afterSequence", String(messageCursor));
            const page = await request(`${currentBase}/messages?${query}`);
            if (!valid(stamp) || queryStamp !== messageRevision) return;
            messages = append || tail ? [...messages, ...(page.items || []).filter(next => !messages.some(old => old.id === next.id))] : page.items || [];
            messageCursor = page.nextSequence; renderMessages();
            for (const item of page.items || []) if (item.runId) { knownRuns.add(item.runId); pendingRuns.add(item.runId); }
            const failures = Object.entries(page.runStatuses || {}).filter(([, value]) => ["FAILED", "DENIED"].includes(value));
            if (failures.length) status(`会话包含${failures.length}次失败或拒绝的运行，请查看运行记录核对原因。`, true);
        }
        function renderCommands() {
            const fingerprint = JSON.stringify([...commands]);
            if (fingerprint === operationFingerprint) return;
            operationFingerprint = fingerprint;
            const renderStamp = ++operationRenderRevision;
            const region = $("projectCommandResults"); region.replaceChildren();
            for (const [key, command] of commands) {
                const isCommand = command.kind !== "FILE_CHANGE";
                const section = node("section", "", "project-operation-preview");
                const output = node("pre", command.output?.outputAvailable ? `标准输出\n${command.output.stdout || ""}\n标准错误\n${command.output.stderr || ""}` : "尚未读取输出。"), feedback = node("p", "", "form-status");
                section.append(node("h4", isCommand ? "命令执行" : "文件变更"));
                const stamp = revision;
                if (command.state) section.append(node("p", stateLabel(command.state), "field-help"));
                if (command.notice) section.append(node("p", command.notice, "field-help"));
                // 归档只读操作元数据，不暴露停止、人工收口或原始载荷入口。
                if (archived()) {
                    section.append(node("p", `操作编号：${command.reference.operationId} · 版本 ${command.reference.version}`, "field-help"));
                    region.append(section); continue;
                }
                if (command.state === "UNKNOWN") {
                    const acknowledge = document.createElement("input"); acknowledge.type = "checkbox"; acknowledge.checked = false;
                    const label = node("label", ""); label.append(acknowledge, node("span", "我已人工核对并接受可能已发生的副作用；仅终结原意图，不撤销或重放"));
                    const resolve = button("终结原意图", async () => {
                        if (!valid(stamp) || renderStamp !== operationRenderRevision || !acknowledge.checked || resolveLocks.has(key)) return;
                        resolveLocks.set(key, {phase: "SUBMITTING"}); resolve.disabled = true; acknowledge.disabled = true;
                        try {
                            await request(`${operationBase(command.runId, command.reference.operationId, "operations")}/resolve-unknown`, {
                                method: "POST", body: JSON.stringify({expectedVersion: command.reference.version, acknowledgeExternalEffects: true})});
                            if (!valid(stamp)) return;
                            resolveLocks.set(key, {phase: "UNKNOWN"}); pendingRuns.add(command.runId);
                            await recoverOperations();
                        } catch (error) {
                            if (!valid(stamp)) return;
                            resolveLocks.set(key, {phase: "UNKNOWN"});
                            feedback.textContent = `${error.message} 收口结果尚未确认，请刷新操作状态核对，不会自动再次提交。`;
                        }
                    });
                    resolve.disabled = true; acknowledge.disabled = resolveLocks.has(key);
                    acknowledge.addEventListener("change", () => { resolve.disabled = !acknowledge.checked || resolveLocks.has(key); });
                    section.append(label, resolve);
                }
                const exact = node("div", "", "project-exact-preview");
                section.append(button(isCommand ? "读取精确命令清单" : "读取精确差异", async () => {
                    if (!valid(stamp)) return; exact.textContent = "";
                    try {
                        const result = await request(operationBase(command.runId, command.reference.operationId, isCommand ? "commands" : "changes") + (isCommand ? "?preview=true" : ""));
                        if (!valid(stamp)) return;
                        if (!matchesPreview(command.reference, result) || !completePreview(result, isCommand)) throw new Error("操作版本或预览不完整，请刷新操作列表核对。");
                        renderExactPreview(document, exact, result, isCommand);
                    } catch(error) { if(valid(stamp)) feedback.textContent = error.message; }
                }), exact);
                if (!isCommand) { section.append(feedback); region.append(section); continue; }
                const read = async () => {
                    if (!valid(stamp)) return;
                    try {
                        const result = await request(operationBase(command.runId, command.reference.operationId, "commands"));
                        if (!valid(stamp)) return;
                        feedback.textContent = `${stateLabel(result.state)} · 序号 ${result.sequence}${result.exitCode == null ? "" : ` · 退出码 ${result.exitCode}`}${result.truncated ? " · 输出已截断" : ""}`;
                        output.textContent = result.outputAvailable ? `标准输出\n${result.stdout || ""}\n标准错误\n${result.stderr || ""}` : "当前没有可读取的输出。";
                        stop.disabled = stopping || !["RUNNING", "PREPARED"].includes(result.state);
                    } catch (error) { if (valid(stamp)) feedback.textContent = error.message; }
                };
                let stopping = false;
                const stop = button("停止这次命令", async () => {
                    if (stopping || !valid(stamp)) return; stopping = true; stop.disabled = true;
                    try { await request(`${operationBase(command.runId, command.reference.operationId, "commands")}/cancel`, {method: "POST"}); }
                    catch (error) { if (valid(stamp)) feedback.textContent = `${error.message} 请查询原命令状态，不会重复停止请求。`; }
                    if (valid(stamp)) { feedback.textContent += " 停止请求不代表已经退出，请刷新输出核对。"; await read(); }
                });
                stop.disabled = true;
                section.append(button("刷新状态与输出", read), stop, feedback, output); region.append(section);
            }
        }
        async function loadHistory(append = false) {
            if (!active || !isCurrent() || !conversation) return;
            const stamp = revision, query = ++historyRevision;
            const params = new URLSearchParams({limit: "50"});
            if (append && historyCursor) params.set("cursor", historyCursor);
            const page = await request(`${base()}/approvals/history?${params}`);
            if (!valid(stamp) || query !== historyRevision) return;
            for (const item of page.items || []) if (item.runId) { knownRuns.add(item.runId); pendingRuns.add(item.runId); }
            historyCursor = page.nextCursor || null;
            if (archived()) {
                historyItems = append ? [...historyItems, ...(page.items || [])] : page.items || [];
                const region = $("projectApprovalItems"); region.replaceChildren();
                region.append(node("h4", "审批历史（只读）"));
                for (const item of historyItems) {
                    const row = node("section", "", "project-approval-card");
                    row.append(node("p", `状态：${stateLabel(item.status)}`, "field-help"));
                    if (item.runId) row.append(node("p", `运行编号：${item.runId}`, "field-help"));
                    for (const detail of item.items || []) row.append(node("p", `${detail.toolName || "工具调用"} · ${({APPROVE: "允许", DENY: "拒绝"})[detail.decision] || "未决定"}`, "field-help"));
                    region.append(row);
                }
                if (!historyItems.length) region.append(node("p", "此会话没有审批历史。", "field-help"));
            }
            if ($("projectOperationHistoryMore")) $("projectOperationHistoryMore").hidden = !historyCursor;
        }
        async function recoverOperations() {
            if (!active || !isCurrent() || !conversation) return;
            const stamp = revision, query = ++operationsRevision;
            const batch = [...pendingRuns].slice(0, 20); let operationFailure = null;
            for (const runId of batch) {
                if (!valid(stamp) || query !== operationsRevision) return;
                try {
                    const observedLocks = new Map(resolveLocks);
                    const result = await request(`/api/projects/${encodeURIComponent(project.id)}/runs/${encodeURIComponent(runId)}/operations?limit=100`);
                    if (!valid(stamp) || query !== operationsRevision) return;
                    if (!Array.isArray(result)) throw new Error("运行操作列表格式无效，请重新核对。");
                    for (const item of result) if (["COMMAND", "FILE_CHANGE"].includes(item.kind)
                            && operationReference(`项目操作 operationId=${item.operationId} version=${item.version} digest=${item.digest}`)) {
                        const key = `${runId}/${item.operationId}`, observed = observedLocks.get(key);
                        // 只有失败后明确发起的权威查询可解除未知锁；提交前已在途的旧GET不能解锁重复提交。
                        if (observed?.phase === "UNKNOWN" && resolveLocks.get(key) === observed) { resolveLocks.delete(key); operationFingerprint = null; }
                        commands.set(key, {output: commands.get(key)?.output, kind: item.kind, state: item.state, notice: item.notice, runId, reference: {operationId: item.operationId, version: item.version, digest: item.digest}});
                    }
                    pendingRuns.delete(runId);
                } catch (error) { if (valid(stamp)) { operationFailure ||= error; status(`操作读取未完成：${error.message}`, true); } }
            }
            if (!valid(stamp) || query !== operationsRevision) return;
            renderCommands();
            if ($("projectOperationsMore")) $("projectOperationsMore").hidden = !pendingRuns.size;
            return operationFailure;
        }
        async function loadApprovals() {
            if (!active || !isCurrent() || !conversation) return;
            approvalPending = true; $("projectChatSend").disabled = true;
            const stamp = revision, query = ++approvalRevision;
            const page = await request(`${base()}/approvals`);
            if (!valid(stamp) || query !== approvalRevision) return;
            approvalPending = (page.items || []).some(item => item.status === "PENDING");
            $("projectChatSend").disabled = archived() || !runKnown || sendBusy || sendUnknown || approvalPending || activeRun(currentRun);
            // 自动查询不能清空正在阅读的精确预览或已作出的本地选择；只有服务端审批版本变化才重绘。
            const fingerprint = JSON.stringify(page.items || []);
            if (fingerprint === approvalFingerprint) return;
            approvalFingerprint = fingerprint;
            // 查询代际只过滤迟到的GET；控件代际仅在审批内容变化时推进，普通轮询不能使现有预览按钮失效。
            const renderStamp = ++approvalRenderRevision;
            const currentApproval = () => valid(stamp) && renderStamp === approvalRenderRevision;
            const region = $("projectApprovalItems"); region.replaceChildren();
            for (const approval of page.items || []) {
                if (approval.runId) { knownRuns.add(approval.runId); pendingRuns.add(approval.runId); }
                if (approval.status !== "PENDING") { lockedApprovals.delete(approval.approvalId); continue; }
                const card = node("section", "", "project-approval-card"), choices = new Map();
                const editable = approval.canDecide === true && !lockedApprovals.has(approval.approvalId);
                card.append(node("h4", "核对本次操作"), node("p", "逐项查看精确内容，明确选择允许或拒绝。允许仅对这一次调用有效。", "field-help"));
                const submit = button("提交本次决定", async () => {
                    if (!currentApproval() || !editable || choices.size !== approval.items.length || lockedApprovals.has(approval.approvalId)) return;
                    lockedApprovals.add(approval.approvalId); submit.disabled = true;
                    card.querySelectorAll("select, input, button").forEach(control => { control.disabled = true; });
                    status("正在提交决定并等待原运行继续，请勿重复提交。");
                    try {
                        const outcome = await request(`/api/projects/${encodeURIComponent(project.id)}/conversations/${encodeURIComponent(conversation.id)}/approvals/${encodeURIComponent(approval.approvalId)}/decision`, {
                            method: "POST", body: JSON.stringify({expectedVersion: approval.version, decisions: approval.items.map(item => ({itemId: item.itemId, decision: choices.get(item.itemId)}))})});
                        if (!valid(stamp)) return;
                        lockedApprovals.delete(approval.approvalId);
                        status(`审批决定已接受，运行状态：${stateLabel(outcome.run?.status)}。${outcome.run?.errorMessage || ""}`);
                    } catch (error) { if (valid(stamp)) status(`${error.message} 提交结果尚未确认，请刷新会话核对，不能再次提交。`, true); }
                    if (valid(stamp)) await refresh();
                }); submit.disabled = true;
                for (const item of approval.items || []) {
                    const part = node("div", "", "project-approval-item"), reference = operationReference(item.inputSummary);
                    const command = item.toolName === "project_run_command", change = item.toolName === "project_apply_changes";
                    const supported = reference && (command && capabilities.commands || change && capabilities.changes);
                    const title = command ? "命令执行" : change ? "文件变更" : "待核对操作";
                    part.append(node("strong", title));
                    const location = document.createElement("details"); location.className = "project-version-details";
                    location.append(node("summary","版本与定位信息"),node("p",item.inputSummary,"field-help")); part.append(location);
                    const previewBody = node("div", "", "project-exact-preview"), feedback = node("p", "", "form-status");
                    const reviewed = document.createElement("input"); reviewed.type = "checkbox"; reviewed.disabled = true;
                    const reviewedLabel = node("label", ""); reviewedLabel.append(reviewed, node("span", "我已核对以上精确预览"));
                    const decision = document.createElement("select"); decision.setAttribute("aria-label", `${title} 本次决定`); decision.disabled = !editable;
                    const placeholder = node("option", "请选择本次决定"); placeholder.value = "";
                    const deny = node("option", "拒绝这次调用"); deny.value = "DENY";
                    const allow = node("option", "允许这次调用"); allow.value = "APPROVE"; allow.disabled = true;
                    decision.append(placeholder, deny, allow);
                    const update = () => {
                        if (!currentApproval()) return;
                        if (decision.value === "APPROVE" && (!reviewed.checked || reviewed.disabled)) decision.value = "";
                        if (decision.value) choices.set(item.itemId, decision.value); else choices.delete(item.itemId);
                        allow.disabled = !editable || !reviewed.checked || reviewed.disabled;
                        submit.disabled = !editable || choices.size !== approval.items.length;
                    };
                    reviewed.addEventListener("change", update); decision.addEventListener("change", update);
                    const showPreview = button("读取精确预览", async () => {
                        if (!currentApproval()) return;
                        reviewed.checked = false; reviewed.disabled = true; previewBody.textContent = ""; update();
                        try {
                            const result = await request(operationBase(approval.runId, reference.operationId, command ? "commands" : "changes") + (command ? "?preview=true" : ""));
                            if (!currentApproval()) return;
                            if (!matchesPreview(reference, result)) throw new Error("操作版本或摘要不一致，请刷新审批，不能批准。");
                            if (!completePreview(result, command)) throw new Error("精确预览不完整，不能批准；请联系管理员核对接口响应。");
                            // 专用接口的精确载荷只进入当前预览，不复制到普通消息、日志或持久缓存。
                            renderExactPreview(document, previewBody, result, command);
                            reviewed.disabled = !editable; feedback.textContent = "预览与本次审批的操作编号、版本、摘要一致。";
                            if (command) { commands.set(`${approval.runId}/${reference.operationId}`, {runId: approval.runId, reference}); renderCommands(); }
                        } catch (error) { if (currentApproval()) feedback.textContent = error.message; }
                    }); showPreview.disabled = !supported;
                    if (!supported) feedback.textContent = "当前操作没有可核验的完整预览，不能允许；仍可拒绝。";
                    part.append(showPreview, feedback, previewBody, reviewedLabel, decision); card.append(part);
                }
                card.append(submit); region.append(card);
            }
            if (!region.children.length) region.append(node("p", "当前没有待处理审批。", "field-help"));
        }
        async function refresh(automatic = false) {
            if (!active || !isCurrent() || !conversation) return;
            const stamp = revision;
            if (refreshInFlight?.stamp === stamp) return refreshInFlight.promise;
            clearPoll();
            const work = (async () => {
                const results = await Promise.allSettled(archived() ? [loadMessages(), loadHistory()] : [loadMessages(false, automatic), loadApprovals(), loadHistory(), loadRun()]);
                if (!valid(stamp)) return;
                let failure = results.find(item => item.status === "rejected")?.reason;
                for (const id of knownRuns) pendingRuns.add(id);
                const operationFailure = await recoverOperations(); failure ||= operationFailure;
                if (automatic && valid(stamp)) {
                    try { await refreshCommandOutputs(stamp); }
                    catch (error) { if (valid(stamp)) failure ||= error; }
                }
                if (valid(stamp)) {
                    pollFailures = failure ? pollFailures + 1 : 0;
                    if (failure) status(`${failure.message}${pollFailures >= 3 ? " 自动查询已暂停，请手动核对。" : ""}`, true);
                    renderRun(); schedulePoll();
                }
            })();
            refreshInFlight = {stamp, promise: work};
            try { await work; }
            finally { if (refreshInFlight?.promise === work) refreshInFlight = null; }
        }
        async function selectConversation(value) {
            if (!active || !isCurrent()) return;
            if (conversation?.id === value.id) { await refresh(); return; }
            clearPoll(); currentRun = null; runKnown = false; approvalFingerprint = null; operationFingerprint = null; pollCount = 0; pollFailures = 0; revision++; conversation = value; messages = []; commands.clear(); resolveLocks.clear(); knownRuns.clear(); pendingRuns.clear(); lockedApprovals.clear(); messageCursor = null; historyCursor = null; historyItems = []; sendBusy = false; sendUnknown = false;
            approvalPending = true;
            $("projectChatInput").value = ""; $("projectChatSend").disabled = true;
            $("projectChatTitle").textContent = value.title || "项目会话"; $("projectChatBody").hidden = false;
            $("projectApprovalItems").replaceChildren(); $("projectCommandResults").replaceChildren(); renderMessages(); status(""); await refresh();
        }
        async function listConversations() {
            if (!active || !isCurrent()) return;
            const stamp = revision;
            const list = await request(`/api/projects/${encodeURIComponent(project.id)}/conversations?limit=50`);
            if (!valid(stamp)) return;
            const target = $("projectConversationList"); target.replaceChildren();
            for (const item of list) target.append(button(item.title || "项目会话", () => selectConversation(item)));
            if (!list.length) target.append(node("p", archived() ? "此归档项目没有会话历史。" : "还没有项目会话。选择已授权 Agent 后新建。", "field-help"));
        }
        async function mount(value, flags) {
            project = value; capabilities = flags; const stamp = revision;
            const region = $("projectDevelopment"); region.hidden = flags.conversations !== true;
            if (region.hidden) return;
            for (const id of ["projectChatAgent", "projectNewConversation"]) { $(id).hidden = archived(); $(id).disabled = archived(); }
            if ($("projectChatAgentLabel")) $("projectChatAgentLabel").hidden = archived();
            $("projectChatForm").hidden = archived(); $("projectChatInput").disabled = archived();
            if ($("projectAuthorizationMode")) $("projectAuthorizationMode").disabled = archived();
            $("projectAuthorizationMode")?.addEventListener("change", renderAuthorizationHelp); renderAuthorizationHelp();
            $("projectChatSend").disabled = true;
            if (archived()) status("已归档项目仅供查看会话、审批历史和操作记录。");
            $("projectNewConversation").addEventListener("click", async () => {
                const agentId = $("projectChatAgent").value; if (!valid(revision) || !agentId || archived()) return;
                const creationStamp = revision;
                $("projectNewConversation").disabled = true;
                try {
                    const result = await request(`/api/projects/${encodeURIComponent(project.id)}/conversations`, {method: "POST", body: JSON.stringify({agentId})});
                    if (!valid(creationStamp)) return;
                    await listConversations();
                    if (!valid(creationStamp)) return;
                    await selectConversation(result);
                    if (valid(creationStamp)) status("项目会话已创建，固定 project_* 工具已准备给所选 Agent。");
                    if (active && isCurrent()) $("projectNewConversation").disabled = false;
                } catch (error) { if (valid(creationStamp)) status(`${error.message} 请刷新会话列表核对，不要重复新建。`, true); }
            });
            $("projectChatRefresh").addEventListener("click", () => { pollFailures = 0; pollCount = 0; return refresh(); });
            $("projectRunStop").addEventListener("click", async () => {
                if (!active || !isCurrent() || archived() || !activeRun(currentRun) || stoppedRuns.has(currentRun.id)) return;
                const stamp = revision, runId = currentRun.id; stoppedRuns.add(runId); pollCount = 0; pollFailures = 0; renderRun();
                try {
                    await request(`${base()}/runs/${encodeURIComponent(runId)}/cancel`, {method: "POST"});
                    if (valid(stamp)) status("停止请求已接受，请等待服务端确认实际退出。");
                } catch (error) { if (valid(stamp)) status(`${error.message} 停止结果尚未确认，不会重复提交；请继续核对运行状态。`, true); }
                if (valid(stamp)) await refresh();
            });
            $("projectOperationsMore")?.addEventListener("click", recoverOperations);
            $("projectOperationsRefresh")?.addEventListener("click", async () => { for(const id of knownRuns) pendingRuns.add(id); await recoverOperations(); });
            $("projectOperationHistoryMore")?.addEventListener("click", async () => { try { await loadHistory(true); await recoverOperations(); } catch(error) { if(active && isCurrent()) status(error.message,true); } });
            $("projectConversationRefresh").addEventListener("click", async () => { try { await listConversations(); } catch(error) { if(active && isCurrent()) status(error.message,true); } });
            $("projectMessagesMore").addEventListener("click", async () => { try { await loadMessages(true); await recoverOperations(); } catch(error) { if(active && isCurrent()) status(error.message,true); } });
            $("projectChatForm").addEventListener("submit", async event => {
                event.preventDefault(); if (!active || !isCurrent() || !conversation || archived() || !runKnown || sendBusy || sendUnknown || approvalPending || activeRun(currentRun)) return;
                const input = $("projectChatInput").value.trim(); if (!input) return;
                const projectAuthorizationMode = $("projectAuthorizationMode").value;
                if (!["ASK_ON_EFFECT", "ASK_ON_RISK", "FULL_ACCESS"].includes(projectAuthorizationMode)) return;
                const current = revision; sendBusy = true; $("projectChatSend").disabled = true; $("projectAuthorizationMode").disabled = true; status("正在执行项目会话；授权方式已与当前 Run 绑定。"); pollCount = 0; pollFailures = 0; schedulePoll();
                try {
                    const outcome = await request(`${base()}/messages`, {method: "POST", body: JSON.stringify({input, projectAuthorizationMode})});
                    if (!valid(current)) return;
                    $("projectChatInput").value = ""; status(`本轮状态：${stateLabel(outcome.run?.status)}。${outcome.run?.errorMessage || ""}`); await refresh();
                } catch (error) { if (valid(current)) { sendUnknown = true; status(`${error.message} 结果尚未确认，请刷新核对；不会自动再次发送。`, true); } }
                finally { if (valid(current)) { sendBusy = false; renderRun(); schedulePoll(); } }
            });
            try {
                if (archived()) { await listConversations(); return; }
                const agents = await request("/api/agents"); if (!valid(stamp)) return;
                const select = $("projectChatAgent"); select.replaceChildren();
                for (const agent of agents.filter(item => item.enabled)) { const option = node("option", agent.name); option.value = agent.id; select.append(option); }
                $("projectNewConversation").disabled = !select.children.length;
                await listConversations();
            } catch (error) { if (valid(stamp)) status(error.message, true); }
        }
        return {mount, referenceFile(value) {
            if (!active || !isCurrent() || !conversation) throw new Error("请先选择或新建项目会话，再引用文件。");
            if (archived()) throw new Error("已归档项目仅供查看历史，不能引用本地文件。");
            if (sendBusy || sendUnknown) throw new Error("本轮结果尚未确认，请核对后再编辑引用。");
            const input = $("projectChatInput"), quote = fileReference(value);
            input.value = input.value ? `${input.value}\n\n${quote}` : quote; input.focus?.();
            status("文件路径和版本已加入输入框，尚未发送；文件变化时请刷新预览后重新引用。");
        }, dispose() { clearPoll(); active = false; revision++; conversation = null; messages = []; commands.clear(); resolveLocks.clear(); knownRuns.clear(); pendingRuns.clear(); lockedApprovals.clear();
            for (const id of ["projectChatMessages", "projectApprovalItems", "projectCommandResults", "projectConversationList"]) $(id)?.replaceChildren();
            if ($("projectChatInput")) $("projectChatInput").value = "";
        }};
    }
    return {operationReference, matchesPreview, completePreview, fileReference, renderExactPreview, create};
});
