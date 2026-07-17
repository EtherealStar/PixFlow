package com.pixflow.common.concurrent;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

public final class CancellationSource {
    private final AtomicReference<CancellationReason> reason = new AtomicReference<>();

    private final CompletableFuture<Void> signal = new CompletableFuture<>();

    private final CancellationToken token = new SourceToken();

    public CancellationToken token() {
        return token;
    }

    public boolean cancel(CancellationReason cancellationReason) {
        Objects.requireNonNull(cancellationReason, "cancellationReason");
        if (!reason.compareAndSet(null, cancellationReason)) {
            return false;
        }
        // 原因发布成功后再完成 signal，订阅方被唤醒时一定能读取到稳定原因。
        signal.complete(null);
        return true;
    }

    private final class SourceToken implements CancellationToken {
        @Override
        public boolean isCancellationRequested() {
            return reason.get() != null;
        }

        @Override
        public Optional<CancellationReason> reason() {
            return Optional.ofNullable(reason.get());
        }

        @Override
        public CompletionStage<Void> cancellationSignal() {
            // 只暴露只读视图，消费者不能通过 toCompletableFuture().cancel() 反向破坏 source。
            return signal.minimalCompletionStage();
        }

        @Override
        public void throwIfCancellationRequested() {
            CancellationReason current = reason.get();
            if (current != null) {
                throw new OperationCancelledException(current);
            }
        }
    }
}
