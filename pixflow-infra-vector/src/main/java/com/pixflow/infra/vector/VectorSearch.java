package com.pixflow.infra.vector;

import java.util.List;
import java.util.Optional;

/** 在线运行时唯一可注入的只读向量能力。 */
public interface VectorSearch {
    void verifyCollection(String collection, int dimension, Distance distance);

    List<ScoredPoint> search(
            String collection,
            float[] query,
            int topK,
            float threshold,
            VectorFilter filter);

    Optional<VectorPointView> get(String collection, String id);
}
