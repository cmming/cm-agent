package com.cmagent.api;

import java.util.List;

public record ApiPageResponse<T>(List<T> items, long total, int page, int size) {

    public ApiPageResponse {
        items = List.copyOf(items);
    }
}
