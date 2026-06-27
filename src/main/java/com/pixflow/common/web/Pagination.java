package com.pixflow.common.web;

import com.pixflow.common.error.BusinessException;
import com.pixflow.common.error.CommonErrorCode;
import java.util.Map;

/**
 * 列表接口统一分页参数入口。
 */
public record Pagination(long page, long size) {
    public static final long DEFAULT_PAGE = 1L;
    public static final long DEFAULT_SIZE = 20L;
    public static final long MAX_SIZE = 100L;

    public static Pagination of(Long page, Long size) {
        long resolvedPage = page == null ? DEFAULT_PAGE : page;
        long resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 1 || resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_PARAM,
                    "分页参数非法",
                    Map.of("page", resolvedPage, "size", resolvedSize, "maxSize", MAX_SIZE));
        }
        return new Pagination(resolvedPage, resolvedSize);
    }
}
