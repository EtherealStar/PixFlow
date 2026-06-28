package com.pixflow.harness.context.runtime;

import com.pixflow.harness.context.snapshot.ContextSnapshot;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class CurrentModelContext {
    private final AtomicReference<ContextSnapshot> snapshot = new AtomicReference<>();

    public void set(ContextSnapshot snapshot) {
        this.snapshot.set(snapshot);
    }

    public Optional<ContextSnapshot> snapshot() {
        return Optional.ofNullable(snapshot.get());
    }

    public void clear() {
        snapshot.set(null);
    }
}
