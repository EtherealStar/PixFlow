package com.pixflow.agent.subagent;

import com.pixflow.agent.config.AgentProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Subagent 独立线程池（{@code pixflow.agent.subagent.pool}）。
 *
 * <p>对应 {@code agent.md §8.8}：
 * <ul>
 *   <li>core-size: 4</li>
 *   <li>max-size: 16</li>
 *   <li>queue-capacity: 100</li>
 *   <li>keep-alive-seconds: 60</li>
 * </ul>
 *
 * <p>为什么独立池：VLLM 几秒~十几秒，与主循环的毫秒级工具并发不应抢资源。
 */
@Configuration
public class SubagentPool {

    /**
     * 暴露 {@link ExecutorService} bean（{@code @Bean(destroyMethod="shutdown")}）。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService subagentPool(AgentProperties props) {
        AgentProperties.Subagent.Pool poolCfg = props.getSubagent().getPool();
        return new ThreadPoolExecutor(
                poolCfg.getCoreSize(),
                poolCfg.getMaxSize(),
                poolCfg.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(poolCfg.getQueueCapacity()),
                new SubagentThreadFactory()
        );
    }

    private static class SubagentThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "agent-subagent-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}