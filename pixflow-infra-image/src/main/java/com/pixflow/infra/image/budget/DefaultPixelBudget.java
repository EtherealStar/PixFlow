package com.pixflow.infra.image.budget;

import com.pixflow.infra.image.ImageProcessingException;
import java.time.Duration;
import java.util.Objects;

public final class DefaultPixelBudget implements PixelBudget {
    private final long capacity;

    private long available;

    public DefaultPixelBudget(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.available = capacity;
    }

    @Override
    public Permit acquire(long weightedPixels, Duration timeout) {
        if (weightedPixels <= 0) {
            throw new IllegalArgumentException("weightedPixels must be positive");
        }
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (weightedPixels > capacity) {
            throw failure(ImageProcessingException.Reason.PIXEL_BUDGET_EXCEEDED,
                    "weighted pixels exceed the global pixel budget");
        }

        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (this) {
            while (available < weightedPixels) {
                if (remainingNanos <= 0) {
                    throw failure(ImageProcessingException.Reason.PIXEL_BUDGET_TIMEOUT,
                            "timed out waiting for the global pixel budget");
                }
                try {
                    long millis = remainingNanos / 1_000_000L;
                    int nanos = (int) (remainingNanos % 1_000_000L);
                    wait(millis, nanos);
                } catch (InterruptedException interrupted) {
                    // 保留中断标记，让上层任务取消逻辑能够观察到本次中断。
                    Thread.currentThread().interrupt();
                    throw failure(ImageProcessingException.Reason.PIXEL_BUDGET_CANCELLED,
                            "interrupted while waiting for the global pixel budget");
                }
                remainingNanos = deadline - System.nanoTime();
            }
            available -= weightedPixels;
        }
        return new BudgetPermit(weightedPixels);
    }

    private ImageProcessingException failure(ImageProcessingException.Reason reason, String message) {
        return new ImageProcessingException(reason, null, null, null, message);
    }

    private final class BudgetPermit implements Permit {
        private final long weightedPixels;

        private boolean closed;

        private BudgetPermit(long weightedPixels) {
            this.weightedPixels = weightedPixels;
        }

        @Override
        public long weightedPixels() {
            return weightedPixels;
        }

        @Override
        public void close() {
            synchronized (DefaultPixelBudget.this) {
                if (closed) {
                    return;
                }
                closed = true;
                available += weightedPixels;
                DefaultPixelBudget.this.notifyAll();
            }
        }
    }
}
