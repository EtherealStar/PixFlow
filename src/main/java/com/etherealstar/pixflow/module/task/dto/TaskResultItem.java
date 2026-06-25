package com.etherealstar.pixflow.module.task.dto;

import com.etherealstar.pixflow.module.task.entity.ProcessResult;

/**
 * 任务结果项（需求 11.3、12.4、12.5）。
 *
 * <p>失败记录返回 {@code errorMsg}（需求 11.3）；成功记录返回 {@code previewUrl} 指向结果图原始字节流端点。</p>
 *
 * @param id            结果 id
 * @param taskId        所属任务 id
 * @param imageId       对应原图 id
 * @param skuId         SKU ID
 * @param branchId      支路标识（同一 imageId 下唯一）
 * @param outputPath    处理后图片路径（失败为 null）
 * @param generatedCopy 生成文案（文案分支结果）
 * @param status        0 待处理 / 1 成功 / 2 失败
 * @param errorMsg      失败原因（成功为 null）
 * @param previewUrl    成功结果图预览 URL（失败为 null）
 */
public record TaskResultItem(
        Long id,
        Long taskId,
        Long imageId,
        String skuId,
        String branchId,
        String outputPath,
        String generatedCopy,
        Integer status,
        String errorMsg,
        String previewUrl) {

    /** 预览 URL 模板：指向结果图原始字节流端点（见 {@code ResultPreviewController})。 */
    private static final String RAW_URL_TEMPLATE = "/api/asset/result/%d/raw";

    public static TaskResultItem from(ProcessResult r) {
        boolean success = r.getStatus() != null && r.getStatus() == 1
                && r.getOutputPath() != null && !r.getOutputPath().isBlank();
        String previewUrl = success ? String.format(RAW_URL_TEMPLATE, r.getId()) : null;
        return new TaskResultItem(
                r.getId(),
                r.getTaskId(),
                r.getImageId(),
                r.getSkuId(),
                r.getBranchId(),
                r.getOutputPath(),
                r.getGeneratedCopy(),
                r.getStatus(),
                r.getErrorMsg(),
                previewUrl);
    }
}
