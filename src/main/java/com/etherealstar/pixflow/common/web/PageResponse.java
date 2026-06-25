package com.etherealstar.pixflow.common.web;

import java.util.List;

/**
 * 统一分页响应结构。
 *
 * <p>供素材包列表、任务列表、结果列表等分页接口复用（需求 4.4、12.1、13.1），
 * 返回当前页记录、总记录数与回显的分页参数。</p>
 *
 * @param records 当前页记录
 * @param total   符合条件的总记录数
 * @param page    当前页码（从 1 开始）
 * @param size    每页条数
 * @param <T>     记录类型
 */
public record PageResponse<T>(List<T> records, long total, long page, long size) {

    public static <T> PageResponse<T> of(List<T> records, long total, long page, long size) {
        return new PageResponse<>(records, total, page, size);
    }
}
