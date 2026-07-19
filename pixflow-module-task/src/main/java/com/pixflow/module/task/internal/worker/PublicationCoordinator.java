package com.pixflow.module.task.internal.worker;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.task.api.publication.CandidateKind;
import com.pixflow.module.task.api.publication.GeneratedAssetCandidate;
import com.pixflow.module.task.api.publication.GeneratedAssetPublicationPort;
import com.pixflow.module.task.api.publication.ProducerIdentity;
import com.pixflow.module.task.api.publication.SourceImageIdentity;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessResultMemberMapper;
import java.time.Clock;
import java.util.List;

/**
 * owner thread 的 publication backlog 编排器。
 *
 * <p>执行 checkpoint 与 File 发布分属两个事务，本类只依靠 resultId 幂等重放， 不把对象 copy 伪装成数据库事务的一部分。
 */
final class PublicationCoordinator {
  private final ProcessResultMapper resultMapper;

  private final ProcessResultMemberMapper memberMapper;

  private final GeneratedAssetPublicationPort publicationPort;

  private final Clock clock;

  PublicationCoordinator(
      ProcessResultMapper resultMapper,
      ProcessResultMemberMapper memberMapper,
      GeneratedAssetPublicationPort publicationPort,
      Clock clock) {
    this.resultMapper = resultMapper;
    this.memberMapper = memberMapper;
    this.publicationPort = publicationPort;
    this.clock = clock;
  }

  boolean publishBacklog(ProcessTask task, ExecutionRun run) {
    run.assertCommitAllowed();
    List<ProcessResult> backlog = resultMapper.findPublicationBacklog(task.getId());
    for (ProcessResult result : backlog) {
      if (!publishOne(task, result, run)) {
        return false;
      }
    }
    return true;
  }

  private boolean publishOne(ProcessTask task, ProcessResult result, ExecutionRun run) {
    try {
      var published = publicationPort.publish(toCandidate(task, result));
      run.assertCommitAllowed();
      return resultMapper.bindPublished(
              task.getId(),
              result.getId(),
              result.getRunEpoch(),
              run.epoch(),
              published.imageId(),
              published.referenceKey(),
              clock.instant())
          == 1;
    } catch (RuntimeException failure) {
      String safeError = safeMessage(failure);
      resultMapper.recordPublicationFailure(task.getId(), result.getId(), run.epoch(), safeError);
      return false;
    }
  }

  private GeneratedAssetCandidate toCandidate(ProcessTask task, ProcessResult result) {
    CandidateKind kind = CandidateKind.valueOf(result.getProducerKind());
    ProducerIdentity producer =
        kind == CandidateKind.GENERATIVE
            ? ProducerIdentity.generative(result.getProducerProvider(), result.getProducerModel())
            : ProducerIdentity.deterministic(result.getProducerTool(), result.getProducerNodeId());
    List<SourceImageIdentity> sources =
        memberMapper.findByResultId(result.getId()).stream()
            .map(member -> new SourceImageIdentity(member.getImageId()))
            .distinct()
            .toList();
    return new GeneratedAssetCandidate(
        task.getId(),
        result.getId(),
        result.getUnitKey(),
        result.getRunEpoch(),
        task.getPackageId(),
        ObjectLocation.of(
            BucketType.valueOf(result.getCandidateBucket()), result.getOutputMinioKey()),
        result.getBytesOut(),
        result.getCandidateContentType(),
        result.getCandidateExtension(),
        kind,
        sources,
        producer);
  }

  private static String safeMessage(RuntimeException failure) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) {
      message = failure.getClass().getSimpleName();
    }
    return message.length() <= 1000 ? message : message.substring(0, 1000);
  }
}
