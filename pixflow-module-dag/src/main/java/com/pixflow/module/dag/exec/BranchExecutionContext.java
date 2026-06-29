package com.pixflow.module.dag.exec;

import com.pixflow.infra.image.RasterImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单元执行的资源清理上下文(对齐 dag.md §8.5)。
 *
 * <p>try-with-resources 保证任何异常/正常返回都触发清理;LIFO 顺序,后注册先释放。
 * close() 幂等(AtomicBoolean 守护),允许多次 close 不重复释放。
 *
 * <p>注册的资源:BufferedImage(RasterImage)、InputStream、watermark byte[] 等。
 */
public final class BranchExecutionContext implements AutoCloseable {

    private final Deque<Runnable> cleanups = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 注册一个 RasterImage 释放任务;后续 close 时按 LIFO 顺序释放。 */
    public void onDispose(RasterImage image) {
        if (image == null) {
            return;
        }
        cleanups.push(() -> {
            try {
                var buf = image.buffer();
                if (buf != null) {
                    buf.getGraphics().dispose();
                }
            } catch (Throwable ignored) {
                // 释放失败不影响其他清理
            }
        });
    }

    /** 注册一个 InputStream 关闭任务。 */
    public void onClose(InputStream stream) {
        if (stream == null) {
            return;
        }
        cleanups.push(() -> {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        });
    }

    /** 注册任意清理任务。 */
    public void onCleanup(Runnable task) {
        if (task != null) {
            cleanups.push(task);
        }
    }

    /** 当前已注册清理任务数(供测试断言)。 */
    public int pendingCleanups() {
        return cleanups.size();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // LIFO 弹出执行;异常吞噬不影响后续清理
            while (!cleanups.isEmpty()) {
                Runnable task = cleanups.pop();
                try {
                    task.run();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}