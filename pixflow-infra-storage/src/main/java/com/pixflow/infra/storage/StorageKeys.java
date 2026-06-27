package com.pixflow.infra.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务 key 模板中心，只负责把业务事实转成稳定对象位置。
 */
public final class StorageKeys {
    private StorageKeys() {
    }

    public static ObjectLocation packageSource(long packageId) {
        return ObjectLocation.of(BucketType.PACKAGES, packageId + "/source.zip");
    }

    public static ObjectLocation packageImage(long packageId, String relPath) {
        return ObjectLocation.of(BucketType.PACKAGES, packageId + "/images/" + normalizeRelativePath(relPath, true));
    }

    public static ObjectLocation packageDoc(long packageId, String fileName) {
        return ObjectLocation.of(BucketType.PACKAGES, packageId + "/doc/" + normalizeSegment(fileName));
    }

    public static ObjectLocation result(long taskId, String skuId, long imageId, String branchId, String ext) {
        return ObjectLocation.of(BucketType.RESULTS, taskId + "/" + normalizeSegment(skuId) + "_" + imageId + "_" + normalizeSegment(branchId) + "." + normalizeExtension(ext));
    }

    public static ObjectLocation groupResult(long taskId, String skuId, String groupKey, String branchId, String ext) {
        return ObjectLocation.of(BucketType.RESULTS, taskId + "/" + normalizeSegment(skuId) + "_g" + normalizeSegment(groupKey) + "_" + normalizeSegment(branchId) + "." + normalizeExtension(ext));
    }

    public static ObjectLocation generated(long taskId, String skuId, long imageId, String ext) {
        return ObjectLocation.of(BucketType.GENERATED, taskId + "/" + normalizeSegment(skuId) + "_" + imageId + "." + normalizeExtension(ext));
    }

    public static ObjectLocation toolResult(String id) {
        return ObjectLocation.of(BucketType.TOOL_RESULTS, normalizeSegment(id) + ".txt");
    }

    public static ObjectLocation tmpBranch(long taskId, long imageId, String branchId, String name) {
        return ObjectLocation.of(BucketType.TMP, taskId + "/" + imageId + "/" + normalizeSegment(branchId) + "/" + normalizeSegment(name));
    }

    public static ObjectLocation tmpGroup(long taskId, String groupKey, String branchId, long imageId, String name) {
        return ObjectLocation.of(BucketType.TMP, taskId + "/" + normalizeSegment(groupKey) + "/" + normalizeSegment(branchId) + "/" + imageId + "/" + normalizeSegment(name));
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
        if (normalized.isBlank() || normalized.startsWith("../") || normalized.contains("/../") || normalized.endsWith("/..") || normalized.equals("..")) {
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
        if (normalized.startsWith("/") || normalized.contains("/") || normalized.contains("..") || normalized.contains(":")) {
            throw new IllegalArgumentException("segment contains invalid characters");
        }
        return normalized;
    }
}
