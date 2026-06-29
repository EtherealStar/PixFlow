package com.pixflow.module.task.internal.worker;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.task.domain.error.TaskErrorCode;
import com.pixflow.module.task.domain.model.ProcessTask;
import com.pixflow.module.task.domain.model.TaskType;
import com.pixflow.module.task.domain.model.WorkUnit;
import java.util.List;

public class WorkerRouter {
    private final ProcessWorker processWorker;
    private final ImageGenWorker imageGenWorker;

    public WorkerRouter(ProcessWorker processWorker, ImageGenWorker imageGenWorker) {
        this.processWorker = processWorker;
        this.imageGenWorker = imageGenWorker;
    }

    public List<WorkUnit> plan(ProcessTask task) {
        String taskId = task.getId().toString();
        if (task.getTaskType() == TaskType.IMAGE_PROCESS) {
            return processWorker.plan(taskId, task.getPackageId(), task.getDagJson());
        }
        if (task.getTaskType() == TaskType.IMAGE_GEN) {
            return imageGenWorker.plan(taskId, task.getPackageId(), task.getDagJson());
        }
        throw new PixFlowException(TaskErrorCode.TASK_DAG_PAYLOAD_INVALID,
                "unknown task type: " + task.getTaskType());
    }

    public void execute(WorkUnit unit, UnitExecutionContext context) {
        if (unit.taskType() == TaskType.IMAGE_PROCESS) {
            processWorker.execute(unit, context);
        } else {
            imageGenWorker.execute(unit, context);
        }
    }
}
