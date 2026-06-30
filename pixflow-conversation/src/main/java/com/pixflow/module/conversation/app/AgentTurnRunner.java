package com.pixflow.module.conversation.app;

import com.pixflow.harness.loop.Attachment;
import com.pixflow.harness.loop.event.AgentEventSink;
import java.util.List;

/**
 * conversation 到 Agent/loop 的倒置接缝。
 *
 * <p>conversation 不组装 system prompt 和 tool schema；Wave 5 agent 层负责实现该 SPI。
 */
@FunctionalInterface
public interface AgentTurnRunner {
    String stream(String conversationId, String prompt, List<Attachment> attachments, AgentEventSink sink);
}
