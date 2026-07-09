package com.pixflow.agent.planmode;

import com.pixflow.agent.config.AgentProperties;
import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.tools.ToolRuntimeContext;
import java.util.Map;
import java.util.function.BiConsumer;
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
        enter(state == null ? Map.of() : state.metadata(), state == null ? null : state::putMetadata);
    }

    public void enter(ToolRuntimeContext context) {
        enter(context == null ? Map.of() : context.metadata(), context == null ? null : context::putMetadata);
    }

    /**
     * 退出 Plan 模式。
     *
     * @param draftPlan 草拟计划（保留到下回合 WorkspaceStateSection 渲染）
     * @throws IllegalStateException 当前不是 ACTIVE 时
     */
    public void exit(RuntimeState state, String draftPlan) {
        exit(state == null ? Map.of() : state.metadata(), state == null ? null : state::putMetadata, draftPlan);
    }

    public void exit(ToolRuntimeContext context, String draftPlan) {
        exit(context == null ? Map.of() : context.metadata(), context == null ? null : context::putMetadata, draftPlan);
    }

    /**
     * 读当前 Plan 模式状态。
     */
    public PlanModeState readPlanMode(RuntimeState state) {
        return readPlanMode(state == null ? Map.of() : state.metadata());
    }

    public PlanModeState readPlanMode(ToolRuntimeContext context) {
        return readPlanMode(context == null ? Map.of() : context.metadata());
    }

    private void enter(Map<String, Object> metadata, BiConsumer<String, Object> writer) {
        PlanModeState current = readPlanMode(metadata);
        if (current == PlanModeState.ACTIVE) {
            throw new IllegalStateException("Already in Plan mode. Use plan_exit to leave.");
        }
        requireWriter(writer).accept(PLAN_MODE_KEY, Boolean.TRUE);
    }

    private void exit(Map<String, Object> metadata, BiConsumer<String, Object> writer, String draftPlan) {
        PlanModeState current = readPlanMode(metadata);
        if (current != PlanModeState.ACTIVE) {
            throw new IllegalStateException("Not in Plan mode. Use plan to enter first.");
        }
        BiConsumer<String, Object> checkedWriter = requireWriter(writer);
        checkedWriter.accept(PLAN_MODE_KEY, Boolean.FALSE);
        if (props.getPlanMode().isKeepDraftOnExit() && draftPlan != null && !draftPlan.isBlank()) {
            checkedWriter.accept(LAST_PLAN_DRAFT_KEY, draftPlan);
        }
    }

    private PlanModeState readPlanMode(Map<String, Object> metadata) {
        Object value = metadata.getOrDefault(PLAN_MODE_KEY, Boolean.FALSE);
        if (value instanceof Boolean b && b) {
            return PlanModeState.ACTIVE;
        }
        return PlanModeState.OFF;
    }

    private static BiConsumer<String, Object> requireWriter(BiConsumer<String, Object> writer) {
        if (writer == null) {
            throw new IllegalStateException("Tool runtime context is not available");
        }
        return writer;
    }
}
