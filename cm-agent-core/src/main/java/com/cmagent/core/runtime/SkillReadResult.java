package com.cmagent.core.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 受治理网关成功提交读取记录后返回的技能正文。
 *
 * @param content 可以交给模型的 UTF-8 文本正文
 * @param recordId 已提交的读取记录标识
 */
public record SkillReadResult(String content, UUID recordId) {

    /** 确保成功结果总能关联到持久化读取记录。 */
    public SkillReadResult {
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(recordId, "recordId 不能为空");
    }
}
