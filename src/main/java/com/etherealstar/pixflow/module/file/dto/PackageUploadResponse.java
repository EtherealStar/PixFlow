package com.etherealstar.pixflow.module.file.dto;

import com.etherealstar.pixflow.module.file.domain.PackageScanResult;
import com.etherealstar.pixflow.module.file.domain.SkippedFile;
import java.util.List;

/**
 * 素材包上传响应体。
 *
 * <p>返回素材包基础信息与本次扫描结果（图片计数、状态、被跳过文件清单、失败原因），
 * 供前端展示上传校验反馈（需求 1.6–1.10）。</p>
 *
 * @param packageId    素材包 id
 * @param name         素材包名称（取上传 zip 文件名）
 * @param status       素材包状态（1 就绪 / 2 解析失败）
 * @param imageCount   成功识别图片数
 * @param skippedFiles 被跳过文件清单（名称 + 原因）
 * @param failureReason 解析失败原因（status==2 时非空）
 */
public record PackageUploadResponse(
        Long packageId,
        String name,
        Integer status,
        Integer imageCount,
        List<SkippedFile> skippedFiles,
        String failureReason) {

    /**
     * 由持久化后的素材包 id/名称与扫描结果组装响应。
     */
    public static PackageUploadResponse from(Long packageId, String name, PackageScanResult scan) {
        return new PackageUploadResponse(
                packageId,
                name,
                scan.getStatus(),
                scan.getImageCount(),
                scan.getSkippedFiles(),
                scan.getFailureReason());
    }
}
