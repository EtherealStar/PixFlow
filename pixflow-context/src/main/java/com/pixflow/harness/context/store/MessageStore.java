package com.pixflow.harness.context.store;

import com.pixflow.harness.context.compaction.CompactionTrigger;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MessageStore {
    private final TranscriptPort transcriptPort;
    private final List<Message> messages = new ArrayList<>();
    private String conversationId;

    public MessageStore() {
        this(null);
    }

    public MessageStore(TranscriptPort transcriptPort) {
        this.transcriptPort = transcriptPort;
    }

    public static MessageStore transcriptBacked(TranscriptPort transcriptPort) {
        return new MessageStore(transcriptPort);
    }

    public void bindConversation(String conversationId) {
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
    }

    public Message appendUser(String content) {
        return appendOne(Message.user(content));
    }

    public Message appendAssistant(Message assistant) {
        if (assistant.role() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("assistant message expected");
        }
        return appendOne(assistant);
    }

    public List<Message> appendToolResults(List<Message> results) {
        for (Message result : results) {
            if (result.role() != MessageRole.TOOL_RESULT) {
                throw new IllegalArgumentException("tool result message expected");
            }
        }
        return appendMany(results);
    }

    public List<Message> appendAttachments(List<Message> attachments) {
        for (Message attachment : attachments) {
            if (attachment.role() != MessageRole.ATTACHMENT) {
                throw new IllegalArgumentException("attachment message expected");
            }
        }
        return appendMany(attachments);
    }

    /**
     * 记录 skill 工具调用事件 trail。
     *
     * <p>role 是 {@link MessageRole#USER}，content 是描述性 metadata；调用方约定
     * "事件 trail 是 agent 行为而非用户产生"，所以 role 取 USER 但内容是结构化事件描述。
     *
     * @param skillName skill 名称（剥 {@code skill__} 前缀）
     * @param skillVersion skill 版本号
     * @param bodyChars 返回 body 字节数（≤ 50KB 内置；外置由 handler 完成）
     * @return 持久化的 message
     */
    public Message appendSkillInvocation(String skillName, int skillVersion, int bodyChars) {
        Objects.requireNonNull(skillName, "skillName");
        MessageMetadata metadata = MessageMetadata.of(Map.of(
                MessageMetadata.EVENT, MessageMetadata.EVENT_SKILL_INVOCATION,
                "skill_name", skillName,
                "skill_version", skillVersion,
                "body_chars", bodyChars
        ));
        return appendOne(Message.userEvent(
                "[skill_invocation] " + skillName,
                metadata));
    }

    /**
     * 记录 Plan 模式切换事件 trail。
     *
     * <p>role 是 {@link MessageRole#USER}，content 是描述性 metadata；状态归属
     * {@code RuntimeState.metadata["planMode"]}，本方法只留审计行。
     *
     * @param from 切换前状态（{@code "OFF"} / {@code "ACTIVE"} / 其他字符串）
     * @param to 切换后状态
     * @return 持久化的 message
     */
    public Message appendPlanModeChange(String from, String to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        MessageMetadata metadata = MessageMetadata.of(Map.of(
                MessageMetadata.EVENT, MessageMetadata.EVENT_PLAN_MODE_CHANGE,
                "from", from,
                "to", to
        ));
        return appendOne(Message.userEvent(
                "[plan_mode_change] " + from + " -> " + to,
                metadata));
    }

    public List<Message> currentMessages() {
        return List.copyOf(messages);
    }

    public List<Message> seedMessages(List<Message> seed) {
        messages.clear();
        messages.addAll(seed == null ? List.of() : seed);
        return currentMessages();
    }

    public List<Message> replaceMessagesForCompaction(
            List<Message> replacement,
            CompactionTrigger trigger,
            Map<String, Object> metadata) {
        List<Message> safeReplacement = List.copyOf(replacement == null ? List.of() : replacement);
        List<Message> persisted = transcriptPort == null
                ? safeReplacement
                : transcriptPort.replaceForCompaction(requiredConversationId(), safeReplacement, trigger, metadata);
        messages.clear();
        messages.addAll(persisted);
        return currentMessages();
    }

    public void flush() {
        // 目前 append 已写穿透；保留显式 flush 入口给后续 session 实现对齐生命周期。
    }

    private Message appendOne(Message message) {
        return appendMany(List.of(message)).get(0);
    }

    private List<Message> appendMany(List<Message> batch) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }
        List<Message> prepared = batch.stream()
                .map(message -> message.createdAt() == null
                        ? new Message(message.id(), message.role(), message.content(), message.toolCallId(), message.metadata(), Instant.now())
                        : message)
                .toList();
        List<Message> persisted = transcriptPort == null
                ? prepared
                : transcriptPort.append(requiredConversationId(), prepared);
        messages.addAll(persisted);
        return List.copyOf(persisted);
    }

    private String requiredConversationId() {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalStateException("conversationId is required when transcriptPort is configured");
        }
        return conversationId;
    }
}
