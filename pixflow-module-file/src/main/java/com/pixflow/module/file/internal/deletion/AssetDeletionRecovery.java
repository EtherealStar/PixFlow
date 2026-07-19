package com.pixflow.module.file.internal.deletion;

import com.pixflow.module.file.api.AssetDeletionService;
import org.springframework.scheduling.annotation.Scheduled;

public final class AssetDeletionRecovery {
    private static final int BATCH_SIZE = 100;

    private final AssetDeletionService deletionService;

    public AssetDeletionRecovery(AssetDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @Scheduled(fixedDelayString = "${pixflow.file.deletion-recovery-interval:PT1M}")
    public void recover() {
        deletionService.resumePending(BATCH_SIZE);
    }
}
