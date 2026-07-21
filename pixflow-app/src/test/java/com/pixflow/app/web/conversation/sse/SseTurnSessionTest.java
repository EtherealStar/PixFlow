package com.pixflow.app.web.conversation.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.conversation.app.PreparedTurn;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseTurnSessionTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void shutdownScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void normalCompletionCompletesTransportBeforeClosingPreparedTurn() throws Exception {
        PreparedTurn prepared = prepared("ok");
        FakeEmitter emitter = new FakeEmitter();
        SseTurnSession session = session(prepared, emitter, directExecutor());

        session.start();

        assertThat(emitter.completeCount.get()).isEqualTo(1);
        verify(prepared, times(1)).close();
    }

    @Test
    void businessFailureSendsOneErrorFrameAndCompletesNormally() throws Exception {
        PreparedTurn prepared = mock(PreparedTurn.class);
        when(prepared.conversationId()).thenReturn("conv-1");
        when(prepared.ownerUserId()).thenReturn(7L);
        doThrow(new PixFlowException(CommonErrorCode.INTERNAL_ERROR, "failed"))
                .when(prepared).execute(any(), any());
        FakeEmitter emitter = new FakeEmitter();
        SseTurnSession session = session(prepared, emitter, directExecutor());

        session.start();

        assertThat(emitter.sendCount.get()).isEqualTo(1);
        assertThat(emitter.completeCount.get()).isEqualTo(1);
        verify(prepared, times(1)).close();
    }

    @Test
    void rejectionClosesPreparedTurnBeforeReturningAnHttpError() throws Exception {
        PreparedTurn prepared = prepared("never");
        FakeEmitter emitter = new FakeEmitter();
        ExecutorService rejecting = new AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public java.util.List<Runnable> shutdownNow() { return java.util.List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { throw new RejectedExecutionException("full"); }
        };
        SseTurnSession session = session(prepared, emitter, rejecting);

        assertThatThrownBy(session::start)
                .isInstanceOf(PixFlowException.class)
                .satisfies(error -> assertThat(((PixFlowException) error).code())
                        .isEqualTo(ConversationErrorCode.TURN_CAPACITY_EXCEEDED));
        verify(prepared, times(1)).close();
        assertThat(emitter.sendCount.get()).isZero();
    }

    @Test
    void runningCancellationWaitsForCallableExitBeforeClosingLock() throws Exception {
        PreparedTurn prepared = mock(PreparedTurn.class);
        when(prepared.conversationId()).thenReturn("conv-1");
        when(prepared.ownerUserId()).thenReturn(7L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // 模拟暂时忽略 interrupt 的第三方 handler，锁仍不能提前释放。
                }
            }
            invocation.getArgument(1, com.pixflow.common.concurrent.CancellationToken.class)
                    .throwIfCancellationRequested();
            return "cancelled";
        }).when(prepared).execute(any(), any());
        FakeEmitter emitter = new FakeEmitter();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SseTurnSession session = session(prepared, emitter, executor);

        session.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        emitter.fireTimeout();
        verify(prepared, times(0)).close();

        release.countDown();
        verify(prepared, timeout(1000).times(1)).close();
        executor.shutdownNow();
    }

    @Test
    void duplicateTerminalCallbacksStillCloseOnce() throws Exception {
        PreparedTurn prepared = mock(PreparedTurn.class);
        when(prepared.conversationId()).thenReturn("conv-1");
        when(prepared.ownerUserId()).thenReturn(7L);
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            while (true) {
                invocation.getArgument(1, com.pixflow.common.concurrent.CancellationToken.class)
                        .throwIfCancellationRequested();
                Thread.onSpinWait();
            }
        }).when(prepared).execute(any(), any());
        FakeEmitter emitter = new FakeEmitter();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SseTurnSession session = session(prepared, emitter, executor);

        session.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        emitter.fireTimeout();
        emitter.fireError(new IOException("closed"));
        emitter.fireCompletion();

        verify(prepared, timeout(1000).times(1)).close();
        executor.shutdownNow();
    }

    @Test
    void callerStopEmitsStoppedCompletionBeforeCancellingTransport() throws Exception {
        PreparedTurn prepared = mock(PreparedTurn.class);
        when(prepared.conversationId()).thenReturn("conv-1");
        when(prepared.ownerUserId()).thenReturn(7L);
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            while (true) {
                invocation.getArgument(1, com.pixflow.common.concurrent.CancellationToken.class)
                        .throwIfCancellationRequested();
                Thread.onSpinWait();
            }
        }).when(prepared).execute(any(), any());
        FakeEmitter emitter = new FakeEmitter();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SseTurnSession session = session(prepared, emitter, executor);

        session.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        session.cancelByCaller();

        assertThat(emitter.sendCount.get()).isEqualTo(1);
        verify(prepared, timeout(1000).times(1)).close();
        executor.shutdownNow();
    }

    private PreparedTurn prepared(String result) {
        PreparedTurn prepared = mock(PreparedTurn.class);
        when(prepared.conversationId()).thenReturn("conv-1");
        when(prepared.ownerUserId()).thenReturn(7L);
        when(prepared.execute(any(), any())).thenReturn(result);
        return prepared;
    }

    private SseTurnSession session(PreparedTurn prepared, FakeEmitter emitter, ExecutorService executor) {
        Object lock = new Object();
        java.util.concurrent.atomic.AtomicReference<SseTurnSession> ref = new java.util.concurrent.atomic.AtomicReference<>();
        SseAgentEventSink sink = new SseAgentEventSink(
                emitter,
                new ObjectMapper(),
                lock,
                () -> ref.get() == null || ref.get().isWritable(),
                error -> { if (ref.get() != null) ref.get().transportFailed(error); });
        SseHeartbeat heartbeat = new SseHeartbeat(
                emitter, scheduler, Duration.ZERO, lock,
                error -> { if (ref.get() != null) ref.get().transportFailed(error); });
        SseTurnSession session = new SseTurnSession(
                prepared, emitter, sink, heartbeat, executor,
                new SseTurnMetrics(new SimpleMeterRegistry()),
                () -> { });
        ref.set(session);
        return session;
    }

    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            private volatile boolean shutdown;
            @Override public void shutdown() { shutdown = true; }
            @Override public java.util.List<Runnable> shutdownNow() { shutdown = true; return java.util.List.of(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }

    private static final class FakeEmitter extends SseEmitter {
        private final AtomicInteger sendCount = new AtomicInteger();
        private final AtomicInteger completeCount = new AtomicInteger();
        private Runnable timeout;
        private Runnable completion;
        private Consumer<Throwable> error;

        @Override
        public void send(SseEventBuilder builder) {
            sendCount.incrementAndGet();
        }

        @Override
        public void complete() {
            completeCount.incrementAndGet();
            if (completion != null) completion.run();
        }

        @Override public void onTimeout(Runnable callback) { timeout = callback; }
        @Override public void onError(Consumer<Throwable> callback) { error = callback; }
        @Override public void onCompletion(Runnable callback) { completion = callback; }
        void fireTimeout() { timeout.run(); }
        void fireError(Throwable throwable) { error.accept(throwable); }
        void fireCompletion() { completion.run(); }
    }
}
