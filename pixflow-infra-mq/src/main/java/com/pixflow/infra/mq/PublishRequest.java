package com.pixflow.infra.mq;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public record PublishRequest(
        String topic,
        String tag,
        List<String> keys,
        Object payload,
        Map<String, Object> headers,
        int schemaVersion,
        Duration sendTimeout) {

    public PublishRequest {
        Assert.hasText(topic, "topic must not be blank");
        Assert.hasText(tag, "tag must not be blank");
        Assert.notNull(payload, "payload must not be null");
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        keys = immutableList(keys);
        headers = immutableMap(headers);
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(5) : sendTimeout;
    }

    public static PublishRequest of(String topic, String tag, Object payload) {
        return new PublishRequest(
                topic, tag, List.of(), payload, Map.of(), MessageEnvelope.CURRENT_SCHEMA_VERSION, null);
    }

    public PublishRequest withKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("key must not be blank");
        }
        List<String> copy = new ArrayList<>(keys);
        copy.add(key);
        return new PublishRequest(topic, tag, copy, payload, headers, schemaVersion, sendTimeout);
    }

    public PublishRequest withKeys(List<String> newKeys) {
        return new PublishRequest(topic, tag, newKeys, payload, headers, schemaVersion, sendTimeout);
    }

    public PublishRequest withSendTimeout(Duration timeout) {
        return new PublishRequest(topic, tag, keys, payload, headers, schemaVersion, timeout);
    }

    public PublishRequest withHeader(String name, Object value) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("header name must not be blank");
        }
        Map<String, Object> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new PublishRequest(topic, tag, keys, payload, copy, schemaVersion, sendTimeout);
    }

    private static List<String> immutableList(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
