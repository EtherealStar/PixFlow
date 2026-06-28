package com.pixflow.harness.state.model;

import java.util.Objects;

public record ProgressView(
        int total,
        int done,
        int failed,
        ProgressSource source,
        PersistedProgress persisted,
        Long redisDone,
        long drift) {

    public ProgressView {
        if (total < 0 || done < 0 || failed < 0) {
            throw new IllegalArgumentException("progress counts must not be negative");
        }
        source = Objects.requireNonNull(source, "source");
        persisted = Objects.requireNonNull(persisted, "persisted");
    }

    public static ProgressView fromMysql(PersistedProgress persisted) {
        return new ProgressView(
                persisted.total(),
                persisted.succeeded(),
                persisted.failed(),
                ProgressSource.MYSQL,
                persisted,
                null,
                0);
    }

    public static ProgressView fromRedis(PersistedProgress persisted, long redisDone) {
        if (redisDone < 0) {
            throw new IllegalArgumentException("redisDone must not be negative");
        }
        return new ProgressView(
                persisted.total(),
                Math.toIntExact(redisDone),
                persisted.failed(),
                ProgressSource.REDIS,
                persisted,
                redisDone,
                redisDone - persisted.succeeded());
    }

    public record PersistedProgress(int total, int succeeded, int failed) {
        public PersistedProgress {
            if (total < 0 || succeeded < 0 || failed < 0) {
                throw new IllegalArgumentException("persisted counts must not be negative");
            }
        }
    }
}
