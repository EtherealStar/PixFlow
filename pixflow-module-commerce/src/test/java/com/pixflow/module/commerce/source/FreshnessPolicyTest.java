package com.pixflow.module.commerce.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.commerce.config.CommerceProperties;
import com.pixflow.module.commerce.store.CommerceAggregateRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FreshnessPolicyTest {
    @Test
    void returnsOnlyMissingOrExpiredRequestedSkus() {
        CommerceProperties properties = new CommerceProperties();
        properties.getSource().setFreshnessTtl(Duration.ofHours(2));
        Clock clock = Clock.fixed(Instant.parse("2026-06-28T12:00:00Z"), ZoneOffset.UTC);
        FreshnessPolicy policy = new FreshnessPolicy(properties, clock);

        var stale = policy.staleSkus(
                List.of("SKU_FRESH", "SKU_EXPIRED", "SKU_MISSING"),
                List.of(
                        row("SKU_FRESH", Instant.parse("2026-06-28T11:00:00Z")),
                        row("SKU_EXPIRED", Instant.parse("2026-06-28T08:00:00Z")),
                        row("SKU_UNREQUESTED", Instant.parse("2026-06-28T08:00:00Z"))));

        assertThat(stale).containsExactlyInAnyOrder("SKU_EXPIRED", "SKU_MISSING");
    }

    private static CommerceAggregateRow row(String skuId, Instant fetchedAt) {
        return new CommerceAggregateRow(
                skuId,
                "dress",
                100L,
                new BigDecimal("0.10"),
                new BigDecimal("0.02"),
                new BigDecimal("0.01"),
                fetchedAt,
                "LOCAL_IMPORT");
    }
}
