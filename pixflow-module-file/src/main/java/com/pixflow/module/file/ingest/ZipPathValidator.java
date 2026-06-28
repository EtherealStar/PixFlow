package com.pixflow.module.file.ingest;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.file.error.FileErrorCode;
import java.nio.file.Path;

public final class ZipPathValidator {
    private ZipPathValidator() {
    }

    public static String validate(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            throw invalid(entryName);
        }
        String normalized = entryName.trim().replace('\\', '/');
        if (normalized.startsWith("/") || normalized.startsWith("../") || normalized.contains("/../")
                || normalized.endsWith("/..") || normalized.contains(":") || Path.of(normalized).isAbsolute()) {
            throw invalid(entryName);
        }
        for (String part : normalized.split("/")) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                throw invalid(entryName);
            }
        }
        return normalized;
    }

    private static PixFlowException invalid(String entryName) {
        return new PixFlowException(FileErrorCode.ZIP_PATH_TRAVERSAL, "zip entry path is unsafe: " + entryName);
    }
}
