package com.pixflow.module.commerce.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ImportReport {
    private int total;

    private int succeeded;

    private int skipped;

    private final List<ImportFailure> failures = new ArrayList<>();

    private final List<String> warnings = new ArrayList<>();

    public int getTotal() {
        return total;
    }

    public int getSucceeded() {
        return succeeded;
    }

    public int getSkipped() {
        return skipped;
    }

    public List<ImportFailure> getFailures() {
        return Collections.unmodifiableList(failures);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    void countTotal() {
        total++;
    }

    void countSucceeded() {
        succeeded++;
    }

    void addFailure(int rowNumber, String reason) {
        skipped++;
        failures.add(new ImportFailure(rowNumber, reason));
    }

    void addWarning(String warning) {
        warnings.add(warning);
    }
}
