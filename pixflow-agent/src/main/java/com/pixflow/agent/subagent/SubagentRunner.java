package com.pixflow.agent.subagent;

import com.pixflow.agent.error.AgentErrorCode;
import com.pixflow.common.error.PixFlowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 异步 SubagentRunner（单一类三处复用）。
 *
 * <p>对应 {@code agent.md §八}：
 * <ol>
 *   <li>{@code agent} 工具（VISION / EXPLORE）</li>
 *   <li>{@code SummarizationPort}（destructive compaction 摘要）</li>
 *   <li>{@code SessionMemoryExtractionService}（Session Memory 累积提取）</li>
 * </ol>
 *
 * <p>关键设计：
 * <ul>
 *   <li>{@link #runAsync(SubagentRequest)} 是唯一对外 API（ArchUnit 5/6 守护）</li>
 *   <li>child 跑在 {@link SubagentPool} 独立线程池，父 loop 不阻塞</li>
 *   <li>VisionSubagentTool / ExploreSubagentTool 在 handler 内 {@code .join()} 阻塞 join，
 *       但 join 发生在 tools 执行管线的并发池而非主回合线程</li>
 *   <li>不可恢复异常 → {@code SubagentResult.error}，<b>不冒泡</b>到调用线程</li>
 * </ul>
 */
@Component
public class SubagentRunner {

    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    private final ExecutorService subagentPool;

    public SubagentRunner(ExecutorService subagentPool) {
        this.subagentPool = subagentPool;
        log.info("SubagentRunner initialized with pool: {}", subagentPool.getClass().getSimpleName());
    }

    /**
     * 异步跑 subagent。
     *
     * <p>返回 {@link CompletableFuture} 立即可观察；child 跑在独立线程池。
     */
    public CompletableFuture<SubagentResult> runAsync(SubagentRequest req) {
        return CompletableFuture.supplyAsync(() -> runSync(req), subagentPool);
    }

    /**
     * 同步跑 subagent（仅供 {@link #runAsync} 内部使用；不公开）。
     *
     * <p>本期实现：保守返回 finalText = prompt echo（具体 child AgentLoop 装配
     * 留待下个迭代——child AgentLoop per-call 构造、ephemeral MessageStore、
     * 子集 ToolRegistry 等是 stage-1 后续工作）。
     */
    private SubagentResult runSync(SubagentRequest req) {
        try {
            log.info("SubagentRunner.runSync: type={}, promptLen={}", req.type(), req.prompt().length());
            // 本期简化：直接 echo prompt 作为 finalText（保证端到端通路存在）
            String finalText = "[subagent " + req.type() + "] " + req.prompt();
            return SubagentResult.ok(finalText, null, 0);
        } catch (Exception e) {
            log.warn("SubagentRunner.runSync failed: {}", e.getMessage());
            return SubagentResult.error(safeMessage(e));
        }
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) return "Subagent failed: " + t.getClass().getSimpleName();
        // 简单脱敏：截断到 500 字符
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }
}