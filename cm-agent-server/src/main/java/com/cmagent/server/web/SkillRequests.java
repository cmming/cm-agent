package com.cmagent.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.cmagent.core.domain.SkillBindingMode;
import com.cmagent.core.domain.ToolApprovalDecision;

import java.util.List;
import java.util.UUID;

/** 技能管理接口的请求对象集合。 */
public final class SkillRequests {
    private SkillRequests() {
    }

    /**
     * 显式设置技能启用状态，避免 toggle 在重试时产生相反结果。
     *
     * @param enabled 目标启用状态
     */
    public record EnabledRequest(boolean enabled) {
    }

    /**
     * 条件更新一个技能逻辑依赖到租户工具的映射。
     *
     * <p>请求体不接收 tenantId：租户只能来自认证主体，客户端传入的租户字段一律忽略，
     * 避免通过改包把映射写到别的租户。</p>
     *
     * @param toolId 目标租户工具标识，由服务端在可信租户内重新解析
     * @param expectedRevision 调用方看到的技能级映射修订，用于条件更新
     */
    public record DependencyMappingRequest(
            @NotNull(message = "toolId 不能为空") UUID toolId,
            long expectedRevision
    ) {
    }

    /**
     * 显式确认将当前候选发布为正式版本。
     *
     * @param candidateVersionId 当前候选版本，避免迟到页面发布了别的候选
     * @param expectedPublishedVersionId 当前已发布版本；首次发布允许为空
     * @param qualifyingTrialRunId 当前映射修订下实际读取目标技能的成功试运行
     */
    public record PublishRequest(
            @NotNull(message = "candidateVersionId 不能为空") UUID candidateVersionId,
            UUID expectedPublishedVersionId,
            @NotNull(message = "qualifyingTrialRunId 不能为空") UUID qualifyingTrialRunId
    ) {
    }

    /**
     * 显式确认将曾发布版本重新发布为当前版本。
     *
     * @param targetVersionId 曾正式发布的目标版本
     * @param expectedPublishedVersionId 调用方看到的当前发布版本；首次回滚不适用
     */
    public record RollbackRequest(
            @NotNull(message = "targetVersionId 不能为空") UUID targetVersionId,
            UUID expectedPublishedVersionId
    ) {
    }

    /**
     * 设置 Agent 绑定的取版策略；请求不含 tenantId，租户只能来自认证主体。
     *
     * @param mode 跟随正式发布或固定历史发布版本
     * @param pinnedVersionId 固定模式下的曾发布版本；跟随模式必须为空
     * @param expectedRevision 已有绑定的乐观锁修订；首次创建可以为空
     */
    public record BindingStrategyRequest(
            @NotNull(message = "mode 不能为空") SkillBindingMode mode,
            UUID pinnedVersionId,
            Long expectedRevision
    ) {
    }

    /**
     * 创建一次指定候选或历史版本的真实技能试运行。
     *
     * @param versionId 需要临时注入的不可变版本
     * @param agentId 使用真实模型和既有工具授权执行的目标 Agent
     * @param input 本次测试输入；不会写入会话消息
     */
    public record TrialRequest(
            @NotNull(message = "versionId 不能为空") UUID versionId,
            @NotNull(message = "agentId 不能为空") UUID agentId,
            @NotBlank(message = "input 不能为空") String input
    ) {
    }

    /** 提交 TEST Run 高风险工具审批的完整决定集合。 */
    public record TrialApprovalDecisionRequest(
            long expectedVersion,
            @NotNull(message = "items 不能为空") List<TrialApprovalItemDecision> items
    ) {
        public TrialApprovalDecisionRequest {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /** 客户端只能提交审批明细标识和允许或拒绝决定。 */
    public record TrialApprovalItemDecision(
            @NotNull(message = "itemId 不能为空") UUID itemId,
            @NotNull(message = "decision 不能为空") ToolApprovalDecision decision
    ) {
    }
}
