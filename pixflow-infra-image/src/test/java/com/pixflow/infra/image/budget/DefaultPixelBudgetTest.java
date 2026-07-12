package com.pixflow.infra.image.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.image.ImageProcessingException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultPixelBudgetTest {

    @Test
    void rejectsRequestLargerThanGlobalCapacity() {
        PixelBudget budget = new DefaultPixelBudget(10);

        assertThatThrownBy(() -> budget.acquire(11, Duration.ofMillis(10)))
                .isInstanceOf(ImageProcessingException.class)
                .extracting(error -> ((ImageProcessingException) error).reason())
                .isEqualTo(ImageProcessingException.Reason.PIXEL_BUDGET_EXCEEDED);
    }

    @Test
    void timedOutWaitDoesNotLeakCapacity() {
        PixelBudget budget = new DefaultPixelBudget(10);
        PixelBudget.Permit first = budget.acquire(10, Duration.ofMillis(10));

        assertThatThrownBy(() -> budget.acquire(1, Duration.ofMillis(10)))
                .isInstanceOf(ImageProcessingException.class)
                .extracting(error -> ((ImageProcessingException) error).reason())
                .isEqualTo(ImageProcessingException.Reason.PIXEL_BUDGET_TIMEOUT);

        first.close();
        try (PixelBudget.Permit all = budget.acquire(10, Duration.ofMillis(10))) {
            assertThat(all.weightedPixels()).isEqualTo(10);
        }
    }

    @Test
    void interruptionIsReportedAsCancellation() throws Exception {
        PixelBudget budget = new DefaultPixelBudget(1);
        PixelBudget.Permit held = budget.acquire(1, Duration.ofSeconds(1));
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                budget.acquire(1, Duration.ofSeconds(5));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        waiter.start();
        Thread.sleep(50);
        waiter.interrupt();
        waiter.join(1000);
        held.close();
        assertThat(waiter.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(ImageProcessingException.class);
        assertThat(((ImageProcessingException) failure.get()).reason())
                .isEqualTo(ImageProcessingException.Reason.PIXEL_BUDGET_CANCELLED);
    }

    @Test
    void twoWorkerPoolsShareOneGlobalCapacity() throws Exception {
        PixelBudget budget = new DefaultPixelBudget(10);
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        Runnable work = () -> {
            try (PixelBudget.Permit ignored = budget.acquire(10, Duration.ofSeconds(1))) {
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                entered.countDown();
                release.await();
                concurrent.decrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            first.submit(work);
            entered.await();
            second.submit(work);
            Thread.sleep(50);
            assertThat(maxConcurrent).hasValue(1);
            release.countDown();
        } finally {
            release.countDown();
            first.shutdownNow();
            second.shutdownNow();
        }
    }
}
