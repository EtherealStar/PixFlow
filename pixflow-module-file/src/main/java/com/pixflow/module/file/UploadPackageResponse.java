package com.pixflow.module.file;

import com.pixflow.module.file.pkg.PackageStatus;

public record UploadPackageResponse(long packageId, PackageStatus status, boolean messageConfirmed) {
}
