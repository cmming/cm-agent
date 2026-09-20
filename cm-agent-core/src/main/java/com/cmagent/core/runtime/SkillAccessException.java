package com.cmagent.core.runtime;

import com.cmagent.api.ApiErrorCode;

import java.util.Objects;

/**
 * 技能运行链路中的受控异常，保留跨 HTTP、SSE 和 Adapter 边界所需的稳定分类。
 *
 * <p>该异常不携带 HTTP 状态，也不保存原始上游错误；Server 负责协议映射，Adapter
 * 根据 {@link #fatal()} 判断是否必须中止本轮，未知原因另交最终诊断日志边界。</p>
 */
public final class SkillAccessException extends RuntimeException {

    private final ApiErrorCode code;
    private final String errorId;
    private final boolean fatal;

    /**
     * 创建脱敏的技能访问异常。
     *
     * @param code 稳定错误码
     * @param safeMessage 可安全展示给用户的中文原因
     * @param errorId 可关联后台日志的错误编号
     * @param fatal 是否必须阻止本轮继续调用模型或业务工具
     */
    public SkillAccessException(ApiErrorCode code, String safeMessage, String errorId, boolean fatal) {
        super(requireText(safeMessage, "safeMessage 不能为空"));
        this.code = Objects.requireNonNull(code, "code 不能为空");
        this.errorId = requireText(errorId, "errorId 不能为空");
        this.fatal = fatal;
    }

    /** @return 稳定错误码 */
    public ApiErrorCode code() {
        return code;
    }

    /** @return 已脱敏的用户可见原因 */
    public String safeMessage() {
        return getMessage();
    }

    /** @return 日志关联错误编号 */
    public String errorId() {
        return errorId;
    }

    /** @return 是否必须中止本轮运行 */
    public boolean fatal() {
        return fatal;
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
