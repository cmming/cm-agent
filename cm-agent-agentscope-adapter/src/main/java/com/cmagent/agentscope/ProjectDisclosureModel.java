package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.RuntimePendingApproval.RuntimePendingToolCall;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.runtime.RuntimeApprovalDecisions;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 在 AgentScope 实际发往模型前审查项目内容；同一 Model 实例只供一个 Run 的当前执行片段使用。
 *
 * <p>提案使用宿主内部的 ToolUse 暂停 AgentScope 2.0.2 推理循环，复用其检查点和确认恢复，
 * 宿主工具和回执在发送前移除。真实 Provider 不能调用或批准这个内部工具。</p>
 */
final class ProjectDisclosureModel extends ToolBase implements Model {
    private static final String CALL_PREFIX = "cm-project-disclosure-";
    private static final int MAX_PREVIEW_BYTES = 64 * 1024;
    private static final int MAX_AUTHORIZED_FRAGMENTS = 1024;
    private final Model delegate;
    private final AgentRunRequest request;
    private final AgentStateStore store;
    private final ObjectMapper json;
    private final String userId;
    private final String sessionId;
    private final String targetBinding;
    private final String targetDisplayName;
    /** 只豁免宿主在本轮装配的静态系统文本，其他系统块按项目内容检查。 */
    private final String trustedSystemPrompt;
    private final boolean automatic;
    /** 最近的加密检查点，在任何有权继续执行的订阅之前替换。 */
    private volatile ProjectModelDisclosureState state;
    /** 真实确认入口复核原决策后才置为 true，单独验证内部标记工具的执行。 */
    private volatile boolean approvedMarker;

    ProjectDisclosureModel(Model delegate, AgentRunRequest request, AgentStateStore store, ObjectMapper json,
            String trustedSystemPrompt) {
        // AgentScope 2.0.2 只对 ToolBase 应用 Permission Engine。普通 AgentTool 会绕过 ASK。
        super(ToolBase.builder().name(ProjectModelDisclosureState.NAME)
                .description("运行宿主生成的项目片段发送审批")
                .inputSchema(Map.of("type", "object", "properties", Map.of("digest", Map.of("type", "string")),
                        "required", List.of("digest"), "additionalProperties", false))
                .readOnly(false).concurrencySafe(false));
        this.delegate = delegate;
        this.request = request;
        this.store = store;
        this.json = json;
        this.trustedSystemPrompt = trustedSystemPrompt;
        userId = request.tenantId() + ":" + request.principal().principalId();
        sessionId = request.runId().toString();
        var config = request.modelConfig();
        targetBinding = hash(Map.of("run", sessionId, "project", request.projectContext().sourceBinding().projectId(),
                "provider", config.providerType().name(), "config", config.id(), "url", config.baseUrl(),
                "model", delegate.getModelName()));
        targetDisplayName = redact(config.displayName() + " · " + config.providerType().name()
                + " · " + delegate.getModelName());
        automatic = request.projectContext().authorizationMode() == OperationAuthorizationMode.FULL_ACCESS;
        state = store.get(userId, sessionId, ProjectModelDisclosureState.KEY, ProjectModelDisclosureState.class)
                .orElseGet(() -> new ProjectModelDisclosureState(Set.of(), null));
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            checkRunning();
            var outgoing = new ArrayList<Msg>();
            var pending = new LinkedHashMap<String, String>();
            boolean projectData = false;
            // 历史与本轮输入会被组合为一个普通用户消息，无法可靠区分来源。项目对话的非系统内容
            // 因此整体纳入检查。格式化前只保留受支持的文本，确保审批预览与提交使用相同数据。
            for (Msg message : messages) {
                var blocks = new ArrayList<ContentBlock>();
                for (ContentBlock original : message.getContent()) {
                    if (internalMarker(original)) continue;
                    ContentBlock safe = safeBlock(original);
                    blocks.add(safe);
                    // 恢复时 AgentScope 可能用不同角色装载同一系统提示。只放行逐字匹配的宿主提示，
                    // 不能依据 SYSTEM 标签排除运行中注入的项目内容。
                    if (original instanceof TextBlock text && text.getText().strip().equals(trustedSystemPrompt.strip())) continue;
                    String fragment = fragment(safe);
                    if (fragment.isBlank()) continue;
                    projectData = true;
                    if (!automatic) {
                        String digest = hash(Map.of("target", targetBinding, "fragment", fragment));
                        if (!state.authorized().contains(digest)) pending.putIfAbsent(digest, fragment);
                    }
                }
                if (!blocks.isEmpty()) {
                    outgoing.add(Msg.builder().id(message.getId()).role(message.getRole()).content(blocks)
                            .name(redact(message.getName())).build());
                }
            }
            if (!pending.isEmpty()) return Flux.just(prepare(pending));
            if (projectData) {
                // 宿主在真正订阅外部调用前复核项目和模型范围并完成审计，失败不能降级为直接发送。
                request.projectModelDisclosure().beforeSend(automatic);
            }
            checkRunning();
            List<ToolSchema> publicTools = tools == null ? null : tools.stream()
                    .filter(tool -> !getName().equals(tool.getName())).toList();
            return delegate.stream(List.copyOf(outgoing), publicTools, options).map(response -> {
                if (response.getContent() != null && response.getContent().stream().anyMatch(ProjectDisclosureModel::internalMarker)) {
                    throw new IllegalStateException("在线模型返回了保留的项目审批标记");
                }
                return response;
            });
        });
    }

    private ChatResponse prepare(Map<String, String> pending) {
        var fragments = List.copyOf(pending.values());
        ProjectModelDisclosureState.Proposal proposal = state.pending();
        String digest = hash(Map.of("target", targetBinding, "fragments", List.copyOf(pending.keySet())));
        if (proposal == null || !proposal.digest().equals(digest)) {
            int size = fragments.stream().mapToInt(text -> text.getBytes(StandardCharsets.UTF_8).length).sum();
            if (size > MAX_PREVIEW_BYTES || state.authorized().size() + pending.size() > MAX_AUTHORIZED_FRAGMENTS) {
                throw new IllegalArgumentException("本轮可审批的项目片段过多，请缩小文件或会话范围");
            }
            proposal = new ProjectModelDisclosureState.Proposal(CALL_PREFIX + UUID.randomUUID(), digest,
                    hash(Map.of("digest", digest)), pending.keySet(),
                    new ProjectModelDisclosureState.Preview(targetDisplayName, fragments));
            save(new ProjectModelDisclosureState(state.authorized(), proposal));
        }
        Map<String, Object> arguments = Map.of("digest", proposal.digest());
        // AgentScope 2.0.2 的 ToolExecutor 根据原始 JSON 校验 Schema；仅设置输入 Map 时，
        // 流累积器会把原文设成空对象，用户批准后仍无法执行该标记。
        return ChatResponse.builder().content(List.of(new ToolUseBlock(proposal.callId(), getName(),
                        arguments, render(arguments), Map.of()))).finishReason("tool_calls").build();
    }

    RuntimePendingToolCall pendingApproval(ToolUseBlock tool) {
        var proposal = requireProposal(tool);
        return new RuntimePendingToolCall(tool.getId(), ProjectModelDisclosureState.approvalTypeId(request.tenantId()), getName(),
                ToolRiskLevel.HIGH, "发送项目片段给在线模型", proposal.inputHash());
    }

    /** 框架没有正式暂停时禁止将只生成了预览的运行报告为成功。 */
    boolean hasPendingProposal() { return state.pending() != null; }

    void verifyDecision(ToolUseBlock tool, RuntimeApprovalDecisions.Item decision) {
        var proposal = requireProposal(tool);
        if (!ProjectModelDisclosureState.approvalTypeId(request.tenantId()).equals(decision.toolId())
                || !proposal.inputHash().equals(decision.inputHash())
                || decision.decision() != ToolApprovalDecision.APPROVE) {
            throw new IllegalStateException("项目片段发送审批已失效或未批准");
        }
        approvedMarker = true;
    }

    private ProjectModelDisclosureState.Proposal requireProposal(ToolUseBlock tool) {
        var proposal = state.pending();
        if (proposal == null || !getName().equals(tool.getName()) || !proposal.callId().equals(tool.getId())
                || !proposal.inputHash().equals(hash(tool.getInput()))) {
            throw new IllegalStateException("项目片段审批内容已变化");
        }
        return proposal;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            var proposal = requireProposal(param.getToolUseBlock());
            if (!approvedMarker) throw new IllegalStateException("项目片段缺少有效审批");
            checkRunning();
            var authorized = new HashSet<>(state.authorized());
            authorized.addAll(proposal.hashes());
            save(new ProjectModelDisclosureState(authorized, null));
            approvedMarker = false;
            return ToolResultBlock.text("已确认项目片段发送");
        });
    }

    private void save(ProjectModelDisclosureState updated) {
        store.save(userId, sessionId, ProjectModelDisclosureState.KEY, updated);
        state = updated;
    }

    private ContentBlock safeBlock(ContentBlock block) {
        return switch (block) {
            case TextBlock value -> TextBlock.builder().text(redact(value.getText())).build();
            case ThinkingBlock value -> ThinkingBlock.builder().thinking(redact(value.getThinking())).build();
            case ToolResultBlock value -> new ToolResultBlock(value.getId(), value.getName(),
                    value.getOutput().stream().map(this::safeBlock).toList(), Map.of(), value.getState());
            case ToolUseBlock value -> {
                Map<String, Object> arguments = safeArguments(value.getInput());
                yield new ToolUseBlock(value.getId(), value.getName(), arguments,
                        render(arguments), Map.of(), value.getState());
            }
            default -> throw new IllegalArgumentException("项目片段包含无法安全预览的数据类型");
        };
    }

    private Map<String, Object> safeArguments(Map<String, Object> input) {
        var copy = new LinkedHashMap<String, Object>();
        if (input != null) input.forEach((key, value) -> {
            String name = redact(key);
            String normalized = key.replaceAll("[_\\-. ]", "").toLowerCase(java.util.Locale.ROOT);
            copy.put(name, Set.of("apikey", "token", "password", "secret", "authorization", "cookie", "jwt")
                    .contains(normalized) ? "<已脱敏>" : safeArgument(value));
        });
        return copy;
    }

    private Object safeArgument(Object input) {
        return switch (input) {
            case null -> null;
            case String value -> redact(value);
            case Boolean value -> value;
            case Number value -> value;
            case Map<?, ?> value -> {
                var arguments = new LinkedHashMap<String, Object>();
                value.forEach((key, item) -> arguments.put(Objects.toString(key), item));
                yield safeArguments(arguments);
            }
            case List<?> value -> value.stream().map(this::safeArgument).toList();
            default -> throw new IllegalArgumentException("项目工具参数包含无法安全预览的数据类型");
        };
    }

    private String fragment(ContentBlock block) {
        return switch (block) {
            case TextBlock value -> value.getText();
            case ThinkingBlock value -> value.getThinking();
            case ToolResultBlock value -> value.getName() + "\n"
                    + String.join("\n", value.getOutput().stream().map(this::fragment).toList());
            case ToolUseBlock value -> value.getName() + "\n" + render(value.getInput());
            default -> throw new IllegalArgumentException("项目片段无法预览");
        };
    }

    private String render(Map<String, Object> input) {
        try {
            return json.writeValueAsString(input);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("项目工具参数无法预览");
        }
    }

    private String redact(String value) {
        return value == null ? null : request.projectModelDisclosure().redact(value);
    }

    private void checkRunning() {
        if (request.cancellation().isRequested()) throw new com.cmagent.core.runtime.RunCancellation.StoppedException();
    }

    private String hash(Map<String, Object> value) {
        return AgentScopeReActExecutor.approvalInputHash(json, value);
    }

    private static boolean internalMarker(ContentBlock block) {
        return switch (block) {
            case ToolUseBlock tool -> ProjectModelDisclosureState.NAME.equals(tool.getName()) || reserved(tool.getId());
            case ToolResultBlock tool -> ProjectModelDisclosureState.NAME.equals(tool.getName()) || reserved(tool.getId());
            default -> false;
        };
    }

    private static boolean reserved(String id) { return id != null && id.startsWith(CALL_PREFIX); }

    @Override public String getModelName() { return delegate.getModelName(); }
}
