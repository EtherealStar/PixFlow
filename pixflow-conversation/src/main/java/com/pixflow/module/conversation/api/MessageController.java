package com.pixflow.module.conversation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.conversation.app.MessageSubmitRequest;
import com.pixflow.module.conversation.app.TurnDispatchService;
import com.pixflow.module.conversation.config.ConversationProperties;
import java.util.concurrent.ExecutorService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class MessageController {
    private final TurnDispatchService turnDispatchService;
    private final ConversationProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService conversationExecutor;

    public MessageController(
            TurnDispatchService turnDispatchService,
            ConversationProperties properties,
            ObjectMapper objectMapper,
            ExecutorService conversationExecutor) {
        this.turnDispatchService = turnDispatchService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.conversationExecutor = conversationExecutor;
    }

    @PostMapping(path = "/conversations/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submit(
            @PathVariable String conversationId,
            @RequestBody MessageSubmitRequest request) {
        SseEmitter emitter = new SseEmitter(properties.getSse().getTimeout().toMillis());
        SseAgentEventSink sink = new SseAgentEventSink(emitter, objectMapper);
        conversationExecutor.execute(() -> {
            try {
                turnDispatchService.stream(conversationId, request, sink);
                emitter.complete();
            } catch (Throwable ex) {
                sink.error(ex);
            }
        });
        return emitter;
    }
}
