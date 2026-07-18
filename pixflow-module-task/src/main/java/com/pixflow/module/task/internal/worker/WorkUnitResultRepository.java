package com.pixflow.module.task.internal.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.state.model.UnitKeyCodec;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessResultMember;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessResultMemberMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import org.springframework.transaction.annotation.Transactional;

public class WorkUnitResultRepository {
  private final ProcessResultMapper resultMapper;

  private final ProcessResultMemberMapper memberMapper;

  private final ProcessTaskMapper taskMapper;

  private final ObjectStorage objectStorage;

  private final ObjectMapper objectMapper;

  public WorkUnitResultRepository(
      ProcessResultMapper resultMapper,
      ProcessResultMemberMapper memberMapper,
      ProcessTaskMapper taskMapper,
      ObjectStorage objectStorage,
      ObjectMapper objectMapper) {
    this.resultMapper = resultMapper;
    this.memberMapper = memberMapper;
    this.taskMapper = taskMapper;
    this.objectStorage = objectStorage;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public CommitResult commit(ExecutionRun run, WorkUnitCompletion completion) {
    if (!run.taskId().equals(completion.unit().taskId()) || run.epoch() != completion.runEpoch()) {
      throw new IllegalArgumentException("completion 不属于当前 execution run");
    }
    run.assertCommitAllowed();
    long taskId = Long.parseLong(run.taskId());
    String unitKey = UnitKeyCodec.encode(completion.unit().unitKey());
    ProcessResult existing = resultMapper.selectByUnit(taskId, unitKey);
    if (existing == null) {
      throw new IllegalStateException("冻结 selection 缺少 PENDING 结果行: " + unitKey);
    }
    if (existing.getStatus() == ResultStatus.SUCCESS) {
      return CommitResult.ALREADY_SUCCEEDED;
    }

    // MinIO I/O 在取得数据库行锁前完成，避免外部依赖延长 task epoch 临界事务。
    verifyArtifact(completion, unitKey);
    // 锁住当前 task epoch，claim 新 epoch 与本次结果提交不能交错。
    if (taskMapper.lockRunningEpoch(taskId, run.epoch()) == null) {
      return CommitResult.FENCED;
    }

    ProcessResult row = project(completion);
    int updated = resultMapper.commitForEpoch(taskId, unitKey, run.epoch(), row);
    if (updated != 1) {
      return CommitResult.FENCED;
    }
    if (completion instanceof WorkUnitCompletion.Succeeded succeeded
        && succeeded.candidate() != null) {
      for (var source : succeeded.candidate().sourceImages()) {
        ProcessResultMember child = new ProcessResultMember();
        child.setResultId(existing.getId());
        child.setTaskId(taskId);
        child.setImageId(source.imageId());
        succeeded.members().stream()
            .filter(member -> member.imageId().equals(source.imageId()))
            .findFirst()
            .ifPresent(
                member -> {
                  child.setViewId(member.viewId());
                  child.setSourcePath(member.sourceObjectKey());
                });
        child.setCreatedAt(succeeded.finishedAt());
        memberMapper.insert(child);
      }
    }
    return CommitResult.APPLIED;
  }

  private void verifyArtifact(WorkUnitCompletion completion, String encodedUnitKey) {
    if (!(completion instanceof WorkUnitCompletion.Succeeded success)
        || success.candidate() == null) {
      return;
    }
    CandidateArtifact candidate = success.candidate();
    String expectedCategory =
        candidate.kind() == com.pixflow.module.task.api.publication.CandidateKind.GENERATIVE
            ? "generated"
            : "results";
    String expectedPrefix =
        expectedCategory
            + "/"
            + completion.unit().taskId()
            + "/units/"
            + UnitKeyCodec.sha256(completion.unit().unitKey())
            + "/epochs/"
            + completion.runEpoch()
            + "/output.";
    if (!candidate.location().key().startsWith(expectedPrefix)) {
      throw new IllegalStateException("SUCCESS 对象不属于当前 task/unit/epoch: " + encodedUnitKey);
    }
    var metadata = objectStorage.stat(candidate.location());
    if (metadata.size() != candidate.size()
        || metadata.contentType() != null
            && !metadata.contentType().equalsIgnoreCase(candidate.contentType())) {
      throw new IllegalStateException("SUCCESS candidate metadata 不一致: " + candidate.location());
    }
  }

  private ProcessResult project(WorkUnitCompletion completion) {
    ProcessResult row = new ProcessResult();
    row.setStartedAt(completion.startedAt());
    row.setFinishedAt(completion.finishedAt());
    if (completion instanceof WorkUnitCompletion.Succeeded success) {
      row.setStatus(ResultStatus.SUCCESS);
      if (success.candidate() != null) {
        CandidateArtifact candidate = success.candidate();
        row.setCandidateBucket(candidate.location().bucket().name());
        row.setOutputMinioKey(candidate.location().key());
        row.setCandidateContentType(candidate.contentType());
        row.setCandidateExtension(candidate.extension());
        row.setProducerKind(candidate.kind().name());
        row.setProducerProvider(candidate.producer().provider());
        row.setProducerModel(candidate.producer().model());
        row.setProducerTool(candidate.producer().tool());
        row.setProducerNodeId(candidate.producer().nodeId());
        row.setPublicationStatus(com.pixflow.module.task.domain.model.PublicationStatus.PENDING);
        row.setBytesOut(candidate.size());
      } else {
        row.setPublicationStatus(
            com.pixflow.module.task.domain.model.PublicationStatus.NOT_APPLICABLE);
      }
      row.setGeneratedCopy(success.generatedCopy());
    } else if (completion instanceof WorkUnitCompletion.Failed failure) {
      row.setStatus(ResultStatus.FAILED);
      row.setFailureCode(failure.code());
      row.setFailureCategory(failure.category());
      row.setFailureRecovery(failure.recovery());
      row.setFailedNodeId(failure.failedNodeId());
      row.setFailedTool(failure.failedTool());
      row.setAttemptCount(failure.attemptCount());
      row.setErrorMsg(failure.safeMessage());
      try {
        row.setFailureDetailsJson(objectMapper.writeValueAsString(failure.details()));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("序列化结构化 failure 失败", e);
      }
    } else {
      row.setStatus(ResultStatus.SKIPPED);
    }
    return row;
  }

  public enum CommitResult {
    APPLIED,
    ALREADY_SUCCEEDED,
    FENCED
  }
}
