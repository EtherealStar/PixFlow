package com.pixflow.module.file.visual;

import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.module.file.api.visual.AssetVisualInputEvent;
import com.pixflow.module.file.api.visual.AssetVisualInputEventSink;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public class AssetVisualInputOutboxDispatcher {
    private final AssetVisualInputOutboxMapper mapper;

    private final AssetVisualInputEventSink sink;

    private final Clock clock;

    public AssetVisualInputOutboxDispatcher(
            AssetVisualInputOutboxMapper mapper, AssetVisualInputEventSink sink, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int dispatchDue(int limit) {
        int confirmed = 0;
        for (AssetVisualInputOutbox row : mapper.findDue(clock.instant(), limit)) {
            try {
                sink.publish(new AssetVisualInputEvent(
                        row.getEventId(), AssetVisualInputEvent.Kind.valueOf(row.getEventKind()),
                        row.getPackageId(), row.getSkuId(), row.getCreatedAt()));
                confirmed += mapper.confirm(row.getId());
            } catch (RuntimeException failure) {
                int attempts = row.getAttemptCount() + 1;
                long delaySeconds = Math.min(300L, 1L << Math.min(8, attempts));
                mapper.defer(row.getId(), clock.instant().plus(Duration.ofSeconds(delaySeconds)),
                        Sanitizer.sanitizeMessage(failure.getMessage()));
            }
        }
        return confirmed;
    }

    @Scheduled(fixedDelayString = "${pixflow.file.visual-outbox.interval:PT5S}")
    public void dispatchDue() {
        dispatchDue(100);
    }
}
