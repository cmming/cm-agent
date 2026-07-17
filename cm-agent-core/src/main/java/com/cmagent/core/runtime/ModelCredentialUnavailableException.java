package com.cmagent.core.runtime;

public final class ModelCredentialUnavailableException extends RuntimeException {

    public ModelCredentialUnavailableException() {
        super("模型凭据不可用");
    }

    public ModelCredentialUnavailableException(Throwable cause) {
        super("模型凭据不可用", cause);
    }
}
