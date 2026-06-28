package com.pixflow.infra.mq;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public record PublishRequest(
        String exchange,
        String routingKey,
        Object payload,
        Map<String, Object> headers,
        int schemaVersion,
        Duration confirmTimeout) {

    public PublishRequest {
        Assert.hasText(exchange, "exchange must not be blank");
        Assert.hasText(routingKey, "routingKey must not be blank");
        Assert.notNull(payload, "payload must not be null");
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        headers = immutableCopy(headers);
        confirmTimeout = confirmTimeout == null ? Duration.ofSeconds(5) : confirmTimeout;
    }

    public static PublishRequest of(String exchange, String routingKey, Object payload) {
        return new PublishRequest(exchange, routingKey, payload, Map.of(), MessageEnvelope.CURRENT_SCHEMA_VERSION, null);
    }

    public PublishRequest withConfirmTimeout(Duration timeout) {
        return new PublishRequest(exchange, routingKey, payload, headers, schemaVersion, timeout);
    }

    public PublishRequest withHeader(String name, Object value) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("header name must not be blank");
        }
        Map<String, Object> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new PublishRequest(exchange, routingKey, payload, copy, schemaVersion, confirmTimeout);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
