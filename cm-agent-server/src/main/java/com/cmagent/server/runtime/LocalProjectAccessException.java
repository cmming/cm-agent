package com.cmagent.server.runtime;

/** 已在最终服务边界完成审计/诊断的安全错误；不携带宿主异常或路径。 */
public final class LocalProjectAccessException extends RuntimeException {
    /** 稳定业务错误码，不能由请求内容指定。 */
    private final String errorCode;
    /** 与后台最终诊断一致的随机关联编号。 */
    private final String errorId;
    /** 明确的HTTP语义，不以成功状态返回失败。 */
    private final int status;

    /** @param errorCode 固定错误分类 @param errorId 随机关联编号 @param status HTTP状态 @param message 安全中文原因 */
    public LocalProjectAccessException(String errorCode, String errorId, int status, String message) {
        super(message);
        this.errorCode = errorCode; this.errorId = errorId; this.status = status;
    }
    public String errorCode() { return errorCode; }
    public String errorId() { return errorId; }
    public int status() { return status; }
}
