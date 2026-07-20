package com.pixflow.app.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.TaskCommandType;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.identity.AdministratorEligibility;
import com.pixflow.module.task.api.authorization.TaskAuthorizationFacts;
import com.pixflow.module.task.api.authorization.TaskAuthorizationFactsQuery;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionProofAdaptersTest {
    @Test
    void administratorProofRechecksCurrentEligibilityAndIdentity() {
        AdministratorEligibility eligibility = mock(AdministratorEligibility.class);
        when(eligibility.requireEligible(7L))
                .thenReturn(new AuthPrincipal(7L, "admin", "Admin"));
        AuthAdministratorPermissionProof proof =
                new AuthAdministratorPermissionProof(eligibility);

        assertThat(proof.verify(new PermissionPrincipal("7", "admin")))
                .isEqualTo(ProofResult.PROVED);
        assertThat(proof.verify(new PermissionPrincipal("7", "stale-name")))
                .isEqualTo(ProofResult.DENIED);
    }

    @Test
    void administratorDependencyFailureIsUnavailable() {
        AdministratorEligibility eligibility = mock(AdministratorEligibility.class);
        when(eligibility.requireEligible(7L)).thenThrow(new IllegalStateException("db down"));

        assertThat(new AuthAdministratorPermissionProof(eligibility)
                .verify(new PermissionPrincipal("7", "admin")))
                .isEqualTo(ProofResult.UNAVAILABLE);
    }

    @Test
    void taskProofRequiresConversationOwnershipAndCommandCompatibleState() {
        TaskAuthorizationFactsQuery facts = mock(TaskAuthorizationFactsQuery.class);
        when(facts.find("11")).thenReturn(Optional.of(new TaskAuthorizationFacts(
                "11", "conv-1", false, true, true, true)));
        TaskPermissionProof proof = new TaskPermissionProof(facts);
        PermissionPrincipal principal = new PermissionPrincipal("7", "admin");

        assertThat(proof.proveCommand(principal, "conv-1", "11", TaskCommandType.RETRY))
                .isEqualTo(ProofResult.PROVED);
        assertThat(proof.proveCommand(principal, "conv-1", "11", TaskCommandType.CANCEL))
                .isEqualTo(ProofResult.DENIED);
        assertThat(proof.proveCommand(principal, "other-conv", "11", TaskCommandType.RETRY))
                .isEqualTo(ProofResult.DENIED);
        assertThat(proof.proveCommand(principal, "conv-1", "11", TaskCommandType.CONFIRM_REPLAY))
                .isEqualTo(ProofResult.PROVED);
        assertThat(proof.proveCommand(principal, "other-conv", "11", TaskCommandType.CONFIRM_REPLAY))
                .isEqualTo(ProofResult.DENIED);
    }

    @Test
    void taskDependencyFailureIsUnavailable() {
        TaskAuthorizationFactsQuery facts = mock(TaskAuthorizationFactsQuery.class);
        when(facts.find("11"))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(new TaskPermissionProof(facts).proveCommand(
                new PermissionPrincipal("7", "admin"),
                "conv-1", "11", TaskCommandType.DOWNLOAD))
                .isEqualTo(ProofResult.UNAVAILABLE);
    }
}
