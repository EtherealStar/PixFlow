package com.pixflow.module.task.internal.download;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessResult;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        List<BundleSource> sources = results.stream()
                .filter(result -> result.getOutputMinioKey() != null)
                .map(result -> {
                    BucketType bucket = result.getUnitKind() == com.pixflow.harness.state.model.UnitKind.GENERATIVE
                            ? BucketType.GENERATED : BucketType.RESULTS;
                    return new BundleSource(entryName(result), ObjectLocation.of(bucket, result.getOutputMinioKey()));
                })
                .toList();
        return build("task-downloads/" + taskId + ".zip", sources);
    }

    public ObjectRef build(String archiveKey, List<BundleSource> sources) {
        long maxBytes = properties.getDownload().getMaxBundleBytes();
        Path temporary = null;
        try {
            temporary = Files.createTempFile("pixflow-task-bundle-", ".zip");
            try (OutputStream file = Files.newOutputStream(temporary);
                 LimitedOutputStream limited = new LimitedOutputStream(file, maxBytes);
                 ZipOutputStream zip = new ZipOutputStream(limited)) {
            int index = 0;
            for (BundleSource source : sources) {
                if (source == null || source.location() == null) {
                    continue;
                }
                try (InputStream in = objectStorage.getStream(source.location())) {
                    zip.putNextEntry(new ZipEntry(entryName(++index, source.entryName())));
                    in.transferTo(zip);
                    zip.closeEntry();
                }
            }
            zip.finish();
            }
            long archiveSize = Files.size(temporary);
            ObjectLocation location = ObjectLocation.of(BucketType.TMP, archiveKey);
            // ObjectStorage.put 需要已知长度，因此使用受限临时文件承接 ZIP，避免整包驻留 JVM 堆。
            try (InputStream archive = Files.newInputStream(temporary)) {
                return objectStorage.put(location, archive, archiveSize, "application/zip");
            }
        } catch (BundleTooLargeException e) {
            throw tooLarge(maxBytes);
        } catch (IOException e) {
            throw new PixFlowException(TaskErrorCode.TASK_RESULT_WRITE_FAILED, "build download bundle failed", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时目录由操作系统兜底清理；主下载结果不因清理失败而回滚。
                }
            }
        }
    }

    private static String entryName(ProcessResult result) {
        String member = result.getGroupKey() != null ? result.getGroupKey() : result.getImageId();
        String safeMember = member == null ? "result" : member.replaceAll("[^A-Za-z0-9_.-]", "_");
        String branch = result.getBranchId() == null ? "default" : result.getBranchId().replaceAll("[^A-Za-z0-9_.-]", "_");
        return "%s_%s".formatted(safeMember, branch);
    }

    private static String entryName(int index, String entryName) {
        String fallback = entryName == null || entryName.isBlank() ? "image" : entryName;
        String safe = fallback.replace('\\', '/');
        int slash = safe.lastIndexOf('/');
        if (slash >= 0) {
            safe = safe.substring(slash + 1);
        }
        safe = safe.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.isBlank()) {
            safe = "image";
        }
        return "%03d_%s".formatted(index, safe);
    }

    private static PixFlowException tooLarge(long maxBytes) {
        return new PixFlowException(TaskErrorCode.TASK_DOWNLOAD_BUNDLE_TOO_LARGE,
                "download bundle exceeds max bytes: " + maxBytes);
    }

    public record BundleSource(String entryName, ObjectLocation location) {
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long maxBytes;
        private long written;

        private LimitedOutputStream(OutputStream delegate, long maxBytes) {
            super(delegate);
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            out.write(value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            out.write(bytes, offset, length);
            written += length;
        }

        private void ensureCapacity(int additional) throws BundleTooLargeException {
            if (additional < 0 || written > maxBytes - additional) {
                throw new BundleTooLargeException();
            }
        }
    }

    private static final class BundleTooLargeException extends IOException {
    }
}
