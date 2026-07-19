package com.pixflow.module.file.api;

import java.util.Optional;

public interface AssetReferenceHistory {
    Optional<DeletedAssetReference> findDeleted(String referenceKey);
}
