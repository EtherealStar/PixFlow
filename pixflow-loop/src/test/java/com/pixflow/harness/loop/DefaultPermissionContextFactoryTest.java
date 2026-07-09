package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.harness.loop.permission.DefaultPermissionContextFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link DefaultPermissionContextFactory} 从 {@code RuntimeState.metadata} 翻译 {@code PermissionContext}。
 */
class DefaultPermissionContextFactoryTest {

    @Test
    void emptyMetadataProducesMinimalContext() {
        RuntimeState state = new RuntimeState();
        state.setConversationId("c-1");
        var ctx = new DefaultPermissionContextFactory().create(state);
        assertThat(ctx.conversationId()).isEqualTo("c-1");
        assertThat(ctx.deniedTools()).isEmpty();
        assertThat(ctx.disabledTools()).isEmpty();
        assertThat(ctx.subagent()).isNull();
        assertThat(ctx.isSubagent()).isFalse();
    }

    @Test
    void deniedAndDisabledFlowThrough() {
        RuntimeState state = new RuntimeState();
        state.setConversationId("c-1");
        state.putMetadata("deniedTools", Set.of("rm", "kill"));
        state.putMetadata("disabledTools", List.of("web"));
        var ctx = new DefaultPermissionContextFactory().create(state);
        assertThat(ctx.deniedTools()).containsExactlyInAnyOrder("rm", "kill");
        assertThat(ctx.disabledTools()).containsExactly("web");
    }

    @Test
    void subagentConstraintFromMetadata() {
        RuntimeState state = new RuntimeState();
        state.setConversationId("c-1");
        state.putMetadata("subagent", Map.of(
                "agentType", "vision",
                "readOnly", true,
                "allowedTools", List.of("read"),
                "disallowedTools", List.of("write")));
        var ctx = new DefaultPermissionContextFactory().create(state);
        assertThat(ctx.isSubagent()).isTrue();
        assertThat(ctx.subagent().agentType()).isEqualTo("vision");
        assertThat(ctx.subagent().readOnly()).isTrue();
        assertThat(ctx.subagent().allowedTools()).containsExactly("read");
        assertThat(ctx.subagent().disallowedTools()).containsExactly("write");
    }

    @Test
    void malformedSubagentMetadataFailsClosed() {
        RuntimeState state = new RuntimeState();
        state.setConversationId("c-1");
        state.putMetadata("subagent", Map.of("readOnly", true));
        assertThatThrownBy(() -> new DefaultPermissionContextFactory().create(state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentType");
    }

    @Test
    void malformedSubagentFieldTypeFailsClosed() {
        RuntimeState state = new RuntimeState();
        state.setConversationId("c-1");
        state.putMetadata("subagent", Map.of(
                "agentType", "vision",
                "readOnly", "true"));
        assertThatThrownBy(() -> new DefaultPermissionContextFactory().create(state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readOnly");
    }

    @Test
    void blankConversationIdIsRejected() {
        RuntimeState state = new RuntimeState();
        assertThatThrownBy(() -> new DefaultPermissionContextFactory().create(state))
                .isInstanceOf(IllegalStateException.class);
    }
}
