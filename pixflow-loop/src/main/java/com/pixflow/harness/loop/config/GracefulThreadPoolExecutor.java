package com.pixflow.harness.loop.config;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * loop 工具执行线程池：Spring 销毁时先优雅等待，超时后再强制取消队列任务。
 */
public final class GracefulThreadPoolExecutor extends ThreadPoolExecutor {
    private final long shutdownTimeoutSeconds;

    public GracefulThreadPoolExecutor(int poolSize,
                                      BlockingQueue<Runnable> workQueue,
                                      ThreadFactory threadFactory,
                                      RejectedExecutionHandler rejectedExecutionHandler,
                                      long shutdownTimeoutSeconds) {
        super(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS, workQueue, threadFactory, rejectedExecutionHandler);
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    public void shutdownGracefully() {
        shutdown();
        try {
            if (!awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                shutdownNow();
            }
        } catch (InterruptedException ex) {
            shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
