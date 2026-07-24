package com.cmagent.server.security;

/** 登录请求，仅在认证处理期间使用，不应写入日志或审计详情。 */
public record LoginRequest(String username, String password) {
}
