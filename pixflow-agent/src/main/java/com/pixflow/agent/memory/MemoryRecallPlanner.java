package com.pixflow.agent.memory;

import com.pixflow.agent.config.AgentProperties;
import com.pixflow.module.memory.MemoryService;
import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemoryContextRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Prompt 组装前的记忆上下文规划器。
 *
 * <p>本类是 agent 到 module-memory 的薄适配层：agent 只组装请求并同步调用
 * {@link MemoryService#prepareContext(MemoryContextRequest)}。偏好、SKU 历史、
 * 分析结论混合召回和降级逻辑全部归 module-memory。
 */
@Component
public class MemoryRecallPlanner {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallPlanner.class);

    private final MemoryService memoryService;
    private final AgentProperties props;

    public MemoryRecallPlanner(MemoryService memoryService, AgentProperties props) {
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService");
        this.props = props == null ? new AgentProperties() : props;
        log.info("MemoryRecallPlanner initialized with module-memory MemoryService");
    }

    /**
     * 同步准备 Prompt 可注入的记忆上下文。
     */
    public MemoryContext plan(MemoryRecallSignal signal) {
        Objects.requireNonNull(signal, "signal");
        MemoryContextRequest request = toRequest(signal);
        return memoryService.prepareContext(request);
    }

    MemoryContextRequest toRequest(MemoryRecallSignal signal) {
        Integer tokenBudget = signal.tokenBudget() == null
                ? props.getMemory().getRecall().getMaxTokens()
                : signal.tokenBudget();
        return new MemoryContextRequest(
                signal.conversationId(),
                signal.turnNo(),
                signal.traceId(),
                signal.userMessage(),
                signal.attachments(),
                signal.packageId(),
                signal.taskId(),
                signal.skuIds(),
                signal.categoryHints(),
                signal.metadata(),
                tokenBudget);
    }
}
