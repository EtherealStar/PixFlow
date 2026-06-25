package com.etherealstar.pixflow.module.dag.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * 并发上限属性测试（任务 12.11）。
 *
 * <p>Feature: pixflow, Property 26: 并发上限——批处理期间观测到的最大实时并发数永远不超过配置的
 * 最大并发上限（{@code pixflow.engine.maxConcurrency}）。{@link ImageWorkerPool} 以固定大小线程池
 * 调度全部工作单元，{@link ConcurrencyGauge} 在进入/退出每个工作单元时插桩记录峰值并发。
 * Validates: Requirements 8.3
 *
 * <p>本测试为纯并发逻辑测试：工作单元仅做短暂休眠以制造重叠，不涉及真实图片、文案或外部 API。
 */
class ConcurrencyLimitPropertyTest {

    private ImageWorkerPool poolWithMaxConcurrency(int maxConcurrency) {
        EngineProperties props = new EngineProperties();
        props.setMaxConcurrency(maxConcurrency);
        return new ImageWorkerPool(props);
    }

    /** 制造重叠的工作单元：休眠数毫秒，期间实时并发计入峰值。 */
    private List<Callable<Integer>> sleepingTasks(int count) {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int value = i;
            tasks.add(() -> {
                Thread.sleep(5);
                return value;
            });
        }
        return tasks;
    }

    @Property(tries = 100)
    void peakConcurrencyNeverExceedsConfiguredLimit(
            @ForAll @IntRange(min = 1, max = 8) int maxConcurrency,
            @ForAll @IntRange(min = 0, max = 24) int taskCount) {

        ImageWorkerPool pool = poolWithMaxConcurrency(maxConcurrency);
        ConcurrencyGauge gauge = new ConcurrencyGauge();

        List<Integer> results = pool.runAll(sleepingTasks(taskCount), gauge);

        // 不变量：峰值并发不超过配置上限
        assertThat(gauge.peak())
                .as("峰值并发 %d 不应超过上限 %d", gauge.peak(), maxConcurrency)
                .isLessThanOrEqualTo(maxConcurrency);

        // 结果按入参顺序完整返回，执行结束后实时并发归零
        assertThat(results).hasSize(taskCount);
        for (int i = 0; i < taskCount; i++) {
            assertThat(results.get(i)).isEqualTo(i);
        }
        assertThat(gauge.current()).isZero();
        if (taskCount > 0) {
            assertThat(gauge.peak()).isGreaterThanOrEqualTo(1);
        }
    }

    @Property(tries = 100)
    void poolSizeIsCappedRegardlessOfWorkload(
            @ForAll @IntRange(min = 1, max = 8) int maxConcurrency) {
        // maxConcurrency() 始终返回 >=1 且等于配置值（下限钳制为 1）
        ImageWorkerPool pool = poolWithMaxConcurrency(maxConcurrency);
        assertThat(pool.maxConcurrency()).isEqualTo(maxConcurrency);
    }

    @Property(tries = 100)
    void nonPositiveConcurrencyIsClampedToOne(
            @ForAll @IntRange(min = -4, max = 0) int nonPositive) {
        ImageWorkerPool pool = poolWithMaxConcurrency(nonPositive);
        ConcurrencyGauge gauge = new ConcurrencyGauge();

        pool.runAll(sleepingTasks(6), gauge);

        assertThat(pool.maxConcurrency()).isEqualTo(1);
        assertThat(gauge.peak()).isLessThanOrEqualTo(1);
    }
}
