package com.pixflow.module.conversation.app;

import com.pixflow.common.error.BusinessException;
import com.pixflow.common.web.PageResponse;
import com.pixflow.harness.session.persistence.MessageReadMapper;
import com.pixflow.module.conversation.config.ConversationProperties;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.conversation.history.MessageView;
import java.util.Map;

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

    public PageResponse<MessageView> timeline(long ownerUserId, String conversationId, Long page, Long size) {
        conversationService.requireActive(ownerUserId, conversationId);
        long resolvedPage = page == null ? 1L : Math.max(1L, page);
        long resolvedSize = size == null
                ? properties.getHistory().getDefaultPageSize()
                : Math.max(1L, Math.min(size, properties.getHistory().getMaxPageSize()));
        if (resolvedPage > Long.MAX_VALUE / resolvedSize) {
            throw new BusinessException(ConversationErrorCode.HISTORY_PAGE_INVALID,
                    "history page offset overflow",
                    Map.of("page", resolvedPage, "size", resolvedSize));
        }
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
