package com.cmagent.api;

/**
 * 使用页码和页容量表达的通用分页请求。
 *
 * @param page 从零开始的页码
 * @param size 每页返回的记录数，取值范围为 1 到 200
 */
public record ApiPageRequest(int page, int size) {

    /**
     * 校验分页边界，防止无效参数进入查询层。
      *
      * @param page 从零开始的页码
      * @param size 每页返回数量
     */
    public ApiPageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("页码不能小于 0");
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("每页数量必须在 1 到 200 之间");
        }
    }
}
