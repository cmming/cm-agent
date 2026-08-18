package com.cmagent.core.runtime;

/**
 * 表示无法为指定模型配置取得可用凭据。
 */
public final class ModelCredentialUnavailableException extends RuntimeException {

    /**
     * 创建模型凭据不可用异常并保留安全错误信息。
      *
      * @param cause 触发当前异常的原始原因
     */
    public ModelCredentialUnavailableException() {
        super("模型凭据不可用");
    }

    /**
     * 使用底层凭据解析失败原因构造受控异常。
     *
     * @param cause 底层凭据解析失败原因
     */
    public ModelCredentialUnavailableException(Throwable cause) {
        super("模型凭据不可用", cause);
    }
}
