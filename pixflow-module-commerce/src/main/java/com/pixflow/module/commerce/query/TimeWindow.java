package com.pixflow.module.commerce.query;

import java.time.LocalDate;

public record TimeWindow(LocalDate from, LocalDate to) {
    public TimeWindow {
        if (from == null || to == null) {
            throw new IllegalArgumentException("time window must not be null");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("time window from must be before to");
        }
    }
}
