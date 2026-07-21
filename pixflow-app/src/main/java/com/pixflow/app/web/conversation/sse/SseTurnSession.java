package com.pixflow.app.web.conversation.sse;

import com.pixflow.common.concurrent.CancellationReason;
import com.pixflow.common.concurrent.CancellationSource;
import com.pixflow.common.concurrent.OperationCancelledException;
import com.pixflow.common.error.ErrorNormalizer;
import com.pixflow.common.error.CommonErrorCode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.conversation.app.PreparedTurn;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class SseTurnSession {
    private static final Logger LOGGER = LoggerFactory.getLogger(SseTurnSession.class);

    enum State { NEW, RUNNING, CANCELLING, TERMINATED }

    private final PreparedTurn preparedTurn;

    private final SseEmitter emitter;

    private final SseAgentEventSink sink;

    private final SseHeartbeat heartbeat;

    private final ExecutorService executor;

    private final SseTurnMetrics metrics;

    private final Runnable ownershipClosed;

    private final String turnId = UUID.randomUUID().toString();

    private final CancellationSource cancellation = new CancellationSource();

    private final ErrorNormalizer errorNormalizer = new ErrorNormalizer();

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    private final AtomicReference<OwnedFutureTask> worker = new AtomicReference<>();

    private final AtomicReference<SseTerminationReason> terminalReason = new AtomicReference<>();

    private final AtomicBoolean metricClosed = new AtomicBoolean();

    // 终态所有权与传输可写性分离：业务错误抢到终态后，仍需保留一次发送 error 帧的机会。
    private final AtomicBoolean transportWritable = new AtomicBoolean(true);

    SseTurnSession(
            PreparedTurn preparedTurn,
            SseEmitter emitter,
            SseAgentEventSink sink,
            SseHeartbeat heartbeat,
            ExecutorService executor,
            SseTurnMetrics metrics,
            Runnable ownershipClosed) {
        this.preparedTurn = Objects.requireNonNull(preparedTurn, "preparedTurn");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.ownershipClosed = ownershipClosed == null ? () -> { } : ownershipClosed;
        emitter.onCompletion(this::onCompletion);
        emitter.onTimeout(() -> cancel(CancellationReason.TIMEOUT, SseTerminationReason.TIMEOUT));
        emitter.onError(error -> cancel(
                CancellationReason.CLIENT_DISCONNECTED,
                SseTerminationReason.CLIENT_DISCONNECTED));
    }

    public void start() {
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            throw new IllegalStateException("SSE turn session can only be started once");
        }
        OwnedFutureTask task = new OwnedFutureTask();
        worker.set(task);
        metrics.sessionStarted();
        try {
            heartbeat.start();
        } catch (RuntimeException heartbeatFailure) {
            state.set(State.TERMINATED);
            worker.set(null);
            transportWritable.set(false);
            preparedTurn.close();
            closeMetric(SseTerminationReason.SERVER_SHUTDOWN);
            ownershipClosed.run();
            throw new PixFlowException(
                    CommonErrorCode.DEPENDENCY_UNAVAILABLE,
                    "SSE heartbeat scheduler is unavailable",
                    heartbeatFailure);
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            state.set(State.TERMINATED);
            transportWritable.set(false);
            worker.set(null);
            heartbeat.stop();
            preparedTurn.close();
            metrics.executorRejected();
            closeMetric(SseTerminationReason.CAPACITY_REJECTED);
            ownershipClosed.run();
            throw new PixFlowException(
                    ConversationErrorCode.TURN_CAPACITY_EXCEEDED,
                    "agent turn capacity is exhausted",
                    rejected);
        }
    }

    public SseEmitter emitter() {
        return emitter;
    }

    boolean belongsTo(long ownerUserId, String conversationId) {
        return preparedTurn.ownerUserId() == ownerUserId
                && preparedTurn.conversationId().equals(conversationId);
    }

    void cancelByCaller() {
        if (state.get() != State.RUNNING) {
            return;
        }
        if (!sink.sendCompleted(null, true)) {
            return;
        }
        cancel(CancellationReason.CALLER_ABORTED, SseTerminationReason.USER_STOPPED);
    }

    boolean isWritable() {
        return transportWritable.get();
    }

    void transportFailed(Throwable error) {
        cancel(CancellationReason.CLIENT_DISCONNECTED, SseTerminationReason.CLIENT_DISCONNECTED);
    }

    void shutdown() {
        if (state.compareAndSet(State.NEW, State.TERMINATED)) {
            terminalReason.set(SseTerminationReason.SERVER_SHUTDOWN);
            transportWritable.set(false);
            preparedTurn.close();
            ownershipClosed.run();
            return;
        }
        cancel(CancellationReason.SERVER_SHUTDOWN, SseTerminationReason.SERVER_SHUTDOWN);
    }

    private Void runWorker() {
        try {
            preparedTurn.execute(sink, cancellation.token());
            succeed();
        } catch (OperationCancelledException cancelled) {
            cancel(cancelled.reason(), terminationReason(cancelled.reason()));
        } catch (Throwable error) {
            fail(error);
        }
        return null;
    }

    private void succeed() {
        if (!state.compareAndSet(State.RUNNING, State.TERMINATED)) {
            return;
        }
        heartbeat.stop();
        terminalReason.compareAndSet(null, SseTerminationReason.COMPLETED);
        transportWritable.set(false);
        safeComplete();
    }

    private void fail(Throwable raw) {
        if (!state.compareAndSet(State.RUNNING, State.TERMINATED)) {
            return;
        }
        heartbeat.stop();
        terminalReason.compareAndSet(null, SseTerminationReason.BUSINESS_ERROR);
        PixFlowException error = errorNormalizer.normalize(raw);
        sink.sendError(error);
        transportWritable.set(false);
        safeComplete();
    }

    private void cancel(CancellationReason reason, SseTerminationReason terminationReason) {
        if (!state.compareAndSet(State.RUNNING, State.CANCELLING)) {
            return;
        }
        heartbeat.stop();
        terminalReason.compareAndSet(null, terminationReason);
        transportWritable.set(false);
        cancellation.cancel(reason);
        OwnedFutureTask task = worker.get();
        if (task != null) {
            task.cancel(true);
        }
    }

    private void onCompletion() {
        if (state.get() == State.RUNNING) {
            cancel(CancellationReason.CLIENT_DISCONNECTED, SseTerminationReason.CLIENT_DISCONNECTED);
        }
    }

    private void finishOwnership() {
        State current = state.get();
        if (current == State.CANCELLING) {
            state.compareAndSet(State.CANCELLING, State.TERMINATED);
            safeComplete();
        }
        preparedTurn.close();
        closeMetric(terminalReason.get() == null
                ? SseTerminationReason.SERVER_SHUTDOWN
                : terminalReason.get());
        ownershipClosed.run();
        LOGGER.info("SSE turn terminated: turnId={} conversationId={} ownerUserId={} reason={} responseCommitted={}",
                turnId, preparedTurn.conversationId(), preparedTurn.ownerUserId(), terminalReason.get(), true);
    }

    private void safeComplete() {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            metrics.lateWrite();
        }
    }

    private void closeMetric(SseTerminationReason reason) {
        if (metricClosed.compareAndSet(false, true)) {
            metrics.sessionEnded(reason);
        }
    }

    private static SseTerminationReason terminationReason(CancellationReason reason) {
        return switch (reason) {
            case TIMEOUT -> SseTerminationReason.TIMEOUT;
            case SERVER_SHUTDOWN -> SseTerminationReason.SERVER_SHUTDOWN;
            case CLIENT_DISCONNECTED -> SseTerminationReason.CLIENT_DISCONNECTED;
            case CALLER_ABORTED -> SseTerminationReason.USER_STOPPED;
        };
    }

    private final class OwnedFutureTask extends FutureTask<Void> {
        private final AtomicBoolean started = new AtomicBoolean();

        private final AtomicBoolean exited = new AtomicBoolean();

        private final AtomicBoolean ownershipClosed = new AtomicBoolean();

        private OwnedFutureTask() {
            super(SseTurnSession.this::runWorker);
        }

        @Override
        public void run() {
            if (isCancelled()) {
                return;
            }
            started.set(true);
            try {
                super.run();
            } finally {
                exited.set(true);
                tryFinishOwnership();
            }
        }

        @Override
        protected void done() {
            // FutureTask.cancel(true) 可能在线程真正退出前触发 done，不能在这里直接释放会话锁。
            tryFinishOwnership();
        }

        private void tryFinishOwnership() {
            // 未启动的任务可直接收口；已启动任务必须等 run() finally 标记 exited 后再释放所有权。
            if ((!started.get() || exited.get()) && ownershipClosed.compareAndSet(false, true)) {
                finishOwnership();
            }
        }
    }
}
