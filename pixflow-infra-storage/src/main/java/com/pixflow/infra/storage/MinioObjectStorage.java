package com.pixflow.infra.storage;

import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.DeleteObject;
import io.minio.messages.DeleteError;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 官方 SDK 的对象存储实现。
 */
public class MinioObjectStorage implements ObjectStorage {
    private static final String PUT = "PUT";

    private static final String GET = "GET";

    private static final String STAT = "STAT";

    private static final String DELETE = "DELETE";

    private static final String DELETE_PREFIX = "DELETE_PREFIX";

    private static final String PRESIGN_GET = "PRESIGN_GET";

    private static final String PRESIGN_PUT = "PRESIGN_PUT";

    private final MinioClient minioClient;

    private final StorageBucketResolver bucketResolver;

    private final StorageProperties properties;

    public MinioObjectStorage(
            MinioClient minioClient,
            StorageBucketResolver bucketResolver,
            StorageProperties properties) {
        this.minioClient = minioClient;
        this.bucketResolver = bucketResolver;
        this.properties = properties;
    }

    @Override
    public ObjectRef put(ObjectLocation loc, InputStream data, long size, String contentType) {
        String bucket = resolveBucket(loc.bucket());
        long partSize = size < 0 ? properties.getUploadPartSize().toBytes() : -1;
        String resolvedType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        try {
            ObjectWriteResponse response = minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(loc.key())
                            .stream(data, size, partSize)
                            .contentType(resolvedType)
                            .build());
            return new ObjectRef(loc.bucket(), loc.key(), size, response.etag());
        } catch (Exception ex) {
            throw wrap(PUT, loc, ex, true);
        }
    }

    @Override
    public InputStream getStream(ObjectLocation loc) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(resolveBucket(loc.bucket()))
                    .object(loc.key())
                    .build());
        } catch (Exception ex) {
            throw wrap(GET, loc, ex, isRetryable(ex));
        }
    }

    @Override
    public byte[] getBytes(ObjectLocation loc) {
        long maxBytes = properties.getMaxBytesReadSize().toBytes();
        try (InputStream inputStream = getStream(loc);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long remaining = maxBytes;
            int read;
            while (remaining > 0
                    && (read = inputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                outputStream.write(buffer, 0, read);
                remaining -= read;
            }
            // getBytes 只服务小对象预览，超过上限时明确要求调用方改用流式读取。
            if (inputStream.read() != -1) {
                throw new StorageException(
                        GET, loc.bucket(), loc.key(), false, "object is larger than maxBytesReadSize", null);
            }
            return outputStream.toByteArray();
        } catch (StorageException ex) {
            throw ex;
        } catch (IOException ex) {
            throw wrap(GET, loc, ex, true);
        }
    }

    @Override
    public boolean exists(ObjectLocation loc) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(resolveBucket(loc.bucket()))
                    .object(loc.key())
                    .build());
            return true;
        } catch (ErrorResponseException ex) {
            if ("NoSuchKey".equalsIgnoreCase(ex.errorResponse().code())
                    || "NoSuchObject".equalsIgnoreCase(ex.errorResponse().code())) {
                return false;
            }
            throw wrap(STAT, loc, ex, isRetryable(ex));
        } catch (Exception ex) {
            throw wrap(STAT, loc, ex, isRetryable(ex));
        }
    }

    @Override
    public StoredObjectMetadata stat(ObjectLocation loc) {
        try {
            var response = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(resolveBucket(loc.bucket()))
                    .object(loc.key())
                    .build());
            Instant lastModified = response.lastModified() == null ? null : response.lastModified().toInstant();
            return new StoredObjectMetadata(response.size(), response.contentType(), response.etag(), lastModified);
        } catch (Exception ex) {
            throw wrap(STAT, loc, ex, isRetryable(ex));
        }
    }

    @Override
    public void delete(ObjectLocation loc) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(resolveBucket(loc.bucket()))
                            .object(loc.key())
                            .build());
        } catch (Exception ex) {
            throw wrap(DELETE, loc, ex, isRetryable(ex));
        }
    }

    @Override
    public void deleteByPrefix(BucketType bucket, String prefix) {
        String safePrefix = normalizePrefix(prefix);
        String resolvedBucket = resolveBucket(bucket);
        try {
            List<DeleteObject> objects = new ArrayList<>();
            for (var item : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(resolvedBucket)
                    .prefix(safePrefix)
                    .recursive(true)
                    .build())) {
                objects.add(new DeleteObject(item.get().objectName()));
                if (objects.size() >= 1000) {
                    removeBatch(resolvedBucket, objects, bucket, safePrefix);
                    objects.clear();
                }
            }
            if (!objects.isEmpty()) {
                removeBatch(resolvedBucket, objects, bucket, safePrefix);
            }
        } catch (Exception ex) {
            throw new StorageException(
                    DELETE_PREFIX, bucket, safePrefix, isRetryable(ex), "failed to delete by prefix", ex);
        }
    }

    @Override
    public URL presignGet(ObjectLocation loc, Duration ttl) {
        return presign(loc, ttl, PRESIGN_GET, io.minio.http.Method.GET);
    }

    @Override
    public URL presignPut(ObjectLocation loc, Duration ttl) {
        return presign(loc, ttl, PRESIGN_PUT, io.minio.http.Method.PUT);
    }

    private URL presign(ObjectLocation loc, Duration ttl, String operation, io.minio.http.Method method) {
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(resolveBucket(loc.bucket()))
                    .object(loc.key())
                    .method(method)
                    .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS)
                    .build());
            return new URL(url);
        } catch (MalformedURLException ex) {
            throw wrap(operation, loc, ex, false);
        } catch (Exception ex) {
            throw wrap(operation, loc, ex, isRetryable(ex));
        }
    }

    private void removeBatch(
            String resolvedBucket, List<DeleteObject> objects, BucketType bucket, String prefix) {
        List<String> failedKeys = new ArrayList<>();
        try {
            for (var result : minioClient.removeObjects(RemoveObjectsArgs.builder()
                    .bucket(resolvedBucket)
                    .objects(List.copyOf(objects))
                    .build())) {
                DeleteError error = result.get();
                failedKeys.add(error.objectName());
            }
        } catch (Exception ex) {
            throw new StorageException(
                    DELETE_PREFIX, bucket, prefix, isRetryable(ex), "failed to delete by prefix", ex);
        }
        if (!failedKeys.isEmpty()) {
            throw new StorageException(
                    DELETE_PREFIX,
                    bucket,
                    prefix,
                    false,
                    "failed to delete objects by prefix",
                    null,
                    java.util.Map.of("failedKeys", failedKeys));
        }
    }

    private String resolveBucket(BucketType bucket) {
        String bucketName = bucketResolver.resolve(bucket);
        if (bucketName == null || bucketName.isBlank()) {
            throw new StorageException("RESOLVE_BUCKET", bucket, null, false, "bucket name is blank", null);
        }
        return bucketName;
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        String normalized = prefix.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains("..") || normalized.contains(":")) {
            throw new IllegalArgumentException("prefix must be a safe relative prefix");
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    private StorageException wrap(String operation, ObjectLocation loc, Exception ex, boolean retryable) {
        return new StorageException(operation, loc.bucket(), loc.key(), retryable, message(operation, loc, ex), ex);
    }

    private String message(String operation, ObjectLocation loc, Exception ex) {
        return operation + " failed for " + loc.bucket() + "/" + loc.key() + ": " + ex.getMessage();
    }

    private boolean isRetryable(Exception ex) {
        return !(ex instanceof ErrorResponseException errorResponseException
                && errorResponseException.errorResponse() != null
                && ("NoSuchKey".equalsIgnoreCase(errorResponseException.errorResponse().code())
                || "NoSuchObject".equalsIgnoreCase(errorResponseException.errorResponse().code())
                || "NoSuchBucket".equalsIgnoreCase(errorResponseException.errorResponse().code())
                || "AccessDenied".equalsIgnoreCase(errorResponseException.errorResponse().code())
                || "InvalidAccessKeyId".equalsIgnoreCase(errorResponseException.errorResponse().code())));
    }
}
