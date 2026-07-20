package com.pixflow.module.task.api.authorization;

import java.util.Optional;

/** Task owner 提供给组合根的只读授权事实查询。 */
public interface TaskAuthorizationFactsQuery {
    Optional<TaskAuthorizationFacts> find(String taskId);
}
