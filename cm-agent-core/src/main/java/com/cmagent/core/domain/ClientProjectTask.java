package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 绑定已配对设备的有界项目任务。
 *
 * <p>请求和结果由 Server 使用持久主密钥加密。选择根任务在项目创建前没有项目标识；
 * 其他任务必须绑定原主体、设备、项目和固定任务种类。客户端只提交任务标识和版本，
 * 不能通过名称、URL 或模型参数改变执行位置。</p>
 *
 * @param id Server 生成的浏览器幂等键和设备任务标识
 * @param tenantId 可信租户
 * @param ownerId 原认证主体
 * @param deviceId 唯一可领取和回报的设备
 * @param projectId 项目任务所属项目；选择根任务为空
 * @param runId 模型副作用任务固定的 Run；目录选择和浏览器查询为空
 * @param agentId 模型副作用任务固定的 Agent；目录选择和浏览器查询为空
 * @param conversationId 模型副作用任务固定的会话；目录选择和浏览器查询为空
 * @param operationId 应用任务对应的客户端文件操作；其他任务为空
 * @param commandId 固定工具链命令任务关联的命令；工具链查询、计划和其他任务为空
 * @param kind 固定任务种类
 * @param encryptedRequest 认证加密后的任务请求 JSON
 * @param state 任务领取与终态
 * @param encryptedResult 认证加密后的成功结果
 * @param safeError 可展示且已脱敏的失败原因
 * @param createdAt Server 登记时间
 * @param expiresAt 最晚领取或完成期限
 * @param claimedAt 唯一领取时间
 * @param completedAt 终态时间
 * @param version 条件版本，从 1 开始
 */
public record ClientProjectTask(UUID id, UUID tenantId, String ownerId, UUID deviceId,
        UUID projectId, UUID runId, UUID agentId, UUID conversationId, UUID operationId,
        UUID commandId,
        Kind kind, String encryptedRequest, State state,
        String encryptedResult, String safeError, Instant createdAt, Instant expiresAt,
        Instant claimedAt, Instant completedAt, long version) {
    /** 固定任务种类；不能由客户端名称或模型输入扩展。 */
    public enum Kind {
        /** 在本机交互桌面确认并选择项目根。 */
        SELECT_ROOT,
        /** 有界列目录。 */
        LIST,
        /** 有界文本读取。 */
        READ,
        /** 有界文件名或脱敏正文搜索。 */
        SEARCH,
        /** 在客户端核对当前文件并形成零副作用变更计划。 */
        PLAN_CHANGE,
        /** 在精确审批和条件版本仍匹配时应用一次变更。 */
        APPLY_CHANGE,
        /** 查询客户端本地配置的固定工具链，不返回可执行文件路径。 */
        LIST_TOOLCHAINS,
        /** 在客户端核对工具链、构建配置和命令参数并生成零副作用清单。 */
        PLAN_COMMAND,
        /** 在精确 HIGH 审批后启动一次本地命令。 */
        RUN_COMMAND,
        /** 查询设备进程和脱敏输出快照。 */
        COMMAND_STATUS,
        /** 请求停止指定命令，返回时不代表进程已经退出。 */
        STOP_COMMAND
    }

    /** 设备任务生命周期。 */
    public enum State {
        /** 已入队，尚未被设备领取。 */
        PENDING,
        /** 已由唯一设备领取，结果仍未知。 */
        DISPATCHED,
        /** 设备明确返回成功。 */
        SUCCEEDED,
        /** 设备或 Server 明确返回失败。 */
        FAILED,
        /** 结果无法确认，禁止自动重派。 */
        UNKNOWN,
        /** 尚未领取即取消或过期。 */
        CANCELLED
    }

    public ClientProjectTask {
        Objects.requireNonNull(id, "任务标识不能为空");
        Objects.requireNonNull(tenantId, "租户不能为空");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        Objects.requireNonNull(kind, "任务种类不能为空");
        Objects.requireNonNull(state, "任务状态不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        Objects.requireNonNull(expiresAt, "截止时间不能为空");
        boolean invalidScope = switch (kind) {
            case SELECT_ROOT -> projectId != null || runId != null || agentId != null
                    || conversationId != null || operationId != null || commandId != null;
            case LIST, READ, SEARCH -> projectId == null || runId != null || agentId != null
                    || conversationId != null || operationId != null || commandId != null;
            case PLAN_CHANGE -> projectId == null || runId == null || agentId == null
                    || conversationId == null || operationId != null || commandId != null;
            case APPLY_CHANGE -> projectId == null || runId == null || agentId == null
                    || conversationId == null || operationId == null || commandId != null;
            case LIST_TOOLCHAINS -> projectId == null || runId == null || agentId == null
                    || conversationId == null || operationId != null || commandId != null;
            case PLAN_COMMAND -> projectId == null || runId == null || agentId == null
                    || conversationId == null || operationId != null || commandId != null;
            case RUN_COMMAND, COMMAND_STATUS, STOP_COMMAND -> projectId == null || runId == null
                    || agentId == null || conversationId == null || operationId != null
                    || commandId == null;
        };
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200
                || encryptedRequest == null || encryptedRequest.isBlank()
                || encryptedResult == null || safeError == null
                || safeError.length() > 500 || version < 1
                || !expiresAt.isAfter(createdAt)
                || invalidScope
                || state == State.PENDING && (claimedAt != null || completedAt != null)
                || state == State.DISPATCHED && (claimedAt == null || completedAt != null)
                || (state == State.SUCCEEDED || state == State.FAILED
                    || state == State.UNKNOWN || state == State.CANCELLED)
                    && completedAt == null) {
            throw new IllegalArgumentException("客户端项目任务元数据不合法");
        }
    }

    /** 兼容 R2 既有文件任务调用点，命令任务必须使用包含 commandId 的规范构造器。 */
    public ClientProjectTask(UUID id, UUID tenantId, String ownerId, UUID deviceId,
            UUID projectId, UUID runId, UUID agentId, UUID conversationId, UUID operationId,
            Kind kind, String encryptedRequest, State state, String encryptedResult,
            String safeError, Instant createdAt, Instant expiresAt, Instant claimedAt,
            Instant completedAt, long version) {
        this(id, tenantId, ownerId, deviceId, projectId, runId, agentId, conversationId,
                operationId, null, kind, encryptedRequest, state, encryptedResult, safeError,
                createdAt, expiresAt, claimedAt, completedAt, version);
    }

    /** 即使调用方误记日志，也不输出任务密文或结果。 */
    @Override public String toString() {
        return "ClientProjectTask[id=" + id + ",kind=" + kind + ",state=" + state
                + ",version=" + version + "]";
    }
}
