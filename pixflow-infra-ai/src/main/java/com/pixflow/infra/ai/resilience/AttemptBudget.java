package com.pixflow.infra.ai.resilience;

/**
 * 一次模型请求范围内的真实供应商调用预算。
 *
 * <p>实现必须在实际 HTTP 请求发出前持久化预留，进程在调用期间崩溃时也不能返还预算。
 */
@FunctionalInterface
public interface AttemptBudget {
    void reserve();

    static AttemptBudget unbounded() {
        return () -> { };
    }
}
