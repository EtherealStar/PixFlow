package com.pixflow.module.memory.preference;

import com.pixflow.module.memory.recall.MemoryItem;
import java.util.List;

public interface PreferenceService {
    List<MemoryItem> recallPreferences(int maxItems);
}
