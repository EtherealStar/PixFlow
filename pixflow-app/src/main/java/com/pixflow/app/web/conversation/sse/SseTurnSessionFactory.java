package com.pixflow.app.web.conversation.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.conversation.app.PreparedTurn;
import com.pixflow.module.conversation.config.ConversationProperties;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PreDestroy;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class SseTurnSessionFactory {
    private final ConversationProperties properties;

    private final ObjectMapper objectMapper;

    private final ExecutorService executor;

    private final ScheduledExecutorService heartbeatScheduler;

    private final SseTurnMetrics metrics;

    private final Set<SseTurnSession> activeSessions = ConcurrentHashMap.newKeySet();

    public SseTurnSessionFactory(
            ConversationProperties properties,
            ObjectMapper objectMapper,
            ExecutorService executor,
            ScheduledExecutorService heartbeatScheduler,
            SseTurnMetrics metrics) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.heartbeatScheduler = Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public SseTurnSession create(PreparedTurn preparedTurn) {
        SseEmitter emitter = new SseEmitter(properties.getSse().getTimeout().toMillis());
        Object sendLock = new Object();
        AtomicReference<SseTurnSession> sessionRef = new AtomicReference<>();
        SseAgentEventSink sink = new SseAgentEventSink(
                emitter,
                objectMapper,
                sendLock,
                () -> sessionRef.get() == null || sessionRef.get().isWritable(),
                error -> {
                    SseTurnSession session = sessionRef.get();
                    if (session != null) {
                        session.transportFailed(error);
                    }
                },
                metrics::lateWrite);
        SseHeartbeat heartbeat = new SseHeartbeat(
                emitter,
                heartbeatScheduler,
                properties.getSse().getHeartbeatInterval(),
                sendLock,
                error -> {
                    SseTurnSession session = sessionRef.get();
                    if (session != null) {
                        session.transportFailed(error);
                    }
                });
        SseTurnSession session = new SseTurnSession(
                preparedTurn,
                emitter,
                sink,
                heartbeat,
                executor,
                metrics,
                () -> {
                    SseTurnSession current = sessionRef.get();
                    if (current != null) {
                        activeSessions.remove(current);
                    }
                });
        sessionRef.set(session);
        activeSessions.add(session);
        return session;
    }

    /** 停止当前管理员在指定会话中的活动回合；重复停止保持幂等。 */
    public void stop(long ownerUserId, String conversationId) {
        activeSessions.stream()
                .filter(session -> session.belongsTo(ownerUserId, conversationId))
                .forEach(SseTurnSession::cancelByCaller);
    }

    @PreDestroy
    void shutdown() {
        activeSessions.forEach(SseTurnSession::shutdown);
    }
}
