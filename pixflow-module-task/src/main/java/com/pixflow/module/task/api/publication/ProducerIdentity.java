package com.pixflow.module.task.api.publication;

/** 候选图片的真实生产者信息；生成式与确定性字段互斥。 */
public record ProducerIdentity(
    CandidateKind kind, String provider, String model, String tool, String nodeId) {
  public ProducerIdentity {
    if (kind == null) {
      throw new IllegalArgumentException("kind must not be null");
    }
    if (kind == CandidateKind.GENERATIVE) {
      provider = requireText(provider, "provider");
      model = requireText(model, "model");
      requireAbsent(tool, "tool");
      requireAbsent(nodeId, "nodeId");
    } else {
      tool = requireText(tool, "tool");
      nodeId = requireText(nodeId, "nodeId");
      requireAbsent(provider, "provider");
      requireAbsent(model, "model");
    }
  }

  public static ProducerIdentity generative(String provider, String model) {
    return new ProducerIdentity(CandidateKind.GENERATIVE, provider, model, null, null);
  }

  public static ProducerIdentity deterministic(String tool, String nodeId) {
    return new ProducerIdentity(CandidateKind.DETERMINISTIC, null, null, tool, nodeId);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  private static void requireAbsent(String value, String name) {
    if (value != null) {
      throw new IllegalArgumentException(name + " must be null");
    }
  }
}
