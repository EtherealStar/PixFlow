package com.pixflow.module.rubrics.api;

import com.pixflow.module.rubrics.model.SubjectType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record ExplicitSubjects(SubjectType subjectType, List<String> subjectIds)
        implements RunSelection {

    public ExplicitSubjects {
        Objects.requireNonNull(subjectType, "subjectType");
        if (subjectIds == null || subjectIds.isEmpty()) {
            throw new IllegalArgumentException("subject ids must not be empty");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String subjectId : subjectIds) {
            if (subjectId == null || subjectId.isBlank()) {
                throw new IllegalArgumentException("subject id must not be blank");
            }
            unique.add(subjectId);
        }
        subjectIds = List.copyOf(unique);
    }
}
