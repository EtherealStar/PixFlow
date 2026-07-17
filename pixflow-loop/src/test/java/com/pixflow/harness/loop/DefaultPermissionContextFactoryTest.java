package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.harness.loop.permission.DefaultPermissionContextFactory;
import com.pixflow.harness.permission.PermissionPlanMode;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.PermissionRuntimeScope;
import org.junit.jupiter.api.Test;

class DefaultPermissionContextFactoryTest {
    @Test
    void trustedRuntimeFieldsBecomePermissionContext() {
        RuntimeState state = state();
        state.setPermissionPrincipal(new PermissionPrincipal("42", "admin"));
        state.setRuntimeScope(RuntimeScope.main());
        state.setPermissionPlanMode(PermissionPlanMode.ACTIVE);
        state.setTraceId("trace-1");

        var context = new DefaultPermissionContextFactory().create(state);

        assertThat(context.principal().userId()).isEqualTo("42");
        assertThat(context.runtimeScope()).isEqualTo(PermissionRuntimeScope.MAIN);
        assertThat(context.planMode()).isEqualTo(PermissionPlanMode.ACTIVE);
        assertThat(context.conversationId()).isEqualTo("conv-1");
        assertThat(context.toolCallId()).isEqualTo("trace-1");
    }

    @Test
    void exploreChildIsMappedExplicitly() {
        RuntimeState state = state();
        state.setPermissionPrincipal(new PermissionPrincipal("42", "admin"));
        state.setRuntimeScope(RuntimeScope.of("explore"));

        assertThat(new DefaultPermissionContextFactory().create(state).runtimeScope())
                .isEqualTo(PermissionRuntimeScope.EXPLORE_CHILD);
    }

    @Test
    void unknownChildScopeFailsClosedAsInternal() {
        RuntimeState state = state();
        state.setPermissionPrincipal(new PermissionPrincipal("42", "admin"));
        state.setRuntimeScope(RuntimeScope.of("legacy-vision"));

        assertThat(new DefaultPermissionContextFactory().create(state).runtimeScope())
                .isEqualTo(PermissionRuntimeScope.INTERNAL);
    }

    @Test
    void metadataCannotManufactureAPrincipal() {
        RuntimeState state = state();
        state.putMetadata("userId", "42");
        state.putMetadata("username", "admin");

        assertThat(new DefaultPermissionContextFactory().create(state).principal()).isNull();
    }

    @Test
    void blankConversationIdIsRejected() {
        RuntimeState state = new RuntimeState();
        assertThatThrownBy(() -> new DefaultPermissionContextFactory().create(state))
                .isInstanceOf(IllegalStateException.class);
    }

    private static RuntimeState state() {
        RuntimeState state = new RuntimeState();
        state.setConversationId("conv-1");
        return state;
    }
}
