package com.cmagent.core.domain;

/**
 * 枚举动态 HTTP 工具参数可写入的请求位置。
 */
public enum HttpParameterLocation {
    /** 写入 URL 路径占位符，必须与必填 PATH 映射一一对应。 */
    PATH,

    /** 写入 URL 查询字符串。 */
    QUERY,

    /** 写入请求头；密钥头必须使用 {@code secret/...} 引用，不允许明文凭据。 */
    HEADER,

    /** 写入 JSON 请求体的指定字段。 */
    BODY,

    /** 将命名 Tool 输入字段的值直接作为整个 HTTP 请求体。 */
    BODY_ROOT
}
