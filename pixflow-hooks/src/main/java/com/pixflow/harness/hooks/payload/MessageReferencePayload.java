package com.pixflow.harness.hooks.payload;

import java.util.Objects;

/** Hook 可观察的最小消息引用，不携带 owner facts、对象 key 或 resolver 结果。 */
public record MessageReferencePayload(String referenceKey, String displayPathSnapshot) {
    public MessageReferencePayload {
        Objects.requireNonNull(referenceKey, "referenceKey");
        Objects.requireNonNull(displayPathSnapshot, "displayPathSnapshot");
    }
}
