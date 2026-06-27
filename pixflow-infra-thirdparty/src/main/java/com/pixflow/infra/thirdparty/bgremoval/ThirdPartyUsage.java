package com.pixflow.infra.thirdparty.bgremoval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ThirdPartyUsage(Integer creditsCharged, Map<String, Object> raw) {
    public ThirdPartyUsage {
        raw = raw == null || raw.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
