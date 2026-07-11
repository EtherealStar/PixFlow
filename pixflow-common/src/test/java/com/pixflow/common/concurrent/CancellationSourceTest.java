package com.pixflow.common.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CancellationSourceTest {
    @Test
    void publishesOnlyTheFirstCancellationReason() throws Exception {
        CancellationSource source = new CancellationSource();

        assertThat(source.cancel(CancellationReason.TIMEOUT)).isTrue();
        assertThat(source.cancel(CancellationReason.CLIENT_DISCONNECTED)).isFalse();
        assertThat(source.token().cancellationSignal().toCompletableFuture().get(1, TimeUnit.SECONDS)).isNull();
        assertThat(source.token().reason()).contains(CancellationReason.TIMEOUT);
        assertThatThrownBy(source.token()::throwIfCancellationRequested)
                .isInstanceOf(OperationCancelledException.class)
                .extracting("reason")
                .isEqualTo(CancellationReason.TIMEOUT);
    }

    @Test
    void noneTokenNeverCancels() {
        assertThat(CancellationToken.NONE.isCancellationRequested()).isFalse();
        assertThat(CancellationToken.NONE.reason()).isEmpty();
        CancellationToken.NONE.throwIfCancellationRequested();
        assertThat(CancellationToken.NONE.cancellationSignal().toCompletableFuture()).isNotDone();
    }
}
