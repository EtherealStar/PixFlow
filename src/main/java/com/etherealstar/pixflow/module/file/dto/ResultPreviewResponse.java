package com.etherealstar.pixflow.module.file.dto;

/**
 * 结果图预览响应（需求 4.6）。
 *
 * @param resultId 结果图 id
 * @param skuId    所属 SKU ID
 * @param url      可访问的预览 URL（指向原始图片字节流端点）
 */
public record ResultPreviewResponse(Long resultId, String skuId, String url) {
}
