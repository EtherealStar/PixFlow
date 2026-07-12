package com.pixflow.infra.cache.lock;

public final class LockOwnershipLostException extends IllegalStateException {
    public LockOwnershipLostException(String message) {
        super(message);
    }
}
