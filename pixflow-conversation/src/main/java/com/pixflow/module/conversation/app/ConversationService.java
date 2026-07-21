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
import com.pixflow.module.conversation.lock.ConversationLock;
import com.pixflow.module.conversation.lock.TurnLockHandle;

public class ConversationService implements ConversationTitleQuery {
    private static final int MAX_TITLE_LENGTH = 120;

    private final ConversationMapper conversationMapper;

    private final Clock clock;

    private final ConversationDeletionGuard deletionGuard;

    private final ConversationDeletionCleanup deletionCleanup;

    private final ConversationLock conversationLock;

    public ConversationService(ConversationMapper conversationMapper, Clock clock) {
        this(conversationMapper, clock, (administratorId, conversationId) -> { },
                conversationId -> { }, null);
    }

    public ConversationService(
            ConversationMapper conversationMapper,
            Clock clock,
            ConversationDeletionGuard deletionGuard,
            ConversationDeletionCleanup deletionCleanup,
            ConversationLock conversationLock) {
        this.conversationMapper = conversationMapper;
        this.clock = clock;
        this.deletionGuard = deletionGuard;
        this.deletionCleanup = deletionCleanup;
        this.conversationLock = conversationLock;
    }

    public ConversationView create(long ownerUserId, CreateConversationRequest request) {
        Instant now = clock.instant();
        ConversationEntity entity = new ConversationEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setOwnerUserId(requireOwner(ownerUserId));
        entity.setTitle(normalizeTitle(request == null ? null : request.title()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        conversationMapper.insert(entity);
        return toView(entity);
    }

    public PageResponse<ConversationView> list(long ownerUserId, long page, long size) {
        LambdaQueryWrapper<ConversationEntity> query = new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getOwnerUserId, requireOwner(ownerUserId))
                .orderByDesc(ConversationEntity::getUpdatedAt);
        IPage<ConversationEntity> result = conversationMapper.selectPage(new Page<>(page, size), query);
        return PageResponse.of(
                result.getRecords().stream().map(ConversationService::toView).toList(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize());
    }

    public ConversationView detail(long ownerUserId, String conversationId) {
        return toView(require(ownerUserId, conversationId));
    }

    @Override
    public String titleSnapshot(String conversationId) {
        String id = normalizeRequired(conversationId, "conversationId");
        ConversationEntity entity = conversationMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ConversationErrorCode.CONVERSATION_NOT_FOUND,
                    "conversation not found: " + id,
                    Map.of("conversationId", id));
        }
        return entity.getTitle() == null ? "" : entity.getTitle();
    }

    public ConversationEntity requireActive(long ownerUserId, String conversationId) {
        return require(ownerUserId, conversationId);
    }

    public void delete(long ownerUserId, String conversationId) {
        ConversationEntity entity = require(ownerUserId, conversationId);
        deletionGuard.requireDeletable(ownerUserId, entity.getId());
        if (conversationLock == null) {
            deletionCleanup.delete(entity.getId());
            conversationMapper.deleteById(entity.getId());
            return;
        }
        TurnLockHandle handle = conversationLock.tryLock(entity.getId()).orElseThrow(() ->
                new BusinessException(ConversationErrorCode.CONVERSATION_BUSY,
                        "conversation has an active turn",
                        Map.of("conversationId", entity.getId())));
        try (handle) {
            // 取得回合锁后再次检查 Task，关闭检查与删除之间的竞争窗口。
            deletionGuard.requireDeletable(ownerUserId, entity.getId());
            deletionCleanup.delete(entity.getId());
            conversationMapper.deleteById(entity.getId());
        }
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

    private static ConversationView toView(ConversationEntity entity) {
        // owner API 只暴露稳定业务事实，持久化实体不会越过模块边界。
        return new ConversationView(
                entity.getId(), entity.getTitle(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
