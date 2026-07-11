package com.pixflow.module.conversation.lock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;

class TurnLockHandleTest {
    @Test
    void releasesUsingOriginalOwnerThreadIdFromWorkerThread() throws Exception {
        RLock lock = mock(RLock.class);
        @SuppressWarnings("unchecked")
        RFuture<Void> unlock = mock(RFuture.class);
        long ownerThreadId = Thread.currentThread().threadId();
        when(lock.isHeldByThread(ownerThreadId)).thenReturn(true);
        when(lock.unlockAsync(ownerThreadId)).thenReturn(unlock);
        when(unlock.toCompletableFuture()).thenReturn(CompletableFuture.completedFuture(null));
        TurnLockHandle handle = new TurnLockHandle(lock);
        ExecutorService worker = Executors.newSingleThreadExecutor();

        worker.submit(handle::close).get();
        handle.close();

        verify(lock, times(1)).unlockAsync(ownerThreadId);
        worker.shutdownNow();
    }
}
