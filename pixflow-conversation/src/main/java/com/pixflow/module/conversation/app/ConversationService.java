package com.pixflow.module.conversation.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pixflow.common.error.BusinessException;
import com.pixflow.common.web.PageResponse;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import com.pixflow.module.conversation.persistence.ConversationEntity;
import com.pixflow.module.conversation.persistence.ConversationMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class ConversationService {
    private static final int MAX_TITLE_LENGTH = 120;

    private final ConversationMapper conversationMapper;

    private final Clock clock;

    public ConversationService(ConversationMapper conversationMapper, Clock clock) {
        this.conversationMapper = conversationMapper;
        this.clock = clock;
    }

    public ConversationView create(long ownerUserId, CreateConversationRequest request) {
        Instant now = clock.instant();
        ConversationEntity entity = new ConversationEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setOwnerUserId(requireOwner(ownerUserId));
        entity.setTitle(normalizeTitle(request == null ? null : request.title()));
        entity.setArchived(false);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        conversationMapper.insert(entity);
        return ConversationView.from(entity);
    }

    public PageResponse<ConversationView> list(long ownerUserId, long page, long size, boolean includeArchived) {
        LambdaQueryWrapper<ConversationEntity> query = new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getOwnerUserId, requireOwner(ownerUserId))
                .orderByDesc(ConversationEntity::getUpdatedAt);
        if (!includeArchived) {
            query.eq(ConversationEntity::getArchived, false);
        }
        IPage<ConversationEntity> result = conversationMapper.selectPage(new Page<>(page, size), query);
        return PageResponse.of(
                result.getRecords().stream().map(ConversationView::from).toList(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize());
    }

    public ConversationView detail(long ownerUserId, String conversationId) {
        return ConversationView.from(require(ownerUserId, conversationId));
    }

    public ConversationEntity requireActive(long ownerUserId, String conversationId) {
        ConversationEntity entity = require(ownerUserId, conversationId);
        if (Boolean.TRUE.equals(entity.getArchived())) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_ARCHIVED,
                    "conversation archived: " + conversationId,
                    Map.of("conversationId", conversationId));
        }
        return entity;
    }

    public void archive(long ownerUserId, String conversationId) {
        ConversationEntity entity = require(ownerUserId, conversationId);
        if (Boolean.TRUE.equals(entity.getArchived())) {
            return;
        }
        ConversationEntity update = new ConversationEntity();
        update.setId(entity.getId());
        update.setArchived(true);
        update.setUpdatedAt(clock.instant());
        conversationMapper.updateById(update);
    }

    private ConversationEntity require(long ownerUserId, String conversationId) {
        String id = normalizeRequired(conversationId, "conversationId");
        // owner_user_id 过滤放在 service 层，避免 controller 漏传时出现横向读取。
        ConversationEntity entity = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getId, id)
                .eq(ConversationEntity::getOwnerUserId, requireOwner(ownerUserId))
                .last("limit 1"));
        if (entity == null) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND,
                    "conversation not found: " + id,
                    Map.of("conversationId", id));
        }
        return entity;
    }

    private static long requireOwner(long ownerUserId) {
        if (ownerUserId <= 0L) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND,
                    "conversation owner is required");
        }
        return ownerUserId;
    }

    private static String normalizeTitle(String title) {
        String normalized = normalizeNullable(title);
        if (normalized != null && normalized.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_TITLE_INVALID,
                    "conversation title too long",
                    Map.of("maxLength", MAX_TITLE_LENGTH));
        }
        return normalized;
    }

    private static String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND,
                    fieldName + " is required");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
