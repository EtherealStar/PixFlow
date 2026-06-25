package com.etherealstar.pixflow.module.dag.engine;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发观测计数器（需求 8.3、Property 26）。
 *
 * <p>由 {@link ImageWorkerPool} 在进入/退出每个工作单元时调用，记录执行期间观测到的实时并发数与
 * 峰值并发数。峰值用于验证「执行期间最大并发不超过配置上限」这一不变量（设计 Testing Strategy 通过
 * 插桩并发计数器观测）。本计数器线程安全。</p>
 */
public final class ConcurrencyGauge {

    private final AtomicInteger current = new AtomicInteger(0);
    private final AtomicInteger peak = new AtomicInteger(0);

    /** 进入一个工作单元：实时并发 +1，并更新峰值。 */
    public void enter() {
        int now = current.incrementAndGet();
        peak.accumulateAndGet(now, Math::max);
    }

    /** 退出一个工作单元：实时并发 -1。 */
    public void exit() {
        current.decrementAndGet();
    }

    /** 当前实时并发数。 */
    public int current() {
        return current.get();
    }

    /** 执行期间观测到的峰值并发数。 */
    public int peak() {
        return peak.get();
    }
}
