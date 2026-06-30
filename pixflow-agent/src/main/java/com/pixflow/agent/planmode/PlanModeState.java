package com.pixflow.agent.planmode;

/**
 * Plan 模式状态枚举。
 *
 * <p>对应 {@code agent.md §11.1}。状态字段在 {@code RuntimeState.metadata["planMode"]}。
 *
 * <p>OFF 是默认；进入 Plan 模式后，
 * {@code PlanModeController} 切到 ACTIVE；所有带后果工具在
 * 可见集 / permission / prompt 三层被强制过滤或拒绝。
 */
public enum PlanModeState {
    OFF,
    ACTIVE
}
