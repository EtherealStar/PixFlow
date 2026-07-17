package com.pixflow.module.conversation.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProposalServiceTest {

    @Test
    void repeatedToolCallReturnsTheSameProposalId() {
        ProposalService service = new ProposalService();
        PublishProposalCommand command = new PublishProposalCommand(
                PendingProposalType.DAG,
                "conversation-1",
                7L,
                "tool-call-1",
                "{}",
                "hash-1",
                1,
                List.of("package:7/image:11"),
                Instant.parse("2026-07-17T00:00:00Z"));

        ProposalView first = service.publish(command);
        ProposalView replay = service.publish(command);

        assertThat(replay).isEqualTo(first);
        assertThat(service.require(first.proposalId()).referenceKeys())
                .containsExactly("package:7/image:11");
    }
}
