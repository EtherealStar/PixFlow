package com.etherealstar.pixflow.module.dag.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 图片处理并发执行池（需求 8.3，DAG_Engine 组件 ImageWorkerPool）。
 *
 * <p>以固定大小线程池逐图逐支路调度工作单元，线程池大小取 {@link EngineProperties#getMaxConcurrency()}
 * （默认 8），从而保证批处理期间的最大并发不超过配置上限（Property 26）。每个工作单元通过
 * {@link ConcurrencyGauge} 插桩进入/退出以便观测实时与峰值并发。</p>
 *
 * <p>每次批处理创建独立的线程池并在完成后关闭，避免任务间相互影响，契合 MVP 的同步阻塞执行模型
 * （提交整批工作单元并阻塞直至全部完成）。</p>
 */
@Component
public class ImageWorkerPool {

    private final EngineProperties properties;

    public ImageWorkerPool(EngineProperties properties) {
        this.properties = properties;
    }

    /** 配置的最大并发数（线程池大小）。 */
    public int maxConcurrency() {
        return Math.max(1, properties.getMaxConcurrency());
    }

    /**
     * 提交整批工作单元并阻塞直至全部完成，按入参顺序返回结果。
     *
     * <p>各工作单元自身负责失败隔离（捕获异常并产出失败结果），因此本方法假定 {@code tasks}
     * 不向外抛出业务异常；若个别任务确有未捕获异常，则以 {@link RuntimeException} 向上抛出。</p>
     *
     * @param tasks 工作单元集合
     * @param gauge 并发观测计数器（用于记录峰值并发，可用于校验并发上限）
     * @param <T>   工作单元返回类型
     * @return 与 {@code tasks} 顺序一致的结果列表
     */
    public <T> List<T> runAll(List<Callable<T>> tasks, ConcurrencyGauge gauge) {
        List<T> results = new ArrayList<>(tasks.size());
        if (tasks.isEmpty()) {
            return results;
        }

        int poolSize = Math.min(maxConcurrency(), tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize, namedThreadFactory());
        try {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(instrument(task, gauge)));
            }
            for (Future<T> future : futures) {
                results.add(awaitResult(future));
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    private <T> Callable<T> instrument(Callable<T> task, ConcurrencyGauge gauge) {
        if (gauge == null) {
            return task;
        }
        return () -> {
            gauge.enter();
            try {
                return task.call();
            } finally {
                gauge.exit();
            }
        };
    }

    private <T> T awaitResult(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("图片处理被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new RuntimeException("图片处理工作单元异常: "
                    + (cause != null ? cause.getMessage() : e.getMessage()), cause);
        }
    }

    private ThreadFactory namedThreadFactory() {
        AtomicInteger seq = new AtomicInteger(0);
        return runnable -> {
            Thread t = new Thread(runnable, "pixflow-image-worker-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
