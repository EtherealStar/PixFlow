package com.pixflow.agent.summarization;

import com.pixflow.agent.subagent.SubagentRequest;
import com.pixflow.agent.subagent.SubagentRunner;
import com.pixflow.harness.context.compaction.SummarizationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * destructive compaction 摘要 SPI 实现（fork child 跑 LLM 摘要）。
 *
 * <p>对应 {@code agent.md §九}：
 * <ul>
 *   <li>实现 {@link SummarizationPort} SPI（由 context 调用）</li>
 *   <li>fork child 跑摘要（child 工具集为空）</li>
 *   <li>失败上报断路器（连续失败 ≥ 3 次后 context 切确定性优先级裁剪）</li>
 * </ul>
 */
@Component
public class ForkChildSummarizationPort implements SummarizationPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForkChildSummarizationPort.class);

    private final SubagentRunner subagentRunner;

    private final SummaryPromptBuilder promptBuilder;

    private final AtomicInteger failureCount = new AtomicInteger(0);

    public ForkChildSummarizationPort(SubagentRunner subagentRunner,
                                       SummaryPromptBuilder promptBuilder) {
        this.subagentRunner = subagentRunner;
        this.promptBuilder = promptBuilder;
    }

    @Override
    public SummaryResult summarize(SummarizationRequest request) {
        try {
            String prompt = promptBuilder.build(request);
            SubagentRequest subReq = SubagentRequest.summary(
                    null, // parentConversationId（context 调用，无父）
                    "summarization", // parentToolCallId
                    prompt,
                    request.focus(),
                    request.summaryInstructions() == null ? null
                            : String.join("\n", request.summaryInstructions())
            );
            var future = subagentRunner.runAsync(subReq);
            var result = future.get(60, TimeUnit.SECONDS);
            if (result.isError()) {
                failureCount.incrementAndGet();
                return new SummaryResult("");
            }
            // 成功 → 重置断路器
            failureCount.set(0);
            return new SummaryResult(result.finalText());
        } catch (Exception e) {
            failureCount.incrementAndGet();
            LOGGER.warn("ForkChildSummarizationPort: summarize failed", e);
            return new SummaryResult("");
        }
    }

    public int failureCount() {
        return failureCount.get();
    }
}
