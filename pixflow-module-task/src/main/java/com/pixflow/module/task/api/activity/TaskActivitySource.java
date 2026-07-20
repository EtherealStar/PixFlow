package com.pixflow.module.task.api.activity;

import com.pixflow.module.task.api.query.PageResult;
import java.util.Optional;

public interface TaskActivitySource {
    Optional<TaskActivitySnapshot> find(String taskId);

    PageResult<TaskActivitySnapshot> listCurrent(int page, int size);
}
