package com.cmagent.api;

import java.util.List;

/**
 * 使用页码分页时的通用响应结构。
 *
 * @param items 当前页的数据快照
 * @param total 满足查询条件的总记录数
 * @param page 当前页码
 * @param size 请求的页容量
 * @param <T> 列表元素类型
 */
public record ApiPageResponse<T>(List<T> items, long total, int page, int size) {

    /**
     * 对当前页数据执行防御性复制，避免响应创建后被外部集合修改。
      *
      * @param items 当前页数据
      * @param total 满足条件的总记录数
      * @param page 从零开始的页码
      * @param size 每页返回数量
     */
    public ApiPageResponse {
        items = List.copyOf(items);
    }
}
