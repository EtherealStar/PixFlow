package com.pixflow.module.task.internal.download;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.UnitKind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DownloadBundleBuilder {
    private final ObjectStorage objectStorage;
    private final TaskProperties properties;

    public DownloadBundleBuilder(ObjectStorage objectStorage, TaskProperties properties) {
        this.objectStorage = objectStorage;
        this.properties = properties;
    }

    public ObjectRef build(long taskId, List<ProcessResult> results) {
        long maxBytes = properties.getDownload().getMaxBundleBytes();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            int index = 0;
            for (ProcessResult result : results) {
                if (result.getOutputMinioKey() == null) {
                    continue;
                }
                BucketType bucket = result.getKind() == UnitKind.GENERATIVE ? BucketType.GENERATED : BucketType.RESULTS;
                try (InputStream in = objectStorage.getStream(ObjectLocation.of(bucket, result.getOutputMinioKey()))) {
                    zip.putNextEntry(new ZipEntry(entryName(++index, result)));
                    in.transferTo(zip);
                    zip.closeEntry();
                }
                if (bytes.size() > maxBytes) {
                    throw tooLarge(maxBytes);
                }
            }
            zip.finish();
            if (bytes.size() > maxBytes) {
                throw tooLarge(maxBytes);
            }
            ObjectLocation location = ObjectLocation.of(BucketType.TMP, "task-downloads/" + taskId + ".zip");
            return objectStorage.put(location, new ByteArrayInputStream(bytes.toByteArray()),
                    bytes.size(), "application/zip");
        } catch (IOException e) {
            throw new PixFlowException(TaskErrorCode.TASK_RESULT_WRITE_FAILED, "build download bundle failed", e);
        }
    }

    private static String entryName(int index, ProcessResult result) {
        String member = result.getGroupKey() != null ? result.getGroupKey() : result.getImageId();
        String safeMember = member == null ? "result" : member.replaceAll("[^A-Za-z0-9_.-]", "_");
        String branch = result.getBranchId() == null ? "default" : result.getBranchId().replaceAll("[^A-Za-z0-9_.-]", "_");
        return "%03d_%s_%s".formatted(index, safeMember, branch);
    }

    private static PixFlowException tooLarge(long maxBytes) {
        return new PixFlowException(TaskErrorCode.TASK_DOWNLOAD_BUNDLE_TOO_LARGE,
                "download bundle exceeds max bytes: " + maxBytes);
    }
}
