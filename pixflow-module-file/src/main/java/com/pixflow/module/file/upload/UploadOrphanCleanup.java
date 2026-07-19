package com.pixflow.module.file.upload;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectStorage;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;

public final class UploadOrphanCleanup {
    private static final int BATCH_SIZE = 100;

    private final UploadSessionStore store;

    private final ObjectStorage objectStorage;

    private final Clock clock;

    public UploadOrphanCleanup(UploadSessionStore store, ObjectStorage objectStorage, Clock clock) {
        this.store = store;
        this.objectStorage = objectStorage;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pixflow.file.upload.orphan-cleanup-interval:PT1H}")
    public void cleanup() {
        for (String uploadId : store.findExpiredUploadIds(clock.instant(), BATCH_SIZE)) {
            // index 比 session 多保留两个 TTL，因此会话意外过期后仍能定位临时分片前缀。
            objectStorage.deleteByPrefix(BucketType.TMP, "uploads/" + uploadId + "/");
            store.forgetUploadId(uploadId);
        }
    }
}
