package com.pixflow.module.conversation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.module.conversation.app.MessageSubmitRequest;
import com.pixflow.module.conversation.app.TurnDispatchService;
import com.pixflow.module.conversation.config.ConversationProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class MessageController {
    private final TurnDispatchService turnDispatchService;
    private final ConversationProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService conversationExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    public MessageController(
            TurnDispatchService turnDispatchService,
            ConversationProperties properties,
            ObjectMapper objectMapper,
            ExecutorService conversationExecutor,
            ScheduledExecutorService heartbeatScheduler) {
        this.turnDispatchService = turnDispatchService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.conversationExecutor = conversationExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @PostMapping(path = "/conversations/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submit(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @RequestBody MessageSubmitRequest request) {
        SseEmitter emitter = new SseEmitter(properties.getSse().getTimeout().toMillis());
        Object sendLock = new Object();
        SseAgentEventSink sink = new SseAgentEventSink(emitter, objectMapper, sendLock);
        SseHeartbeat heartbeat = new SseHeartbeat(
                emitter, heartbeatScheduler, properties.getSse().getHeartbeatInterval(), sendLock);
        heartbeat.start();
        try {
            conversationExecutor.execute(() -> {
            // 取锁 + 锁生命周期延迟到 emitter.complete 之后,避免旧 SSE 残留 + 新回合并发启动。
                try (TurnDispatchService.DispatchHandle handle =
                             turnDispatchService.dispatch(principal.userId(), conversationId, request, sink)) {
                    handle.run();
                    emitter.complete();
                } catch (Exception ex) {
                    sink.error(ex);
                } finally {
                    heartbeat.stop();
                }
            });
        } catch (RejectedExecutionException ex) {
            heartbeat.stop();
            sink.error(ex);
        }
        return emitter;
    }
}
