package com.pixflow.app.web.conversation;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.app.web.conversation.sse.SseTurnSession;
import com.pixflow.app.web.conversation.sse.SseTurnSessionFactory;
import com.pixflow.module.conversation.app.MessageSubmitRequest;
import com.pixflow.module.conversation.app.MessageReferenceInput;
import com.pixflow.module.conversation.app.PreparedTurn;
import com.pixflow.module.conversation.app.TurnPreparationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public final class MessageController {
    private final TurnPreparationService preparation;

    private final SseTurnSessionFactory sessions;

    public MessageController(TurnPreparationService preparation, SseTurnSessionFactory sessions) {
        this.preparation = preparation;
        this.sessions = sessions;
    }

    @PostMapping(path = "/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submit(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @Valid @RequestBody MessageCommand request) {
        PreparedTurn prepared = preparation.prepare(principal, conversationId, request.toOwnerRequest());
        SseTurnSession session = sessions.create(prepared);
        session.start();
        return session.emitter();
    }

    public record MessageCommand(
            @NotNull @Size(max = 20_000) String prompt,
            @Size(max = 20) List<@Valid ReferenceCommand> references) {
        public MessageCommand {
            references = references == null ? List.of() : List.copyOf(references);
        }

        /** 允许纯引用消息，但不能提交既无文本又无引用的空消息。 */
        @AssertTrue(message = "prompt 与 references 至少提供一项")
        public boolean isPromptOrReferencePresent() {
            return prompt != null && (!prompt.isBlank() || !references.isEmpty());
        }

        MessageSubmitRequest toOwnerRequest() {
            return new MessageSubmitRequest(
                    prompt,
                    references.stream()
                            .map(reference -> new MessageReferenceInput(
                                    reference.referenceKey(), reference.displayPathSnapshot()))
                            .toList());
        }
    }

    public record ReferenceCommand(
            @NotBlank String referenceKey,
            @NotBlank @Size(max = 512) String displayPathSnapshot) {
    }
}
