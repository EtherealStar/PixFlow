package com.pixflow.module.rubrics.api;

public record DatasetSelection(String datasetId, String datasetVersion) implements RunSelection {

    public DatasetSelection {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("dataset id must not be blank");
        }
        if (datasetVersion == null || datasetVersion.isBlank()) {
            throw new IllegalArgumentException("dataset version must not be blank");
        }
    }
}
