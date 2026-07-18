package com.pixflow.module.task.api.query;

import java.util.List;

public record PageResult<T>(List<T> records, long total, int page, int size) {
  public PageResult {
    records = records == null ? List.of() : List.copyOf(records);
  }
}
