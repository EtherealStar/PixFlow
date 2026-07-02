package com.pixflow.infra.mq.destination;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public record MessageDestination(String topic, String tag, List<String> keys) {
    public MessageDestination {
        Assert.hasText(topic, "topic must not be blank");
        Assert.hasText(tag, "tag must not be blank");
        keys = keys == null || keys.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(keys));
    }

    public static MessageDestination of(String topic, String tag) {
        return new MessageDestination(topic, tag, List.of());
    }

    public MessageDestination withKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("key must not be blank");
        }
        List<String> copy = new ArrayList<>(keys);
        copy.add(key);
        return new MessageDestination(topic, tag, copy);
    }
}
