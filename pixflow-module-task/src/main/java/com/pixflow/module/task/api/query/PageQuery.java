package com.pixflow.module.task.api.query;

public record PageQuery(int page, int size) {
    public PageQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size <= 0 || size > 200) {
            throw new IllegalArgumentException("size must be between 1 and 200");
        }
    }

    public static PageQuery firstPage() {
        return new PageQuery(0, 20);
    }
}
