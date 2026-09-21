package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.runtime.SkillAccessGateway;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 为一次 AgentScope 运行创建隔离的原生技能目录和加载工具。
 *
 * <p>每次运行都会新建 SkillBox，并仅注册领域请求已固定的历史版本。禁用自动上传和代码执行后，
 * 原生 SkillBox 只能在内存中组织技能元数据；加载工具注册完成后会立即被
 * {@link AgentScopeSkillLoadBridge} 替换，因此模型的每一次正文读取仍会进入 CM Agent 治理网关。</p>
 */
final class AgentScopeSkillSession implements AutoCloseable {
    private String directoryPrompt;

    /**
     * 把固定技能快照注册到本次 Toolkit，并安装受治理的加载桥接器。
     *
     * @param request 当前运行的可信技能快照
     * @param toolkit 已注册业务工具的本次运行 Toolkit
     * @param runGate 当前运行共享的中止门控
     * @param gateway 技能读取治理入口
     */
    AgentScopeSkillSession(
            AgentRunRequest request,
            Toolkit toolkit,
            AgentScopeRunGate runGate,
            SkillAccessGateway gateway
    ) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(toolkit, "toolkit 不能为空");
        if (request.skills().isEmpty()) {
            this.directoryPrompt = "";
            return;
        }
        if (toolkit.getTool(AgentScopeSkillLoadBridge.TOOL_NAME) != null) {
            // 业务工具不能伪装为技能加载器；否则替换时可能绕过既有工具治理或改变其语义。
            throw new IllegalArgumentException("业务工具名称不能使用 load_skill_through_path");
        }
        AgentScopeSkillRepository repository = new AgentScopeSkillRepository(request.skills());
        SkillBox skillBox = new SkillBox(toolkit);
        skillBox.setAutoUploadSkill(false);
        skillBox.setExposeAllSkillMetadata(false);
        skillBox.getSkillPromptProvider().setCodeExecutionEnable(false);
        repository.getAllSkills().forEach(skillBox::registerSkill);
        skillBox.registerSkillLoadTool();
        AgentTool nativeTool = Objects.requireNonNull(toolkit.getTool(AgentScopeSkillLoadBridge.TOOL_NAME),
                "AgentScope 未注册技能加载工具");
        Map<String, com.cmagent.core.domain.SkillVersionView> skillsByNativeId = new LinkedHashMap<>();
        for (com.cmagent.core.domain.SkillVersionView view : request.skills()) {
            AgentSkill nativeSkill = Objects.requireNonNull(repository.getSkill(view.definition().name()),
                    "技能未注册到 AgentScope");
            if (skillsByNativeId.putIfAbsent(nativeSkill.getSkillId(), view) != null) {
                throw new IllegalArgumentException("AgentScope 技能标识重复");
            }
        }
        toolkit.removeTool(AgentScopeSkillLoadBridge.TOOL_NAME);
        toolkit.registerAgentTool(new AgentScopeSkillLoadBridge(
                request, runGate, gateway, nativeTool, skillsByNativeId));
        this.directoryPrompt = skillBox.getSkillPrompt();
    }

    /**
     * @return 仅包含已固定技能名称和描述的系统提示目录，不包含技能正文
     */
    String directoryPrompt() {
        return directoryPrompt;
    }

    /**
     * 清除运行内目录摘要，避免执行器复用或异常栈长期持有技能名称、描述等快照元数据。
     *
     * <p>本会话从不创建目录、文件、线程或网络连接，因此关闭操作不触碰宿主环境；原生 Toolkit 仍由
     * {@link io.agentscope.core.ReActAgent} 生命周期统一持有和关闭。</p>
     */
    @Override
    public void close() {
        directoryPrompt = "";
    }
}
