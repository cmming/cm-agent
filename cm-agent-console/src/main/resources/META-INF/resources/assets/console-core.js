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
        if (status === 403) {
            return "请求失败(403)：没有权限执行此操作。";
        }
        if (status === 404) {
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
                mcpPublished: Boolean(fields.mcpPublished)
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
            const headers = new Headers(options.headers || {});
            headers.set("Content-Type", "application/json");
            const token = getToken();
            const sessionEpoch = getSessionEpoch();
            if (token) {
                headers.set("Authorization", `Bearer ${token}`);
            }

            // 显式携带同源 Cookie，避免嵌入式浏览器对 fetch 默认凭据策略的实现差异破坏 v2 跨页会话。
            const response = await fetchImpl(path, {...options, headers, credentials: "same-origin"});
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

    function statusMeta(status) {
        const values = {
            SUCCEEDED: {label: "成功", tone: "success"},
            RUNNING: {label: "运行中", tone: "warning"},
            FAILED: {label: "失败", tone: "error"},
            DENIED: {label: "已拒绝", tone: "error"}
        };
        return values[status] || {label: status || "未知", tone: "neutral"};
    }

    return {
        formatError,
        createApiClient,
        parseSseFrame,
        appendCursorPage,
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
        statusMeta
    };
});
