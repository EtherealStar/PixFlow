package com.pixflow.module.dag.exec;

import java.util.List;
import java.util.Objects;

/**
 * 中立文案上下文:由 task 喂入,不直连 asset_copy 表。
 */
public record CopyContext(
    String skuId,
    String productName,
    List<String> keywords,
    String description
) {
    public CopyContext {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    public static CopyContext of(String skuId, String productName,
                                  List<String> keywords, String description) {
        return new CopyContext(Objects.requireNonNull(skuId, "skuId"), productName,
            keywords, description);
    }
}
