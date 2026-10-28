package com.cmagent.server.runtime;

import com.cmagent.server.CmAgentServerApplication;
import org.springframework.boot.test.context.SpringBootTest;

/** 同一批次/冻结/恢复/停止合同在真实ReAct适配器上执行，回环协议桩不代表真实Provider验收。 */
@SpringBootTest(classes=CmAgentServerApplication.class,properties={
        "cm-agent.fake-runtime-enabled=false","cm-agent.agentscope.enabled=true","cm-agent.agentscope.execution-mode=react",
        "cm-agent.agentscope.studio.enabled=false","cm-agent.agentscope.permission-enabled=true","cm-agent.approval-expiry.enabled=false",
        "cm-agent.agentscope.model-max-attempts=1","cm-agent.agentscope.model-timeout=10s","cm-agent.agentscope.tool-timeout=10s",
        "cm-agent.workspace-context.enabled=true","cm-agent.workspace-context.imports.enabled=true"})
class ReActImportedWorkspaceFlowTest extends HarnessImportedWorkspaceFlowTest{}
