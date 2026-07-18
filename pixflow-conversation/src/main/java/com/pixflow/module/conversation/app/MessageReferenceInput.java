package com.pixflow.module.conversation.app;

/** 客户端提交的消息引用候选；持久化前必须由 MessageReferenceValidator 重新校验。 */
public record MessageReferenceInput(String referenceKey, String displayPathSnapshot) {
}
