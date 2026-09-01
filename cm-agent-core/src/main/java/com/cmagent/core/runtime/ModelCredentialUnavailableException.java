package com.cmagent.core.runtime;

/**
 * 表示无法为指定模型配置取得可用凭据。
 *
 * <p>消息文本固定为脱敏的通用说明，不携带配置标识、密钥版本或底层异常细节；
 * 适配层将其转换为受控的“模型凭据不可用”运行终态，而不是暴露解析失败原因。</p>
 */
public final class ModelCredentialUnavailableException extends RuntimeException {

    /**
     * 创建无底层原因的模型凭据不可用异常。
     */
    public ModelCredentialUnavailableException() {
        super("模型凭据不可用");
    }

    /**
     * 使用底层凭据解析失败原因构造受控异常。
     *
     * <p>仅用于服务端诊断链路：原因会保留在异常栈内供日志排查，
     * 但对外失败消息仍固定为脱敏文本。</p>
     *
     * @param cause 底层凭据解析失败原因
     */
    public ModelCredentialUnavailableException(Throwable cause) {
        super("模型凭据不可用", cause);
    }
}
