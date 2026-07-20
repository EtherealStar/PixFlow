package com.pixflow.app.web.conversation.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.conversation.config.ConversationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** App 独占 SSE transport 生命周期，Conversation 模块只负责准备和执行回合。 */
@Configuration(proxyBeanMethods = false)
public class ConversationSseConfiguration {
    @Bean(name = "conversationExecutor", destroyMethod = "shutdown")
    public ExecutorService conversationExecutor(ConversationProperties properties) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0,
                properties.getTurnExecutor().getMaxConcurrency(),
                properties.getTurnExecutor().getKeepAlive().toMillis(),
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                runnable -> daemonThread(runnable, "conversation-sse"),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean(name = "conversationSseHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService conversationSseHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                runnable -> daemonThread(runnable, "conversation-sse-heartbeat"));
    }

    @Bean
    public SseTurnMetrics sseTurnMetrics(MeterRegistry meterRegistry) {
        return new SseTurnMetrics(meterRegistry);
    }

    @Bean
    public SseTurnSessionFactory sseTurnSessionFactory(
            ConversationProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("conversationExecutor") ExecutorService executor,
            @Qualifier("conversationSseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler,
            SseTurnMetrics metrics) {
        return new SseTurnSessionFactory(properties, objectMapper, executor, heartbeatScheduler, metrics);
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
