package com.pixflow.module.file.copydoc;

import com.pixflow.module.file.config.FileProperties;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class CopyDocColumnMapping {
    private final Map<String, String> normalizedHeaders;

    private final FileProperties.Copydoc properties;

    public CopyDocColumnMapping(List<String> headers, FileProperties.Copydoc properties) {
        this.properties = properties;
        this.normalizedHeaders = headers.stream()
                .collect(Collectors.toMap(CopyDocColumnMapping::normalize, header -> header, (left, right) -> left));
    }

    public Optional<String> skuIdColumn() {
        return find(properties.getSkuIdColumns());
    }

    public Optional<String> productNameColumn() {
        return find(properties.getProductNameColumns());
    }

    public Optional<String> keywordsColumn() {
        return find(properties.getKeywordsColumns());
    }

    public Optional<String> descriptionColumn() {
        return find(properties.getDescriptionColumns());
    }

    private Optional<String> find(List<String> candidates) {
        return candidates.stream()
                .map(CopyDocColumnMapping::normalize)
                .filter(normalizedHeaders::containsKey)
                .findFirst()
                .map(normalizedHeaders::get);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }
}
