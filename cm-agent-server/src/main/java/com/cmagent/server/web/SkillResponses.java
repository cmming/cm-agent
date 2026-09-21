package com.cmagent.server.web;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.SkillLoadStatus;

import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
                          int versionNo, boolean enabled, Instant updatedAt) {
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
