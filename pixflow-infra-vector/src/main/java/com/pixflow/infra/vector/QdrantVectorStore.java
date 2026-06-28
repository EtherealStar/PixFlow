package com.pixflow.infra.vector;

import com.google.common.util.concurrent.ListenableFuture;
import com.pixflow.infra.vector.observability.VectorMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import java.math.BigDecimal;
import java.math.BigInteger;
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

public class QdrantVectorStore implements VectorStore {
    private final QdrantClient client;
    private final VectorProperties properties;
    private final VectorMetrics metrics;
    private final Retry retry;

    public QdrantVectorStore(QdrantClient client, VectorProperties properties, VectorMetrics metrics) {
        this.client = client;
        this.properties = properties;
        this.metrics = metrics;
        Predicate<Throwable> retryPredicate = throwable -> retryableCause(unwrap(throwable));
        this.retry = Retry.of("pixflow-vector", RetryConfig.custom()
                .maxAttempts(Math.max(1, properties.getRetry().getMaxAttempts()))
                .waitDuration(properties.getRetry().getWaitDuration())
                .retryOnException(retryPredicate)
                .build());
    }

    @Override
    public void ensureCollection(String collection, int dim, Distance distance) {
        requireCollection(collection);
        if (dim <= 0) {
            throw deterministic("ENSURE", collection, "向量维度必须大于 0", Map.of("dim", dim));
        }
        call("ENSURE", collection, () -> {
            if (Boolean.TRUE.equals(await(client.collectionExistsAsync(collection, properties.getTimeout())))) {
                validateCollection(collection, dim, distance);
                return null;
            }
            if (!properties.isAutoCreateCollection()) {
                throw deterministic("ENSURE", collection, "向量集合不存在且未开启自动创建", Map.of("collection", collection));
            }
            await(client.createCollectionAsync(collection, vectorParams(dim, distance), properties.getTimeout()));
            return null;
        });
    }

    @Override
    public void upsert(String collection, List<VectorPoint> points) {
        requireCollection(collection);
        if (points == null || points.isEmpty()) {
            return;
        }
        call("UPSERT", collection, () -> {
            List<Points.PointStruct> qdrantPoints = points.stream()
                    .map(this::toQdrantPoint)
                    .toList();
            await(client.upsertAsync(collection, qdrantPoints, properties.getTimeout()));
            return null;
        });
    }

    @Override
    public List<ScoredPoint> search(String collection, float[] query, int topK, float threshold, VectorFilter filter) {
        requireCollection(collection);
        if (query == null || query.length == 0) {
            throw deterministic("SEARCH", collection, "查询向量不能为空", Map.of());
        }
        if (topK <= 0) {
            throw deterministic("SEARCH", collection, "topK 必须大于 0", Map.of("topK", topK));
        }
        if (threshold < 0f || threshold > 1f) {
            throw deterministic("SEARCH", collection, "threshold 必须在 [0,1] 范围内", Map.of("threshold", threshold));
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
            return await(client.searchAsync(builder.build(), properties.getTimeout())).stream()
                    .map(point -> new ScoredPoint(uuid(point.getId()), point.getScore(), fromPayload(point.getPayloadMap())))
                    .sorted((left, right) -> Float.compare(right.score(), left.score()))
                    .toList();
        });
        metrics.recordSearchReturned(result.size());
        return result;
    }

    @Override
    public Optional<VectorPoint> get(String collection, String id) {
        requireCollection(collection);
        Common.PointId pointId = pointId("GET", collection, id);
        return call("GET", collection, () -> await(client.retrieveAsync(
                collection,
                List.of(pointId),
                true,
                true,
                null)).stream().findFirst().map(point -> new VectorPoint(
                uuid(point.getId()),
                vector(point.getVectors()),
                fromPayload(point.getPayloadMap()))));
    }

    @Override
    public void delete(String collection, List<String> ids) {
        requireCollection(collection);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        call("DELETE", collection, () -> {
            List<Common.PointId> pointIds = ids.stream()
                    .map(id -> pointId("DELETE", collection, id))
                    .toList();
            await(client.deleteAsync(collection, pointIds, properties.getTimeout()));
            return null;
        });
    }

    @Override
    public void deleteByFilter(String collection, VectorFilter filter) {
        requireCollection(collection);
        if (filter == null || filter.isEmpty()) {
            throw deterministic("DELETE", collection, "deleteByFilter 不能使用空过滤条件", Map.of());
        }
        call("DELETE", collection, () -> {
            await(client.deleteAsync(collection, VectorFilterTranslator.translate(filter), properties.getTimeout()));
            return null;
        });
    }

    @Override
    public boolean collectionExists(String collection) {
        requireCollection(collection);
        return call("EXISTS", collection, () -> await(client.collectionExistsAsync(collection, properties.getTimeout())));
    }

    public void healthCheck() {
        call("HEALTH", "", () -> {
            await(client.healthCheckAsync(properties.getTimeout()));
            return null;
        });
    }

    private void validateCollection(String collection, int dim, Distance distance) throws Exception {
        Collections.CollectionInfo info = await(client.getCollectionInfoAsync(collection, properties.getTimeout()));
        Collections.VectorParams params = info.getConfig().getParams().getVectorsConfig().getParams();
        Distance actualDistance = fromQdrantDistance(params.getDistance());
        if (params.getSize() != dim || actualDistance != distance) {
            throw deterministic("ENSURE", collection, "向量集合维度或距离配置不一致", Map.of(
                    "expectedDim", dim,
                    "actualDim", params.getSize(),
                    "expectedDistance", distance,
                    "actualDistance", actualDistance));
        }
    }

    private <T> T call(String operation, String collection, Callable<T> action) {
        Instant start = Instant.now();
        try {
            Callable<T> decorated = Retry.decorateCallable(retry, action);
            T result = decorated.call();
            metrics.recordOperation(operation.toLowerCase(), "ok", Duration.between(start, Instant.now()));
            return result;
        } catch (VectorException ex) {
            metrics.recordOperation(operation.toLowerCase(), "error", Duration.between(start, Instant.now()));
            throw ex;
        } catch (Exception ex) {
            metrics.recordOperation(operation.toLowerCase(), "error", Duration.between(start, Instant.now()));
            Throwable cause = unwrap(ex);
            if (cause instanceof VectorException vectorException) {
                throw vectorException;
            }
            throw new VectorException(
                    operation,
                    collection,
                    retryableCause(cause),
                    "Qdrant 向量操作失败: " + operation,
                    cause,
                    Map.of("operation", operation, "collection", collection));
        }
    }

    private Points.PointStruct toQdrantPoint(VectorPoint point) {
        // Qdrant 原生 UUID id 便于 MySQL 镜像表与向量点一一对应，也避免任意字符串 id 的迁移风险。
        return Points.PointStruct.newBuilder()
                .setId(pointId("UPSERT", "", point.id()))
                .setVectors(Points.Vectors.newBuilder().setVector(VectorFactory.vector(point.vector())).build())
                .putAllPayload(toPayload(point.payload()))
                .build();
    }

    private Map<String, JsonWithInt.Value> toPayload(Map<String, Object> payload) {
        Map<String, JsonWithInt.Value> result = new LinkedHashMap<>();
        payload.forEach((key, value) -> result.put(key, toValue(value)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private JsonWithInt.Value toValue(Object value) {
        if (value == null) {
            return ValueFactory.nullValue();
        }
        if (value instanceof String stringValue) {
            return ValueFactory.value(stringValue);
        }
        if (value instanceof Boolean booleanValue) {
            return ValueFactory.value(booleanValue);
        }
        if (value instanceof Float || value instanceof Double) {
            return ValueFactory.value(((Number) value).doubleValue());
        }
        if (value instanceof BigDecimal || value instanceof BigInteger) {
            return ValueFactory.value(value.toString());
        }
        if (value instanceof Number numberValue) {
            return ValueFactory.value(numberValue.longValue());
        }
        if (value instanceof List<?> listValue) {
            return ValueFactory.list(listValue.stream().map(this::toValue).toList());
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, JsonWithInt.Value> nested = new LinkedHashMap<>();
            mapValue.forEach((key, nestedValue) -> nested.put(String.valueOf(key), toValue(nestedValue)));
            return ValueFactory.value(nested);
        }
        return ValueFactory.value(String.valueOf(value));
    }

    private Map<String, Object> fromPayload(Map<String, JsonWithInt.Value> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        payload.forEach((key, value) -> result.put(key, fromValue(value)));
        return result;
    }

    private Object fromValue(JsonWithInt.Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case LIST_VALUE -> value.getListValue().getValuesList().stream().map(this::fromValue).toList();
            case STRUCT_VALUE -> fromPayload(value.getStructValue().getFieldsMap());
            case NULL_VALUE, KIND_NOT_SET -> null;
        };
    }

    private float[] vector(Points.VectorsOutput vectors) {
        if (!vectors.hasVector()) {
            return new float[0];
        }
        List<Float> values = vectors.getVector().getDataList();
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private Collections.VectorParams vectorParams(int dim, Distance distance) {
        return Collections.VectorParams.newBuilder()
                .setSize(dim)
                .setDistance(toQdrantDistance(distance))
                .build();
    }

    private Collections.Distance toQdrantDistance(Distance distance) {
        return switch (distance) {
            case COSINE -> Collections.Distance.Cosine;
            case DOT -> Collections.Distance.Dot;
            case EUCLID -> Collections.Distance.Euclid;
        };
    }

    private Distance fromQdrantDistance(Collections.Distance distance) {
        if (distance == Collections.Distance.Cosine) {
            return Distance.COSINE;
        }
        if (distance == Collections.Distance.Dot) {
            return Distance.DOT;
        }
        return Distance.EUCLID;
    }

    private List<Float> floatList(float[] vector) {
        Float[] values = new Float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            values[i] = vector[i];
        }
        return List.of(values);
    }

    private Common.PointId pointId(String operation, String collection, String id) {
        try {
            return PointIdFactory.id(UUID.fromString(id));
        } catch (RuntimeException ex) {
            throw deterministic(operation, collection, "point id 必须是 UUID 字符串", Map.of("id", id));
        }
    }

    private String uuid(Common.PointId pointId) {
        return pointId.getUuid();
    }

    private <T> T await(ListenableFuture<T> future) throws Exception {
        return future.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void requireCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            throw deterministic("VALIDATE", collection, "集合名不能为空", Map.of());
        }
    }

    private VectorException deterministic(String operation, String collection, String message, Map<String, ?> details) {
        return new VectorException(operation, collection, false, message, null, details);
    }

    private boolean retryableCause(Throwable throwable) {
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

    private Throwable unwrap(Throwable throwable) {
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
