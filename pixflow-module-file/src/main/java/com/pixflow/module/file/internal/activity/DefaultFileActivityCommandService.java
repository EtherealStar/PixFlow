package com.pixflow.module.file.internal.activity;

import com.pixflow.module.file.FileService;
import com.pixflow.module.file.api.activity.FileActivityCommandService;
import com.pixflow.module.file.api.activity.FileActivitySourceKind;
import com.pixflow.module.file.upload.UploadSessionService;
import java.util.Objects;

public final class DefaultFileActivityCommandService implements FileActivityCommandService {
    private final UploadSessionService uploads;

    private final FileService files;

    public DefaultFileActivityCommandService(UploadSessionService uploads, FileService files) {
        this.uploads = Objects.requireNonNull(uploads, "uploads");
        this.files = Objects.requireNonNull(files, "files");
    }

    @Override
    public void cancel(FileActivitySourceKind sourceKind, String sourceId) {
        if (sourceKind == FileActivitySourceKind.UPLOAD) {
            uploads.cancel(sourceId);
            return;
        }
        files.cancelExtraction(packageId(sourceId));
    }

    @Override
    public void clear(FileActivitySourceKind sourceKind, String sourceId) {
        if (sourceKind == FileActivitySourceKind.UPLOAD) {
            throw new IllegalArgumentException("upload activity cannot be cleared");
        }
        // 包清理规则由 Asset Library 执行，App 不直接拼接删除步骤。
        files.delete(packageId(sourceId));
    }

    private static long packageId(String sourceId) {
        return Long.parseLong(Objects.requireNonNull(sourceId, "sourceId"));
    }
}
