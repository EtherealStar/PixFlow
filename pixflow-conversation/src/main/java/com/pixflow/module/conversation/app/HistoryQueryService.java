package com.pixflow.module.conversation.app;

import com.pixflow.common.web.PageResponse;
import com.pixflow.harness.session.persistence.MessageReadMapper;
import com.pixflow.module.conversation.config.ConversationProperties;
import com.pixflow.module.conversation.history.MessageView;

public class HistoryQueryService {
    private final ConversationService conversationService;
    private final MessageReadMapper messageReadMapper;
    private final ConversationProperties properties;

    public HistoryQueryService(
            ConversationService conversationService,
            MessageReadMapper messageReadMapper,
            ConversationProperties properties) {
        this.conversationService = conversationService;
        this.messageReadMapper = messageReadMapper;
        this.properties = properties;
    }

    public PageResponse<MessageView> timeline(String conversationId, Long page, Long size) {
        conversationService.requireActive(conversationId);
        long resolvedPage = page == null ? 1L : Math.max(1L, page);
        long resolvedSize = size == null
                ? properties.getHistory().getDefaultPageSize()
                : Math.max(1L, Math.min(size, properties.getHistory().getMaxPageSize()));
        long offset = (resolvedPage - 1L) * resolvedSize;
        long total = messageReadMapper.countMessagesByConversation(conversationId);
        return PageResponse.of(
                messageReadMapper.findMessagesByConversation(conversationId, offset, resolvedSize)
                        .stream()
                        .map(MessageView::from)
                        .toList(),
                total,
                resolvedPage,
                resolvedSize);
    }
}
