package com.cmagent.server.web;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.SkillLoadStatus;
import com.cmagent.core.domain.SkillPreflightItemStatus;
import com.cmagent.core.domain.SkillPreflightScope;
import com.cmagent.core.domain.SkillPreflightStatus;
import com.cmagent.core.domain.SkillTrial;
import com.cmagent.core.domain.SkillTrialStatus;
import com.cmagent.server.runtime.RunPersistenceService;

import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 技能管理、绑定和运行读取接口的稳定响应对象集合。 */
public final class SkillResponses {
    private SkillResponses() {
    }

    /**
     * @param id 技能稳定标识
     * @param name 技能名称
     * @param description 当前版本描述
     * @param currentVersionId 当前版本标识
     * @param versionNo 当前版本号
     * @param enabled 是否启用
     * @param updatedAt 最近更新时间
     */
    public record Summary(UUID id, String name, String description, UUID currentVersionId,
                          UUID candidateVersionId, UUID publishedVersionId,
                          int versionNo, boolean enabled, Instant updatedAt) {
        /** 兼容旧调用方的当前版本摘要构造方式。 */
        public Summary(UUID id, String name, String description, UUID currentVersionId,
                       int versionNo, boolean enabled, Instant updatedAt) {
            this(id, name, description, currentVersionId, null, null, versionNo, enabled, updatedAt);
        }
    }

    /** 标识版本在发布治理中的状态。 */
    public enum VersionState {
        /** 当前候选版本，尚未正式发布。 */
        CANDIDATE,
        /** 当前正式发布版本。 */
        CURRENT_PUBLISHED,
        /** 曾正式发布但已被新版本替代。 */
        PREVIOUSLY_PUBLISHED,
        /** 从未形成正式发布事实的历史版本。 */
        UNPUBLISHED_HISTORY
    }

    /** 标识文本片段在两侧版本之间的变化。 */
    public enum DiffState {
        /** 仅新版本存在该资源。 */
        ADDED,
        /** 仅旧版本存在该资源。 */
        REMOVED,
        /** 两侧都存在但内容不同。 */
        CHANGED,
        /** 两侧内容一致。 */
        UNCHANGED
    }

    /**
     * @param versionId 版本标识
     * @param versionNo 技能内递增版本号
     * @param description 版本描述
     * @param sha256 版本规范摘要
     * @param createdBy 版本创建主体
     * @param createdAt 版本创建时间
     * @param state 发布治理状态
     */
    public record VersionSummary(UUID versionId, int versionNo, String description, String sha256,
                                 String createdBy, Instant createdAt, VersionState state) {
    }

    /**
     * @param state 文本变化状态
     * @param fromText 旧版本安全文本，缺失时为空
     * @param toText 新版本安全文本，缺失时为空
     */
    public record DiffPart(DiffState state, String fromText, String toText) {
        public DiffPart {
            fromText = fromText == null ? "" : fromText;
            toText = toText == null ? "" : toText;
        }
    }

    /**
     * @param path 规范化资源路径
     * @param state 资源变化状态
     * @param fromText 旧版本资源文本，缺失时为空
     * @param toText 新版本资源文本，缺失时为空
     */
    public record ResourceDiff(String path, DiffState state, String fromText, String toText) {
        public ResourceDiff {
            path = Objects.requireNonNull(path, "path 不能为空");
            Objects.requireNonNull(state, "state 不能为空");
            fromText = fromText == null ? "" : fromText;
            toText = toText == null ? "" : toText;
        }
    }

    /**
     * @param skillId 技能稳定标识
     * @param fromVersionId 差异左侧版本
     * @param toVersionId 差异右侧版本
     * @param instruction 指令正文差异
     * @param resources 资源差异，按规范化路径排序
     */
    public record VersionDiff(UUID skillId, UUID fromVersionId, UUID toVersionId,
                              DiffPart instruction, List<ResourceDiff> resources) {
        public VersionDiff {
            resources = List.copyOf(resources);
        }
    }

    /**
     * @param path 资源相对路径
     * @param mediaType 文本媒体类型
     * @param byteLength UTF-8 字节数
     */
    public record ResourceEntry(String path, String mediaType, int byteLength) {
    }

    /**
     * @param summary 当前版本摘要
     * @param metadata 只读元数据
     * @param content 技能指令正文
     * @param resources 文本资源目录
     * @param boundAgentCount 当前绑定 Agent 数量
     */
    public record Detail(Summary summary, Map<String, Object> metadata, String content,
                         List<ResourceEntry> resources, long boundAgentCount) {
        public Detail {
            // 元数据领域契约允许显式 null；Map.copyOf 会误拒绝该合法值，因此保留顺序后冻结。
            metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            resources = List.copyOf(resources);
        }
    }

    /**
     * @param skillId 技能稳定标识
     * @param versionId 固定版本标识
     * @param path 资源相对路径；{@code SKILL.md} 表示指令正文
     * @param mediaType 文本媒体类型
     * @param content 文本正文
     */
    public record Resource(UUID skillId, UUID versionId, String path, String mediaType, String content) {
    }

    /**
     * @param bindingId 绑定标识
     * @param skillId 技能稳定标识
     * @param name 技能名称
     * @param description 当前版本描述
     * @param versionNo 当前版本号
     * @param enabled 是否启用
     * @param mode 新建运行解析正式版本的策略
     * @param pinnedVersionId 固定策略选择的已发布版本；跟随发布时为空
     * @param revision 绑定策略的乐观锁修订
     */
    public record Binding(UUID bindingId, UUID skillId, String name, String description,
                          int versionNo, boolean enabled, String mode, UUID pinnedVersionId, long revision) {
    }

    /**
     * @param enabled 技能写入功能是否开启
     * @param allowedExtensions 允许的文本资源扩展名
     * @param maxZipBytes ZIP 字节上限
     * @param maxExpandedBytes 解压文本总字节上限
     * @param maxFiles 普通文件数量上限
     * @param maxInstructionBytes 指令正文字节上限
     * @param maxResourceBytes 单资源字节上限
     * @param maxPathLength 资源路径字符上限
     * @param maxBoundSkills 单 Agent 绑定数量上限
     */
    public record Capabilities(boolean enabled, List<String> allowedExtensions, int maxZipBytes,
                               int maxExpandedBytes, int maxFiles, int maxInstructionBytes,
                               int maxResourceBytes, int maxPathLength, int maxBoundSkills) {
        public Capabilities {
            allowedExtensions = List.copyOf(allowedExtensions);
        }
    }

    /**
     * 一条逻辑依赖在当前租户的映射与预检状态。
     *
     * <p>响应只包含工具标识和管理员可读说明，不返回工具端点、HTTP 请求头或授权内部字段。</p>
     *
     * @param logicalKey 技能版本声明的逻辑工具键
     * @param required 是否为阻断性必需依赖
     * @param description 技能包提供的依赖用途说明
     * @param mappedToolId 已映射的工具标识；未映射时为空
     * @param status 最近一次预检状态；没有历史结果时为空
     * @param errorCode 未就绪时的稳定错误码
     * @param message 已脱敏的中文结果说明
     */
    public record DependencyEntry(String logicalKey, boolean required, String description,
                                  UUID mappedToolId, SkillPreflightItemStatus status,
                                  ApiErrorCode errorCode, String message) {
        public DependencyEntry {
            description = description == null ? "" : description;
            message = message == null ? "" : message;
        }
    }

    /**
     * 技能级依赖映射集合及其权威修订。
     *
     * @param skillId 技能稳定标识
     * @param revision 技能级映射修订，随任一映射变化单调递增
     * @param items 依赖明细，按技能声明的稳定顺序排列
     */
    public record DependencyView(UUID skillId, long revision, List<DependencyEntry> items) {
        public DependencyView {
            items = List.copyOf(items);
        }
    }

    /**
     * @param id 预检标识
     * @param skillId 目标技能
     * @param versionId 目标不可变版本
     * @param mappingRevision 预检采用的技能级映射修订
     * @param scope 预检场景
     * @param agentId 发布或结构预检为空，单 Agent 预检为目标 Agent
     * @param status 汇总状态
     * @param createdAt 预检完成时间
     */
    public record PreflightSummary(UUID id, UUID skillId, UUID versionId, long mappingRevision,
                                   SkillPreflightScope scope, UUID agentId,
                                   SkillPreflightStatus status, Instant createdAt) {
    }

    /**
     * @param checkId 所属预检标识
     * @param agentId 受检 Agent；纯结构检查为空
     * @param logicalKey 逻辑依赖键
     * @param required 是否为必需依赖
     * @param toolId 已解析的租户工具；未映射时为空
     * @param status 明细状态
     * @param errorCode 未就绪时的稳定错误码
     * @param message 已脱敏的中文说明
     * @param errorId 需要后台定位时的关联编号
     */
    public record PreflightItemView(UUID checkId, UUID agentId, String logicalKey, boolean required,
                                    UUID toolId, SkillPreflightItemStatus status,
                                    ApiErrorCode errorCode, String message, String errorId) {
    }

    /**
     * 一次依赖预检的完整结果。
     *
     * @param check 预检汇总
     * @param items 预检明细，按依赖声明顺序和受检 Agent 排列
     */
    public record PreflightView(PreflightSummary check, List<PreflightItemView> items) {
        public PreflightView {
            items = List.copyOf(items);
        }
    }

    /**
     * @param id 读取记录标识
     * @param skillId 技能标识，无法解析时为空
     * @param name 稳定技能名称，无法解析时为空
     * @param versionId 固定版本标识，无法解析时为空
     * @param versionNo 历史版本号，无法解析时为空
     * @param path 请求路径
     * @param status 读取结果
     * @param deliveredBytes 实际交付字节数
     * @param durationMillis 耗时毫秒数
     * @param errorCode 失败错误码
     * @param errorId 失败关联编号
     * @param createdAt 记录时间
     */
    public record Load(UUID id, UUID skillId, String name, UUID versionId, Integer versionNo,
                       String path, SkillLoadStatus status, int deliveredBytes, long durationMillis,
                       ApiErrorCode errorCode, String errorId, Instant createdAt) {
    }

    /**
     * 指定版本 TEST Run 的公开状态；不返回原始模型输入、工具参数或会话数据。
     *
     * @param runId TEST Run 标识，可用于刷新后恢复查询
     * @param skillId 临时注入的技能
     * @param versionId 临时注入的不可变版本
     * @param agentId 实际执行的 Agent
     * @param mappingRevision 试运行固定的依赖映射修订
     * @param status 试运行状态
     * @param qualifiesRelease 是否可作为当前候选发布依据
     * @param createdAt 创建时间
     * @param updatedAt 最近状态时间
     * @param preview 已脱敏的运行结果预览；不返回运行输入、租户或主体内部字段
     */
    public record Trial(UUID runId, UUID skillId, UUID versionId, UUID agentId, long mappingRevision,
                        SkillTrialStatus status, boolean qualifiesRelease, Instant createdAt, Instant updatedAt,
                        TrialPreview preview) {
        /** 保留仅包含试运行治理状态的旧构造方式，供既有 Java 调用方兼容。 */
        public Trial(UUID runId, UUID skillId, UUID versionId, UUID agentId, long mappingRevision,
                     SkillTrialStatus status, boolean qualifiesRelease, Instant createdAt, Instant updatedAt) {
            this(runId, skillId, versionId, agentId, mappingRevision, status, qualifiesRelease,
                    createdAt, updatedAt, null);
        }

        /** 将领域事实转换为不含租户和内部主体的公开视图。 */
        public static Trial from(SkillTrial trial) {
            return new Trial(trial.runId(), trial.skillId(), trial.versionId(), trial.agentId(),
                    trial.mappingRevision(), trial.status(), trial.qualifiesRelease(),
                    trial.createdAt(), trial.updatedAt());
        }

        /** 将试运行事实和已脱敏的持久化运行明细组装为公开预览。 */
        public static Trial from(SkillTrial trial, RunPersistenceService.RunDetail detail) {
            return new Trial(trial.runId(), trial.skillId(), trial.versionId(), trial.agentId(),
                    trial.mappingRevision(), trial.status(), trial.qualifiesRelease(), trial.createdAt(),
                    trial.updatedAt(), TrialPreview.from(detail));
        }
    }

    /**
     * 供技能管理页预览的 TEST Run 结果，不含调用输入原文或内部租户、主体和工具标识。
     *
     * @param status Agent 运行实际状态
     * @param output 已脱敏的最终输出
     * @param errorMessage 已脱敏的运行错误；成功时为空
     * @param startedAt 运行开始时间
     * @param finishedAt 运行结束时间；等待审批时为空
     * @param toolCalls 已脱敏并限长的工具调用摘要
     */
    public record TrialPreview(RunStatus status, String output, String errorMessage, Instant startedAt,
                               Instant finishedAt, List<TrialToolCall> toolCalls) {
        public TrialPreview {
            toolCalls = List.copyOf(toolCalls);
        }

        /** 仅从持久化层已脱敏的运行明细创建预览，避免直接序列化 Runtime 原始结果。 */
        public static TrialPreview from(RunPersistenceService.RunDetail detail) {
            return new TrialPreview(detail.run().status(), detail.run().output(), detail.run().errorMessage(),
                    detail.run().startedAt(), detail.run().finishedAt(),
                    detail.toolCalls().stream().map(TrialToolCall::from).toList());
        }
    }

    /**
     * 单条可展示的工具调用摘要。
     *
     * @param toolName 工具名称快照
     * @param inputSummary 已脱敏且限长的调用输入摘要
     * @param outputSummary 已脱敏且限长的调用输出摘要
     * @param status 工具调用状态
     * @param durationMillis 工具调用耗时毫秒数；不可用时为空
     * @param authorized 本次调用是否通过授权复核
     * @param errorMessage 已脱敏的工具错误说明
     */
    public record TrialToolCall(String toolName, String inputSummary, String outputSummary, RunStatus status,
                                Long durationMillis, boolean authorized, String errorMessage) {
        /** 从运行持久化层已脱敏的工具调用记录中提取最小展示字段。 */
        public static TrialToolCall from(RunToolCall toolCall) {
            return new TrialToolCall(toolCall.toolName(), toolCall.inputSummary(), toolCall.outputSummary(),
                    toolCall.status(), toolCall.durationMillis(), toolCall.authorized(), toolCall.errorMessage());
        }
    }
}
