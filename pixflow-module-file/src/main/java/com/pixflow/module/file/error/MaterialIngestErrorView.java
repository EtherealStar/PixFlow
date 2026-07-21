package com.pixflow.module.file.error;

import java.time.Instant;

/** 素材导入错误的公开事实，刻意不暴露压缩包内路径和数据库主键。 */
public record MaterialIngestErrorView(
        long packageId,
        String stage,
        String code,
        String message,
        Instant createdAt) {
}
