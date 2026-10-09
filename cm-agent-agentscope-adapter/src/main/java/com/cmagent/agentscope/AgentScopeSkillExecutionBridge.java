package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.SkillVersionView;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.runtime.SkillAccessGateway;
import com.cmagent.core.runtime.SkillReadRequest;
import com.cmagent.core.runtime.SkillReadResult;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * 将模型脚本请求解析为当前 Run 的固定资源，通过服务端网关执行；不启用原生宿主执行工具。
 * 每个请求复用业务工具的串行、中止及致命失败门控，保证超时后的迟到输出不再交付模型。
 */
final class AgentScopeSkillExecutionBridge implements AgentTool {
    /** 内部保留名称，业务工具不能占用。 */
    static final String TOOL_NAME = "run_skill_script";
    /** 当前 Run 的可信主体、版本与资源快照。 */
    private final AgentRunRequest request;
    /** 本轮共享的执行中止门控。 */
    private final AgentScopeRunGate gate;
    /** 服务端脚本授权与沙箱入口。 */
    private final SkillAccessGateway gateway;
    /** 仅包含本次 SkillBox 标识，不允许模型选择其他租户或版本。 */
    private final Map<String, SkillVersionView> skills;

    AgentScopeSkillExecutionBridge(AgentRunRequest request, AgentScopeRunGate gate,
                                  SkillAccessGateway gateway, Map<String, SkillVersionView> skills) {
        this.request = request;
        this.gate = gate;
        this.gateway = gateway;
        this.skills = Map.copyOf(skills);
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public String getDescription() { return "在隔离沙箱中执行本轮技能的 Python 脚本。使用目录中的 skillId 和包内 .py 路径，stdin 为输入文本；不提供网络访问。启用文件产物时，固定脚本将文件写入 CM_AGENT_ARTIFACT_DIR 指定的 /workspace/output，运行成功后用户可在控制台下载；文件字节不返回模型。"; }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object", "properties", Map.of(
                "skillId", Map.of("type", "string", "description", "本轮技能目录中的技能标识"),
                "path", Map.of("type", "string", "description", "固定版本中已登记的 .py 相对路径"),
                "stdin", Map.of("type", "string", "description", "脚本标准输入，最多 32768 字节")),
                "required", java.util.List.of("skillId", "path"), "additionalProperties", false);
    }

    @Override
    public boolean isReadOnly() { return false; }

    /**
     * 订阅后执行，避免尚未调度时产生副作用。模型参数只能定位已登记的脚本，不能提交代码。
     *
     * @param param AgentScope 提供的工具调用参数
     * @return 脱敏输出或携带稳定错误编号的工具错误；致命失败交给门控传播
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            String callId = param.getToolUseBlock() == null || param.getToolUseBlock().getId() == null
                    ? "skill-execute-" + UUID.randomUUID() : param.getToolUseBlock().getId();
            Object id = param.getInput().get("skillId");
            Object path = param.getInput().get("path");
            Object input = param.getInput().getOrDefault("stdin", "");
            SkillVersionView skill = id instanceof String ? skills.get(id) : null;
            if (skill == null || !(path instanceof String script) || !script.endsWith(".py")
                    || !(input instanceof String stdin)
                    || skill.resources().stream().noneMatch(resource -> resource.path().equals(script))) {
                return ToolResultBlock.error("请求的技能脚本不存在或输入不合法").withIdAndName(callId, TOOL_NAME);
            }
            UUID attempt = UUID.randomUUID();
            SkillReadRequest execution = new SkillReadRequest(request.principal(), request.agentId(), request.runId(),
                    callId, attempt, skill.definition().id(), skill.version().id(), script);
            try {
                // 复用读取门控的串行与致命失败传播，仅委托 execute；不调用原生文件/代码执行器。
                SkillReadResult result = gate.invokeSkill((trusted, ignored) ->
                        new SkillReadResult(gateway.execute(trusted, stdin), attempt), execution, () -> "");
                return ToolResultBlock.text(result.content()).withIdAndName(callId, TOOL_NAME);
            } catch (SkillAccessException failure) {
                if (failure.fatal()) throw failure;
                return ToolResultBlock.error(failure.safeMessage() + "（" + failure.code() + "，errorId="
                        + failure.errorId() + "）").withIdAndName(callId, TOOL_NAME);
            }
        });
    }
}
