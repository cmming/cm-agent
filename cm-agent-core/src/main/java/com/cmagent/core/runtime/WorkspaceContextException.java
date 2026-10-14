package com.cmagent.core.runtime;

import com.cmagent.api.ApiErrorCode;
import java.util.Objects;

/** 已脱敏的工作区资料失败；宿主需保留同一编号完成严格失败收口，不能回退为无上下文重跑。 */
public final class WorkspaceContextException extends RuntimeException {
    /** 稳定错误分类，供 HTTP 与日志共同使用。 */
    private final ApiErrorCode code;
    /** 原 Run 的关联编号，不携带主机路径或资料正文。 */
    private final String errorId;
    /** 同一异常跨HTTP/SSE线程传播时只由首个诊断边界记录，避免重复堆栈。 */
    private final java.util.concurrent.atomic.AtomicBoolean diagnostic = new java.util.concurrent.atomic.AtomicBoolean();

    /** 创建安全异常；message 必须为宿主选择的中文说明，不得传入原始文件系统异常消息。 */
    public WorkspaceContextException(ApiErrorCode code, String message, String errorId) {
        super(message);
        if (message == null || message.isBlank() || errorId == null || errorId.isBlank()) throw new IllegalArgumentException("资料失败必须包含安全原因与关联编号");
        this.code = Objects.requireNonNull(code); this.errorId = errorId;
    }
    /** @return 稳定错误码 */ public ApiErrorCode code() { return code; }
    /** @return 前后端相同的关联编号 */ public String errorId() { return errorId; }
    /** @return 是否首次获得安全诊断职责；来源未记录时最终接口边界必须补充日志 */
    public boolean claimDiagnostic() { return diagnostic.compareAndSet(false, true); }
}
