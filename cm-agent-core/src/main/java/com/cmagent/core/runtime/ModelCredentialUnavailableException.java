package com.cmagent.core.runtime;

/**
 * ModelCredentialUnavailableException 的核心领域类型。
 */
public final class ModelCredentialUnavailableException extends RuntimeException {

    /**
     * 构造 ModelCredentialUnavailableException 实例并校验输入参数。
     */
    public ModelCredentialUnavailableException() {
        super("模型凭据不可用");
    }

    /**
     * 构造 ModelCredentialUnavailableException 实例并校验输入参数。
     */
    public ModelCredentialUnavailableException(Throwable cause) {
        super("模型凭据不可用", cause);
    }
}
