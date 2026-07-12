package com.pixflow.module.task.internal.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.state.model.UnitKeyCodec;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessResultMember;
import com.pixflow.module.task.domain.model.ResultStatus;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessResultMemberMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import org.springframework.transaction.annotation.Transactional;

public class WorkUnitResultRepository {
    private final ProcessResultMapper resultMapper;
    private final ProcessResultMemberMapper memberMapper;
    private final ProcessTaskMapper taskMapper;
    private final ObjectStorage objectStorage;
    private final ObjectMapper objectMapper;

    public WorkUnitResultRepository(ProcessResultMapper resultMapper,
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
        if (existing == null) throw new IllegalStateException("冻结 selection 缺少 PENDING 结果行: " + unitKey);
        if (existing.getStatus() == ResultStatus.SUCCESS) return CommitResult.ALREADY_SUCCEEDED;

        // MinIO I/O 在取得数据库行锁前完成，避免外部依赖延长 task epoch 临界事务。
        verifyArtifact(completion, unitKey);
        // 锁住当前 task epoch，claim 新 epoch 与本次结果提交不能交错。
        if (taskMapper.lockRunningEpoch(taskId, run.epoch()) == null) return CommitResult.FENCED;

        ProcessResult row = project(completion);
        int updated = resultMapper.commitForEpoch(taskId, unitKey, run.epoch(), row);
        if (updated != 1) return CommitResult.FENCED;
        if (completion instanceof WorkUnitCompletion.Succeeded succeeded) {
            for (var member : succeeded.members()) {
                ProcessResultMember child = new ProcessResultMember();
                child.setResultId(existing.getId());
                child.setTaskId(taskId);
                child.setImageId(member.imageId());
                child.setViewId(member.viewId());
                child.setSourcePath(member.sourceObjectKey());
                child.setCreatedAt(succeeded.finishedAt());
                memberMapper.insert(child);
            }
        }
        return CommitResult.APPLIED;
    }

    private void verifyArtifact(WorkUnitCompletion completion, String encodedUnitKey) {
        if (!(completion instanceof WorkUnitCompletion.Succeeded success)
                || success.outputObjectKey() == null) return;
        String expectedPrefix = "results/" + completion.unit().taskId() + "/units/"
                + UnitKeyCodec.sha256(completion.unit().unitKey()) + "/epochs/" + completion.runEpoch() + "/output.";
        if (!success.outputObjectKey().startsWith(expectedPrefix)) {
            throw new IllegalStateException("SUCCESS 对象不属于当前 task/unit/epoch: " + encodedUnitKey);
        }
        BucketType bucket = completion.unit().taskType() == com.pixflow.module.task.domain.model.TaskType.IMAGE_GEN
                ? BucketType.GENERATED : BucketType.RESULTS;
        ObjectLocation location = ObjectLocation.of(bucket, success.outputObjectKey());
        if (!objectStorage.exists(location)) {
            throw new IllegalStateException("SUCCESS 对象尚不存在: " + location.key());
        }
    }

    private ProcessResult project(WorkUnitCompletion completion) {
        ProcessResult row = new ProcessResult();
        row.setStartedAt(completion.startedAt());
        row.setFinishedAt(completion.finishedAt());
        if (completion instanceof WorkUnitCompletion.Succeeded success) {
            row.setStatus(ResultStatus.SUCCESS);
            row.setOutputMinioKey(success.outputObjectKey());
            row.setGeneratedCopy(success.generatedCopy());
            row.setBytesOut(success.bytesOut());
        } else if (completion instanceof WorkUnitCompletion.Failed failure) {
            row.setStatus(ResultStatus.FAILED);
            row.setFailureCode(failure.code());
            row.setFailureCategory(failure.category());
            row.setFailureRecovery(failure.recovery());
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

    public enum CommitResult { APPLIED, ALREADY_SUCCEEDED, FENCED }
}
