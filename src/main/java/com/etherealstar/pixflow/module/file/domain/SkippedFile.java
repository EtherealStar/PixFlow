package com.etherealstar.pixflow.module.file.domain;

import java.util.Objects;

/**
 * 解压扫描过程中被跳过的文件记录（需求 1.6、1.7）。
 *
 * @param name   被跳过文件相对 zip 根目录的路径
 * @param reason 被跳过原因（非白名单扩展名 / 无法解码等）
 */
public record SkippedFile(String name, String reason) {

    public SkippedFile {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
