"use strict";

// 独立验收脚本，不是生产启动器：随机凭据只存在于内存，真实登录和治理不替换，最终关闭浏览器及 fixture。
const {chromium} = require("playwright");
const {spawn} = require("node:child_process");
const {randomUUID} = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const root = path.resolve(__dirname, "..");
const base = "http://127.0.0.1:18094";
const out = path.join(root, "output", "playwright");
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));

async function main() {
    try { await fetch(`${base}/actuator/health`); throw new Error("验收端口已有服务，不能覆盖或停止它"); }
    catch (error) { if (error.message.includes("已有服务")) throw error; }
    fs.mkdirSync(out, {recursive: true});
    const modules = ["cm-agent-server", "cm-agent-core", "cm-agent-api", "cm-agent-persistence",
        "cm-agent-agentscope-adapter", "cm-agent-spring-boot-starter", "cm-agent-console"];
    const classpath = [path.join(root, "cm-agent-console/src/main/resources"), path.join(root, "cm-agent-server/target/test-classes"), ...modules.map(module => path.join(root, module, "target/classes")),
        fs.readFileSync(path.join(root, "cm-agent-server/target/chat-stop-classpath.txt"), "utf8").trim()].join(path.delimiter);
    const password = randomUUID();
    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java.exe") : "java";
    const server = spawn(java, ["-cp", classpath, "com.cmagent.server.runtime.ChatStopBrowserFixture"], {
        cwd: root, windowsHide: true, env: {...process.env, CM_AGENT_FIXTURE_PASSWORD: password}, stdio: ["ignore", "pipe", "pipe"]
    });
    const log = fs.createWriteStream(path.join(out, "chat-stop-fixture.log"));
    server.stdout.pipe(log); server.stderr.pipe(log);
    let browser;
    try {
        let bootReady = false;
        for (let attempt = 0; attempt < 90 && server.exitCode === null; attempt++) {
            try { bootReady = (await fetch(`${base}/actuator/health`)).ok; } catch { }
            if (bootReady) break;
            await delay(500);
        }
        assert.ok(bootReady, "受控 fixture 未就绪，查看本地验收日志");
        browser = await chromium.launch({channel: "msedge", headless: true});
        const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
        const failures = [];
        page.on("pageerror", error => failures.push(error.message));
        await page.goto(`${base}/console/v2/login.html`);
        await page.locator("#loginUsername").fill("fixture-admin");
        await page.locator("#loginPassword").fill(password);
        await page.locator("#loginBtn").click();
        await page.waitForURL("**/console/v2/overview.html");
        await page.goto(`${base}/console/v2/chat.html`);
        await page.locator("#chatAgentSelect option").first().waitFor({state: "attached"});
        await page.waitForFunction(() => !document.getElementById("chatInput").disabled);
        await page.screenshot({path: path.join(out, "chat-stop-ready-desktop.png")});

        async function send(input) {
            await page.locator("#chatInput").fill(input);
            await page.locator("#chatSendBtn").click();
            await page.locator("#chatStopBtn").waitFor({state: "visible"});
        }
        async function stop(expected = 202) {
            const response = page.waitForResponse(response => response.url().endsWith("/cancel") && response.request().method() === "POST");
            await page.locator("#chatStopBtn").click();
            const result = await response;
            assert.equal(result.status(), expected);
            const row = await result.json();
            assert.equal(row.status, expected === 202 ? "CANCELLING" : "CANCELLED");
            if (expected === 202) assert.equal(await page.locator("#chatInput").isDisabled(), true);
            return row;
        }
        async function ready() {
            await page.waitForFunction(() => !document.getElementById("chatInput").disabled, {timeout: 10000});
        }
        async function newConversation() { await ready(); await page.locator("#newChatConversationTopBtn").click(); await ready(); }

        await send("停止本轮浏览器验收");
        await page.waitForFunction(() => document.getElementById("chatMessageList").textContent.includes("已经输出的正文"));
        await stop();
        await page.screenshot({path: path.join(out, "chat-stop-cancelling-desktop.png")});
        await ready();
        assert.ok((await page.locator("#chatMessageList").innerText()).includes("回答可能不完整"));
        await page.screenshot({path: path.join(out, "chat-stop-cancelled-desktop.png")});
        await page.setViewportSize({width: 390, height: 844});
        await page.locator("#chatInput").scrollIntoViewIfNeeded();
        await page.screenshot({path: path.join(out, "chat-stop-cancelled-mobile.png")});
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
        assert.equal(await page.locator("#chatRefreshRunBtn").evaluate(element => Math.round(element.getBoundingClientRect().width)), 40);
        await page.setViewportSize({width: 1440, height: 1000});

        // SSE 观察者断开不停止后台；刷新必须通过 GET 恢复原轮次，而不是再次发送消息。
        await send("刷新中保持运行");
        await page.reload();
        await page.locator("#chatStopBtn").waitFor({state: "visible"});
        assert.equal(await page.locator("#chatSendBtn").isDisabled(), true);
        await stop(); await ready();
        await page.locator("#chatInput").fill("快速结束新一轮");
        await page.locator("#chatSendBtn").click();
        await page.waitForFunction(() => document.getElementById("chatMessageList").textContent.includes("新一轮已经正常完成"));
        await ready();

        await newConversation();
        await send("等待审批");
        await page.locator("#chatApprovalRegion").waitFor({state: "visible"});
        await stop(200); await ready();
        assert.ok((await page.locator("#chatMessageList").innerText()).includes("本轮已停止"));
        const history = page.locator(".chat-approval-history-run").first();
        if (await history.count()) await history.evaluate(element => { element.open = true; });
        assert.ok((await page.locator("body").innerText()).includes("已取消"));
        await page.screenshot({path: path.join(out, "chat-stop-approval-cancelled.png")});

        await newConversation(); await send("等待审批");
        const form = page.locator("#chatApprovalList form");
        await form.locator('input[value="APPROVE"]').check();
        await form.locator('button[type="submit"]').click();
        await page.waitForFunction(() => document.getElementById("chatMessageList").textContent.includes("已经输出的正文"));
        await stop(); await ready();
        assert.ok((await page.locator("#chatMessageList").innerText()).includes("回答可能不完整"));
        await page.screenshot({path: path.join(out, "chat-stop-approved-resume.png")});

        await newConversation(); await send("停止请求网络失败");
        let attempted = 0;
        await page.route("**/cancel", route => { attempted++; return route.abort(); });
        await page.locator("#chatStopBtn").click();
        await page.waitForFunction(() => document.getElementById("chatFormStatus").textContent.includes("不会自动重试停止"));
        await delay(1400);
        assert.equal(attempted, 1);
        assert.equal(await page.locator("#chatInput").isDisabled(), true);
        await page.screenshot({path: path.join(out, "chat-stop-network-failure.png")});
        await page.unroute("**/cancel");
        await page.locator("#chatRefreshRunBtn").click();
        await page.waitForFunction(() => !document.getElementById("chatStopBtn").disabled);
        await stop(); await ready();
        assert.deepEqual(failures, []);
        console.log("浏览器通过：停止中/已停止、部分正文、刷新恢复、新消息、待审批取消、批准后停止、网络失败无重放、390px 无横向溢出。");
    } finally {
        if (browser) await browser.close();
        server.kill();
        await new Promise(resolve => server.exitCode !== null ? resolve() : server.once("exit", resolve));
        log.end();
    }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
