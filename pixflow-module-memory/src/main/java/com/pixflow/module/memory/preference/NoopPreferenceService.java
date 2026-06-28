package com.pixflow.module.memory.preference;

import com.pixflow.module.memory.recall.MemoryItem;
import java.util.List;

public class NoopPreferenceService implements PreferenceService {
    @Override
    public List<MemoryItem> recallPreferences(int maxItems) {
        return List.of();
    }
}
