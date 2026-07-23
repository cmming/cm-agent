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
        if (status >= 500) {
            return `请求失败(${status})：服务暂时不可用，请稍后重试。`;
        }
        const structuredMessage = body && typeof body === "object"
            ? body.message || body.error || body.detail
            : "";
        const readableMessage = structuredMessage
            || (typeof body === "string" && body.trim())
            || (fallbackText && fallbackText.trim());
        return readableMessage
            ? `请求失败(${status})：${readableMessage}`
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
        if (!tool || (tool.type !== "HTTP" && tool.type !== "LOCAL")) {
            return false;
        }
        return tool.riskLevel !== "HIGH" || confirmedToolName === tool.name;
    }

    function buildHttpToolPayload(fields) {
        const inputSchema = parseJsonField(fields.inputSchemaText, "输入 Schema");
        const parameterMappings = parseJsonField(fields.parameterMappingsText, "参数映射");
        const secretHeaders = parseJsonField(fields.secretHeadersText, "Secret 引用");
        const timeoutMillis = Number(fields.timeoutMillis);
        if (!Array.isArray(parameterMappings)) {
            throw new Error("参数映射必须是 JSON 数组。");
        }
        if (!secretHeaders || Array.isArray(secretHeaders) || typeof secretHeaders !== "object"
                || Object.values(secretHeaders).some((reference) => typeof reference !== "string"
                || !/^secret\/[A-Za-z0-9][A-Za-z0-9._-]*(?:\/[A-Za-z0-9][A-Za-z0-9._-]*)*$/.test(reference))) {
            throw new Error("Secret 引用必须是键值均为引用标识的 JSON 对象。");
        }
        if (!Number.isInteger(timeoutMillis) || timeoutMillis < 100 || timeoutMillis > 30000) {
            throw new Error("超时时间必须是 100 到 30000 毫秒之间的整数。");
        }
        return {
            name: String(fields.name || "").trim(),
            description: String(fields.description || "").trim(),
            type: "HTTP",
            riskLevel: fields.riskLevel,
            mcpPublished: Boolean(fields.mcpPublished),
            httpConfig: {
                method: fields.method,
                urlTemplate: String(fields.urlTemplate || "").trim(),
                inputSchema,
                parameterMappings,
                secretHeaders,
                timeoutMillis
            }
        };
    }

    function createApiClient({fetchImpl, getToken, onUnauthorized}) {
        if (typeof fetchImpl !== "function" || typeof getToken !== "function" || typeof onUnauthorized !== "function") {
            throw new TypeError("请求客户端依赖不完整");
        }

        return {request};

        async function request(path, options = {}) {
            const headers = new Headers(options.headers || {});
            headers.set("Content-Type", "application/json");
            const token = getToken();
            if (token) {
                headers.set("Authorization", `Bearer ${token}`);
            }

            const response = await fetchImpl(path, {...options, headers});
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
                    onUnauthorized();
                    throw new Error("未登录或令牌已失效，请重新登录。");
                }
                throw new Error(formatError(response.status, body, rawBody));
            }
            return body;
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
        appendCursorPage,
        buildCursorPath,
        parseJsonField,
        canDebugTool,
        buildHttpToolPayload,
        formatDateTime,
        statusMeta
    };
});
