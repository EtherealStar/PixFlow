package com.pixflow.module.conversation.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.common.error.BusinessException;
import com.pixflow.module.conversation.persistence.ConversationEntity;
import com.pixflow.module.conversation.persistence.ConversationMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ConversationServiceTest {
    private final ConversationMapper mapper = org.mockito.Mockito.mock(ConversationMapper.class);
    private final ConversationService service = new ConversationService(
            mapper,
            Clock.fixed(Instant.parse("2026-06-30T04:00:00Z"), ZoneOffset.UTC));

    @Test
    void createsConversationWithNormalizedFields() {
        ConversationView view = service.create(7L, new CreateConversationRequest("  新会话  "));

        assertThat(view.id()).isNotBlank();
        assertThat(view.title()).isEqualTo("新会话");
        assertThat(view.archived()).isFalse();
        verify(mapper).insert(any(ConversationEntity.class));
    }

    @Test
    void rejectsTooLongTitle() {
        assertThatThrownBy(() -> service.create(7L, new CreateConversationRequest("x".repeat(121))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsArchivedConversationForActiveUse() {
        ConversationEntity entity = new ConversationEntity();
        entity.setId("conv-1");
        entity.setOwnerUserId(7L);
        entity.setArchived(true);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

        assertThatThrownBy(() -> service.requireActive(7L, "conv-1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void treatsConversationOwnedByAnotherUserAsNotFound() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.requireActive(8L, "conv-1"))
                .isInstanceOf(BusinessException.class);
    }
}
