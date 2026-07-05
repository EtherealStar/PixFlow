package com.pixflow.app.download;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.task.api.query.DownloadHandle;
import com.pixflow.module.task.config.TaskProperties;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.UnitKind;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.internal.download.DownloadBundleBuilder;
import java.net.URL;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class CustomDownloadService {
    private final AssetImageMapper assetImageMapper;
    private final ProcessResultMapper resultMapper;
    private final DownloadBundleBuilder bundleBuilder;
    private final ObjectStorage objectStorage;
    private final TaskProperties taskProperties;
    private final Clock clock;

    public CustomDownloadService(AssetImageMapper assetImageMapper,
                                 ProcessResultMapper resultMapper,
                                 DownloadBundleBuilder bundleBuilder,
                                 ObjectStorage objectStorage,
                                 TaskProperties taskProperties,
                                 Clock clock) {
        this.assetImageMapper = assetImageMapper;
        this.resultMapper = resultMapper;
        this.bundleBuilder = bundleBuilder;
        this.objectStorage = objectStorage;
        this.taskProperties = taskProperties;
        this.clock = clock;
    }

    public DownloadHandle build(CustomBundleRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new PixFlowException(TaskErrorCode.TASK_DOWNLOAD_NOT_READY, "download bundle requires at least one item");
        }
        List<DownloadBundleBuilder.BundleSource> sources = new ArrayList<>();
        for (CustomBundleRequest.BundleItem item : request.items()) {
            sources.add(resolve(item));
        }
        String archiveKey = "custom-downloads/" + UUID.randomUUID() + "/" + archiveName(request.archiveName());
        var ref = bundleBuilder.build(archiveKey, sources);
        URL url = objectStorage.presignGet(ObjectLocation.of(BucketType.TMP, ref.key()),
                taskProperties.getDownload().getSingleUrlExpiry());
        return new DownloadHandle(url, clock.instant().plus(taskProperties.getDownload().getSingleUrlExpiry()),
                "application/zip", ref.size());
    }

    private DownloadBundleBuilder.BundleSource resolve(CustomBundleRequest.BundleItem item) {
        String type = item.type() == null ? "" : item.type().toUpperCase(Locale.ROOT);
        if ("ASSET_IMAGE".equals(type)) {
            AssetImage image = assetImageMapper.selectById(parseId(item.imageId()));
            if (image == null || image.getDeletedAt() != null || image.getMinioKey() == null) {
                throw new PixFlowException(TaskErrorCode.TASK_DOWNLOAD_NOT_READY, "asset image is not ready for bundle");
            }
            return new DownloadBundleBuilder.BundleSource(
                    firstNonBlank(item.filename(), image.getDisplayName(), basename(image.getOriginalPath()), image.getId() + ".image"),
                    ObjectLocation.of(BucketType.PACKAGES, image.getMinioKey()));
        }
        if ("TASK_RESULT".equals(type)) {
            ProcessResult result = resultMapper.selectById(parseId(item.resultId()));
            if (result == null || result.getDeletedAt() != null || result.getOutputMinioKey() == null) {
                throw new PixFlowException(TaskErrorCode.TASK_DOWNLOAD_NOT_READY, "task result is not ready for bundle");
            }
            BucketType bucket = result.getKind() == UnitKind.GENERATIVE ? BucketType.GENERATED : BucketType.RESULTS;
            return new DownloadBundleBuilder.BundleSource(
                    firstNonBlank(item.filename(), result.getDisplayName(), basename(result.getOutputMinioKey()), result.getId() + ".image"),
                    ObjectLocation.of(bucket, result.getOutputMinioKey()));
        }
        throw new PixFlowException(TaskErrorCode.TASK_DOWNLOAD_NOT_READY, "unknown download bundle item type: " + item.type());
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException ex) {
            throw new PixFlowException(TaskErrorCode.TASK_DOWNLOAD_NOT_READY, "invalid download bundle item id", ex);
        }
    }

    private static String archiveName(String name) {
        String normalized = firstNonBlank(name, "selected-images.zip").replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.toLowerCase(Locale.ROOT).endsWith(".zip") ? normalized : normalized + ".zip";
    }

    private static String basename(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "image";
    }
}
