package com.pixflow.infra.thirdparty.bgremoval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record BackgroundRemovalOptions(
        BackgroundRemovalOutputFormat outputFormat,
        boolean crop,
        Integer featherRadius,
        Map<String, Object> providerHints) {

    public BackgroundRemovalOptions {
        outputFormat = outputFormat == null ? BackgroundRemovalOutputFormat.PNG : outputFormat;
        providerHints = providerHints == null || providerHints.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(providerHints));
    }

    public static BackgroundRemovalOptions defaults() {
        return new BackgroundRemovalOptions(BackgroundRemovalOutputFormat.PNG, false, null, Map.of());
    }
}
