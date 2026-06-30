package com.pixflow.agent.planmode;

import com.pixflow.agent.config.AgentProperties;
import com.pixflow.harness.loop.RuntimeState;
import org.springframework.stereotype.Component;

/**
 * Plan 模式状态机控制器。
 *
 * <p>对应 {@code agent.md §11}：
 * <ul>
 *   <li>enter()：OFF → ACTIVE，写 RuntimeState.metadata["planMode"] = true</li>
 *   <li>exit(draftPlan)：ACTIVE → OFF，写 lastPlanDraft metadata</li>
 * </ul>
 *
 * <p>handler 不直接改 RuntimeState.metadata，统一经 controller 改写。
 */
@Component
public class PlanModeController {

    private static final String PLAN_MODE_KEY = "planMode";
    private static final String LAST_PLAN_DRAFT_KEY = "lastPlanDraft";

    private final AgentProperties props;

    public PlanModeController(AgentProperties props) {
        this.props = props;
    }

    /**
     * 进入 Plan 模式。
     *
     * @throws IllegalStateException 当前已是 ACTIVE 时
     */
    public void enter(RuntimeState state) {
        PlanModeState current = readPlanMode(state);
        if (current == PlanModeState.ACTIVE) {
            throw new IllegalStateException("Already in Plan mode. Use plan_exit to leave.");
        }
        state.putMetadata(PLAN_MODE_KEY, Boolean.TRUE);
    }

    /**
     * 退出 Plan 模式。
     *
     * @param draftPlan 草拟计划（保留到下回合 WorkspaceStateSection 渲染）
     * @throws IllegalStateException 当前不是 ACTIVE 时
     */
    public void exit(RuntimeState state, String draftPlan) {
        PlanModeState current = readPlanMode(state);
        if (current != PlanModeState.ACTIVE) {
            throw new IllegalStateException("Not in Plan mode. Use plan to enter first.");
        }
        state.putMetadata(PLAN_MODE_KEY, Boolean.FALSE);
        if (props.getPlanMode().isKeepDraftOnExit() && draftPlan != null && !draftPlan.isBlank()) {
            state.putMetadata(LAST_PLAN_DRAFT_KEY, draftPlan);
        }
    }

    /**
     * 读当前 Plan 模式状态。
     */
    public PlanModeState readPlanMode(RuntimeState state) {
        if (state == null) return PlanModeState.OFF;
        Object value = state.metadataOrDefault(PLAN_MODE_KEY, Boolean.FALSE);
        if (value instanceof Boolean b && b) {
            return PlanModeState.ACTIVE;
        }
        return PlanModeState.OFF;
    }
}