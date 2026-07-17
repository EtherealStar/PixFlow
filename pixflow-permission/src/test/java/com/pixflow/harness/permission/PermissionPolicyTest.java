package com.pixflow.harness.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.permission.PermissionSubject.AssetAccess;
import com.pixflow.harness.permission.PermissionSubject.ProposalConfirmation;
import com.pixflow.harness.permission.PermissionSubject.ProposalPublication;
import com.pixflow.harness.permission.PermissionSubject.TaskCommand;
import com.pixflow.harness.permission.PermissionSubject.ToolInvocation;
import com.pixflow.harness.permission.proof.AdministratorEligibilityPort;
import com.pixflow.harness.permission.proof.AssetAuthorizationPort;
import com.pixflow.harness.permission.proof.ConversationAuthorizationPort;
import com.pixflow.harness.permission.proof.ProofResult;
import com.pixflow.harness.permission.proof.ProposalAuthorizationPort;
import com.pixflow.harness.permission.proof.TaskAuthorizationPort;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PermissionPolicyTest {
    private MutableProofs proofs;
    private DefaultPermissionPolicy policy;

    @BeforeEach
    void setUp() {
        proofs = new MutableProofs();
        policy = new DefaultPermissionPolicy(proofs, proofs, proofs, proofs, proofs);
    }

    @Test
    void missingPrincipalIsDeniedBeforeAnyProofLookup() {
        PermissionContext context = new PermissionContext(
                null, PermissionRuntimeScope.MAIN, PermissionPlanMode.OFF, "conv-1", "call-1");

        PermissionDecision decision = policy.evaluate(
                context, new ToolInvocation("read_asset", true, Map.of()));

        assertThat(decision.action()).isEqualTo(PermissionAction.DENY);
        assertThat(decision.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_UNAUTHENTICATED);
        assertThat(proofs.totalCalls()).isZero();
    }

    @Test
    void unavailableAdministratorProofFailsClosed() {
        proofs.administrator = ProofResult.UNAVAILABLE;

        PermissionDecision decision = policy.evaluate(mainContext(),
                new ToolInvocation("read_asset", true, Map.of()));

        assertThat(decision.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_ADMIN_INELIGIBLE);
        assertThat(proofs.administratorCalls).hasValue(1);
        assertThat(proofs.conversationCalls).hasValue(0);
    }

    @Test
    void planModeRejectsSideEffectBeforeConversationOrAssetProofs() {
        PermissionContext context = new PermissionContext(
                principal(), PermissionRuntimeScope.MAIN, PermissionPlanMode.ACTIVE, "conv-1", "call-1");

        PermissionDecision decision = policy.evaluate(context,
                new ProposalPublication("IMAGE_PROCESS", List.of("package:1/image:2"), "sha256:abc"));

        assertThat(decision.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_PLAN_MODE_DENIED);
        assertThat(proofs.conversationCalls).hasValue(0);
        assertThat(proofs.assetCalls).hasValue(0);
    }

    @Test
    void exploreChildAllowsReadOnlyToolButRejectsSideEffect() {
        PermissionContext context = new PermissionContext(
                principal(), PermissionRuntimeScope.EXPLORE_CHILD, PermissionPlanMode.OFF, "conv-1", "call-1");

        PermissionDecision read = policy.evaluate(context,
                new ToolInvocation("search_assets", true, Map.of("queryKind", "IMAGE")));
        PermissionDecision write = policy.evaluate(context,
                new ToolInvocation("submit_image_plan", false, Map.of()));

        assertThat(read.action()).isEqualTo(PermissionAction.ALLOW);
        assertThat(write.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_SCOPE_DENIED);
    }

    @Test
    void visibilityUsesDescriptorCapabilityAndFailsClosedForMissingScope() {
        PermissionContext plan = new PermissionContext(
                principal(), PermissionRuntimeScope.MAIN, PermissionPlanMode.ACTIVE, "conv-1", "call-1");
        PermissionContext explore = new PermissionContext(
                principal(), PermissionRuntimeScope.EXPLORE_CHILD, PermissionPlanMode.OFF,
                "conv-1", "call-1");
        PermissionContext missingScope = new PermissionContext(
                principal(), null, PermissionPlanMode.OFF, "conv-1", "call-1");

        assertThat(policy.isToolVisible("custom_lookup", true, plan)).isTrue();
        assertThat(policy.isToolVisible("search_named_but_writes", false, plan)).isFalse();
        assertThat(policy.isToolVisible("custom_lookup", true, explore)).isTrue();
        assertThat(policy.isToolVisible("custom_write", false, explore)).isFalse();
        assertThat(policy.isToolVisible("custom_lookup", true, missingScope)).isFalse();
    }

    @Test
    void deniedConversationStopsBeforeAssetProof() {
        proofs.conversation = ProofResult.DENIED;

        PermissionDecision decision = policy.evaluate(mainContext(),
                new AssetAccess("package:1/image:2", AssetAccessMode.READ));

        assertThat(decision.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_CONVERSATION_DENIED);
        assertThat(proofs.assetCalls).hasValue(0);
    }

    @Test
    void unavailableAssetProofDeniesProposalPublication() {
        proofs.asset = ProofResult.UNAVAILABLE;

        PermissionDecision decision = policy.evaluate(mainContext(),
                new ProposalPublication("IMAGE_PROCESS", List.of("package:1/image:2"), "sha256:abc"));

        assertThat(decision.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_ASSET_DENIED);
    }

    @Test
    void proposalAndTaskProofsMapToTheirOwnTerminalErrors() {
        proofs.proposal = ProofResult.DENIED;
        PermissionDecision proposal = policy.evaluate(mainContext(),
                new ProposalConfirmation(
                        "proposal-1", List.of("package:1/image:2"), "sha256:abc"));

        proofs.proposal = ProofResult.PROVED;
        proofs.task = ProofResult.UNAVAILABLE;
        PermissionDecision task = policy.evaluate(mainContext(),
                new TaskCommand("task-1", TaskCommandType.CANCEL));

        assertThat(proposal.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_PROPOSAL_DENIED);
        assertThat(task.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_TASK_DENIED);
    }

    @Test
    void proposalConfirmationRechecksReferencesBeforeProposalState() {
        proofs.asset = ProofResult.UNAVAILABLE;

        PermissionDecision decision = policy.evaluate(mainContext(),
                new ProposalConfirmation(
                        "proposal-1", List.of("package:1/image:2"), "sha256:abc"));

        assertThat(decision.errorCode()).isEqualTo(PermissionErrorCode.PERMISSION_ASSET_DENIED);
        assertThat(proofs.assetCalls).hasValue(1);
        assertThat(proofs.proposalCalls).hasValue(0);
    }

    @Test
    void allRequiredCurrentFactsAllowTheAction() {
        PermissionDecision decision = policy.evaluate(mainContext(),
                new ProposalPublication(
                        "IMAGE_PROCESS",
                        List.of("package:1", "package:1/image:2"),
                        "sha256:abc"));

        assertThat(decision.action()).isEqualTo(PermissionAction.ALLOW);
        assertThat(decision.errorCode()).isNull();
        assertThat(proofs.conversationCalls).hasValue(1);
        assertThat(proofs.assetCalls).hasValue(2);
    }

    private static PermissionContext mainContext() {
        return new PermissionContext(
                principal(), PermissionRuntimeScope.MAIN, PermissionPlanMode.OFF, "conv-1", "call-1");
    }

    private static PermissionPrincipal principal() {
        return new PermissionPrincipal("42", "admin");
    }

    private static final class MutableProofs implements AdministratorEligibilityPort,
            ConversationAuthorizationPort, AssetAuthorizationPort,
            ProposalAuthorizationPort, TaskAuthorizationPort {
        private ProofResult administrator = ProofResult.PROVED;
        private ProofResult conversation = ProofResult.PROVED;
        private ProofResult asset = ProofResult.PROVED;
        private ProofResult proposal = ProofResult.PROVED;
        private ProofResult task = ProofResult.PROVED;
        private final AtomicInteger administratorCalls = new AtomicInteger();
        private final AtomicInteger conversationCalls = new AtomicInteger();
        private final AtomicInteger assetCalls = new AtomicInteger();
        private final AtomicInteger proposalCalls = new AtomicInteger();
        private final AtomicInteger taskCalls = new AtomicInteger();

        @Override
        public ProofResult verify(PermissionPrincipal principal) {
            administratorCalls.incrementAndGet();
            return administrator;
        }

        @Override
        public ProofResult proveAccess(PermissionPrincipal principal, String conversationId) {
            conversationCalls.incrementAndGet();
            return conversation;
        }

        @Override
        public ProofResult proveAccess(
                PermissionPrincipal principal, String referenceKey, AssetAccessMode mode) {
            assetCalls.incrementAndGet();
            return asset;
        }

        @Override
        public ProofResult proveConfirmable(
                PermissionPrincipal principal,
                String conversationId,
                String proposalId,
                String payloadHash) {
            proposalCalls.incrementAndGet();
            return proposal;
        }

        @Override
        public ProofResult proveCommand(
                PermissionPrincipal principal,
                String conversationId,
                String taskId,
                TaskCommandType command) {
            taskCalls.incrementAndGet();
            return task;
        }

        int totalCalls() {
            return administratorCalls.get() + conversationCalls.get() + assetCalls.get()
                    + proposalCalls.get() + taskCalls.get();
        }
    }
}
