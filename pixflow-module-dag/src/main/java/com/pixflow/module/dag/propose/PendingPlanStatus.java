package com.pixflow.module.dag.propose;

/**
 * pending_plan 状态机(对齐 dag.md §6.2):
 *
 * <pre>
 *   PENDING ──(confirmed)──> CONFIRMED ──(terminal)──┐
 *      │                                              │
 *      ├──(discarded by user)──> DISCARDED           │
 *      └──(expired by cron)───> EXPIRED              │
 *                                                      ↓
 *                                            (process_task created)
 * </pre>
 */
public enum PendingPlanStatus {
    PENDING,
    CONFIRMED,
    DISCARDED,
    EXPIRED
}