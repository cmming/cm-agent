const test = require("node:test");
const assert = require("node:assert/strict");
const {buildPayload, canSetDefault} = require("../../main/resources/META-INF/resources/console/v2/assets/sandbox-endpoints.js");
const {errorMessage} = require("../../main/resources/META-INF/resources/console/v2/assets/sandbox-endpoints.js");
const core = require("../../main/resources/META-INF/resources/assets/console-core.js");
// 简化DOM只验证安全状态与真实事件编排，布局和原生输入由独立浏览器验收。
function endpointHarness({permissions=["sandbox:read","sandbox:write","sandbox:credential:write"],enabled=true,key=true}={}) {
    class Node {
        constructor(tag){this.tag=tag;this.children=[];this.listeners={};this.dataset={};this.value="";this.className="";}
        append(...nodes){this.children.push(...nodes);}
        replaceChildren(...nodes){this.children=nodes;}
        setAttribute(name,value){this[name]=value;}
        addEventListener(name,handler){this.listeners[name]=handler;}
        all(){return [this,...this.children.flatMap(n=>n.all())];}
        querySelectorAll(selector){return this.all().filter(n=>selector.startsWith(".")?n.className.split(" ").includes(selector.slice(1)):
            selector.startsWith("[name=")?n.name===selector.slice(7,-2):selector.split(",").includes(n.tag));}
        querySelector(selector){return this.querySelectorAll(selector)[0];}
        fire(name){return this.listeners[name]?.({preventDefault(){}});}
        before(node){roots.push(node);}
        after(node){roots.push(node);}
    }
    const root=new Node("section");root.id="skillsPage";
    const skills=new Node("div");skills.className="skills-management-grid";root.append(skills);
    const roots=[root],requests=[];
    const document={createElement:tag=>new Node(tag),getElementById:id=>roots.flatMap(n=>n.all()).find(n=>n.id===id)};
    const endpoint={id:"fixture",displayName:"密码端点",backend:"docker",mode:"SSH",sshAuthType:"PASSWORD",host:"example.invalid",port:22,
        username:"fixture",enabled:true,hasCredential:true,credentialVersion:1,revision:1,probeRevision:0,probeStatus:"NOT_TESTED"};
    const api={request:async(url,options)=>{
        requests.push({url,options});
        if(!options)return {enabled,credentialKeyConfigured:key,items:[endpoint]};
        throw Object.assign(new Error("测试参数拒绝"),{code:"SKILL_SANDBOX_INVALID",errorId:"fixture-error-id"});
    }};
    const page=require("../../main/resources/META-INF/resources/console/v2/assets/sandbox-endpoints.js").createSandboxPage({api,document,getSessionEpoch:()=>1,getPermissions:()=>permissions});
    page.mount();
    return {page,requests,field:name=>document.getElementById("sandbox-"+name),form:()=>document.getElementById("sandboxEndpointForm"),
        status:()=>document.getElementById("sandboxPageStatus"),flush:()=>new Promise(resolve=>setImmediate(resolve))};
}
test("密码只写，提交失败清空材料保留元数据，认证切换不能沿用旧材料",async()=>{
    const flow=endpointHarness();await flow.page.reload();
    assert.equal(flow.field("password").type,"password");assert.equal(flow.field("password").value,"");
    flow.field("replaceCredential").checked=true;flow.field("replaceCredential").fire("change");
    flow.field("displayName").value="保留新名称";flow.field("password").value=" 测试中文口令 ";flow.field("knownHosts").value="测试信任";
    flow.form().fire("submit");await flow.flush();
    const body=JSON.parse(flow.requests.find(r=>r.options)?.options.body);
    assert.equal(body.credentials.password," 测试中文口令 ");assert.equal(flow.field("password").value,"");assert.equal(flow.field("knownHosts").value,"");
    assert.equal(flow.field("displayName").value,"保留新名称");assert.match(flow.status().textContent,/fixture-error-id/);
    flow.field("password").value="切换前测试材料";flow.field("sshAuthType").value="KEY";flow.field("sshAuthType").fire("change");
    assert.equal(flow.field("password").value,"");assert.equal(flow.field("replaceCredential").checked,true);assert.equal(flow.field("replaceCredential").disabled,true);
    flow.field("knownHosts").value="切换前信任";flow.field("mode").value="LOCAL";flow.field("mode").fire("change");assert.equal(flow.field("knownHosts").value,"");
});
test("只读、部署未启用和主密钥未就绪均禁用密码表单写入",async()=>{
    for(const options of [{permissions:["sandbox:read"]},{enabled:false},{key:false}]){
        const flow=endpointHarness(options);await flow.page.reload();
        assert.equal(flow.field("password").disabled,true);assert.equal(flow.field("sshAuthType").disabled,true);
        assert.equal(flow.form().all().find(n=>n.type==="submit").disabled,true);
    }
});
test("SSH认证缺省私钥，密码原样传递且省略材料保留同类型", () => {
    const values={displayName:"密码端点",mode:"SSH",host:"host",port:22,username:"test",enabled:true};
    assert.equal(buildPayload(values,null,null).sshAuthType,"KEY");
    const material={password:" 空格 Unicode测试 ",knownHosts:"测试信任"};
    const password=buildPayload({...values,sshAuthType:"PASSWORD"},null,material);
    assert.equal(password.sshAuthType,"PASSWORD");assert.equal(password.credentials.password,material.password);
    const retained=buildPayload(values,{revision:4,sshAuthType:"PASSWORD"},null);
    assert.equal(retained.sshAuthType,"PASSWORD");assert.equal(Object.hasOwn(retained,"credentials"),false);
});
test("目标拒绝保留具体恢复原因且错误编号不重复", () => {
    const message=core.formatError(403,{code:"SKILL_SANDBOX_TARGET_DENIED",message:"沙箱目标不在部署允许范围",errorId:"test-error-id"},"");
    assert.match(message,/部署允许范围/);assert.match(message,/test-error-id/);
    assert.equal(errorMessage({message,code:"SKILL_SANDBOX_TARGET_DENIED",errorId:"test-error-id"}),message);
    assert.match(errorMessage({message:"缺少权限",code:"ACCESS_DENIED",errorId:"test-error-id"}),/ACCESS_DENIED.*test-error-id/);
});
test("默认端点必须启用且通过当前配置版本测试", () => {
    assert.equal(canSetDefault({enabled:true,revision:2,probeRevision:2,probeStatus:"PASSED"}), true);
    assert.equal(canSetDefault({enabled:true,revision:3,probeRevision:2,probeStatus:"PASSED"}), false);
    assert.equal(canSetDefault({enabled:false,revision:2,probeRevision:2,probeStatus:"PASSED"}), false);
    assert.equal(canSetDefault({enabled:true,revision:2,probeRevision:2,probeStatus:"FAILED"}), false);
});
test("本地模式清除远程输入而省略凭据表示保留", () => {
    const payload=buildPayload({displayName:" 测试 ", mode:"LOCAL", host:"旧主机",port:22,username:"旧用户",enabled:true},{revision:4},{privateKey:"测试私钥"});
    assert.deepEqual(payload,{displayName:"测试",backend:"docker",mode:"LOCAL",host:"",port:0,username:"",enabled:true,revision:4});
    const remote=buildPayload({displayName:"测试",mode:"TLS",host:" host ",port:"2376",username:"旧用户",enabled:false},{revision:4},null);
    assert.equal(remote.username,"");assert.equal(remote.host,"host");assert.equal(remote.port,2376);
    assert.equal(Object.hasOwn(remote,"credentials"),false);
});
test("认证写入是独立副本且不包含租户或内部文件路径", () => {
    const credentials={privateKey:"测试私钥",knownHosts:"测试信任"};
    const payload=buildPayload({displayName:"测试",mode:"SSH",host:"host",port:22,username:"test",enabled:true},null,credentials);
    credentials.privateKey="后来修改";
    assert.equal(payload.credentials.privateKey,"测试私钥");
    assert.equal(Object.hasOwn(payload,"tenantId"),false);
    assert.equal(Object.hasOwn(payload,"privateKeyFile"),false);
});
