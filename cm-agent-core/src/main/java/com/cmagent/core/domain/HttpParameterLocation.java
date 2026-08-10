package com.cmagent.core.domain;

/**
 * 枚举动态 HTTP 工具参数可写入的请求位置。
 */
public enum HttpParameterLocation {
    PATH,
    QUERY,
    HEADER,
    BODY,
    /** 将命名 Tool 输入字段的值直接作为整个 HTTP 请求体。 */
    BODY_ROOT
}
