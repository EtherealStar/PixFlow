package com.pixflow.common.concurrent;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface CancellationToken {
    CancellationToken NONE = new CancellationToken() {
        private final CompletionStage<Void> signal = new CompletableFuture<Void>().minimalCompletionStage();

        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        @Override
        public Optional<CancellationReason> reason() {
            return Optional.empty();
        }

        @Override
        public CompletionStage<Void> cancellationSignal() {
            return signal;
        }

        @Override
        public void throwIfCancellationRequested() {
        }
    };

    boolean isCancellationRequested();

    Optional<CancellationReason> reason();

    CompletionStage<Void> cancellationSignal();

    void throwIfCancellationRequested();
}
