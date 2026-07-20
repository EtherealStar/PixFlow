package com.pixflow.module.task.internal.cleanup;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.task.api.command.ClearTaskCommand;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.infra.persistence.ProcessResultMapper;
import com.pixflow.module.task.infra.persistence.ProcessResultMemberMapper;
import com.pixflow.module.task.infra.persistence.ProcessTaskMapper;
import java.util.Locale;
import org.springframework.transaction.annotation.Transactional;

public final class TaskCleanupService {
    private final ProcessTaskMapper tasks;

    private final ProcessResultMapper results;

    private final ProcessResultMemberMapper members;

    private final ObjectStorage storage;

    public TaskCleanupService(
            ProcessTaskMapper tasks,
            ProcessResultMapper results,
            ProcessResultMemberMapper members,
            ObjectStorage storage) {
        this.tasks = tasks;
        this.results = results;
        this.members = members;
        this.storage = storage;
    }

    @Transactional
    public boolean clear(ClearTaskCommand command) {
        ProcessTask task = tasks.findByIdAndConversation(command.taskId(), command.conversationId());
        if (task == null || !task.getStatus().terminal()) {
            return false;
        }
        for (ProcessResult result : results.findByTaskId(command.taskId())) {
            deleteCandidate(result);
        }
        members.deleteByTaskId(command.taskId());
        results.deleteByTaskId(command.taskId());
        return tasks.deleteById(command.taskId()) == 1;
    }

    private void deleteCandidate(ProcessResult result) {
        if (result.getCandidateBucket() == null || result.getOutputMinioKey() == null) {
            return;
        }
        BucketType bucket = BucketType.valueOf(result.getCandidateBucket().toUpperCase(Locale.ROOT));
        // Published Image 已复制到 File-owned stable key；这里只清理由 Task 持有的候选对象。
        storage.delete(ObjectLocation.of(bucket, result.getOutputMinioKey()));
    }
}
