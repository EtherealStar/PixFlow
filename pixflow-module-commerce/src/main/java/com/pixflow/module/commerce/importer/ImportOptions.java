package com.pixflow.module.commerce.importer;

import com.pixflow.module.commerce.query.PeriodType;
import java.time.LocalDate;

public record ImportOptions(
        String source,
        PeriodType defaultPeriodType,
        LocalDate defaultPeriodStart,
        LocalDate defaultPeriodEnd,
        CategoryConflictPolicy categoryConflictPolicy) {

    public String effectiveSource() {
        return source == null || source.isBlank() ? "LOCAL_IMPORT" : source.trim();
    }
}
