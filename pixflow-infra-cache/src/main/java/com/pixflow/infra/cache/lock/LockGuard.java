package com.pixflow.infra.cache.lock;

/**
 * 当前线程持有的锁只读视图。
 *
 * <p>guard 不暴露 unlock；锁的释放始终由 {@link LockTemplate} 在 owner 线程的 finally 中完成。
 */
public interface LockGuard {
    boolean isHeldByCurrentThread();

    default void assertHeld() {
        if (!isHeldByCurrentThread()) {
            throw new LockOwnershipLostException("当前线程已不再持有执行锁");
        }
    }
}
