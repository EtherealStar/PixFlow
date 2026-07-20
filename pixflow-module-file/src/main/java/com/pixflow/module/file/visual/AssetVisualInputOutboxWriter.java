package com.pixflow.module.file.visual;

import com.pixflow.module.file.api.visual.AssetVisualInputEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class AssetVisualInputOutboxWriter {
    private final AssetVisualInputOutboxMapper mapper;

    public AssetVisualInputOutboxWriter(AssetVisualInputOutboxMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public void packageReady(long packageId, Instant now) {
        append(AssetVisualInputEvent.Kind.PACKAGE_READY, packageId, null, now);
    }

    public void skuChanged(long packageId, String skuId, Instant now) {
        if (skuId != null && !skuId.isBlank()) {
            append(AssetVisualInputEvent.Kind.SKU_INPUT_CHANGED, packageId, skuId, now);
        }
    }

    private void append(AssetVisualInputEvent.Kind kind, long packageId, String skuId, Instant now) {
        AssetVisualInputOutbox row = new AssetVisualInputOutbox();
        row.setEventId(UUID.randomUUID().toString());
        row.setEventKind(kind.name());
        row.setPackageId(packageId);
        row.setSkuId(skuId);
        row.setAttemptCount(0);
        row.setNextAttemptAt(now);
        row.setCreatedAt(now);
        mapper.insert(row);
    }
}
