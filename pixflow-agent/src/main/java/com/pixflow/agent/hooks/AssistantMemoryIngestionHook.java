package com.pixflow.agent.hooks;

import com.pixflow.harness.hooks.HookCallback;
import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.hooks.payload.HookPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * ASSISTANT_MESSAGE_COMPLETED → 分析结论异步巩固触发器。
 *
 * <p>对应 {@code agent.md §七}：
 * <ul>
 *   <li>订阅 ASSISTANT_MESSAGE_COMPLETED（order=200）</li>
 *   <li>异步提交 memory 巩固任务（本期不调 LLM，立即返回 noop）</li>
 * </ul>
 */
@Component
public class AssistantMemoryIngestionHook implements HookCallback {

    private static final Logger log = LoggerFactory.getLogger(AssistantMemoryIngestionHook.class);

    @Override
    public Set<HookEvent> supportedEvents() {
        return Set.of(HookEvent.ASSISTANT_MESSAGE_COMPLETED);
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public HookResult handle(HookEvent event, HookPayload payload) {
        if (event != HookEvent.ASSISTANT_MESSAGE_COMPLETED) {
            return HookResult.noop();
        }
        if (payload == null || payload.runtime() == null) {
            return HookResult.noop();
        }
        if (payload.runtime().subagent()) {
            // child subagent 不触发
            return HookResult.noop();
        }
        log.debug("AssistantMemoryIngestionHook: triggered for conversationId={}",
                payload.conversationId());
        // 本期：不调 LLM 做分析结论抽取；下迭代接入 module/memory.ingestAsync
        return HookResult.noop();
    }
}