package com.pixflow.module.vision.analyze;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import java.util.Objects;

/**
 * 指向一张待分析图片的只读引用，包含采样和 prompt 所需的轻量元数据。
 */
public record VisionImageRef(
        ObjectLocation object,
        String skuId,
        String viewId,
        String hintLabel,
        Long sizeHintBytes) {

    public VisionImageRef {
        object = Objects.requireNonNull(object, "object");
        skuId = normalizeNullable(skuId);
        viewId = normalizeNullable(viewId);
        hintLabel = normalizeNullable(hintLabel);
        if (sizeHintBytes != null && sizeHintBytes < 0) {
            throw new IllegalArgumentException("sizeHintBytes must be non-negative");
        }
    }

    public static VisionImageRef of(BucketType bucket, String key, String skuId, String viewId, String hintLabel) {
        return new VisionImageRef(ObjectLocation.of(bucket, key), skuId, viewId, hintLabel, null);
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
