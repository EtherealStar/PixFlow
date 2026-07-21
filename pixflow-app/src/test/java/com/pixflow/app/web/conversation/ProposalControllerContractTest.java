package com.pixflow.app.web.conversation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.conversation.app.ConfirmationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProposalControllerContractTest {
    private final ConfirmationService confirmations = mock(ConfirmationService.class);
    private final ProposalController controller = new ProposalController(confirmations);

    @Test
    void confirmRejectsNonEmptyBodyBeforeInvokingOwner() {
        var body = new ObjectMapper().createObjectNode().put("idempotencyKey", "legacy-key");

        assertThatThrownBy(() -> controller.confirm(null, "conversation-1", "proposal-1", body))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        verifyNoInteractions(confirmations);
    }

    @Test
    void rejectRejectsEvenEmptyJsonObjectBodyBeforeInvokingOwner() {
        var body = new ObjectMapper().createObjectNode();

        assertThatThrownBy(() -> controller.reject(null, "conversation-1", "proposal-1", body))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        verifyNoInteractions(confirmations);
    }
}
