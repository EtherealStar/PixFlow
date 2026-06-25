package com.etherealstar.pixflow.module.file.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 素材包解压扫描结果（design.md 领域对象 {@code PackageScanResult}）。
 *
 * <p>聚合一次 zip 扫描的产出：成功识别的图片相对路径列表、被跳过文件清单、图片计数与素材包状态。
 * 本对象为纯数据载体，不涉及 I/O，便于上层组装持久化与后续（SKU 提取 / 入库，任务 3）复用。</p>
 *
 * <p>状态判定（需求 1.8、1.9、1.10）：
 * <ul>
 *   <li>{@code imageCount} = 成功识别图片数 = {@code recognizedImages.size()}；</li>
 *   <li>{@code imageCount > 0} → {@code status = }{@link PackageStatus#READY}（就绪）；</li>
 *   <li>{@code imageCount == 0} → {@code status = }{@link PackageStatus#PARSE_FAILED}（解析失败），并附带 {@code failureReason}。</li>
 * </ul>
 */
public final class PackageScanResult {

    private final List<String> recognizedImages;
    private final List<SkippedFile> skippedFiles;
    private final int status;
    private final String failureReason;

    private PackageScanResult(List<String> recognizedImages, List<SkippedFile> skippedFiles,
                              int status, String failureReason) {
        this.recognizedImages = List.copyOf(recognizedImages);
        this.skippedFiles = List.copyOf(skippedFiles);
        this.status = status;
        this.failureReason = failureReason;
    }

    /**
     * 依据识别结果组装扫描结果，并自动判定状态（需求 1.8–1.10）。
     *
     * @param recognizedImages 成功识别图片相对 zip 根目录的路径列表
     * @param skippedFiles     被跳过文件清单（名称 + 原因）
     */
    public static PackageScanResult of(List<String> recognizedImages, List<SkippedFile> skippedFiles) {
        List<String> images = recognizedImages == null ? Collections.emptyList() : recognizedImages;
        List<SkippedFile> skipped = skippedFiles == null ? Collections.emptyList() : skippedFiles;
        if (images.isEmpty()) {
            return new PackageScanResult(new ArrayList<>(images), new ArrayList<>(skipped),
                    PackageStatus.PARSE_FAILED, "未识别到任何合法图片");
        }
        return new PackageScanResult(new ArrayList<>(images), new ArrayList<>(skipped),
                PackageStatus.READY, null);
    }

    /** 成功识别图片相对路径列表（相对 zip 根目录）。 */
    public List<String> getRecognizedImages() {
        return recognizedImages;
    }

    /** 被跳过文件清单。 */
    public List<SkippedFile> getSkippedFiles() {
        return skippedFiles;
    }

    /** 成功识别图片数（= {@code recognizedImages.size()}）。 */
    public int getImageCount() {
        return recognizedImages.size();
    }

    /** 素材包状态（{@link PackageStatus}）。 */
    public int getStatus() {
        return status;
    }

    /** 解析失败原因；仅当 {@code status == }{@link PackageStatus#PARSE_FAILED} 时非空。 */
    public String getFailureReason() {
        return failureReason;
    }
}
