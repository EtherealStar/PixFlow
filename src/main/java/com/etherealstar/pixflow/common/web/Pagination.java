package com.etherealstar.pixflow.common.web;

import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分页参数校验与归一化工具。
 *
 * <p>统一约束：{@code page ≥ 1}（默认 1），{@code 1 ≤ size ≤ 100}（默认 20）。
 * 越界即抛出 {@link ErrorCode#INVALID_PAGINATION}（需求 4.5、13.2），错误 details 含约束范围，
 * 便于前端定位非法参数。</p>
 *
 * <p>{@code null} 视为未指定，回退默认值；显式越界值（如 page=0、size=200）视为非法并拒绝。</p>
 */
public final class Pagination {

    public static final long DEFAULT_PAGE = 1L;
    public static final long DEFAULT_SIZE = 20L;
    public static final long MIN_PAGE = 1L;
    public static final long MIN_SIZE = 1L;
    public static final long MAX_SIZE = 100L;

    private final long page;
    private final long size;

    private Pagination(long page, long size) {
        this.page = page;
        this.size = size;
    }

    /**
     * 校验并归一化分页参数。
     *
     * @param page 请求页码（{@code null} 取默认 1）
     * @param size 请求每页条数（{@code null} 取默认 20）
     * @return 合法的分页参数
     * @throws BusinessException 当 {@code page < 1} 或 {@code size} 不在 [1,100] 时（INVALID_PAGINATION）
     */
    public static Pagination of(Long page, Long size) {
        long resolvedPage = page == null ? DEFAULT_PAGE : page;
        long resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < MIN_PAGE || resolvedSize < MIN_SIZE || resolvedSize > MAX_SIZE) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("page", resolvedPage);
            details.put("size", resolvedSize);
            details.put("minPage", MIN_PAGE);
            details.put("minSize", MIN_SIZE);
            details.put("maxSize", MAX_SIZE);
            throw new BusinessException(ErrorCode.INVALID_PAGINATION,
                    "分页参数非法：page 须 ≥ 1，size 须在 [1,100] 之间", details);
        }
        return new Pagination(resolvedPage, resolvedSize);
    }

    public long page() {
        return page;
    }

    public long size() {
        return size;
    }
}
