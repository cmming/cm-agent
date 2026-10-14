package com.cmagent.agentscope;

import java.util.Locale;

/** 宿主启动时选择的执行策略；同一持久化数据集必须排空后才允许切换。 */
public enum AgentScopeExecutionMode {
    /** 默认 ReAct 路径，保持已有嵌入式工厂与服务端升级兼容。 */
    REACT,
    /** 使用受控 Harness 包装，仍复用 CM Agent 的治理、历史和检查点。 */
    HARNESS;

    /** 只接受明确支持的配置值；未知值不能静默回退造成检查点策略混用。 */
    public static AgentScopeExecutionMode parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("执行模式不能为空");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "react" -> REACT;
            case "harness" -> HARNESS;
            default -> throw new IllegalArgumentException("执行模式只支持 react 或 harness");
        };
    }

    /** 返回用于部署配置和模式仓储的稳定小写值。 */
    public String configValue() { return name().toLowerCase(Locale.ROOT); }
}

