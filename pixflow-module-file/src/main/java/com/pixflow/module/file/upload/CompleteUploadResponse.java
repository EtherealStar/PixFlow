package com.pixflow.module.file.upload;

import com.pixflow.module.file.pkg.PackageStatus;

public record CompleteUploadResponse(long packageId, PackageStatus status) {
}
