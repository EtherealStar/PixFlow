package com.pixflow.harness.eval.recorder;

import com.pixflow.harness.eval.config.EvalProperties;
import com.pixflow.harness.eval.store.AgentTraceEntity;
import com.pixflow.harness.eval.store.AgentTraceRepository;
import com.pixflow.harness.eval.support.TracePayloadCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class TraceIngestBuffer {
    private final BlockingQueue<TraceCommand> queue;
    private final EvalProperties properties;
    private final TracePayloadCodec codec;
    private final AgentTraceRepository repository;
    private final Counter droppedCounter;
    private final Timer flushTimer;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService executor;

    public TraceIngestBuffer(
            EvalProperties properties,
            TracePayloadCodec codec,
            AgentTraceRepository repository,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.codec = codec;
        this.repository = repository;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, properties.getBuffer().getCapacity()));
        this.droppedCounter = Counter.builder("pixflow.eval.trace.dropped").register(meterRegistry);
        this.flushTimer = Timer.builder("pixflow.eval.trace.flush.latency").register(meterRegistry);
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, properties.getBuffer().getFlushThreads()),
                task -> {
                    Thread thread = new Thread(task, "pixflow-eval-trace-flusher");
                    thread.setDaemon(true);
                    return thread;
                });
        for (int i = 0; i < Math.max(1, properties.getBuffer().getFlushThreads()); i++) {
            executor.submit(this::run);
        }
        meterRegistry.gauge("pixflow.eval.trace.buffer.size", queue, BlockingQueue::size);
    }

    public boolean offer(TraceCommand command) {
        if (command == null || !running.get()) {
            return false;
        }
        boolean accepted = queue.offer(command);
        if (!accepted) {
            // trace 是旁路观测数据，队列满时只计数丢弃，不能反压主循环。
            dropped.incrementAndGet();
            droppedCounter.increment();
        }
        return accepted;
    }

    public void flushNow() {
        List<TraceCommand> batch = drainBatch();
        flush(batch);
    }

    public long droppedCount() {
        return dropped.get();
    }

    public int size() {
        return queue.size();
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        Duration timeout = properties.getBuffer().getDrainTimeoutOnShutdown();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            flushNow();
        }
        long remaining = queue.size();
        if (remaining > 0) {
            dropped.addAndGet(remaining);
            droppedCounter.increment(remaining);
            queue.clear();
        }
        executor.shutdownNow();
    }

    private void run() {
        while (running.get()) {
            try {
                TraceCommand first = queue.poll(properties.getBuffer().getFlushInterval().toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<TraceCommand> batch = new ArrayList<>();
                batch.add(first);
                queue.drainTo(batch, Math.max(1, properties.getBuffer().getFlushBatchSize()) - 1);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<TraceCommand> drainBatch() {
        List<TraceCommand> batch = new ArrayList<>();
        queue.drainTo(batch, Math.max(1, properties.getBuffer().getFlushBatchSize()));
        return batch;
    }

    private void flush(List<TraceCommand> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        flushTimer.record(() -> {
            try {
                List<AgentTraceEntity> entities = batch.stream().map(codec::encode).toList();
                repository.upsertBatch(entities);
            } catch (RuntimeException e) {
                dropped.addAndGet(batch.size());
                droppedCounter.increment(batch.size());
            }
        });
    }
}
