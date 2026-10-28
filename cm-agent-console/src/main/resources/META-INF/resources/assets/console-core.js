(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) {
        module.exports = api;
    }
    if (root) {
        root.CmAgentConsoleCore = api;
    }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    function formatError(status, body, fallbackText) {
        // 沙箱及导入受控拒绝有恢复动作和关联编号，保留服务端安全说明。
        const sandboxFailure = typeof body?.code === "string" && (body.code.startsWith("SKILL_SANDBOX_") || body.code.startsWith("WORKSPACE_IMPORT_"));
        if (status === 403 && !sandboxFailure) {
            return "请求失败(403)：没有权限执行此操作。";
        }
        if (status === 404 && !sandboxFailure) {
            return "请求失败(404)：请求的资源不存在或已不可用。";
        }
        const structuredMessage = body && typeof body === "object"
            ? body.message || body.error || body.detail
            : "";
        const readableMessage = structuredMessage
            || (typeof body === "string" && body.trim())
            || (fallbackText && fallbackText.trim());
        const diagnosticDetails = body && typeof body === "object"
            ? [
                body.code ? `错误码：${body.code}` : "",
                body.errorId ? `错误编号：${body.errorId}` : ""
            ].filter(Boolean)
            : [];
        if (status >= 500 && !readableMessage) {
            return `请求失败(${status})：服务暂时不可用，请稍后重试。`;
        }
        const diagnosticSuffix = diagnosticDetails.length ? `（${diagnosticDetails.join("，")}）` : "";
        return readableMessage
            ? `请求失败(${status})：${readableMessage}${diagnosticSuffix}`
            : `请求失败(${status})：服务器未返回可读错误信息`;
    }

    // 客户端只提交明细标识和决定；工具参数、主体与检查点始终由服务端持有。
    function buildApprovalDecisionPayload(approval, decisions) {
        if (approval?.status !== "PENDING" || !approval.canDecide) {
            throw new Error("当前审批不可提交，请刷新后查看权限和状态。");
        }
        const expected = new Set((approval.items || []).map((item) => item.itemId));
        if (!expected.size || !Array.isArray(decisions) || decisions.length !== expected.size) {
            throw new Error("请为每个工具调用选择允许或拒绝。");
        }
        const seen = new Set();
        const normalized = decisions.map((item) => {
            if (!item || !expected.has(item.itemId) || seen.has(item.itemId)
                    || !["APPROVE", "DENY"].includes(item.decision)) {
                throw new Error("审批明细不完整或重复，请重新选择。");
            }
            seen.add(item.itemId);
            return {itemId: item.itemId, decision: item.decision};
        });
        return {expectedVersion: approval.version, decisions: normalized};
    }

    // 汇总仅用于交互反馈；正式提交仍由 buildApprovalDecisionPayload 与服务端再次完整校验。
    function summarizeApprovalChoices(items, decisions) {
        const expected = new Set((items || []).map((item) => item.itemId));
        const selected = new Map();
        let invalid = expected.size !== (items || []).length;
        for (const item of decisions || []) {
            if (!item || !expected.has(item.itemId) || selected.has(item.itemId)
                    || !["APPROVE", "DENY"].includes(item.decision)) {
                invalid = true;
                continue;
            }
            selected.set(item.itemId, item.decision);
        }
        const approved = [...selected.values()].filter((decision) => decision === "APPROVE").length;
        const denied = selected.size - approved;
        const total = expected.size;
        const ready = !invalid && total > 0 && selected.size === total;
        const submitLabel = !ready ? "请先完成全部选择" : denied === total
            ? `拒绝${total === 1 ? "本次调用" : `全部 ${total} 项`}并结束`
            : denied === 0 ? `允许${total === 1 ? "本次调用" : ` ${total} 项调用`}并继续`
                : `提交决定：允许 ${approved} 项，拒绝 ${denied} 项`;
        return {total, selected: selected.size, approved, denied, ready, submitLabel};
    }

    // 运行恢复和审批决定是不同状态：批准不代表工具已成功，未知结果不能引导用户再次提交。
    function approvalUiState(approval, phase = "") {
        const phases = {
            SUBMITTING: ["正在提交决定", "尚未收到服务端确认，请勿重复提交。"],
            RESUMING: ["决定已接受", "正在处理原运行；批准不代表工具已执行成功。"],
            CHECKING: ["正在核对状态", "正在查询服务端最新记录，不会重复提交决定。"],
            UNKNOWN: ["结果尚未确认", "请先刷新审批状态。确认结果之前不能再次提交。"]
        };
        if (phases[phase]) return {label: phases[phase][0], message: phases[phase][1], editable: false, terminal: false};
        const statuses = {
            APPROVED: ["已允许本次调用", "审批决定已保存，实际执行结果请查看会话回答或运行记录。"],
            PARTIALLY_APPROVED: ["已提交混合决定", "仅允许选中的调用；实际执行结果请查看会话回答或运行记录。"],
            DENIED: ["已拒绝全部调用", "本次请求的工具调用不会获准执行。"],
            EXPIRED: ["审批已过期", "本次请求不能再审批，如仍需执行，请重新发起请求。"],
            CANCELLED: ["审批已取消", "本次请求不能再审批。"]
        };
        if (statuses[approval?.status]) return {label: statuses[approval.status][0], message: statuses[approval.status][1], editable: false, terminal: true};
        if (approval?.status === "PENDING" && approval.canDecide) {
            return {label: "等待你的确认", message: "先核对参数，再选择每项决定；点击提交后才会生效，仅授权本次具体调用。", editable: true, terminal: false};
        }
        return {label: "当前不可审批", message: "审批可能已过期，或当前账号不满足处理条件。仅发起本次运行且同时拥有 agent:run、agent:approve 权限的账号可处理；请刷新状态，仍无法操作时联系管理员核对权限。", editable: false, terminal: false};
    }

    // 草稿只在当前页面内存保留；版本或调用快照变化立即失效，不能把旧批准选择套到新调用。
    function createApprovalDraftStore() {
        const drafts = new Map();
        const signature = (approval) => JSON.stringify([approval.agentId, approval.conversationId, approval.version,
            (approval.items || []).map((item) => [item.itemId, item.toolId, item.toolCallId, item.inputSummary])]);
        return {
            read(approval) {
                const draft = drafts.get(approval.approvalId);
                if (!approvalUiState(approval).editable || draft?.signature !== signature(approval)) {
                    drafts.delete(approval.approvalId);
                    return [];
                }
                return draft.decisions.map((item) => ({...item}));
            },
            write(approval, decisions) {
                const ids = new Set((approval.items || []).map((item) => item.itemId));
                const seen = new Set();
                if (!approvalUiState(approval).editable) return;
                const safe = (decisions || []).filter((item) => {
                    if (!item || !ids.has(item.itemId) || seen.has(item.itemId)
                            || !["APPROVE", "DENY"].includes(item.decision)) return false;
                    seen.add(item.itemId);
                    return true;
                }).map(({itemId, decision}) => ({itemId, decision}));
                drafts.set(approval.approvalId, {signature: signature(approval), decisions: safe});
            },
            remove(approvalId) { drafts.delete(approvalId); },
            clear() { drafts.clear(); }
        };
    }

    function appendCursorPage(currentItems, page) {
        const existingItems = Array.isArray(currentItems) ? currentItems : [];
        const incomingItems = Array.isArray(page?.items) ? page.items : [];
        return {
            items: [...existingItems, ...incomingItems],
            nextCursor: page?.nextCursor || ""
        };
    }

    function buildCursorPath(basePath, limit, cursor) {
        const separator = basePath.includes("?") ? "&" : "?";
        const limitParameter = `limit=${encodeURIComponent(String(limit))}`;
        const cursorParameter = cursor ? `&cursor=${encodeURIComponent(cursor)}` : "";
        return `${basePath}${separator}${limitParameter}${cursorParameter}`;
    }

    function parseJsonField(value, fieldName) {
        try {
            return JSON.parse(String(value || "").trim());
        } catch {
            throw new Error(`${fieldName}必须是有效 JSON。`);
        }
    }

    function canDebugTool(tool, confirmedToolName) {
        if (!tool || (tool.type !== "HTTP" && tool.type !== "LOCAL") || tool.runtimeReady !== true) {
            return false;
        }
        return tool.riskLevel !== "HIGH" || confirmedToolName === tool.name;
    }

    function buildLocalExampleInstallPath(key) {
        return `/api/tools/local-examples/${encodeURIComponent(String(key || ""))}`;
    }

    function formatJsonInput(value) {
        return JSON.stringify(value ?? {}, null, 2);
    }

    function buildHttpToolPayload(fields) {
        const secretHeaders = parseJsonField(fields.secretHeadersText, "Secret 引用");
        const timeoutMillis = Number(fields.timeoutMillis);
        if (!secretHeaders || Array.isArray(secretHeaders) || typeof secretHeaders !== "object"
                || Object.values(secretHeaders).some((reference) => typeof reference !== "string"
                || !/^secret\/[A-Za-z0-9][A-Za-z0-9._-]*(?:\/[A-Za-z0-9][A-Za-z0-9._-]*)*$/.test(reference))) {
            throw new Error("Secret 引用必须是键值均为引用标识的 JSON 对象。");
        }
        if (!Number.isInteger(timeoutMillis) || timeoutMillis < 100 || timeoutMillis > 30000) {
            throw new Error("超时时间必须是 100 到 30000 毫秒之间的整数。");
        }
        const httpConfig = {
            method: fields.method,
            urlTemplate: String(fields.urlTemplate || "").trim(),
            parameters: normalizeHttpParameterDefinitions(fields.parameters),
            secretHeaders,
            timeoutMillis
        };
        return {
            name: String(fields.name || "").trim(),
            description: String(fields.description || "").trim(),
            type: "HTTP",
            riskLevel: fields.riskLevel,
            mcpPublished: Boolean(fields.mcpPublished),
            httpConfig
        };
    }

    function formatToolDebugFailure(result) {
        const reason = String(result?.errorMessage || "").trim() || "工具执行失败";
        const statusCode = Number.isInteger(result?.statusCode) ? `HTTP ${result.statusCode}` : "";
        const errorId = String(result?.errorId || "").trim();
        const details = [statusCode, errorId ? `错误编号：${errorId}` : ""].filter(Boolean);
        return details.length ? `${reason}（${details.join("，")}）` : reason;
    }

    function normalizeHttpParameterDefinitions(parameters) {
        if (!Array.isArray(parameters)) {
            throw new Error("HTTP 输入参数必须是数组。");
        }
        const allowedTypes = new Set(["STRING", "INTEGER", "NUMBER", "BOOLEAN", "OBJECT", "ARRAY"]);
        const allowedLocations = new Set(["PATH", "QUERY", "HEADER", "BODY", "BODY_ROOT"]);
        const normalized = parameters.map((parameter) => {
            const id = String(parameter?.id || "").trim();
            const parentId = String(parameter?.parentId || "").trim();
            const name = String(parameter?.name || "").trim();
            const dataType = String(parameter?.dataType || "").trim();
            const requestLocation = String(parameter?.requestLocation || "").trim();
            if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(id)) {
                throw new Error("参数 ID 格式不正确，请删除后重新添加该参数。");
            }
            if (!allowedTypes.has(dataType)) {
                throw new Error(`参数“${name || id}”的数据类型无效。`);
            }
            if (!parentId && !name) {
                throw new Error("顶层参数名称不能为空。");
            }
            if (!parentId && !allowedLocations.has(requestLocation)) {
                throw new Error(`顶层参数“${name || id}”必须选择请求位置。`);
            }
            if (parentId && requestLocation) {
                throw new Error(`嵌套参数“${name || id}”不能重复选择请求位置。`);
            }
            const result = {
                ...parameter,
                id,
                parentId: parentId || null,
                name: name || null,
                dataType,
                requestLocation: requestLocation || null,
                description: String(parameter?.description || "").trim(),
                required: Boolean(parameter?.required)
            };
            delete result.defaultValueText;
            delete result.exampleValueText;
            const defaultValueText = String(parameter?.defaultValueText || "").trim();
            const exampleValueText = String(parameter?.exampleValueText || "").trim();
            if (defaultValueText) result.defaultValue = parseJsonField(defaultValueText, `参数“${name || id}”默认值`);
            else delete result.defaultValue;
            if (exampleValueText) result.exampleValue = parseJsonField(exampleValueText, `参数“${name || id}”示例值`);
            else delete result.exampleValue;
            return result;
        });
        const ids = new Set(normalized.map((parameter) => parameter.id));
        if (ids.size !== normalized.length) {
            throw new Error("参数 ID 不能重复。");
        }
        for (const parameter of normalized) {
            if (parameter.parentId && !ids.has(parameter.parentId)) {
                throw new Error(`参数“${parameter.name || parameter.id}”选择的父参数不存在。`);
            }
        }
        validateHttpParameterTree(normalized);
        return normalized;
    }

    function validateHttpParameterTree(parameters) {
        const byId = new Map(parameters.map((parameter) => [parameter.id, parameter]));
        const children = new Map();
        for (const parameter of parameters) {
            const key = parameter.parentId || "";
            if (!children.has(key)) children.set(key, []);
            children.get(key).push(parameter);
        }
        const roots = children.get("") || [];
        validateNamedChildren(roots, "顶层参数");
        const visited = new Set();
        const visiting = new Set();

        function visit(parameter) {
            if (visiting.has(parameter.id)) throw new Error("参数父子关系不能形成循环。");
            if (visited.has(parameter.id)) return;
            visiting.add(parameter.id);
            const directChildren = children.get(parameter.id) || [];
            if (parameter.dataType === "OBJECT") {
                validateNamedChildren(directChildren, `参数“${parameter.name || parameter.id}”`);
            } else if (parameter.dataType === "ARRAY") {
                if (directChildren.length !== 1) {
                    throw new Error(`数组参数“${parameter.name || parameter.id}”必须有且只有一个元素节点。`);
                }
                const item = directChildren[0];
                if (item.name || item.required || item.defaultValue !== undefined) {
                    throw new Error(`数组参数“${parameter.name || parameter.id}”的直接元素节点必须匿名，且不能必填或配置默认值。`);
                }
            } else if (directChildren.length) {
                throw new Error(`标量参数“${parameter.name || parameter.id}”不能包含子参数。`);
            }
            directChildren.forEach(visit);
            visiting.delete(parameter.id);
            visited.add(parameter.id);
        }

        roots.forEach(visit);
        if (visited.size !== byId.size) throw new Error("参数树包含循环引用或无法到达的节点。");
    }

    function validateNamedChildren(parameters, ownerName) {
        const names = new Set();
        for (const parameter of parameters) {
            if (!parameter.name) throw new Error(`${ownerName}的字段名称不能为空。`);
            if (names.has(parameter.name)) throw new Error(`${ownerName}下的字段名称不能重复。`);
            names.add(parameter.name);
        }
    }

    function buildToolUpdatePayload(tool, fields) {
        if (tool?.type === "LOCAL" && String(fields.name || "").trim() !== tool.name) {
            throw new Error("LOCAL 工具不支持改名。");
        }
        const payload = tool?.type === "HTTP"
            ? buildHttpToolPayload(fields)
            : {
                name: String(fields.name || "").trim(),
                description: String(fields.description || "").trim(),
                type: tool?.type,
                riskLevel: fields.riskLevel,
                // LOCAL 普通编辑保持当前发布状态，发布和取消发布由独立操作完成。
                mcpPublished: tool?.type === "LOCAL" ? Boolean(tool.mcpPublished) : Boolean(fields.mcpPublished)
            };
        return {...payload, type: tool?.type, enabled: Boolean(fields.enabled)};
    }

    function buildToolFormPayload(tool, fields) {
        if (tool) {
            return buildToolUpdatePayload(tool, fields);
        }
        if (fields?.type === "HTTP") {
            return buildHttpToolPayload(fields);
        }
        return {
            name: String(fields?.name || "").trim(),
            description: String(fields?.description || "").trim(),
            type: fields?.type,
            riskLevel: fields?.riskLevel
        };
    }

    function buildToolUpdatePath(toolId) {
        return `/api/tools/${encodeURIComponent(String(toolId || ""))}`;
    }

    function buildToolDeletePath(toolId) {
        return `/api/tools/${encodeURIComponent(String(toolId || ""))}`;
    }

    function buildToolGrantDeletePath(toolId, agentId) {
        return `${buildToolDeletePath(toolId)}/grants/${encodeURIComponent(String(agentId || ""))}`;
    }

    function shouldReloadRevokedAgent(selectedAgentId, revokedAgentId) {
        return Boolean(selectedAgentId) && selectedAgentId === revokedAgentId;
    }

    function shouldResetSavedToolForm(currentEditingToolId, savedEditingToolId) {
        return Boolean(currentEditingToolId) && currentEditingToolId === savedEditingToolId;
    }

    function isToolDeleteConflict(error) {
        return error?.status === 409
            && typeof error?.message === "string"
            && error.message.includes("工具仍被 Agent 关联");
    }

    function createToolPublicationLock() {
        const activeToolIds = new Set();
        return {
            tryAcquire(toolId) {
                if (!toolId || activeToolIds.has(toolId)) {
                    return false;
                }
                activeToolIds.add(toolId);
                return true;
            },
            release(toolId) {
                activeToolIds.delete(toolId);
            }
        };
    }

    function createLoadRevisionGate() {
        let revision = 0;
        return {
            issue() {
                revision += 1;
                return revision;
            },
            invalidate() {
                revision += 1;
            },
            completeWrite() {
                revision += 1;
                return revision;
            },
            isCurrent(candidate) {
                return candidate === revision;
            }
        };
    }

    function createKeyedLoadRevisionGate() {
        const revisions = new Map();

        function next(key) {
            const normalizedKey = String(key || "");
            const revision = (revisions.get(normalizedKey) || 0) + 1;
            revisions.set(normalizedKey, revision);
            return revision;
        }

        return {
            issue(key) {
                return next(key);
            },
            invalidate(key) {
                next(key);
            },
            invalidateAll() {
                revisions.clear();
            },
            completeWrite(key) {
                return next(key);
            },
            isCurrent(key, candidate) {
                return candidate === revisions.get(String(key || ""));
            }
        };
    }

    function createSessionEpochGate() {
        let epoch = 0;
        return {
            capture() {
                return epoch;
            },
            invalidate() {
                epoch += 1;
                return epoch;
            },
            isCurrent(candidate) {
                return candidate === epoch;
            }
        };
    }

    function createSubmitStateGuard() {
        const activeTickets = new Map();
        let sequence = 0;
        return {
            begin(key, session) {
                const ticket = Object.freeze({key, session, sequence: ++sequence});
                activeTickets.set(key, ticket);
                return ticket;
            },
            invalidate(key) {
                activeTickets.delete(key);
            },
            invalidateAll() {
                activeTickets.clear();
            },
            finish(ticket, currentSession) {
                if (!ticket || activeTickets.get(ticket.key) !== ticket) {
                    return false;
                }
                activeTickets.delete(ticket.key);
                return ticket.session === currentSession;
            }
        };
    }

    function createApiClient({fetchImpl, getToken, getSessionEpoch = () => undefined, onUnauthorized}) {
        if (typeof fetchImpl !== "function" || typeof getToken !== "function"
                || typeof getSessionEpoch !== "function" || typeof onUnauthorized !== "function") {
            throw new TypeError("请求客户端依赖不完整");
        }

        return {request, stream};

        async function request(path, options = {}) {
            const {responseType, ...fetchOptions} = options;
            const headers = new Headers(options.headers || {});
            // FormData 的边界由浏览器生成；手工设置 JSON 或 multipart Content-Type 都会破坏 ZIP 上传。
            const isMultipart = typeof FormData !== "undefined" && options.body instanceof FormData;
            if (isMultipart) {
                headers.delete("Content-Type");
            } else if (!headers.has("Content-Type")) {
                headers.set("Content-Type", "application/json");
            }
            const token = getToken();
            const sessionEpoch = getSessionEpoch();
            if (token) {
                headers.set("Authorization", `Bearer ${token}`);
            }

            // 显式携带同源 Cookie，避免嵌入式浏览器对 fetch 默认凭据策略的实现差异破坏 v2 跨页会话。
            const response = await fetchImpl(path, {...fetchOptions, headers, credentials: "same-origin"});
            if (response.ok && responseType === "blob") {
                const size = Number(response.headers.get("Content-Length"));
                const errorId = response.headers.get("X-Error-Id") || "—";
                const incomplete = () => new Error(`文件下载不完整或超过上限，请重试（错误编号：${errorId}）`);
                if (!Number.isSafeInteger(size) || size < 1 || size > 4194304) {
                    await response.body?.cancel();
                    throw incomplete();
                }
                if (!response.body?.getReader) {
                    const blob = await response.blob();
                    if (blob.size !== size) throw incomplete();
                    return blob;
                }
                const reader = response.body.getReader();
                const chunks = [];
                let received = 0;
                try {
                    while (true) {
                        const next = await reader.read();
                        if (next.done) break;
                        received += next.value.byteLength;
                        if (received > size) throw incomplete();
                        chunks.push(next.value);
                    }
                    if (received !== size) throw incomplete();
                    return new Blob(chunks, {type: response.headers.get("Content-Type") || "application/octet-stream"});
                } finally {
                    await reader.cancel();
                    reader.releaseLock();
                }
            }
            const rawBody = await response.text();
            let body = null;
            if (rawBody) {
                try {
                    body = JSON.parse(rawBody);
                } catch {
                    body = rawBody;
                }
            }

            if (!response.ok) {
                if (response.status === 401) {
                    if (token === getToken() && sessionEpoch === getSessionEpoch()) {
                        onUnauthorized();
                    }
                    throw new Error("未登录或令牌已失效，请重新登录。");
                }
                const error = new Error(formatError(response.status, body, rawBody));
                error.status = response.status;
                if (body && typeof body === "object") {
                    if (typeof body.code === "string") error.code = body.code;
                    if (typeof body.errorId === "string") error.errorId = body.errorId;
                }
                throw error;
            }
            return body;
        }

        /**
         * 发起同源 SSE POST 请求，并将完整事件帧按顺序交给调用方。
         *
         * <p>运行接口使用 HttpOnly Cookie 和内存令牌的同源认证策略，因此这里与普通请求保持
         * 相同的凭据和 401 收口逻辑。解析器按空行切分事件，能够正确处理网络层把一个事件拆分为
         * 多个 {@code ReadableStream} 数据块的情况。</p>
         */
        async function stream(path, options = {}, onEvent) {
            if (typeof onEvent !== "function") {
                throw new Error("流式响应处理器必须是函数。");
            }
            const headers = new Headers(options.headers || {});
            headers.set("Content-Type", "application/json");
            headers.set("Accept", "text/event-stream");
            const token = getToken();
            const sessionEpoch = getSessionEpoch();
            if (token) {
                headers.set("Authorization", `Bearer ${token}`);
            }
            const response = await fetchImpl(path, {...options, headers, credentials: "same-origin"});
            if (!response.ok) {
                const rawBody = await response.text();
                let body = null;
                try {
                    body = rawBody ? JSON.parse(rawBody) : null;
                } catch {
                    body = rawBody;
                }
                if (response.status === 401) {
                    if (token === getToken() && sessionEpoch === getSessionEpoch()) {
                        onUnauthorized();
                    }
                    throw new Error("未登录或令牌已失效，请重新登录。");
                }
                const error = new Error(formatError(response.status, body, rawBody));
                error.status = response.status;
                throw error;
            }
            if (!response.body) {
                throw new Error("服务未返回可读取的流式响应。");
            }
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffered = "";
            while (true) {
                const chunk = await reader.read();
                if (chunk.done) break;
                buffered += decoder.decode(chunk.value, {stream: true});
                // \r\n 也可能刚好跨越网络分片边界，因此必须在合并后再统一规范化。
                buffered = buffered.replace(/\r\n/g, "\n");
                let boundary = buffered.indexOf("\n\n");
                while (boundary >= 0) {
                    const frame = buffered.slice(0, boundary);
                    buffered = buffered.slice(boundary + 2);
                    const event = parseSseFrame(frame);
                    if (event) onEvent(event);
                    boundary = buffered.indexOf("\n\n");
                }
            }
            buffered += decoder.decode();
            buffered = buffered.replace(/\r\n/g, "\n");
            const trailingEvent = parseSseFrame(buffered);
            if (trailingEvent) onEvent(trailingEvent);
        }
    }

    // 历史与待办分离；即使服务端错误标记 canDecide，历史视图也不能生成新的决定操作。
    function mergeApprovalHistory(current, incoming, agentId, conversationId) {
        const terminal = new Set(["APPROVED", "PARTIALLY_APPROVED", "DENIED", "EXPIRED", "CANCELLED"]);
        const merged = new Map();
        for (const item of [...(current || []), ...(incoming || [])]) {
            if (!item?.approvalId || !item.runId || item.agentId !== agentId || item.conversationId !== conversationId
                    || !terminal.has(item.status) || !Number.isFinite(Date.parse(item.decidedAt))) continue;
            const previous = merged.get(item.approvalId);
            if (previous && previous.version > item.version) continue;
            merged.set(item.approvalId, {...item, canDecide: false});
        }
        return [...merged.values()].sort((left, right) => Date.parse(right.decidedAt) - Date.parse(left.decidedAt)
            || String(right.approvalId).localeCompare(String(left.approvalId)));
    }

    // 页面按 Run 关联，一次 Run 中的多轮 ASK 按决定时间正序展示，不覆盖之前已处理的请求。
    function groupApprovalHistory(history) {
        const runs = new Map();
        for (const approval of history || []) {
            if (!runs.has(approval.runId)) runs.set(approval.runId, []);
            runs.get(approval.runId).push(approval);
        }
        return [...runs].map(([runId, items]) => ({runId, items: items.slice().sort((left, right) =>
            Date.parse(left.decidedAt) - Date.parse(right.decidedAt)
            || String(left.approvalId).localeCompare(String(right.approvalId)))}));
    }

    function parseSseFrame(frame) {
        if (!frame || frame.startsWith(":")) return null;
        let type = "message";
        const dataLines = [];
        frame.split("\n").forEach((line) => {
            if (line.startsWith("event:")) type = line.slice(6).trim() || "message";
            if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
        });
        if (!dataLines.length) return null;
        const rawData = dataLines.join("\n");
        try {
            return {type, data: JSON.parse(rawData)};
        } catch {
            return {type, data: rawData};
        }
    }

    function formatDateTime(value) {
        if (!value) {
            return "—";
        }
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) {
            return "—";
        }
        return new Intl.DateTimeFormat("zh-CN", {
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit"
        }).format(date);
    }

    // completed 只表示协议结束；只有 SUCCEEDED 才能提示成功。原因沿用服务端安全字段，不能推测底层异常。
    function chatRunOutcome(run) {
        const runId = run?.runId || run?.id;
        const details = [run?.errorCode || run?.code ? `错误码：${run.errorCode || run.code}` : "",
            run?.errorId ? `错误编号：${run.errorId}` : "", runId ? `运行编号：${runId}` : ""].filter(Boolean);
        switch (run?.status) {
            case "FAILED":
                return {tone: "error", message: `本轮运行失败：${String(run.errorMessage || "").trim() || "未生成完整回答"}。${details.length ? `（${details.join("，")}）` : ""} 请核对运行记录，或联系管理员排查后重新发送。`};
            case "SUCCEEDED": return {tone: "success", message: "回答已完成。"};
            case "CANCELLED": return {tone: "neutral", message: "本轮已停止，已保留输出内容。"};
            case "DENIED": return {tone: "neutral", message: "本轮已拒绝，不会执行被拒绝的调用。"};
            case "WAITING_APPROVAL": return {tone: "neutral", message: "运行已暂停，请处理高风险工具审批。"};
            default: return {tone: "neutral", message: "尚未确认本轮最终状态，请刷新会话核对。"};
        }
    }

    function statusMeta(status) {
        const values = {
            SUCCEEDED: {label: "成功", tone: "success"},
            SANDBOX_PREPARED: {label: "沙箱资源已准备", tone: "neutral"},
            RUNNING: {label: "运行中", tone: "warning"},
            CANCELLING: {label: "停止中", tone: "warning"},
            CANCELLED: {label: "已停止", tone: "neutral"},
            WAITING_APPROVAL: {label: "等待审批", tone: "warning"},
            FAILED: {label: "失败", tone: "error"},
            DENIED: {label: "已拒绝", tone: "error"}
        };
        return values[status] || {label: status || "未知", tone: "neutral"};
    }

    // 运行详情只用该状态文案描述受控读取记录，避免将技能正文或原始失败载荷写进页面提示。
    function formatSkillLoadState({loading, error, items}) {
        if (loading) return "正在加载技能读取记录…";
        if (error) return `技能读取记录加载失败：${error}`;
        return items?.length ? `已加载 ${items.length} 条技能读取记录` : "本轮未读取技能";
    }

    // 状态只保留在当前页面；请求代次与会话 epoch 同时隔离，写入失败不自动重试。
    function createWorkspaceSelectionState({api, getEpoch, isContextCurrent = () => true, onChange = () => {}}) {
        let revision = 0, current = {agent: "", conversation: "", epoch: null, loading: true, error: "", catalog: null, view: null};
        const defaults = () => ({selection: {mode: "DEFAULT", workspaceId: null}, displayName: "沿用 Agent 配置", version: 0, editable: true, available: true});
        // 来源身份必须完整保留，不能把导入或未来未知来源悄悄解释为DEFAULT。
        const supported = selection => ["DEFAULT", "SELECTED", "NONE", "IMPORTED"].includes(selection?.mode);
        const emit = () => onChange(current);
        const isCurrent = (ticket, context) => ticket === revision && context.epoch === getEpoch()
            && context.agent === current.agent && context.conversation === current.conversation && isContextCurrent(context);
        async function load(agent, conversation = "") {
            const ticket = ++revision, context = {agent, conversation, epoch: getEpoch()};
            current = {...context, loading: true, error: "", catalog: null, view: null}; emit();
            if (!agent) { current.loading = false; current.error = "请先选择 Agent"; emit(); return; }
            const base = `/api/agents/${encodeURIComponent(agent)}`;
            try {
                const [catalog, view] = await Promise.all([api.request(`${base}/workspaces`), conversation
                    ? api.request(`${base}/conversations/${encodeURIComponent(conversation)}/workspace`) : Promise.resolve(defaults())]);
                if (!isCurrent(ticket, context)) return;
                current = {...context, catalog, view: supported(view?.selection) ? view : {...view, editable: false, available: false},
                    loading: false, error: supported(view?.selection) ? "" : "当前页面尚未开放此资料来源，请使用支持该来源的完整版本。"}; emit();
            } catch (error) {
                if (!isCurrent(ticket, context)) return;
                current = {...context, loading: false, catalog: null, view: null, error: error.message}; emit();
            }
        }
        async function save(selection, imported = null, expectedVersion = null, operationCurrent = () => true) {
            if (!supported(selection)) throw new Error("当前页面尚未开放此资料来源，请使用服务器登记目录。");
            if (current.loading || current.error || !current.view?.editable || current.epoch !== getEpoch())
                throw new Error("当前目录尚不可修改，请重新读取会话或新建会话。");
            if (expectedVersion !== null && current.view.version !== expectedVersion)
                throw new Error("当前会话绑定已经变化，请重新读取后核对，原选择未被覆盖。");
            if (selection.mode === "IMPORTED" && (!imported?.bindable || imported.state !== "READY" || imported.id !== selection.workspaceId))
                throw new Error("导入资料尚未就绪，请先核对上传结果。");
            const ticket = ++revision, context = {...current}; current = {...current, loading: true}; emit();
            try {
                const view = current.conversation ? await api.request(`/api/agents/${encodeURIComponent(current.agent)}/conversations/${encodeURIComponent(current.conversation)}/workspace`, {
                    method: "PUT", body: JSON.stringify({workspaceSelection: selection, expectedVersion: current.view.version})
                }) : {...defaults(), selection, displayName: selection.mode === "NONE" ? "不使用工作区"
                    : selection.mode === "SELECTED" ? current.catalog.items.find(item => item.id === selection.workspaceId)?.name
                        : selection.mode === "IMPORTED" ? imported.displayName : "沿用 Agent 配置"};
                if (!isCurrent(ticket, context)) return false;
                if (!operationCurrent()) {
                    current = {...context, loading: false, error: "绑定请求可能已经发送，当前显示暂保留；请重新读取权威状态后再发送。"}; emit(); return false;
                }
                current = {...context, loading: false, error: "", view}; emit(); return true;
            } catch (error) {
                if (!isCurrent(ticket, context)) return false;
                current = {...context, loading: false, error: `${formatWorkspaceImportFailure(error)} 请重新读取目录状态后再发送。`}; emit(); throw error;
            }
        }
        return {load, save, value: () => current,
            payload: () => ({workspaceSelection: current.view?.selection || defaults().selection}),
            ready: () => current.epoch === getEpoch() && !current.loading && !current.error && !!current.view?.available,
            reset: () => { ++revision; current = {loading: true, error: "", view: null, catalog: null}; emit(); }};
    }

    function formatWorkspaceImportFailure(error) {
        const suffix = [error?.code && !String(error.message).includes(error.code) ? `错误码：${error.code}` : "",
            error?.errorId && !String(error.message).includes(error.errorId) ? `错误编号：${error.errorId}` : ""].filter(Boolean).join("，");
        return `${error?.message || "当前操作未完成"}${suffix ? `（${suffix}）` : ""}`;
    }

    // 只在当前页面持有File/句柄和草稿。任何文件正文、路径或manifest都必须等明确确认后才发送。
    function createNativeWorkspaceImportState({api, getEpoch, getContext, directoryInput, getDirectoryInput = () => directoryInput, host = globalThis, onChange = () => {}, now = () => Date.now()}) {
        let generation = 0, capabilityGeneration = 0, context = null, pendingInput = null, operationAbort = null;
        const empty = () => ({phase: "IDLE", capabilities: null, name: "", candidates: [], excluded: [], selected: new Set(),
            preview: "", message: "", progress: "", batch: null, requestId: "", manifest: null, attempted: 0, bindingVersion: null, canConfirm: false});
        let current = empty();
        const same = value => !!context && value?.agent === context.agent && (value?.conversation || "") === context.conversation && context.epoch === getEpoch();
        const valid = (ticket, stamp) => ticket === generation && same(stamp) && same(getContext()) && (current.phase === "BINDING" || !!getContext()?.editable);
        const emit = () => { current.canConfirm = readyToConfirm(); onChange(current); };
        const base = () => `/api/agents/${encodeURIComponent(context.agent)}/workspace-imports`;
        const cap = () => current.capabilities;
        const limits = () => {
            const value = cap();
            const bounded = (key, hard) => Number.isSafeInteger(value?.[key]) && value[key] > 0 ? Math.min(value[key], hard) : 0;
            return {files: bounded("maxFiles", 8), fileBytes: bounded("maxFileBytes", 32768), total: bounded("maxTotalBytes", 65536),
                entries: bounded("maxCandidates", 1000), depth: bounded("maxDepth", 16), milliseconds: bounded("enumerationTimeoutMillis", 5000)};
        };
        const enabled = () => cap()?.enabled === true && cap()?.runtimeAvailable === true && Object.values(limits()).every(value => value > 0);
        const busy = () => ["ENUMERATING", "READING", "UPLOADING", "COMPLETING", "CHECKING", "BINDING"].includes(current.phase);
        const budget = () => {
            const items = current.candidates.filter(item => current.selected.has(item.path));
            const bytes = items.reduce((total, item) => total + item.size, 0), policy = limits();
            return {items, count: items.length, bytes, allowed: items.length > 0 && items.length <= policy.files && bytes <= policy.total};
        };
        function readyToConfirm() {
            return enabled() && !busy() && current.phase === "LOCAL" && budget().allowed && !!host.crypto?.subtle && !!host.crypto?.getRandomValues;
        }
        function clearInput() {
            if (!pendingInput) return;
            pendingInput.input.removeEventListener("change", pendingInput.change); pendingInput.input.removeEventListener("cancel", pendingInput.cancel);
            pendingInput.input.value = "";
            pendingInput.resolve(null); pendingInput = null;
        }
        function discard(message = "") {
            ++generation; operationAbort?.abort(); operationAbort = null; clearInput(); const capabilities = current.capabilities;
            current = {...empty(), capabilities, message}; emit();
        }
        function activate(agent, conversation = "", force = false) {
            const next = {agent: agent || "", conversation: conversation || "", epoch: getEpoch()};
            if (!force && same(next)) return;
            ++generation; ++capabilityGeneration; operationAbort?.abort(); operationAbort = null; clearInput(); context = next; current = {...empty(), phase: agent ? "LOADING" : "IDLE"}; emit();
            if (!agent || !same(getContext())) return;
            const ticket = capabilityGeneration, stamp = {...context};
            api.request(`${base()}/capabilities`).then(capabilities => {
                if (ticket !== capabilityGeneration || !same(stamp) || !same(getContext())) return;
                current.capabilities = capabilities; current.phase = "IDLE";
                current.message = enabled() ? "先在本机选择文件夹，再勾选要上传的资料。"
                    : capabilities?.enabled ? "导入运行尚未开放或预算无法确认，请使用服务器登记目录。" : "部署者尚未启用本机资料导入，请使用服务器登记目录。";
                emit();
            }, error => { if (ticket === capabilityGeneration && same(stamp) && same(getContext())) { current.phase = "ERROR"; current.message = explain(error) + " 可继续使用服务器登记目录。"; emit(); } });
        }
        function explain(error) {
            return formatWorkspaceImportFailure(error);
        }
        function nativeError(error) {
            if (error?.name === "AbortError") return "已取消系统文件夹选择，原工作区未变。";
            if (error?.name === "SecurityError") return "浏览器限制了系统目录选择，请使用HTTPS并在独立窗口打开控制台。";
            if (error?.name === "NotAllowedError") return "未获文件夹读取权限，请重新选择并允许只读访问。";
            return error?.local ? error.message : "所选文件夹或资料暂不可读，请重新选择。原工作区未变。";
        }
        function local(message) { const error = new Error(message); error.local = true; return error; }
        function excludedName(name) {
            const value = name.toLocaleLowerCase();
            return [".git", ".ssh", ".aws", ".codex", ".workbuddy", "node_modules"].includes(value) || value.startsWith(".env")
                || /credential|secret|password|private[_-]key|api_key|access_token/.test(value) || /^id_(rsa|ed25519|ecdsa)/.test(value);
        }
        function safePath(path) {
            return !!path && path.length <= 160 && path.normalize("NFKC") === path && !/^[\/]|[\\:]|[\p{Cc}\p{Cf}]/u.test(path)
                && path.split("/").every(part => part && ![".", ".."].includes(part) && !/[. ]$/.test(part)
                    && !excludedName(part) && !/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i.test(part));
        }
        function rootName(name) {
            if (!name || name.length > 120 || /[\/:\\]|[\p{Cc}\p{Cf}]/u.test(name) || excludedName(name))
                throw local("该文件夹名称或范围不适合导入，请选择一个普通资料子目录。");
            return name;
        }
        function candidate(path, file, found, rejected, identities) {
            const identity = path.normalize("NFKC").toLocaleLowerCase();
            if (identities.has(identity)) throw local("发现重复、大小写或Unicode等价路径，请缩小目录范围后重新选择。");
            identities.add(identity);
            const allowedTypes = [".md", ".txt", ".json", ".csv"].filter(type => cap()?.allowedTypes?.includes(type));
            const reason = !safePath(path) ? "路径或名称不符合只读资料边界" : !allowedTypes.some(type => path.endsWith(type)) ? "不支持的文件类型"
                : !Number.isSafeInteger(file.size) || file.size < 1 ? "空文件或无法确认大小" : file.size > limits().fileBytes ? "超过单文件字节预算" : "";
            if (reason) rejected.push({path, reason}); else found.push({path, size: file.size, file});
        }
        async function fromHandle(handle, ticket, stamp) {
            const name = rootName(handle.name), found = [], rejected = [], identities = new Set(), policy = limits(), deadline = now() + policy.milliseconds;
            let entries = 0;
            async function walk(directory, prefix, depth) {
                for await (const [name, entry] of directory.entries()) {
                    if (!valid(ticket, stamp)) return;
                    if (++entries > policy.entries || now() > deadline) throw local("目录枚举超过条目或时间预算，未采用部分结果；请选择更小的资料目录。");
                    const path = prefix ? `${prefix}/${name}` : name;
                    if (entry.kind === "directory") {
                        if (excludedName(name)) { rejected.push({path, reason: "已排除私有或生成目录"}); continue; }
                        if (depth >= policy.depth) throw local("目录层级超过预算，未采用部分结果；请选择更浅的资料目录。");
                        await walk(entry, path, depth + 1);
                    } else if (entry.kind === "file") {
                        const file = await entry.getFile(); if (!valid(ticket, stamp)) return;
                        candidate(path, file, found, rejected, identities);
                    }
                }
            }
            let timer;
            try { await Promise.race([walk(handle, "", 0), new Promise((_, reject) => { timer = host.setTimeout(() => reject(local("目录枚举超时，未采用部分结果；请选择更小的资料目录。")), policy.milliseconds); })]); }
            finally { host.clearTimeout(timer); }
            return {name, found, rejected};
        }
        function fromFiles(files) {
            const policy = limits();
            if (!files?.length) return null;
            // 目录input可能已由浏览器展开整棵树；应用不能假称提前剪枝，超界直接拒绝整个选择。
            if (files.length > policy.entries) throw local("浏览器展开的目录超过条目预算，未采用部分结果；请选择更小的资料目录。");
            const first = String(files[0].webkitRelativePath || ""), separator = first.indexOf("/");
            if (separator < 1) throw local("未收到目录层级，不能把普通单文件框当作文件夹选择。");
            const name = rootName(first.slice(0, separator)), found = [], rejected = [], identities = new Set(), deadline = now() + policy.milliseconds;
            for (let index = 0; index < files.length; index++) {
                if (now() > deadline) throw local("目录枚举超过时间预算，请选择更小的资料目录。");
                const file = files[index], raw = String(file.webkitRelativePath || "");
                if (!raw.startsWith(`${name}/`)) throw local("发现多个选择根，不能把不同目录合并为同一批次。");
                const path = raw.slice(name.length + 1);
                if (path.split("/").length > policy.depth + 1) throw local("目录层级超过预算，请选择更浅的资料目录。");
                candidate(path, file, found, rejected, identities);
            }
            return {name, found, rejected};
        }
        function pick() {
            if (!enabled()) return Promise.resolve(false);
            if (!getContext()?.editable || busy()) return Promise.resolve(false);
            const ticket = ++generation, stamp = {...context}; clearInput();
            current = {...empty(), capabilities: cap(), phase: "PICKING", bindingVersion: getContext().bindingVersion, message: "请在系统窗口中选择资料文件夹；仅选择不会上传。"}; emit();
            let selected; const input = getDirectoryInput();
            // 原生调用必须在点击栈同步发生，前面不能await网络、能力读取或浏览器权限请求。
            if (typeof host.showDirectoryPicker === "function") {
                try { selected = host.showDirectoryPicker({mode: "read"}); }
                catch (error) { selected = Promise.reject(error); }
            } else if (input && "webkitdirectory" in input) {
                selected = new Promise(resolve => {
                    const change = () => { const files = input.files; clearHandlers(); resolve(files); };
                    const cancel = () => { clearHandlers(); resolve(null); };
                    const clearHandlers = () => { input.removeEventListener("change", change); input.removeEventListener("cancel", cancel); pendingInput = null; };
                    pendingInput = {input, change, cancel, resolve}; input.value = "";
                    input.addEventListener("change", change); input.addEventListener("cancel", cancel);
                    input.click();
                });
            } else { current.phase = "ERROR"; current.message = "当前浏览器不支持文件夹选择，请更换受支持浏览器或使用服务器登记目录。"; emit(); return Promise.resolve(false); }
            return Promise.resolve(selected).then(async value => {
                if (!valid(ticket, stamp)) return false;
                if (!value) { current.phase = "IDLE"; current.message = "未取得文件夹内容，可能已取消或目录为空；原工作区未变，可重新选择。"; emit(); return false; }
                current.phase = "ENUMERATING"; current.message = "仅在本机检查文件名称、大小和层级…"; emit();
                const result = value.kind === "directory" ? await fromHandle(value, ticket, stamp) : fromFiles(value);
                if (!valid(ticket, stamp)) return false;
                if (!result) { current.phase = "IDLE"; current.message = "未取得目录内容；原工作区未变。"; emit(); return false; }
                current = {...current, phase: "LOCAL", name: result.name, candidates: result.found, excluded: result.rejected, selected: new Set(), preview: "",
                    message: result.found.length ? "文件默认未勾选。核对后选择资料，再明确确认上传。" : "此目录没有符合当前预算的资料，请查看排除原因或选择其他目录。"};
                if (!host.crypto?.subtle || !host.crypto?.getRandomValues) current.message += " 当前环境缺少安全摘要能力，需使用HTTPS或更换浏览器后上传。";
                emit(); return true;
            }).catch(error => { if (valid(ticket, stamp)) { current.phase = "ERROR"; current.candidates = []; current.selected = new Set(); current.message = nativeError(error); emit(); } return false; });
        }
        async function bytesOf(item, ticket, stamp) {
            const bytes = new Uint8Array(await item.file.slice(0, limits().fileBytes + 1).arrayBuffer());
            if (!valid(ticket, stamp)) { bytes.fill(0); return null; }
            if (bytes.length !== item.size || bytes.length > limits().fileBytes) { bytes.fill(0); throw local("资料大小在选择后发生变化或已超限，请重新选择文件夹。"); }
            try {
                const text = new TextDecoder("utf-8", {fatal: true}).decode(bytes);
                if (!text.trim() || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(text)) throw new Error();
            } catch { bytes.fill(0); throw local("所选资料不是有效的非空UTF-8文本，请排除后重新选择。"); }
            return bytes;
        }
        async function preview(path) {
            if (busy()) return;
            const item = current.candidates.find(value => value.path === path); if (!item) return;
            const ticket = generation, stamp = {...context}; current.phase = "READING"; emit();
            try { const bytes = await bytesOf(item, ticket, stamp); if (!bytes) return;
                try { current.preview = new TextDecoder("utf-8", {fatal: true}).decode(bytes); current.phase = "LOCAL"; emit(); }
                finally { bytes.fill(0); }
            } catch (error) { if (valid(ticket, stamp)) { current.phase = "LOCAL"; current.preview = ""; current.message = nativeError(error); emit(); } }
        }
        function select(path, checked) {
            if (busy() || current.phase !== "LOCAL" || !current.candidates.some(item => item.path === path)) return;
            const selected = new Set(current.selected); checked ? selected.add(path) : selected.delete(path); current.selected = selected; emit();
        }
        function uuid() {
            if (typeof host.crypto.randomUUID === "function") return host.crypto.randomUUID();
            const bytes = host.crypto.getRandomValues(new Uint8Array(16)); bytes[6] = bytes[6] & 15 | 64; bytes[8] = bytes[8] & 63 | 128;
            const hex = [...bytes].map(value => value.toString(16).padStart(2, "0")).join("");
            return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
        }
        function verifyBatch(batch) {
            if (typeof batch?.id !== "string" || !batch.id || !Number.isSafeInteger(batch.version) || batch.version < 1
                    || !Array.isArray(batch.files) || batch.files.length !== current.manifest?.files.length || new Set(batch.files.map(file => file.id)).size !== batch.files.length
                    || batch.requestId !== current.requestId || current.manifest.files.some(item => !batch.files.some(file =>
                        file.relativePath === item.relativePath && file.sizeBytes === item.sizeBytes && file.sha256 === item.sha256)))
                throw local("服务器核对结果与本次确认清单不一致，不能继续；请保留原选择并联系管理员。");
            return batch;
        }
        async function checkedBatch(ticket, stamp) {
            const path = current.batch?.id ? `${base()}/${encodeURIComponent(current.batch.id)}` : `${base()}/by-request/${encodeURIComponent(current.requestId)}`;
            const batch = await api.request(path, operationAbort ? {signal: operationAbort.signal} : undefined); if (!valid(ticket, stamp)) return null;
            current.batch = verifyBatch(batch); return batch;
        }
        async function writeOnce(path, options, ticket, stamp, accepted) {
            try { const result = await api.request(path, {...options, ...(operationAbort ? {signal: operationAbort.signal} : {})}); if (!valid(ticket, stamp)) return null; return verifyBatch(result); }
            catch (error) {
                if (!valid(ticket, stamp)) return null;
                try { const result = await checkedBatch(ticket, stamp); if (result && accepted(result)) return result; }
                catch (lookup) { if (!valid(ticket, stamp)) return null; }
                throw error;
            }
        }
        async function confirm() {
            if (!readyToConfirm() || !getContext()?.editable) return null;
            const ticket = ++generation, stamp = {...context, bindingVersion: current.bindingVersion}, selected = budget().items, contents = new Map();
            const controller = typeof AbortController === "function" ? new AbortController() : null; operationAbort = controller;
            current.phase = "READING"; current.progress = "正在核对所选资料…"; current.message = "已确认：所选文件将上传到当前CM Agent Server。"; emit();
            try {
                const files = [];
                for (const item of selected) {
                    const bytes = await bytesOf(item, ticket, stamp); if (!bytes) return null; contents.set(item.path, bytes);
                    const digest = new Uint8Array(await host.crypto.subtle.digest("SHA-256", bytes)); if (!valid(ticket, stamp)) return null;
                    files.push({relativePath: item.path, sizeBytes: bytes.length, sha256: [...digest].map(value => value.toString(16).padStart(2, "0")).join("")});
                }
                current.requestId = uuid(); current.manifest = {displayName: current.name, requestId: current.requestId, confirmed: true, files};
                current.phase = "UPLOADING"; current.progress = `创建只读导入批次 · 0/${files.length}`; emit();
                let batch = await writeOnce(base(), {method: "POST", body: JSON.stringify(current.manifest)}, ticket, stamp, value => value.state === "DRAFT");
                if (!batch) return null; current.batch = batch;
                if (batch.state !== "DRAFT") throw local("原创建结果已不可上传，请撤销或重新选择资料，不会重复创建。");
                for (let index = 0; index < files.length; index++) {
                    const file = batch.files.find(value => value.relativePath === files[index].relativePath); current.attempted = index + 1;
                    batch = await writeOnce(`${base()}/${encodeURIComponent(batch.id)}/files/${encodeURIComponent(file.id)}?expectedVersion=${batch.version}`,
                        {method: "POST", headers: {"Content-Type": "application/octet-stream"}, body: contents.get(files[index].relativePath)}, ticket, stamp,
                        value => value.files.some(item => item.id === file.id && item.uploaded));
                    if (!batch) return null; current.batch = batch; current.progress = `上传并核对资料 · ${index + 1}/${files.length}`; emit();
                }
                current.phase = "COMPLETING"; current.progress = "正在验证全部密文并完成导入…"; emit();
                batch = await writeOnce(`${base()}/${encodeURIComponent(batch.id)}/complete`, {method: "POST", body: JSON.stringify({expectedVersion: batch.version})}, ticket, stamp, value => value.state === "READY");
                if (!batch) return null; return completed(batch, ticket, stamp);
            } catch (error) { if (valid(ticket, stamp)) { current.phase = current.requestId ? "UNKNOWN" : "LOCAL"; current.message = explain(error) + " 原工作区未变，请先核对服务器结果，不会自动重试写入。"; emit(); } return null; }
            finally { for (const bytes of contents.values()) bytes.fill(0); if (operationAbort === controller) operationAbort = null; }
        }
        function completed(batch, ticket, stamp) {
            current.batch = batch;
            if (batch.state !== "READY" || !batch.bindable) throw local("导入尚未达到可绑定的READY，请核对期限、开关和服务器状态。");
            current.phase = "READY"; current.progress = "资料已导入，正在绑定当前空会话…"; emit();
            return {batch, context: stamp, isCurrent: () => valid(ticket, stamp)};
        }
        async function check(complete = false) {
            if (!current.requestId || busy()) return null;
            const ticket = generation, stamp = {...context, bindingVersion: current.bindingVersion}; current.phase = "CHECKING"; emit();
            const controller = typeof AbortController === "function" ? new AbortController() : null; operationAbort = controller;
            try {
                let batch = await checkedBatch(ticket, stamp); if (!batch) return null;
                if (complete && batch.state === "DRAFT" && current.attempted === current.manifest.files.length) {
                    batch = await writeOnce(`${base()}/${encodeURIComponent(batch.id)}/complete`, {method: "POST", body: JSON.stringify({expectedVersion: batch.version})}, ticket, stamp, value => value.state === "READY");
                    if (!batch) return null;
                }
                if (batch.state === "READY") return completed(batch, ticket, stamp);
                current.phase = "UNKNOWN"; current.message = batch.state === "DRAFT"
                    ? "服务器仍为草稿。全部文件已尝试发送时可明确完成核对；否则请撤销批次并重新选择。不会自动重传。"
                    : "原批次已撤销或过期，请重新选择资料。原工作区未变。"; emit(); return null;
            } catch (error) { if (valid(ticket, stamp)) { current.phase = "UNKNOWN"; current.message = explain(error) + " 请继续只读核对，不会重新创建批次。"; emit(); } return null; }
            finally { if (operationAbort === controller) operationAbort = null; }
        }
        async function revoke() {
            if (!current.requestId || busy()) return;
            const ticket = generation, stamp = {...context}; current.phase = "CHECKING"; emit();
            const controller = typeof AbortController === "function" ? new AbortController() : null; operationAbort = controller;
            try {
                let batch = await checkedBatch(ticket, stamp); if (!batch) return;
                batch = await writeOnce(`${base()}/${encodeURIComponent(batch.id)}?expectedVersion=${batch.version}`, {method: "DELETE"}, ticket, stamp, value => ["REVOKED", "EXPIRED"].includes(value.state));
                if (batch && valid(ticket, stamp)) discard("本次导入已撤销，原工作区未变；服务器将在引用释放后清理文件。");
            } catch (error) { if (valid(ticket, stamp)) { current.phase = "UNKNOWN"; current.message = explain(error) + " 撤销结果未确认，请先核对。"; emit(); } }
            finally { if (operationAbort === controller) operationAbort = null; }
        }
        return {activate, pick, select, preview, confirm, check, revoke, discard, budget, busy, enabled, value: () => current,
            canPick: () => enabled() && !busy() && same(getContext()) && !!getContext()?.editable,
            refresh: () => { if (context) activate(context.agent, context.conversation, true); },
            markBinding: () => { if (current.phase === "READY") { current.phase = "BINDING"; emit(); } },
            bindingFailed: message => { current.phase = "READY"; current.message = message; current.progress = ""; emit(); },
            reset: () => { ++generation; ++capabilityGeneration; operationAbort?.abort(); operationAbort = null; clearInput(); context = null; current = empty(); emit(); }};
    }

    return {
        createWorkspaceSelectionState,
        createNativeWorkspaceImportState,
        formatWorkspaceImportFailure,
        formatError,
        buildApprovalDecisionPayload,
        summarizeApprovalChoices,
        approvalUiState,
        createApprovalDraftStore,
        createApiClient,
        parseSseFrame,
        appendCursorPage,
        mergeApprovalHistory,
        groupApprovalHistory,
        buildCursorPath,
        parseJsonField,
        canDebugTool,
        formatToolDebugFailure,
        buildLocalExampleInstallPath,
        formatJsonInput,
        buildHttpToolPayload,
        normalizeHttpParameterDefinitions,
        buildToolUpdatePayload,
        buildToolFormPayload,
        buildToolUpdatePath,
        buildToolDeletePath,
        buildToolGrantDeletePath,
        shouldReloadRevokedAgent,
        shouldResetSavedToolForm,
        isToolDeleteConflict,
        createToolPublicationLock,
        createLoadRevisionGate,
        createKeyedLoadRevisionGate,
        createSessionEpochGate,
        createSubmitStateGuard,
        formatDateTime,
        statusMeta,
        chatRunOutcome,
        formatSkillLoadState
    };
});
