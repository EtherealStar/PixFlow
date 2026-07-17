package com.pixflow.agent.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Subagent 线程池自动配置。
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentSubagentAutoConfiguration {

    public static final String SUBAGENT_EXECUTOR_BEAN = "subagentExecutor";

    public static final String SESSION_MEMORY_EXECUTOR_BEAN = "sessionMemoryExtractionExecutor";

    @Bean(name = SUBAGENT_EXECUTOR_BEAN, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = SUBAGENT_EXECUTOR_BEAN)
    public ExecutorService subagentExecutor(AgentProperties props) {
        AgentProperties.Subagent.Pool poolCfg = props.getSubagent().getPool();
        return new ThreadPoolExecutor(
                poolCfg.getCoreSize(),
                poolCfg.getMaxSize(),
                poolCfg.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(poolCfg.getQueueCapacity()),
                new SubagentThreadFactory());
    }

    @Bean(name = SESSION_MEMORY_EXECUTOR_BEAN, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = SESSION_MEMORY_EXECUTOR_BEAN)
    public ExecutorService sessionMemoryExtractionExecutor(AgentProperties props) {
        AgentProperties.Subagent.Pool poolCfg = props.getSubagent().getPool();
        return new ThreadPoolExecutor(
                Math.max(1, Math.min(2, poolCfg.getCoreSize())),
                Math.max(1, Math.min(4, poolCfg.getMaxSize())),
                poolCfg.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, poolCfg.getQueueCapacity())),
                new SessionMemoryThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static final class SubagentThreadFactory implements ThreadFactory {

        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "agent-subagent-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    private static final class SessionMemoryThreadFactory implements ThreadFactory {

        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "agent-session-memory-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
