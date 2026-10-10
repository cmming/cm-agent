package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 停止与活动运行查询的受控业务错误，消息由服务端固定构造，不能包含底层异常。 */
public final class RunControlException extends ResponseStatusException {
    /** 客户端可稳定分支处理的控制错误码。 */
    private final ApiErrorCode errorCode;

    /**
     * 创建 409 控制失败；日志与响应由统一 HTTP/SSE 边界关联，避免多层重复打印。
     * @param errorCode 控制冲突或控制能力不可用的稳定码
     * @param reason 可行动的脱敏中文原因
     */
    public RunControlException(ApiErrorCode errorCode, String reason) {
        super(HttpStatus.CONFLICT, reason);
        this.errorCode = errorCode;
    }

    /** @return 当前受控失败的稳定错误码 */
    public ApiErrorCode errorCode() { return errorCode; }
}
