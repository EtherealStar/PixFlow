package com.pixflow.module.memory.recall;

import java.util.List;
import java.util.Map;

/** 仅用于召回过滤的 File owner 解析结果，不泄露文件实体或存储位置。 */
public record ResolvedRecallReferences(
        List<String> skuIds, List<String> categoryHints, List<Map<String, Object>> trace) {
    public ResolvedRecallReferences {
        skuIds = skuIds == null ? List.of() : List.copyOf(skuIds);
        categoryHints = categoryHints == null ? List.of() : List.copyOf(categoryHints);
        trace = trace == null ? List.of() : List.copyOf(trace);
    }
}
