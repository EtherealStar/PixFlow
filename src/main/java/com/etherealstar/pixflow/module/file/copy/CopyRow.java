package com.etherealstar.pixflow.module.file.copy;

import java.util.Objects;

/**
 * 单条文案文档数据行的解析结果（需求 3.6）。
 *
 * <p>{@code id} 列作为软关联键 {@code sku_id}；{@code productName}、{@code keywords}、
 * {@code description} 为可选列，缺列或单元格为空时为 {@code null}（入库写空值，不视为错误）。</p>
 *
 * @param skuId       软关联键（取文案文档 {@code id} 列，非空非空白）
 * @param productName 商品名（可空）
 * @param keywords    关键词（可空）
 * @param description 详细描述（可空）
 */
public record CopyRow(String skuId, String productName, String keywords, String description) {

    public CopyRow {
        Objects.requireNonNull(skuId, "skuId must not be null");
    }
}
