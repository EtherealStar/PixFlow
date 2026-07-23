package com.pixflow.module.task.internal.cleanup;

import com.pixflow.module.task.api.command.ClearTaskCommand;

public interface TaskCleanupService {
  boolean clear(ClearTaskCommand command);
}
