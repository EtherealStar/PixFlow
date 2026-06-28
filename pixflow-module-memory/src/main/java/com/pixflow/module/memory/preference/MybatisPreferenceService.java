package com.pixflow.module.memory.preference;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.module.memory.recall.MemoryItem;
import com.pixflow.module.memory.recall.MemoryType;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MybatisPreferenceService implements PreferenceService {
    private final UserPreferenceMapper mapper;

    public MybatisPreferenceService(UserPreferenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public List<MemoryItem> recallPreferences(int maxItems) {
        return mapper.selectList(new LambdaQueryWrapper<UserPreference>()
                        .orderByDesc(UserPreference::getUpdatedAt)
                        .last("LIMIT " + Math.max(1, maxItems)))
                .stream()
                .map(preference -> new MemoryItem(
                        "preference:" + preference.getId(),
                        MemoryType.PREFERENCE,
                        preference.getKey() + "=" + preference.getValue(),
                        "user_preference",
                        "",
                        "",
                        1.0,
                        0,
                        1.0,
                        1.0,
                        1.0,
                        preference.getUpdatedAt(),
                        preference.getUpdatedAt(),
                        Map.of("key", preference.getKey())))
                .toList();
    }
}
