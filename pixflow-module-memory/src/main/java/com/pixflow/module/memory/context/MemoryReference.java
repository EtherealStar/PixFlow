package com.pixflow.module.memory.context;

/** 用户消息中已确认的 canonical 素材引用；展示路径不参与身份推断。 */
public record MemoryReference(String referenceKey, String displayPathSnapshot) {
    public MemoryReference {
        if (referenceKey == null || referenceKey.isBlank()) {
            throw new IllegalArgumentException("referenceKey must not be blank");
        }
        referenceKey = referenceKey.trim();
        displayPathSnapshot = displayPathSnapshot == null ? "" : displayPathSnapshot.trim();
    }
}
