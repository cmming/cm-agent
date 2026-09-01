package com.cmagent.core.security;

/**
 * 表示权限或工具授权校验的允许状态及其原因。
 *
 * <p>拒绝结果中的 {@code reason} 会透出到授权拒绝审计和对外错误提示，必须保持受控、
 * 不含堆栈、内部资源标识或敏感信息；允许结果同样携带固定的可读文案。</p>
 *
 * @param allowed 是否允许访问
 * @param reason 决定原因；允许时为固定文案，拒绝时为受控拒绝原因
 */
public record AuthorizationDecision(boolean allowed, String reason) {

    /**
     * 创建允许访问的授权决定。
     *
     * @return 不携带附加原因的允许决定
     */
    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(true, "允许访问");
    }

    /**
     * 创建包含拒绝原因的授权决定。
     *
     * @param reason 拒绝访问的原因，最终会进入审计与对外提示，须保持受控
     * @return 拒绝访问的决定
     */
    public static AuthorizationDecision deny(String reason) {
        return new AuthorizationDecision(false, reason);
    }
}
