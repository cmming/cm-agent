package com.cmagent.core.domain;
/** 文件产物生命周期；只有 READY 且所属运行成功才允许下载。 */
public enum SkillArtifactStatus {
    /** 已预留配额但尚未完成文件持久化。 */ STAGING,
    /** 文件已持久化，等待运行成功和严格审计。 */ PENDING,
    /** 运行及审计已成功，可在有效期内下载。 */ READY,
    /** 不可交付，等待持有删除租约的实例清理。 */ DELETING,
    /** 文件已确认删除，配额不再占用。 */ DELETED
}
