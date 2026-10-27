"use strict";
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

// 仅用于本机界面验收：提供虚构会话和模拟协议，不连接真实服务、模型或工具，不用于生产。
const root = path.resolve(__dirname, "../../main/resources/META-INF/resources");
const now = new Date();
const conversations = [
    {id:"failed",title:"运行失败且无回答 · 错误反馈验收",updatedAt:now.toISOString()},
    {id:"long",title:"Java 项目说明与生成文件 · 长回答阅读验收",updatedAt:now.toISOString()},
    {id:"approval",title:"高风险操作等待审批 · 只读权限验收",updatedAt:now.toISOString()},
    {id:"history-error",title:"审批历史读取失败 · 错误编号验收",updatedAt:new Date(now-86400000).toISOString()},
    {id:"unknown",title:"发送结果未知 · 防止重复写入验收",updatedAt:"2026-09-30T06:00:00Z"},
    {id:"empty",title:"空会话 · 输入快捷键验收",updatedAt:"2026-09-01T06:00:00Z"}
];
const longText = "# 项目说明\n\n以下内容全部为界面验收模拟数据。\n\n" + Array.from({length:16}, (_,i) =>
    `## 第 ${i+1} 项设计说明\n\n保持会话上下文，按权限执行工具。长回答应允许向上阅读，输出更新不能打断当前位置。\n\n- 配置模型与 Agent\n- 核对工具授权\n- 读取当前运行状态\n`).join("\n")
    + "\n```java\npublic class Demo {\n    public static void main(String[] args) {\n        System.out.println(\"界面验收模拟示例\");\n    }\n}\n```\n\n| 项目 | 状态 |\n|---|---|\n| 会话 | 已完成 |\n| 工具 | 按权限 |";
const messages = new Map(conversations.map(item => [item.id, item.id === "empty" ? [] : [
    {id:item.id+"-user",sequence:1,role:"USER",runId:item.id+"-run",contentBlocks:[{type:"TEXT",text:"请说明项目结构与使用流程。"}]},
    {id:item.id+"-answer",sequence:2,role:"ASSISTANT",senderName:"演示 Agent",runId:item.id+"-run",contentBlocks:[
        {type:"THINKING",text:"这是模拟执行摘要，不属于复制正文。"},
        {type:"TEXT",text:item.id === "long" ? longText : "这是界面验收模拟回答，不调用模型或工具。"}]}]]));
const active = new Map();
const failures = new Map([["failed-run", {id:"failed-run",status:"FAILED",errorMessage:"Agent 运行失败"}]]);
messages.set("failed", [messages.get("failed")[0]]);
const requests = [];
let serial = 0;
const approval = {approvalId:"readonly",agentId:"demo",conversationId:"approval",runId:"approval-run",version:1,status:"PENDING",canDecide:false,
    requestedByDisplayName:"模拟发起人",expiresAt:new Date(now.getTime()+86400000).toISOString(),
    items:[{itemId:"item",toolId:"tool",toolCallId:"call",toolName:"模拟导出工具",inputSummary:"{\"scope\":\"demo\"}"}]};
function json(response, value, status = 200) {
    response.writeHead(status, {"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store"});
    response.end(JSON.stringify(value));
}
const server = http.createServer(async (request, response) => {
    const url = new URL(request.url, "http://localhost");
    if (url.pathname.startsWith("/api/")) requests.push({method:request.method,path:url.pathname});
    if (url.pathname === "/__validation/state") return json(response, {requests});
    if (url.pathname === "/api/auth/me") return json(response, {displayName:"界面验收 · 模拟数据",permissions:["agent:read","agent:run"]});
    if (url.pathname === "/api/agents") return json(response, [{id:"demo",name:"演示 Agent（模拟数据）",enabled:true},{id:"second",name:"第二 Agent（模拟数据）",enabled:true}]);
    const match = url.pathname.match(/^\/api\/agents\/([^/]+)\/conversations(?:\/([^/]+)(.*))?$/);
    if (match) {
        const [,agent,id,tail=""] = match;
        if (!id) {
            if (request.method === "POST") return json(response, {id:"empty"});
            return json(response, {items:agent === "demo" ? conversations : [conversations.at(-1)]});
        }
        if (tail === "/active-run") return json(response, id === "approval" ? {id:"approval-run",status:"WAITING_APPROVAL"} : active.get(id) || null);
        if (tail === "/approvals") return json(response, {items:id === "approval" ? [approval] : []});
        if (tail === "/approvals/history") return id === "history-error"
            ? json(response, {code:"PERSISTENCE_UNAVAILABLE",message:"审批历史暂时不可用，请重试。",errorId:"ui-history-failure"},503)
            : json(response, {items:[],nextCursor:null});
        if (tail === "/messages") return json(response, {items:messages.get(id)||[],runStatuses:Object.fromEntries((messages.get(id)||[]).map(message=>[message.runId,failures.has(message.runId)?"FAILED":"SUCCEEDED"]))});
        if (tail === "/messages/stream") {
            if (id === "unknown") return json(response, {code:"UPSTREAM_UNAVAILABLE",message:"发送结果暂时无法确认。",errorId:"ui-send-unknown"},503);
            let body=""; for await(const chunk of request) body+=chunk;
            const input=JSON.parse(body).input;
            if (input === "拒绝") return json(response, {code:"VALIDATION_FAILED",message:"模拟参数校验未通过。",errorId:"ui-rejected"},400);
            const runId="stream-"+(++serial), rows=messages.get(id)||[];
            messages.set(id, rows);
            rows.push({id:runId+"-user",sequence:rows.length+1,role:"USER",runId,contentBlocks:[{type:"TEXT",text:input}]});
            active.set(id,{id:runId,status:"RUNNING"});
            response.writeHead(200,{"Content-Type":"text/event-stream; charset=utf-8","Cache-Control":"no-store"});
            const event=(type,data)=>response.write(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`);
            event("started",{runId});
            if (input === "模拟失败") {
                const run={runId,status:"FAILED",output:"",toolCalls:[],errorMessage:"Agent 运行失败"};
                failures.set(runId,{...run,id:runId});active.delete(id);
                event("completed",{conversationId:id,userMessageId:runId+"-user",assistantMessageId:null,run,assistantMessage:null});
                response.end();return;
            }
            let text="", index=0;
            const timer=setInterval(()=>{
                const delta=`第 ${++index} 段模拟输出：允许继续阅读历史，界面不会重放写请求。\n\n`;
                text+=delta; event("delta",{delta});
                if(index===40){clearInterval(timer);active.delete(id);rows.push({id:runId+"-answer",sequence:rows.length+1,role:"ASSISTANT",runId,contentBlocks:[{type:"TEXT",text}]});event("completed",{run:{runId,status:"SUCCEEDED"}});response.end();}
            },150);
            response.on("close",()=>{clearInterval(timer);active.delete(id);});
            return;
        }
        if(tail.startsWith("/runs/")) return json(response, failures.get(tail.split("/").at(-1))||active.get(id)||{id:tail.split("/").at(-1),status:"SUCCEEDED"});
    }
    if (url.pathname.endsWith("/artifacts")) return json(response, {status:url.pathname.includes("long-run") ? "AVAILABLE" : "EMPTY",files:url.pathname.includes("long-run") ?
        [{id:"demo-file",status:"READY",filename:"项目说明.docx",mediaType:"application/vnd.openxmlformats-officedocument.wordprocessingml.document",sizeBytes:4780,expiresAt:new Date(now.getTime()+86400000).toISOString()}] : []});
    if (url.pathname.startsWith("/api/")) return json(response, {items:[]});
    const target=path.resolve(root,"."+decodeURIComponent(url.pathname));
    // 只服务控制台资源目录；目录穿越和非文件路径失败关闭，验收服务仅绑定回环地址。
    if(!target.startsWith(root+path.sep)||!fs.existsSync(target)||!fs.statSync(target).isFile()){response.writeHead(404);return response.end();}
    const mime={".html":"text/html", ".js":"text/javascript", ".css":"text/css", ".svg":"image/svg+xml"}[path.extname(target)]||"application/octet-stream";
    response.writeHead(200,{"Content-Type":mime+"; charset=utf-8","Cache-Control":"no-store"});fs.createReadStream(target).pipe(response);
});
server.listen(Number(process.env.CM_AGENT_CHAT_UX_PORT||18081),"127.0.0.1",()=>process.stdout.write("聊天界面模拟验收服务已启动，端口 "+server.address().port+"\n"));
