package com.pixflow.module.task.internal.planning;

import com.pixflow.harness.state.model.UnitKind;
import com.pixflow.module.dag.expand.ImageDescriptor;
import java.util.List;

public record WorkUnitSelection(List<Item> items) {
  public WorkUnitSelection {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(
      UnitKind kind, String memberId, String branchId, List<ImageDescriptor> images) {
    public Item {
      images = images == null ? List.of() : List.copyOf(images);
    }
  }
}
