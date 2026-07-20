package com.pixflow.module.file.internal.activity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.module.file.api.activity.FileActivitySnapshot;
import com.pixflow.module.file.api.activity.FileActivitySource;
import com.pixflow.module.file.api.activity.FileActivityPage;
import com.pixflow.module.file.api.activity.FileActivitySourceKind;
import com.pixflow.module.file.api.activity.FileActivityStatus;
import com.pixflow.module.file.api.activity.UploadActivitySnapshot;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.PackageStatus;
import com.pixflow.module.file.upload.UploadSessionStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class DefaultFileActivitySource implements FileActivitySource {
    private final UploadSessionStore uploads;

    private final AssetPackageMapper packages;

    public DefaultFileActivitySource(UploadSessionStore uploads, AssetPackageMapper packages) {
        this.uploads = uploads;
        this.packages = packages;
    }

    @Override
    public Optional<FileActivitySnapshot> find(FileActivitySourceKind kind, String sourceId) {
        if (kind == FileActivitySourceKind.UPLOAD) {
            return uploads.findActivity(sourceId).map(DefaultFileActivitySource::upload);
        }
        return Optional.ofNullable(packages.selectById(Long.parseLong(sourceId)))
                .filter(assetPackage -> assetPackage.getCleanupStatus() == null)
                .map(this::assetPackage);
    }

    @Override
    public FileActivityPage listCurrent(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("invalid activity page");
        }
        List<FileActivitySnapshot> snapshots = new ArrayList<>();
        uploads.listActivitySnapshots().stream()
                .filter(snapshot -> !"READY".equals(snapshot.status()))
                .map(DefaultFileActivitySource::upload)
                .forEach(snapshots::add);
        packages.selectList(new LambdaQueryWrapper<AssetPackage>()
                        .isNull(AssetPackage::getCleanupStatus)
                        .orderByDesc(AssetPackage::getUpdatedAt))
                .stream().map(this::assetPackage).forEach(snapshots::add);
        List<FileActivitySnapshot> ordered = snapshots.stream()
                .sorted(Comparator.comparing(FileActivitySnapshot::updatedAt).reversed())
                .toList();
        int from = Math.min((page - 1) * size, ordered.size());
        int to = Math.min(from + size, ordered.size());
        return new FileActivityPage(ordered.subList(from, to), ordered.size(), page, size);
    }

    private static FileActivitySnapshot upload(UploadActivitySnapshot session) {
        // 上传完成后会切换到包解压 Activity；这里仅投影上传阶段的分片进度。
        int completed = session.completedChunks();
        return new FileActivitySnapshot(
                FileActivitySourceKind.UPLOAD, session.uploadId(), session.updatedAt().toEpochMilli(),
                FileActivityStatus.UPLOADING, completed, session.expectedChunks(), session.packageId(),
                session.createdAt(), session.updatedAt(), true, false);
    }

    private FileActivitySnapshot assetPackage(AssetPackage assetPackage) {
        int total = value(assetPackage.getImageCount());
        int completed = Math.min(value(assetPackage.getExtractedCount()), total);
        PackageStatus status = assetPackage.getStatus();
        return new FileActivitySnapshot(
                FileActivitySourceKind.PACKAGE, Long.toString(assetPackage.getId()),
                assetPackage.getUpdatedAt().toEpochMilli(), status(status), completed, total,
                assetPackage.getId(), assetPackage.getCreatedAt(), assetPackage.getUpdatedAt(),
                status == PackageStatus.UPLOADED || status == PackageStatus.EXTRACTING,
                status == PackageStatus.READY || status == PackageStatus.PARTIAL
                        || status == PackageStatus.FAILED);
    }

    private static FileActivityStatus status(PackageStatus status) {
        return switch (status) {
            case UPLOADED, EXTRACTING -> FileActivityStatus.EXTRACTING;
            case READY -> FileActivityStatus.SUCCEEDED;
            case PARTIAL -> FileActivityStatus.PARTIALLY_SUCCEEDED;
            case FAILED -> FileActivityStatus.FAILED;
        };
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
