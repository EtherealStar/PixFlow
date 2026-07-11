package com.pixflow.module.conversation.lock;

import org.redisson.api.RLock;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TurnLockHandle implements AutoCloseable {
    private final RLock lock;
    private final long ownerThreadId;
    private final AtomicBoolean closed = new AtomicBoolean();

    TurnLockHandle(RLock lock) {
        this.lock = lock;
        this.ownerThreadId = Thread.currentThread().threadId();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // prepare 在请求线程取锁，worker 退出后跨线程释放；Redisson 需使用原 owner id。
        if (lock.isHeldByThread(ownerThreadId)) {
            lock.unlockAsync(ownerThreadId).toCompletableFuture().join();
        }
    }
}
