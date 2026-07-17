package com.pixflow.module.rubrics.judge;

import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.module.rubrics.evidence.EvidenceEntry;

@FunctionalInterface
public interface EvidenceImageResolver {
    ChatMessage.ImageContent resolve(EvidenceEntry entry);
}
