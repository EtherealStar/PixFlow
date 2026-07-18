package com.pixflow.module.task.api.publication;

/** 候选图片的生产方式，决定 File 发布到哪个稳定桶。 */
public enum CandidateKind {
  DETERMINISTIC,
  GENERATIVE
}
