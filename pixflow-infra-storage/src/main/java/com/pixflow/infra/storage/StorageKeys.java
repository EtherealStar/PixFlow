package com.pixflow.infra.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务 key 模板中心，只负责把业务事实转成稳定对象位置。
 */
public final class StorageKeys {
    private StorageKeys() {
    }

    public static ObjectLocation packageSource(long packageId, String archiveExt) {
        requirePositiveId(packageId, "packageId");
        return ObjectLocation.of(
                BucketType.PACKAGES,
                packageId + "/source." + normalizeArchiveExtension(archiveExt));
    }

    public static ObjectLocation packageImage(long packageId, String relPath) {
        return ObjectLocation.of(BucketType.PACKAGES, packageId + "/images/" + normalizeRelativePath(relPath, true));
    }

    public static ObjectLocation packageDoc(long packageId, String fileName) {
        return ObjectLocation.of(BucketType.PACKAGES, packageId + "/doc/" + normalizeSegment(fileName));
    }

    public static ObjectLocation resultUnit(String taskId, String unitKeyHash, long runEpoch, String ext) {
        return ObjectLocation.of(BucketType.RESULTS, unitOutputKey(taskId, unitKeyHash, runEpoch, ext));
    }

    public static ObjectLocation generatedUnit(String taskId, String unitKeyHash, long runEpoch, String ext) {
        return ObjectLocation.of(BucketType.GENERATED, unitOutputKey(taskId, unitKeyHash, runEpoch, ext));
    }

    public static ObjectLocation resultAsset(long packageId, long imageId, String ext) {
        return assetLocation(BucketType.RESULTS, packageId, imageId, ext);
    }

    public static ObjectLocation generatedAsset(long packageId, long imageId, String ext) {
        return assetLocation(BucketType.GENERATED, packageId, imageId, ext);
    }

    public static ObjectLocation runtimeGroup(String taskId, long runEpoch, String unitKeyHash,
                                              String memberId, String name) {
        requirePositiveEpoch(runEpoch);
        return ObjectLocation.of(BucketType.TMP, normalizeSegment(taskId) + "/" + runEpoch + "/"
                + normalizeSegment(unitKeyHash) + "/" + normalizeSegment(memberId) + "/" + normalizeSegment(name));
    }

    public static ObjectLocation toolResult(String id) {
        return ObjectLocation.of(BucketType.TOOL_RESULTS, normalizeSegment(id) + ".txt");
    }

    private static String unitOutputKey(String taskId, String unitKeyHash, long runEpoch, String ext) {
        requirePositiveEpoch(runEpoch);
        return "results/" + normalizeSegment(taskId) + "/units/" + normalizeSegment(unitKeyHash)
                + "/epochs/" + runEpoch + "/output." + normalizeExtension(ext);
    }

    private static ObjectLocation assetLocation(BucketType bucket, long packageId, long imageId, String ext) {
        requirePositiveId(packageId, "packageId");
        requirePositiveId(imageId, "imageId");
        return ObjectLocation.of(
                bucket,
                packageId + "/images/" + imageId + "/output." + normalizeExtension(ext));
    }

    private static String normalizeArchiveExtension(String ext) {
        String normalized = normalizeExtension(ext);
        if (!normalized.equals("zip") && !normalized.equals("rar") && !normalized.equals("7z")) {
            throw new IllegalArgumentException("unsupported archive extension");
        }
        return normalized;
    }

    private static void requirePositiveId(long id, String name) {
        if (id <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositiveEpoch(long runEpoch) {
        if (runEpoch <= 0) {
            throw new IllegalArgumentException("runEpoch must be positive");
        }
    }

    private static String normalizeExtension(String ext) {
        String normalized = normalizeSegment(ext);
        if (normalized.startsWith(".")) {
            throw new IllegalArgumentException("extension must not start with dot");
        }
        return normalized;
    }

    private static String normalizeRelativePath(String value, boolean allowPath) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()
                || normalized.startsWith("../")
                || normalized.contains("/../")
                || normalized.endsWith("/..")
                || normalized.equals("..")) {
            throw new IllegalArgumentException("path must be relative");
        }
        if (!allowPath && normalized.contains("/")) {
            throw new IllegalArgumentException("segment must not contain path separator");
        }
        String[] segments = normalized.split("/");
        List<String> safe = new ArrayList<>(segments.length);
        for (String segment : segments) {
            String safeSegment = normalizeSegment(segment);
            safe.add(safeSegment);
        }
        return String.join("/", safe);
    }

    private static String normalizeSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("segment must not be blank");
        }
        String normalized = value.trim().replace('\\', '/');
        if (normalized.startsWith("/")
                || normalized.contains("/")
                || normalized.contains("..")
                || normalized.contains(":")) {
            throw new IllegalArgumentException("segment contains invalid characters");
        }
        return normalized;
    }
}
