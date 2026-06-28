package com.pixflow.infra.mq;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MQ 统一消息信封。payload 仍由业务模块定义，infra/mq 只关心版本和技术 header。
 */
public record MessageEnvelope<T>(
        int schemaVersion,
        T payload,
        Map<String, Object> headers) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public MessageEnvelope {
        headers = immutableCopy(headers);
    }

    public static <T> MessageEnvelope<T> current(T payload, Map<String, Object> headers) {
        return new MessageEnvelope<>(CURRENT_SCHEMA_VERSION, payload, headers);
    }

    public boolean supportedVersion() {
        return schemaVersion == CURRENT_SCHEMA_VERSION;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
