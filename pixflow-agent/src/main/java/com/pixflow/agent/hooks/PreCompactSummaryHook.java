package com.pixflow.agent.hooks;

import com.pixflow.harness.hooks.HookCallback;
import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.hooks.payload.HookPayload;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * PRE_COMPACT → 注入 compact.summaryInstructions metadata。
 *
 * <p>对应 {@code agent.md §9.6}：
 * <ul>
 *   <li>订阅 PRE_COMPACT（order=50，最小优先）</li>
 *   <li>返回 HookResult.withMetadata，注入 summaryInstructions 字符串</li>
 *   <li>context 构造 SummarizationRequest 时消费此 metadata key</li>
 * </ul>
 */
@Component
public class PreCompactSummaryHook implements HookCallback {

    private static final String SUMMARY_INSTRUCTIONS_KEY = "compact.summaryInstructions";

    private static final String DEFAULT_INSTRUCTIONS =
            "PixFlow 电商运营 Agent：保留 SKU 处理状态、用户确认/拒绝决策、电商数据指标、用户偏好更新。";

    @Override
    public Set<HookEvent> supportedEvents() {
        return Set.of(HookEvent.PRE_COMPACT);
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public HookResult handle(HookEvent event, HookPayload payload) {
        if (event != HookEvent.PRE_COMPACT) {
            return HookResult.noop();
        }
        return HookResult.withMetadata(Map.of(SUMMARY_INSTRUCTIONS_KEY, DEFAULT_INSTRUCTIONS));
    }
}