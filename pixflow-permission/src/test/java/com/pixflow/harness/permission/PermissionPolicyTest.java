package com.pixflow.harness.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.contracts.confirmation.ConfirmationAction;
import com.pixflow.contracts.confirmation.ConfirmationLevel;
import com.pixflow.contracts.confirmation.ConfirmationToken;
import com.pixflow.contracts.confirmation.TokenClaims;
import com.pixflow.harness.permission.subagent.SubagentConstraint;
import com.pixflow.harness.permission.token.ConfirmationTokenService;
import com.pixflow.harness.permission.token.InMemoryConfirmationTokenStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PermissionPolicyTest {
    private Clock clock;
    private InMemoryConfirmationTokenStore store;
    private ConfirmationTokenService tokenService;
    private DefaultPermissionPolicy policy;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-06-27T00:00:00Z"), ZoneOffset.UTC);
        store = new InMemoryConfirmationTokenStore(clock);
        tokenService = new ConfirmationTokenService(store, clock);
        policy = new DefaultPermissionPolicy(tokenService, 10);
    }

    @Test
    void submitDagWithoutTokenReturnsConfirmRequired() {
        PermissionSubject subject = new PermissionSubject(
                "submit_dag",
                false,
                ConfirmationAction.SUBMIT_DAG,
                "conv-1",
                "pkg-1",
                "hash-a",
                3,
                Map.of());
        PermissionContext context = new PermissionContext("conv-1", null, null, Set.of(), Set.of());

        PermissionDecision decision = policy.evaluate(subject, context);

        assertThat(decision.action()).isEqualTo(PermissionAction.CONFIRM_REQUIRED);
        assertThat(decision.metadata()).containsEntry("requiredAction", "SUBMIT_DAG");
    }

    @Test
    void validTokenAllowsAndConsumesOnce() {
        TokenClaims claims = new TokenClaims(
                ConfirmationAction.SUBMIT_DAG,
                "conv-1",
                "pkg-1",
                "hash-a",
                ConfirmationLevel.NORMAL,
                3,
                Instant.parse("2026-06-27T00:00:00Z"),
                Instant.parse("2026-06-27T00:10:00Z"),
                "nonce-1");
        ConfirmationToken token = tokenService.issue(claims);
        PermissionSubject subject = new PermissionSubject(
                "submit_dag",
                false,
                ConfirmationAction.SUBMIT_DAG,
                "conv-1",
                "pkg-1",
                "hash-a",
                3,
                Map.of());
        PermissionContext context = new PermissionContext("conv-1", token, null, Set.of(), Set.of());

        PermissionDecision first = policy.evaluate(subject, context);
        PermissionDecision second = policy.evaluate(subject, context);

        assertThat(first.action()).isEqualTo(PermissionAction.ALLOW);
        assertThat(second.action()).isEqualTo(PermissionAction.DENY);
    }

    @Test
    void payloadMismatchDeniesExecution() {
        ConfirmationToken token = tokenService.issue(new TokenClaims(
                ConfirmationAction.SUBMIT_DAG,
                "conv-1",
                "pkg-1",
                "hash-a",
                ConfirmationLevel.NORMAL,
                3,
                Instant.parse("2026-06-27T00:00:00Z"),
                Instant.parse("2026-06-27T00:10:00Z"),
                "nonce-1"));
        PermissionSubject subject = new PermissionSubject(
                "submit_dag",
                false,
                ConfirmationAction.SUBMIT_DAG,
                "conv-1",
                "pkg-1",
                "hash-b",
                3,
                Map.of());
        PermissionContext context = new PermissionContext("conv-1", token, null, Set.of(), Set.of());

        PermissionDecision decision = policy.evaluate(subject, context);

        assertThat(decision.action()).isEqualTo(PermissionAction.DENY);
        assertThat(decision.reason()).contains("载荷");
    }

    @Test
    void bulkThresholdRequiresBulkToken() {
        ConfirmationToken token = tokenService.issue(new TokenClaims(
                ConfirmationAction.SUBMIT_DAG,
                "conv-1",
                "pkg-1",
                "hash-a",
                ConfirmationLevel.NORMAL,
                12,
                Instant.parse("2026-06-27T00:00:00Z"),
                Instant.parse("2026-06-27T00:10:00Z"),
                "nonce-1"));
        PermissionSubject subject = new PermissionSubject(
                "submit_dag",
                false,
                ConfirmationAction.SUBMIT_DAG,
                "conv-1",
                "pkg-1",
                "hash-a",
                12,
                Map.of());
        PermissionContext context = new PermissionContext("conv-1", token, null, Set.of(), Set.of());

        PermissionDecision decision = policy.evaluate(subject, context);

        assertThat(decision.action()).isEqualTo(PermissionAction.DENY);
        assertThat(decision.reason()).contains("批量");
    }

    @Test
    void readonlySubagentCannotExecuteSideEffectAction() {
        PermissionSubject subject = new PermissionSubject(
                "submit_dag",
                false,
                ConfirmationAction.SUBMIT_DAG,
                "conv-1",
                "pkg-1",
                "hash-a",
                1,
                Map.of());
        PermissionContext context = new PermissionContext(
                "conv-1",
                null,
                new SubagentConstraint("vision", true, Set.of("run_vision_subagent"), Set.of("submit_dag")),
                Set.of(),
                Set.of());

        PermissionDecision decision = policy.evaluate(subject, context);

        assertThat(decision.action()).isEqualTo(PermissionAction.DENY);
        assertThat(decision.source()).isEqualTo(PermissionSource.SUBAGENT_CONSTRAINT);
    }

    @Test
    void hiddenToolsAreNotVisible() {
        PermissionContext context = new PermissionContext(
                "conv-1",
                null,
                new SubagentConstraint("vision", true, Set.of("run_vision_subagent"), Set.of("submit_dag")),
                Set.of("query_commerce_data"),
                Set.of("compile_dag"));

        assertThat(policy.isToolVisible("submit_dag", context)).isFalse();
        assertThat(policy.isToolVisible("compile_dag", context)).isFalse();
        assertThat(policy.isToolVisible("run_vision_subagent", context)).isTrue();
    }
}
