package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AgentScopeToolBridge implements AgentTool {

    private static final String UNEXPECTED_ERROR_MESSAGE = "工具调用失败";

    private final AgentRunRequest request;
    private final ToolDefinition tool;
    private final ToolInvocationGateway gateway;
    private final ObjectMapper objectMapper;
    private final AgentScopeRunGate runGate;
    private final Map<String, Object> parameters;
    private final ConcurrentLinkedQueue<ToolCallRecord> records = new ConcurrentLinkedQueue<>();

    public AgentScopeToolBridge(
            AgentRunRequest request,
            ToolDefinition tool,
            ToolInvocationGateway gateway,
            ObjectMapper objectMapper
    ) {
        this(request, tool, gateway, objectMapper, new AgentScopeRunGate());
    }

    AgentScopeToolBridge(
            AgentRunRequest request,
            ToolDefinition tool,
            ToolInvocationGateway gateway,
            ObjectMapper objectMapper,
            AgentScopeRunGate runGate
    ) {
        this.request = Objects.requireNonNull(request, "request 不能为空");
        this.tool = Objects.requireNonNull(tool, "tool 不能为空");
        this.gateway = Objects.requireNonNull(gateway, "gateway 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.runGate = Objects.requireNonNull(runGate, "runGate 不能为空");
        this.parameters = parseParameters(tool.inputSchema());
    }

    @Override
    public String getName() {
        return tool.name();
    }

    @Override
    public String getDescription() {
        return tool.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> invoke(param));
    }

    public List<ToolCallRecord> records() {
        return List.copyOf(records);
    }

    public void throwIfInfrastructureFailure() {
        runGate.throwIfInfrastructureFailure();
    }

    private ToolResultBlock invoke(ToolCallParam param) {
        long startedAt = System.nanoTime();
        ToolUseBlock toolUse = param == null ? null : param.getToolUseBlock();
        Map<String, Object> input = toolUse == null || toolUse.getInput() == null
                ? Map.of()
                : toolUse.getInput();
        String toolCallId = toolUse == null ? "" : toolUse.getId();
        String invocationToolName = toolUse == null ? "" : toolUse.getName();
        String inputSummary = summarizeInput(input);
        try {
            String inputJson = objectMapper.writeValueAsString(input);
            ToolInvocationResult result = runGate.invoke(gateway, new ToolInvocationRequest(
                    request.tenantId(),
                    request.agentId(),
                    request.principal(),
                    request.runId(),
                    toolCallId,
                    tool.id(),
                    invocationToolName,
                    inputJson
            ));
            Duration duration = elapsedSince(startedAt);
            if (result.success()) {
                records.add(new ToolCallRecord(
                        tool.id(), tool.name(), inputSummary, result.output(), RunStatus.SUCCEEDED,
                        duration, true, ""));
                return ToolResultBlock.text(result.output()).withState(ToolResultState.SUCCESS);
            }

            RunStatus status = result.authorized() ? RunStatus.FAILED : RunStatus.DENIED;
            records.add(new ToolCallRecord(
                    tool.id(), tool.name(), inputSummary, "", status,
                    duration, result.authorized(), result.errorMessage()));
            return ToolResultBlock.error(result.errorMessage()).withState(ToolResultState.ERROR);
        } catch (AgentScopeRunGate.RunAbortedException aborted) {
            throw aborted;
        } catch (ToolInvocationInfrastructureException infrastructureFailure) {
            throw infrastructureFailure;
        } catch (Exception exception) {
            if (isInterruption(exception)) {
                runGate.markInvocationInterrupted();
                throw new AgentScopeRunGate.RunAbortedException();
            }
            records.add(new ToolCallRecord(
                    tool.id(), tool.name(), inputSummary, "", RunStatus.FAILED,
                    elapsedSince(startedAt), false, UNEXPECTED_ERROR_MESSAGE));
            return ToolResultBlock.error(UNEXPECTED_ERROR_MESSAGE).withState(ToolResultState.ERROR);
        }
    }

    private Map<String, Object> parseParameters(String inputSchema) {
        try {
            JsonNode root = objectMapper.readTree(inputSchema);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("工具输入 Schema 必须是 object");
            }
            Map<String, Object> parsed = objectMapper.convertValue(
                    root, new TypeReference<Map<String, Object>>() { });
            if (!"object".equals(parsed.get("type"))) {
                throw new IllegalArgumentException("工具输入 Schema 必须是 object");
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工具输入 Schema 不是合法 JSON");
        }
    }

    private static String summarizeInput(Map<String, Object> input) {
        return "输入字段: " + input.keySet().stream().sorted().toList();
    }

    private static Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt));
    }

    private static boolean isInterruption(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }
}
