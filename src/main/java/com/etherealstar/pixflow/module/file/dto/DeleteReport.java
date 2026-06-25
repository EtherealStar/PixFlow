package com.etherealstar.pixflow.module.file.dto;

import java.util.List;

/**
 * 素材包删除结果报告（需求 14.1、14.3）。
 *
 * <p>容错删除：即使部分物理文件删除失败也继续删除其余文件，报告含成功删除数量与失败文件列表
 * （路径 + 原因）。{@code deleted} 表示数据库记录是否已删除并返回删除成功指示。</p>
 *
 * @param packageId       被删除的素材包 id
 * @param deleted         数据库记录是否已删除
 * @param deletedFileCount 成功删除的物理文件数量
 * @param failedFiles     删除失败的文件列表（路径 + 原因）
 */
public record DeleteReport(
        Long packageId,
        boolean deleted,
        int deletedFileCount,
        List<FailedFile> failedFiles) {

    /**
     * 删除失败的单个文件记录。
     *
     * @param path   失败文件的相对路径
     * @param reason 失败原因
     */
    public record FailedFile(String path, String reason) {
    }
}
