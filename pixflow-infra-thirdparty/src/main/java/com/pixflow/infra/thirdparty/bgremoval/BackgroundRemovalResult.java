package com.pixflow.infra.thirdparty.bgremoval;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record BackgroundRemovalResult(
        byte[] image,
        String contentType,
        ThirdPartyUsage usage,
        Map<String, Object> metadata) {
    public BackgroundRemovalResult {
        contentType = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
        if (image != null) {
            image = Arrays.copyOf(image, image.length);
        }
        metadata = metadata == null || metadata.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    @Override
    public byte[] image() {
        return image == null ? null : Arrays.copyOf(image, image.length);
    }
}
