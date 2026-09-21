package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.domain.SkillVersionView;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.runtime.SkillAccessGateway;
import com.cmagent.core.runtime.SkillReadRequest;
import com.cmagent.core.runtime.SkillReadResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 将 AgentScope 原生技能加载工具接入 CM Agent 的读取治理边界。
 *
 * <p>原生工具只负责读取当前 {@link AgentScopeSkillRepository} 中的内存技能；本桥接器先把模型
 * 输入解析为固定版本和规范资源路径，再由 {@link SkillAccessGateway} 完成撤销复核、预算、记录与审计。
 * 未知技能或路径不会进入原生工具，从而不会触发其目录回退逻辑或向模型回传原始异常。</p>
 */
final class AgentScopeSkillLoadBridge implements AgentTool {
    static final String TOOL_NAME = "load_skill_through_path";

    private static final String SAFE_NOT_FOUND_MESSAGE = "请求的技能资源不存在";
    private static final String SAFE_LOAD_FAILURE_MESSAGE = "技能内容读取失败";

    private final AgentRunRequest request;
    private final AgentScopeRunGate runGate;
    private final SkillAccessGateway gateway;
    private final AgentTool nativeDelegate;
    private final Map<String, SkillVersionView> skillsByNativeId;

    /**
     * 创建原生加载器的受治理替身。
     *
     * @param request 当前运行的可信领域请求
     * @param runGate 当前运行共享的中止门控
     * @param gateway 技能读取治理入口
     * @param nativeDelegate SkillBox 注册的原生内存加载器
     * @param skillsByNativeId AgentScope 生成的技能 ID 到固定版本的映射
     */
    AgentScopeSkillLoadBridge(
            AgentRunRequest request,
            AgentScopeRunGate runGate,
            SkillAccessGateway gateway,
            AgentTool nativeDelegate,
            Map<String, SkillVersionView> skillsByNativeId
    ) {
        this.request = Objects.requireNonNull(request, "request 不能为空");
        this.runGate = Objects.requireNonNull(runGate, "runGate 不能为空");
        this.gateway = Objects.requireNonNull(gateway, "gateway 不能为空");
        this.nativeDelegate = Objects.requireNonNull(nativeDelegate, "nativeDelegate 不能为空");
        this.skillsByNativeId = Map.copyOf(Objects.requireNonNull(skillsByNativeId, "skillsByNativeId 不能为空"));
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return nativeDelegate.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return nativeDelegate.getParameters();
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 延迟执行受治理读取，避免在 AgentScope 订阅前发生读取、审计或预算扣减。
     *
     * <p>{@code Mono.fromCallable} 不切换线程；原生加载器只读取本次运行内存，实际数据库事务和审计
     * 由网关在其既有工作单元中完成。致命拒绝会向上传播并由运行门控阻止后续业务工具，普通资源不存在
     * 与预算上限则映射为安全工具结果，让模型可以选择其他已公开技能。</p>
     *
     * @param param AgentScope 提供的模型工具调用参数
     * @return 受控的读取正文或安全失败结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> load(param));
    }

    private ToolResultBlock load(ToolCallParam param) {
        Objects.requireNonNull(param, "param 不能为空");
        ResolvedRequest resolved = resolve(param);
        if (resolved == null) {
            return toolError(param, SAFE_NOT_FOUND_MESSAGE);
        }
        SkillReadRequest readRequest = new SkillReadRequest(
                request.principal(), request.agentId(), request.runId(), modelCallId(param), UUID.randomUUID(),
                resolved.skill().definition().id(), resolved.skill().version().id(), resolved.path());
        try {
            SkillReadResult result = runGate.invokeSkill(gateway, readRequest,
                    () -> loadNative(param));
            return toolText(param, result.content());
        } catch (SkillAccessException exception) {
            if (exception.fatal()) {
                throw exception;
            }
            return toolError(param, exception.safeMessage());
        }
    }

    /**
     * 校验模型输入只能引用当前 Run 快照中已登记的名称和文本路径。
     *
     * <p>此处不接受目录、绝对路径、空白路径或临时名称。即使 AgentScope 原生实现未来增加目录回退，
     * 也无法透过本桥接器访问宿主文件系统。</p>
     */
    private ResolvedRequest resolve(ToolCallParam param) {
        Object skillValue = param.getInput().get("skillId");
        Object pathValue = param.getInput().get("path");
        if (!(skillValue instanceof String name) || !(pathValue instanceof String path)
                || name.isBlank() || path.isBlank()) {
            return null;
        }
        SkillVersionView skill = skillsByNativeId.get(name);
        if (skill == null || !isKnownPath(skill, path)) {
            return null;
        }
        return new ResolvedRequest(skill, path);
    }

    private static boolean isKnownPath(SkillVersionView skill, String path) {
        return "SKILL.md".equals(path)
                || skill.resources().stream().map(SkillResource::path).anyMatch(path::equals);
    }

    private String loadNative(ToolCallParam param) {
        ToolResultBlock nativeResult = nativeDelegate.callAsync(param).block();
        // AgentScope 2.0.2 的内置工具成功结果可能省略 state（由 TextBlock 隐式表示成功），
        // 因此只把显式 ERROR 视为失败；未知输入已在调用原生工具前被本桥接器拒绝。
        if (nativeResult == null || nativeResult.getState() == ToolResultState.ERROR) {
            throw new IllegalStateException("原生技能加载失败");
        }
        StringBuilder content = new StringBuilder();
        for (ContentBlock block : nativeResult.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                content.append(textBlock.getText());
            }
        }
        if (content.isEmpty()) {
            throw new IllegalStateException("原生技能加载结果为空");
        }
        return content.toString();
    }

    private static String modelCallId(ToolCallParam param) {
        if (param.getToolUseBlock() != null && param.getToolUseBlock().getId() != null
                && !param.getToolUseBlock().getId().isBlank()) {
            return param.getToolUseBlock().getId();
        }
        return "skill-load-" + UUID.randomUUID();
    }

    private static ToolResultBlock toolText(ToolCallParam param, String content) {
        return ToolResultBlock.text(content).withIdAndName(toolCallId(param), TOOL_NAME);
    }

    private static ToolResultBlock toolError(ToolCallParam param, String safeMessage) {
        return ToolResultBlock.error(safeMessage).withIdAndName(toolCallId(param), TOOL_NAME);
    }

    private static String toolCallId(ToolCallParam param) {
        return param.getToolUseBlock() == null || param.getToolUseBlock().getId() == null
                ? "skill-load" : param.getToolUseBlock().getId();
    }

    private record ResolvedRequest(SkillVersionView skill, String path) {
    }
}
