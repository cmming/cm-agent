"use strict";
const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const core = require("../../main/resources/META-INF/resources/assets/console-core.js");

// 执行真实审批控制器，以SSE终态驱动页面；不因完整拒绝没有assistant正文而保留等待提示。
function harness(events, refresh = async () => {}) {
    const app = fs.readFileSync(path.join(__dirname, "../../main/resources/META-INF/resources/assets/app.js"), "utf8");
    const source = app.slice(app.indexOf("async function submitApprovalDecision("), app.indexOf("// 此操作只查询 GET"));
    const state = {selectedAgentId:"agent", selectedConversationId:"conversation", approvalSubmissions:new Set(),
        approvalFeedback:new Map(), pendingApprovals:new Map()};
    const statuses = [], requests = [];
    const approval = {approvalId:"approval", agentId:"agent", conversationId:"conversation", runId:"run",
        status:"PENDING", canDecide:true, version:1, items:[{itemId:"item"}]};
    const context = {state, core, sessionEpoch:{capture:()=>1,isCurrent:()=>true},
        collectApprovalChoices:()=>[{itemId:"item",decision:"DENY"}],
        renderChatApprovals(){}, renderMarkdown(){}, renderChatProgress(){},
        $:id=>({id,focus(){}}), appendChatStreamingMessage:()=>({output:{},article:{remove(){}}}),
        setStatus:(node,message,tone)=>statuses.push({id:node.id,message,tone}),
        setActiveChatRun:row=>{state.activeChatRun=row;}, approvalDrafts:{remove(){}},
        loadConversations:async()=>{}, refreshApprovalState:()=>refresh(state),
        streamEventError:data=>new Error(data.message),
        api:{stream:async(url,options,accept)=>{requests.push({url,options});await events(accept);}}};
    const outcome = app.slice(app.indexOf("function showChatRunOutcome("), app.indexOf("function createChatRunFailure("));
    context.$ = id => id === "chatMessageList" ? null : ({id,focus(){}});
    vm.runInNewContext(`${outcome}\n${source};globalThis.submit=submitApprovalDecision;`,context);
    return {state,statuses,requests,submit:()=>context.submit(approval,{})};
}

test("完整拒绝的SSE终态清除旧暂停提示并保留一次写入",async()=>{
    const flow=harness(async accept=>{
        accept({type:"approval-decision",data:{status:"DENIED"}});
        accept({type:"completed",data:{run:{runId:"run",status:"DENIED"}}});
    });
    await flow.submit();
    assert.equal(flow.requests.length,1);
    assert.equal(flow.statuses.at(-1)?.id,"chatFormStatus");
    assert.match(flow.statuses.at(-1)?.message||"",/已拒绝/);
    assert.doesNotMatch(flow.statuses.at(-1).message,/暂停|成功/);
    assert.equal(flow.statuses.at(-1).tone,"neutral");
});

test("新审批仍提示等待而不会按上一终态解锁为成功",async()=>{
    const flow=harness(async accept=>accept({type:"approval-required",data:{approvalId:"next"}}));
    await flow.submit();
    assert.match(flow.statuses.at(-1)?.message||"",/新.*审批/);
    assert.equal(flow.statuses.at(-1).tone,"neutral");
});

test("已停止失败和成功按服务端终态显示且失败保留安全原因和运行编号",async()=>{
    for(const [status,pattern,tone] of [["CANCELLED",/已停止/,"neutral"],["FAILED",/上游模型超时.*run/,"error"],["SUCCEEDED",/回答已完成/,"success"]]){
        const flow=harness(async accept=>accept({type:"completed",data:{run:{runId:"run",status,errorMessage:"上游模型超时"}}}));
        await flow.submit();
        assert.match(flow.statuses.at(-1)?.message||"",pattern);
        assert.equal(flow.statuses.at(-1).tone,tone);
    }
});

test("刷新期间切换会话不会把旧审批终态写到新页面",async()=>{
    const flow=harness(async accept=>accept({type:"completed",data:{run:{status:"DENIED"}}}),async state=>{state.selectedConversationId="another";});
    await flow.submit();
    assert.equal(flow.statuses.length,0);
});

test("在途重复提交不重放审批写入",async()=>{
    let finish;
    const flow=harness(accept=>new Promise(resolve=>{finish=()=>{accept({type:"completed",data:{run:{status:"DENIED"}}});resolve();};}));
    const first=flow.submit();
    await flow.submit();
    assert.equal(flow.requests.length,1);
    finish();await first;
    assert.equal(flow.state.approvalSubmissions.size,0);
});
