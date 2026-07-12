package com.pixflow.infra.image.budget;

import java.time.Duration;

public interface PixelBudget {
    Permit acquire(long weightedPixels, Duration timeout);

    interface Permit extends AutoCloseable {
        long weightedPixels();

        @Override
        void close();
    }
}
