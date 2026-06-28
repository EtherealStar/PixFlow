package com.pixflow.infra.vector;

import java.util.List;
import java.util.Optional;

public interface VectorStore {
    void ensureCollection(String collection, int dim, Distance distance);

    void upsert(String collection, List<VectorPoint> points);

    List<ScoredPoint> search(String collection, float[] query, int topK, float threshold, VectorFilter filter);

    Optional<VectorPoint> get(String collection, String id);

    void delete(String collection, List<String> ids);

    void deleteByFilter(String collection, VectorFilter filter);

    boolean collectionExists(String collection);
}
