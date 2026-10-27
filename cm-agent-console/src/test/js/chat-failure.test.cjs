"use strict";
const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const core = require("../../main/resources/META-INF/resources/assets/console-core.js");
const app = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");

// 执行真实渲染和发送函数，仅替换网络及浏览器宿主；模拟错误不会调用模型或工具。
function harness({stream, request, refresh} = {}) {
    const statuses = [], requests = [];
    const state = {selectedAgentId:"agent",selectedConversationId:"conversation",chatBusy:false,chatLoading:false,
        pendingApprovals:new Map(),approvalSubmissions:new Set(),approvalHistory:[],chatRunStatuses:{},conversations:[]};
    let epoch = 0;
    function element(tag, props = {}) {
        const node = {tag,children:[],dataset:{},isConnected:true,hidden:false,disabled:false,textContent:props.text || "",
            className:props.className || "",value:"",setAttribute(name,value){this[name]=value;},
            append(...items){this.children.push(...items);},replaceChildren(...items){this.children=items;},
            querySelector(selector){return this.children.find(child=>child.tag===selector)||null;},
            querySelectorAll(){return this.children.filter(child=>child.className==="chat-run-failure");},
            addEventListener(type,listener){this[type]=listener;},remove(){this.isConnected=false;}};
        return node;
    }
    const nodes = {chatMessageList:element("div"),chatFormStatus:element("p"),chatInput:element("textarea"),
        chatAgentSelect:{value:"agent"},chatSendBtn:element("button"),chatConversationTitle:element("h2")};
    nodes.chatInput.value="模拟问题";
    const context = {state,core,element,$:id=>nodes[id],sessionEpoch:{capture:()=>epoch,isCurrent:value=>value===epoch},
        setStatus:(node,message,tone)=>statuses.push({message,tone}),scrollChatToBottom(){},renderChatProgress(){},renderMarkdown(){},
        chatUx:{enabled:()=>true,isUnknown:()=>false,beginSubmission(){},acceptSubmission(){},failSubmission(){},captureReading:()=>null},
        createChatMessage:()=>element("article"),appendChatStreamingMessage:()=>({output:element("div")}),
        ensureConversation:async()=>"conversation",setChatBusy:busy=>{state.chatBusy=busy;},
        withSubmitState:async(_,action)=>action(),setActiveChatRun:run=>{state.activeChatRun=run;},
        loadConversations:async()=>{if(refresh) await refresh(state);},loadChatMessages:async()=>{state.activeChatRun=null;return true;},
        api:{request:async(url,options)=>{requests.push({url,options});return request?.(url,options);},
            stream:async(url,options,emit)=>{requests.push({url,options});await stream?.(emit);}}};
    const renderer = app.slice(app.indexOf("function renderChatMessages("), app.indexOf("function renderChatWelcome("));
    const sender = app.slice(app.indexOf("async function sendChatMessage("), app.indexOf("// 轮询只查询权威状态"));
    const streamError = app.slice(app.indexOf("function streamEventError("), app.indexOf("function shortIdentifier("));
    vm.runInNewContext(`${streamError}\n${renderer}\n${sender};globalThis.actions={renderChatMessages,createChatRunFailure,showChatRunOutcome,sendChatMessage};`,context);
    return {state,nodes,statuses,requests,actions:context.actions,logout:()=>epoch++};
}

test("completed失败且assistant为空时呈现错误原因和运行编号，不提示回答完成",async()=>{
    const flow=harness({stream:async emit=>{
        emit({type:"started",data:{runId:"run"}});
        emit({type:"completed",data:{assistantMessage:null,assistantMessageId:null,
            run:{runId:"run",status:"FAILED",output:"",errorMessage:"Agent 运行失败"}}});
    }});
    await flow.actions.sendChatMessage();
    const status=flow.statuses.at(-1);
    assert.equal(status.tone,"error");assert.match(status.message,/Agent 运行失败.*运行编号：run/);
    assert.doesNotMatch(status.message,/回答已完成/);
    assert.equal(flow.nodes.chatMessageList.querySelectorAll().length,1);
    assert.equal(flow.requests.length,1);assert.equal(flow.state.chatBusy,false);
});

test("成功、拒绝、停止与缺失终态不会混淆",()=>{
    for(const [status,tone] of [["SUCCEEDED","success"],["DENIED","neutral"],["CANCELLED","neutral"],[undefined,"neutral"],["RUNNING","neutral"]]) {
        assert.equal(core.chatRunOutcome({status}).tone,tone);
    }
    assert.match(core.chatRunOutcome({status:"FAILED",errorMessage:"  "}).message,/未生成完整回答/);
});

test("失败提示沿用服务端错误码和错误编号，不生成虚假诊断编号",()=>{
    const result=core.chatRunOutcome({status:"FAILED",id:"run",errorCode:"MODEL_TIMEOUT",errorId:"safe-error",errorMessage:"模型超时"});
    assert.match(result.message,/模型超时.*MODEL_TIMEOUT.*safe-error.*运行编号：run/);
    assert.doesNotMatch(core.chatRunOutcome({status:"FAILED",runId:"run"}).message,/错误码|错误编号/);
});

test("刷新只有用户消息的失败轮次仍显示提示，重复消息不重复反馈且部分回答保留",()=>{
    const flow=harness();flow.state.chatRunStatuses={failed:"FAILED",partial:"FAILED",ok:"SUCCEEDED"};
    flow.actions.renderChatMessages([{role:"USER",runId:"failed"},{role:"USER",runId:"partial"},
        {role:"ASSISTANT",runId:"partial"},{role:"ASSISTANT",runId:"ok"}]);
    assert.equal(flow.nodes.chatMessageList.children.length,6);
    assert.equal(flow.nodes.chatMessageList.querySelectorAll().length,2);
});

test("历史失败原因只按需GET，文本作为纯文本渲染且不会重放运行",async()=>{
    const reason='<img src=x onerror="alert(1)">';
    const flow=harness({request:async()=>({id:"run",status:"FAILED",errorMessage:reason})});
    const note=flow.actions.createChatRunFailure("run"),button=note.querySelector("button");
    assert.equal(flow.requests.length,0);await button.click();
    assert.equal(flow.requests.length,1);assert.equal(flow.requests[0].options,undefined);
    assert.match(flow.requests[0].url,/\/conversations\/conversation\/runs\/run$/);
    assert.ok(note.querySelector("p").textContent.includes(reason));assert.equal(button.hidden,true);
    assert.equal(note.querySelector("img"),null);
});

test("详情读取失败保留失败状态及接口错误编号并允许只读重试",async()=>{
    const flow=harness({request:async()=>{throw new Error("请求失败(503)：详情暂不可用（错误编号：detail-error）");}});
    const note=flow.actions.createChatRunFailure("run"),button=note.querySelector("button");
    await button.click();
    assert.match(note.querySelector("p").textContent,/本轮运行失败.*run.*detail-error/);
    assert.equal(button.disabled,false);assert.equal(button.textContent,"重新读取原因");
    assert.equal(flow.requests.length,1);
});

test("切换会话、退出与节点移除后迟到详情不改写页面",async()=>{
    for(const change of [flow=>{flow.state.selectedConversationId="other";},flow=>flow.logout(),(_,note)=>{note.isConnected=false;}]) {
        let finish;const flow=harness({request:()=>new Promise(resolve=>{finish=resolve;})});
        const note=flow.actions.createChatRunFailure("run"),before=note.querySelector("p").textContent;
        const pending=note.querySelector("button").click();change(flow,note);
        finish({status:"FAILED",errorMessage:"迟到原因"});await pending;
        assert.equal(note.querySelector("p").textContent,before);
    }
});

test("刷新期间切换会话不会写入旧发送结果",async()=>{
    const flow=harness({stream:async emit=>emit({type:"completed",data:{run:{runId:"run",status:"FAILED"}}}),
        refresh:async state=>{state.selectedConversationId="other";}});
    await flow.actions.sendChatMessage();assert.equal(flow.statuses.length,1);
});

test("SSE错误事件保留服务端原因、错误码与编号且只发送一次",async()=>{
    const flow=harness({stream:async emit=>emit({type:"error",data:{message:"模型暂时不可用",code:"MODEL_UNAVAILABLE",errorId:"stream-error"}})});
    await flow.actions.sendChatMessage();
    assert.equal(flow.statuses.at(-1).tone,"error");
    assert.match(flow.statuses.at(-1).message,/模型暂时不可用.*MODEL_UNAVAILABLE.*stream-error/);
    assert.equal(flow.requests.length,1);
});
