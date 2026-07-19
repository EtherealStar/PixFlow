package com.pixflow.module.file.api;

public interface AssetDeletionService {
    void deleteImage(long packageId, long imageId);

    void deletePackage(long packageId);

    int resumePending(int limit);
}
