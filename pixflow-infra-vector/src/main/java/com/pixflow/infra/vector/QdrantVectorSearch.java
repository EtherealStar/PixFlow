package com.pixflow.infra.vector;

import com.google.common.util.concurrent.ListenableFuture;
import com.pixflow.infra.vector.observability.VectorMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

public final class QdrantVectorSearch implements VectorSearch, AutoCloseable {
    private final QdrantClient client;

    private final VectorProperties properties;

    private final VectorMetrics metrics;

    private final Retry retry;

    public QdrantVectorSearch(VectorProperties properties, VectorMetrics metrics) {
        this(createClient(properties), properties, metrics);
    }

    QdrantVectorSearch(QdrantClient client, VectorProperties properties, VectorMetrics metrics) {
        this.client = client;
        this.properties = properties;
        this.metrics = metrics;
        Predicate<Throwable> retryPredicate = throwable -> retryableCause(unwrap(throwable));
        this.retry = Retry.of("pixflow-vector", RetryConfig.custom()
                .maxAttempts(Math.max(1, properties.getQdrant().getRetry().getMaxAttempts()))
                .waitDuration(properties.getQdrant().getRetry().getWaitDuration())
                .retryOnException(retryPredicate)
                .build());
    }

    @Override
    public void verifyCollection(String collection, int dimension, Distance distance) {
        requireCollection(collection);
        if (dimension <= 0) {
            throw deterministic("VERIFY", collection, "向量维度必须大于 0", Map.of("dimension", dimension));
        }
        if (distance == null) {
            throw deterministic("VERIFY", collection, "距离类型不能为空", Map.of());
        }
        call("VERIFY", collection, () -> {
            boolean exists = await(client.collectionExistsAsync(collection, timeout()));
            if (!exists) {
                throw deterministic("VERIFY", collection, "向量集合不存在", Map.of("collection", collection));
            }
            Collections.CollectionInfo info = await(client.getCollectionInfoAsync(collection, timeout()));
            Collections.VectorParams params = info.getConfig().getParams().getVectorsConfig().getParams();
            Optional<Distance> actualDistance = fromQdrantDistance(params.getDistance());
            if (params.getSize() != dimension || actualDistance.isEmpty() || actualDistance.get() != distance) {
                throw deterministic("VERIFY", collection, "向量集合维度或距离配置不一致", Map.of(
                        "expectedDimension", dimension,
                        "actualDimension", params.getSize(),
                        "expectedDistance", distance,
                        "actualDistance", params.getDistance().name()));
            }
            return null;
        });
    }

    @Override
    public List<ScoredPoint> search(
            String collection,
            float[] query,
            int topK,
            float threshold,
            VectorFilter filter) {
        requireCollection(collection);
        if (query == null || query.length == 0) {
            throw deterministic("SEARCH", collection, "查询向量不能为空", Map.of());
        }
        for (float value : query) {
            if (!Float.isFinite(value)) {
                throw deterministic("SEARCH", collection, "查询向量必须只包含有限数值", Map.of());
            }
        }
        if (topK <= 0) {
            throw deterministic("SEARCH", collection, "topK 必须大于 0", Map.of("topK", topK));
        }
        if (!Float.isFinite(threshold)) {
            // DOT/EUCLID 的有效分数不一定落在 [0,1]，这里只拒绝无法比较的非有限值。
            throw deterministic("SEARCH", collection, "threshold 必须是有限数值", Map.of());
        }
        List<ScoredPoint> result = call("SEARCH", collection, () -> {
            Points.SearchPoints.Builder builder = Points.SearchPoints.newBuilder()
                    .setCollectionName(collection)
                    .addAllVector(floatList(query))
                    .setLimit(topK)
                    .setScoreThreshold(threshold)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());
            Common.Filter translated = VectorFilterTranslator.translate(filter);
            if (translated != null) {
                builder.setFilter(translated);
            }
            return await(client.searchAsync(builder.build(), timeout())).stream()
                    .map(point -> new ScoredPoint(
                            pointId(point.getId()),
                            point.getScore(),
                            fromPayload(point.getPayloadMap())))
                    .filter(point -> point.score() >= threshold)
                    .sorted((left, right) -> Float.compare(right.score(), left.score()))
                    .limit(topK)
                    .toList();
        });
        metrics.recordSearchReturned(result.size());
        return result;
    }

    @Override
    public Optional<VectorPointView> get(String collection, String id) {
        requireCollection(collection);
        Common.PointId qdrantId = parsePointId(collection, id);
        Points.WithPayloadSelector payload = Points.WithPayloadSelector.newBuilder().setEnable(true).build();
        Points.WithVectorsSelector vectors = Points.WithVectorsSelector.newBuilder().setEnable(true).build();
        return call("GET", collection, () -> await(client.retrieveAsync(
                collection,
                List.of(qdrantId),
                payload,
                vectors,
                null,
                timeout())).stream().findFirst().map(point -> new VectorPointView(
                pointId(point.getId()),
                vector(point.getVectors()),
                fromPayload(point.getPayloadMap()))));
    }

    @Override
    public void close() {
        client.close();
    }

    void healthCheck() {
        call("HEALTH", "qdrant", () -> {
            await(client.healthCheckAsync(timeout()));
            return null;
        }, "degraded");
    }

    private static QdrantClient createClient(VectorProperties properties) {
        VectorProperties.Qdrant qdrant = properties.getQdrant();
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                qdrant.getHost(), qdrant.getGrpcPort(), qdrant.isUseTls());
        if (qdrant.getApiKey() != null && !qdrant.getApiKey().isBlank()) {
            builder.withApiKey(qdrant.getApiKey());
        }
        return new QdrantClient(builder.build());
    }

    private <T> T call(String operation, String collection, Callable<T> action) {
        return call(operation, collection, action, "error");
    }

    private <T> T call(String operation, String collection, Callable<T> action, String failureResult) {
        Instant start = Instant.now();
        try {
            T result = Retry.decorateCallable(retry, action).call();
            metrics.recordOperation(operation.toLowerCase(), "ok", Duration.between(start, Instant.now()));
            return result;
        } catch (VectorException ex) {
            metrics.recordOperation(operation.toLowerCase(), failureResult, Duration.between(start, Instant.now()));
            throw ex;
        } catch (Exception ex) {
            metrics.recordOperation(operation.toLowerCase(), failureResult, Duration.between(start, Instant.now()));
            Throwable cause = unwrap(ex);
            if (cause instanceof VectorException vectorException) {
                throw vectorException;
            }
            throw new VectorException(
                    operation,
                    collection,
                    retryableCause(cause),
                    VectorException.FailureKind.DEPENDENCY,
                    "Qdrant 只读操作失败: " + operation,
                    sanitizedCause(cause),
                    Map.of("operation", operation, "collection", collection));
        }
    }

    private Duration timeout() {
        return properties.getQdrant().getTimeout();
    }

    private <T> T await(ListenableFuture<T> future) throws Exception {
        return future.get(timeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Map<String, Object> fromPayload(Map<String, JsonWithInt.Value> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        payload.forEach((key, value) -> result.put(key, fromValue(value)));
        return result;
    }

    private static Object fromValue(JsonWithInt.Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case LIST_VALUE -> value.getListValue().getValuesList().stream()
                    .map(QdrantVectorSearch::fromValue)
                    .toList();
            case STRUCT_VALUE -> fromPayload(value.getStructValue().getFieldsMap());
            case NULL_VALUE, KIND_NOT_SET -> null;
        };
    }

    private static float[] vector(Points.VectorsOutput vectors) {
        Points.VectorOutput output;
        if (vectors.hasVector()) {
            output = vectors.getVector();
        } else if (vectors.hasVectors() && !vectors.getVectors().getVectorsMap().isEmpty()) {
            output = vectors.getVectors().getVectorsMap().values().iterator().next();
        } else {
            return new float[0];
        }
        List<Float> values = output.hasDense() ? output.getDense().getDataList() : output.getDataList();
        float[] result = new float[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    private static List<Float> floatList(float[] vector) {
        Float[] values = new Float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            values[index] = vector[index];
        }
        return List.of(values);
    }

    private Common.PointId parsePointId(String collection, String id) {
        if (id == null || id.isBlank()) {
            throw deterministic("GET", collection, "point id 不能为空", Map.of());
        }
        try {
            return PointIdFactory.id(UUID.fromString(id));
        } catch (IllegalArgumentException ignored) {
            try {
                return PointIdFactory.id(Long.parseUnsignedLong(id));
            } catch (NumberFormatException ex) {
                throw deterministic("GET", collection, "point id 必须是 UUID 或无符号整数", Map.of());
            }
        }
    }

    private static String pointId(Common.PointId pointId) {
        return pointId.hasUuid() ? pointId.getUuid() : Long.toUnsignedString(pointId.getNum());
    }

    private static Optional<Distance> fromQdrantDistance(Collections.Distance distance) {
        if (distance == Collections.Distance.Cosine) {
            return Optional.of(Distance.COSINE);
        }
        if (distance == Collections.Distance.Dot) {
            return Optional.of(Distance.DOT);
        }
        if (distance == Collections.Distance.Euclid) {
            return Optional.of(Distance.EUCLID);
        }
        // Manhattan/未知枚举不冒充 EUCLID，确保集合合同以确定性错误关闭。
        return Optional.empty();
    }

    private static Throwable sanitizedCause(Throwable cause) {
        if (cause instanceof StatusRuntimeException statusException) {
            return new IllegalStateException("Qdrant gRPC status=" + statusException.getStatus().getCode());
        }
        if (cause instanceof TimeoutException) {
            return new IllegalStateException("Qdrant request timed out");
        }
        return new IllegalStateException("Qdrant dependency failure");
    }

    private void requireCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            throw deterministic("VALIDATE", collection, "集合名不能为空", Map.of());
        }
    }

    private VectorException deterministic(
            String operation,
            String collection,
            String message,
            Map<String, ?> details) {
        return new VectorException(operation, collection, false, message, null, details);
    }

    private static boolean retryableCause(Throwable throwable) {
        if (throwable instanceof TimeoutException) {
            return true;
        }
        if (throwable instanceof StatusRuntimeException statusException) {
            Status.Code code = statusException.getStatus().getCode();
            return code == Status.Code.UNAVAILABLE
                    || code == Status.Code.DEADLINE_EXCEEDED
                    || code == Status.Code.RESOURCE_EXHAUSTED
                    || code == Status.Code.ABORTED;
        }
        return false;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof ExecutionException || current instanceof CompletionException) {
            if (current.getCause() == null) {
                return current;
            }
            current = current.getCause();
        }
        return current;
    }
}
