package com.cmagent.server.web;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.SkillLoadStatus;

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
     */
    public record Binding(UUID bindingId, UUID skillId, String name, String description,
                          int versionNo, boolean enabled) {
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
}
