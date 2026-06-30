package com.pixflow.agent.planmode;

import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.tools.plan.PlanModeView;
import org.springframework.stereotype.Component;

/**
 * PlanModeView SPI 实现：读 RuntimeState.metadata["planMode"]。
 *
 * <p>对应 {@code agent.md §11.1} + 决策日志第 9 条：
 * <ul>
 *   <li>PlanModeController 写 RuntimeState.metadata</li>
 *   <li>RuntimeStatePlanModeView 读 RuntimeState.metadata（注入 harness/tools 与 harness/loop）</li>
 * </ul>
 */
@Component
public class RuntimeStatePlanModeView implements PlanModeView {

    private final RuntimeState runtimeState;

    public RuntimeStatePlanModeView(RuntimeState runtimeState) {
        this.runtimeState = runtimeState;
    }

    @Override
    public boolean isPlanMode() {
        Object value = runtimeState.metadataOrDefault("planMode", Boolean.FALSE);
        return value instanceof Boolean b && b;
    }
}