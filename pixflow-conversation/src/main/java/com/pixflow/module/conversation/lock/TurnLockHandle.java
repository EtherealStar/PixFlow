package com.pixflow.module.conversation.lock;

import org.redisson.api.RLock;

public final class TurnLockHandle implements AutoCloseable {
    private final RLock lock;

    TurnLockHandle(RLock lock) {
        this.lock = lock;
    }

    @Override
    public void close() {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
