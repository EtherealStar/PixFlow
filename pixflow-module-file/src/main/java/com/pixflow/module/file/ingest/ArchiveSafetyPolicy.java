package com.pixflow.module.file.ingest;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.file.config.FileProperties;
import com.pixflow.module.file.error.FileErrorCode;

public final class ArchiveSafetyPolicy {
    private final FileProperties properties;

    public ArchiveSafetyPolicy(FileProperties properties) {
        this.properties = properties;
    }

    public String admitPath(String path, int entryCount, long declaredSize,
                            long compressedSize, boolean encrypted) {
        if (encrypted) {
            throw unsafe("encrypted archive entries are not allowed");
        }
        if (entryCount > properties.getZip().getMaxEntries()) {
            throw unsafe("archive entry count exceeds limit");
        }
        if (declaredSize > properties.getZip().getMaxEntryBytes().toBytes()) {
            throw unsafe("archive entry size exceeds limit");
        }
        long safeCompressedSize = Math.max(1L, compressedSize);
        if (declaredSize > 0
                && declaredSize / safeCompressedSize > properties.getZip().getMaxCompressionRatio()) {
            throw unsafe("archive entry compression ratio exceeds limit");
        }
        return ZipPathValidator.validate(path);
    }

    public void admitActualSize(long entrySize, long totalSize) {
        if (entrySize > properties.getZip().getMaxEntryBytes().toBytes()) {
            throw unsafe("archive entry actual size exceeds limit");
        }
        if (totalSize > properties.getZip().getMaxTotalBytes().toBytes()) {
            throw unsafe("archive total actual size exceeds limit");
        }
    }

    public long maxEntryBytes() {
        return properties.getZip().getMaxEntryBytes().toBytes();
    }

    private static PixFlowException unsafe(String message) {
        return new PixFlowException(FileErrorCode.ZIP_BOMB_DETECTED, message);
    }
}
