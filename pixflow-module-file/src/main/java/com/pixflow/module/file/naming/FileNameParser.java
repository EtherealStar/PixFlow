package com.pixflow.module.file.naming;

import java.util.Arrays;
import java.util.List;

public class FileNameParser {
    private final SkuExtractor skuExtractor;

    public FileNameParser(SkuExtractor skuExtractor) {
        this.skuExtractor = skuExtractor;
    }

    public ParsedName parse(String originalPath) {
        String baseName = stripExtension(lastSegment(originalPath));
        String[] rawParts = baseName.split("_", -1);
        boolean hasBlankPart = Arrays.stream(rawParts).anyMatch(part -> part == null || part.isBlank());
        List<String> parts = Arrays.stream(rawParts)
                .map(String::trim)
                .toList();

        // 文件名是业务绑定依据：只认稳定的 2 段/3 段规则，其余交给可替换的 SKU 提取器兜底。
        if (!hasBlankPart && parts.size() == 3) {
            return new ParsedName(parts.get(0), parts.get(1), parts.get(2));
        }
        if (!hasBlankPart && parts.size() == 2) {
            return new ParsedName(null, parts.get(0), parts.get(1));
        }
        return new ParsedName(null, skuExtractor.extract(baseName), null);
    }

    private static String lastSegment(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private static String stripExtension(String name) {
        int index = name.lastIndexOf('.');
        if (index <= 0) {
            return name;
        }
        return name.substring(0, index);
    }
}
