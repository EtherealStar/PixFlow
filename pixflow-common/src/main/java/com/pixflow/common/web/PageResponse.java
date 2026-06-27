package com.pixflow.common.web;

import java.util.List;

/**
 * 分页出参，作为 ApiResponse.data 的统一载体。
 */
public record PageResponse<T>(List<T> records, long total, long page, long size) {
    public static <T> PageResponse<T> of(List<T> records, long total, long page, long size) {
        return new PageResponse<>(records == null ? List.of() : List.copyOf(records), total, page, size);
    }
}
