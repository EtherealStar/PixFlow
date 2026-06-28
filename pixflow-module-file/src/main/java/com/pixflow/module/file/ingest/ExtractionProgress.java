package com.pixflow.module.file.ingest;

import com.pixflow.module.file.pkg.PackageStatus;

public record ExtractionProgress(long packageId, int extracted, int total, PackageStatus status, String traceId) {
}
