package com.cmagent.api;

public record ApiPageRequest(int page, int size) {

    public ApiPageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("页码不能小于 0");
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("每页数量必须在 1 到 200 之间");
        }
    }
}
