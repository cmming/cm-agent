const state = {
    token: "",
    agentId: "",
    toolId: ""
};

const $ = (id) => document.getElementById(id);

const statusEl = $("status");
const loginUsernameEl = $("loginUsername");
const loginPasswordEl = $("loginPassword");
const agentOutputEl = $("agentOutput");
const toolOutputEl = $("toolOutput");
const runOutputEl = $("runOutput");
const auditOutputEl = $("auditOutput");

function setStatus(message, tone = "neutral") {
    statusEl.textContent = message;
    statusEl.dataset.tone = tone;
}

function setOutput(element, value) {
    element.textContent = value;
}

async function request(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("Content-Type", "application/json");
    if (state.token) {
        headers.set("Authorization", `Bearer ${state.token}`);
    }

    const response = await fetch(path, {
        ...options,
        headers
    });

    const contentType = response.headers.get("content-type") || "";
    const rawBody = await response.text();
    let body = null;

    if (rawBody) {
        if (contentType.includes("application/json")) {
            try {
                body = JSON.parse(rawBody);
            } catch {
                body = rawBody;
            }
        } else {
            try {
                body = JSON.parse(rawBody);
            } catch {
                body = rawBody;
            }
        }
    }

    if (!response.ok) {
        throw new Error(formatError(response.status, body, rawBody));
    }

    return body;
}

function formatError(status, body, fallbackText) {
    if (body && typeof body === "object") {
        const message = body.message || body.error || body.detail;
        if (message) {
            return `请求失败(${status})：${message}`;
        }
    }

    if (typeof body === "string" && body.trim()) {
        return `请求失败(${status})：${body.trim()}`;
    }

    if (fallbackText && fallbackText.trim()) {
        return `请求失败(${status})：${fallbackText.trim()}`;
    }

    return `请求失败(${status})：服务器未返回可读错误信息`;
}

function requireAgent() {
    if (!state.agentId) {
        throw new Error("请先创建 Agent。");
    }
}

async function login() {
    const username = loginUsernameEl.value.trim();
    const password = loginPasswordEl.value;
    if (!username || !password) {
        setStatus("登录失败", "error");
        setOutput(agentOutputEl, "请输入 bootstrap admin 用户名和密码。");
        return;
    }

    setStatus("正在登录...", "busy");
    try {
        const body = await request("/api/auth/login", {
            method: "POST",
            body: JSON.stringify({
                username,
                password
            })
        });
        state.token = body.accessToken || "";
        setStatus(`已登录：${body.principalId || username}`, "ok");
        setOutput(agentOutputEl, `登录成功：${body.displayName || "系统管理员"}`);
    } catch (error) {
        setStatus("登录失败", "error");
        setOutput(agentOutputEl, error.message);
    }
}

async function createAgent() {
    try {
        const body = await request("/api/agents", {
            method: "POST",
            body: JSON.stringify({
                name: $("agentName").value.trim(),
                systemPrompt: $("systemPrompt").value.trim(),
                modelName: "qwen-max"
            })
        });
        state.agentId = body.id || "";
        setOutput(agentOutputEl, JSON.stringify(body, null, 2));
        setStatus(`Agent 已创建：${state.agentId || "unknown"}`, "ok");
    } catch (error) {
        setOutput(agentOutputEl, error.message);
        setStatus("创建 Agent 失败", "error");
    }
}

async function createTool() {
    try {
        requireAgent();
        const body = await request("/api/tools", {
            method: "POST",
            body: JSON.stringify({
                name: $("toolName").value.trim(),
                description: "控制台创建的本地工具",
                type: "LOCAL",
                riskLevel: "LOW"
            })
        });
        state.toolId = body.id || "";

        const grant = await request(`/api/tools/${encodeURIComponent(state.toolId)}/grants`, {
            method: "POST",
            body: JSON.stringify({
                agentId: state.agentId
            })
        });

        setOutput(toolOutputEl, JSON.stringify({ tool: body, grant }, null, 2));
        setStatus(`Tool 已创建并授权：${state.toolId || "unknown"}`, "ok");
    } catch (error) {
        setOutput(toolOutputEl, error.message);
        setStatus("创建 Tool 失败", "error");
    }
}

async function runAgent() {
    try {
        requireAgent();
        const body = await request(`/api/agents/${encodeURIComponent(state.agentId)}/runs`, {
            method: "POST",
            body: JSON.stringify({
                input: $("runInput").value.trim()
            })
        });
        setOutput(runOutputEl, JSON.stringify(body, null, 2));
        setStatus("运行完成", "ok");
    } catch (error) {
        setOutput(runOutputEl, error.message);
        setStatus("运行失败", "error");
    }
}

async function loadAudit() {
    try {
        const body = await request("/api/audit-events");
        setOutput(auditOutputEl, JSON.stringify(body, null, 2));
        setStatus("审计日志已刷新", "ok");
    } catch (error) {
        setOutput(auditOutputEl, error.message);
        setStatus("刷新审计日志失败", "error");
    }
}

$("loginBtn").addEventListener("click", login);
$("createAgentBtn").addEventListener("click", createAgent);
$("createToolBtn").addEventListener("click", createTool);
$("runBtn").addEventListener("click", runAgent);
$("auditBtn").addEventListener("click", loadAudit);

setStatus("未登录");
