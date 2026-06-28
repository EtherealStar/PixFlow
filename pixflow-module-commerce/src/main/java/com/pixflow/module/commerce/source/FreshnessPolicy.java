package com.pixflow.module.commerce.source;

import com.pixflow.module.commerce.config.CommerceProperties;
import com.pixflow.module.commerce.store.CommerceAggregateRow;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FreshnessPolicy {
    private final CommerceProperties properties;
    private final Clock clock;

    public FreshnessPolicy(CommerceProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Set<String> staleSkus(Collection<String> requested, List<CommerceAggregateRow> stored) {
        Set<String> fresh = new HashSet<>();
        Instant threshold = Instant.now(clock).minus(properties.getSource().getFreshnessTtl());
        for (CommerceAggregateRow row : stored) {
            if (row.fetchedAt() != null && !row.fetchedAt().isBefore(threshold)) {
                fresh.add(row.skuId());
            }
        }
        Set<String> stale = new HashSet<>(requested);
        stale.removeAll(fresh);
        return stale;
    }
}
