package com.pixflow.agent.subagent;

import com.pixflow.agent.config.AgentSubagentAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 异步 SubagentRunner（单一类三处复用）。
 *
 * <p>对应 {@code agent.md §八}：
 * <ol>
 *   <li>{@code agent(type=explore)} 工具</li>
 *   <li>{@code SummarizationPort}（destructive compaction 摘要）</li>
 *   <li>{@code SessionMemoryExtractionService}（Session Memory 累积提取）</li>
 * </ol>
 *
 * <p>关键设计：
 * <ul>
 *   <li>{@link #runAsync(SubagentRequest)} 是唯一对外 API（ArchUnit 5/6 守护）</li>
 *   <li>child 跑在 subagent 专用 executor，父 loop 不阻塞</li>
 *   <li>不可恢复异常 → {@code SubagentResult.error}，<b>不冒泡</b>到调用线程</li>
 * </ul>
 */
@Component
public class SubagentRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubagentRunner.class);

    private final ExecutorService subagentExecutor;

    public SubagentRunner(
            @Qualifier(AgentSubagentAutoConfiguration.SUBAGENT_EXECUTOR_BEAN)
            ExecutorService subagentExecutor) {
        this.subagentExecutor = subagentExecutor;
        LOGGER.info("SubagentRunner initialized with executor: {}", subagentExecutor.getClass().getSimpleName());
    }

    /**
     * 异步跑 subagent。
     *
     * <p>返回 {@link CompletableFuture} 立即可观察；child 跑在独立线程池。
     */
    public CompletableFuture<SubagentResult> runAsync(SubagentRequest req) {
        return CompletableFuture.supplyAsync(() -> runSync(req), subagentExecutor);
    }

    /**
     * 同步跑 subagent（仅供 {@link #runAsync} 内部使用；不公开）。
     *
     * <p>真实 child runtime 尚未装配时，必须返回结构化失败，不把输入提示
     * 伪装成成功结果。这样调用方能显式降级，也不会泄漏内部提示词。
     */
    private SubagentResult runSync(SubagentRequest req) {
        try {
            LOGGER.info("SubagentRunner.runSync: type={}, promptLen={}", req.type(), req.prompt().length());
            return SubagentResult.error("subagent_runtime_unavailable");
        } catch (Exception e) {
            LOGGER.warn("SubagentRunner.runSync failed", e);
            return SubagentResult.error(safeMessage(e));
        }
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return "Subagent failed: " + t.getClass().getSimpleName();
        }
        // 简单脱敏：截断到 500 字符
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }
}
