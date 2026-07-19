package com.pixflow.module.file.ingest;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.file.error.FileErrorCode;
import java.util.Locale;

public enum ArchiveFormat {
    ZIP("zip"),
    RAR("rar"),
    SEVEN_Z("7z");

    private final String extension;

    ArchiveFormat(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public static ArchiveFormat fromFilename(String filename) {
        String normalized = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        for (ArchiveFormat format : values()) {
            if (normalized.endsWith("." + format.extension)) {
                return format;
            }
        }
        throw new PixFlowException(FileErrorCode.INVALID_ZIP, "archive must be zip, rar or 7z");
    }

    public static ArchiveFormat detect(byte[] header) {
        if (startsWith(header, 0x50, 0x4b, 0x03, 0x04)
                || startsWith(header, 0x50, 0x4b, 0x05, 0x06)
                || startsWith(header, 0x50, 0x4b, 0x07, 0x08)) {
            return ZIP;
        }
        if (startsWith(header, 0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)) {
            return RAR;
        }
        if (startsWith(header, 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c)) {
            return SEVEN_Z;
        }
        throw new PixFlowException(FileErrorCode.INVALID_ZIP, "unsupported archive signature");
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        if (bytes == null || bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[index] & 0xff) != expected[index]) {
                return false;
            }
        }
        return true;
    }
}
