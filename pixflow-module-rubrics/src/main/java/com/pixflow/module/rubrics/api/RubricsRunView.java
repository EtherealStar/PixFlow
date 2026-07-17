package com.pixflow.module.rubrics.api;

import com.pixflow.module.rubrics.model.SubjectType;
import com.pixflow.module.rubrics.run.RunStatus;

public record RubricsRunView(long id, String templateId, String templateVersion, String templateHash,
                             SubjectType subjectType, String evaluatorVersion, RunStatus status,
                             int totalCount, int succeededCount, int partialCount, int failedCount) {}
