package com.pixflow.module.file.api;

import com.pixflow.common.web.PageResponse;
import java.util.List;

public interface AssetReferenceCatalog {
    PageResponse<AssetReferenceCandidate> list(
            AssetReferenceSource source,
            String parentKey,
            String query,
            long page,
            long size,
            List<String> excludedReferenceKeys);

    PageResponse<AssetReferenceCandidate> listGeneratedByTaskId(
            long taskId, long page, long size, List<String> excludedReferenceKeys);
}
