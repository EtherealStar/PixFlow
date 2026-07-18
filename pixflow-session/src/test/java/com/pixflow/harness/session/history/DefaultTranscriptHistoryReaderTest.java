package com.pixflow.harness.session.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.session.mapping.MessageMapper;
import com.pixflow.harness.session.persistence.MessageEntity;
import com.pixflow.harness.session.persistence.MessageReadMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultTranscriptHistoryReaderTest {
    @Test
    void returnsTypedOrderedReferencesWithoutRawMetadata() {
        MessageMapper messageMapper = new MessageMapper(new ObjectMapper());
        MessageEntity entity = messageMapper.toEntity("conv-1", Message.user("process", List.of(
                new MessageReference("package:1", "summer.zip"),
                new MessageReference("package:1/image:2", "summer.zip / front.png"))), 7);
        MessageReadMapper readMapper = mock(MessageReadMapper.class);
        when(readMapper.findMessagesByConversation("conv-1", 0, 20)).thenReturn(List.of(entity));
        when(readMapper.countMessagesByConversation("conv-1")).thenReturn(1L);
        TranscriptHistoryReader reader = new DefaultTranscriptHistoryReader(readMapper, messageMapper);

        assertThat(reader.count("conv-1")).isEqualTo(1L);
        assertThat(reader.page("conv-1", 0, 20)).singleElement().satisfies(view -> {
            assertThat(view.seq()).isEqualTo(7L);
            assertThat(view.references()).containsExactly(
                    new MessageReference("package:1", "summer.zip"),
                    new MessageReference("package:1/image:2", "summer.zip / front.png"));
        });
    }
}
