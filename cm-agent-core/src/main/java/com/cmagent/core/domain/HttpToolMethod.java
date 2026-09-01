package com.cmagent.core.domain;

/**
 * 枚举动态 HTTP 工具支持的请求方法。
 */
public enum HttpToolMethod {
    /** 只读请求，参数仅允许写入 PATH、QUERY 和 HEADER，不允许 BODY。 */
    GET,

    /** 写入或提交请求，参数允许写入全部位置。 */
    POST
}
