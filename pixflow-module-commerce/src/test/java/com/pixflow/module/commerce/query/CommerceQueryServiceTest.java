package com.pixflow.module.commerce.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.module.commerce.config.CommerceProperties;
import com.pixflow.module.commerce.importer.CommerceImportService;
import com.pixflow.module.commerce.source.CommerceDataSource;
import com.pixflow.module.commerce.source.FreshnessPolicy;
import com.pixflow.module.commerce.source.PullSpec;
import com.pixflow.module.commerce.store.CommerceAggregateRow;
import com.pixflow.module.commerce.store.CommerceData;
import com.pixflow.module.commerce.store.CommerceDataMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CommerceQueryServiceTest {
    @Test
    void degradesToStoredDataWhenLiveRefreshFails() {
        CommerceDataMapper mapper = mock(CommerceDataMapper.class);
        CommerceDataSource source = mock(CommerceDataSource.class);
        CommerceImportService importService = mock(CommerceImportService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
        CommerceProperties properties = new CommerceProperties();
        properties.getSource().setLiveEnabled(true);
        properties.getSource().setFreshnessTtl(java.time.Duration.ofHours(1));
        CommerceAggregateRow staleRow = new CommerceAggregateRow(
                "SKU001",
                "dress",
                100L,
                new BigDecimal("0.10"),
                new BigDecimal("0.02"),
                new BigDecimal("0.01"),
                Instant.parse("2026-06-27T00:00:00Z"),
                "LOCAL_IMPORT");
        when(mapper.aggregateBySku(anyCollection(), eq(PeriodType.DAY), any(LocalDate.class), any(LocalDate.class), anyCollection()))
                .thenReturn(List.of(staleRow));
        when(source.supportsLive()).thenReturn(true);
        when(source.pull(any())).thenThrow(new IllegalStateException("timeout"));

        CommerceQueryService service = new CommerceQueryService(
                mapper,
                source,
                importService,
                new FreshnessPolicy(properties, clock),
                new BenchmarkCalculator(),
                properties,
                clock);

        CommerceQueryResult result = service.query(new CommerceQuery(
                List.of("SKU001", "SKU_MISSING"),
                new TimeWindow(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-28")),
                PeriodType.DAY,
                false,
                false,
                CommerceSourceScope.ALL,
                null));

        assertThat(result.degraded()).isTrue();
        assertThat(result.perSku()).hasSize(1);
        assertThat(result.perSku().getFirst().freshness().stale()).isTrue();
        assertThat(result.missingSkus()).containsExactly("SKU_MISSING");
    }

    @Test
    void refreshesStaleSkusAndReadsUpdatedAggregates() {
        CommerceDataMapper mapper = mock(CommerceDataMapper.class);
        CommerceDataSource source = mock(CommerceDataSource.class);
        CommerceImportService importService = mock(CommerceImportService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC);
        CommerceProperties properties = new CommerceProperties();
        properties.getSource().setLiveEnabled(true);
        properties.getSource().setPlatform("fake");
        properties.getSource().setFreshnessTtl(java.time.Duration.ofHours(1));
        CommerceAggregateRow staleRow = new CommerceAggregateRow(
                "SKU001",
                "dress",
                100L,
                new BigDecimal("0.10"),
                new BigDecimal("0.02"),
                new BigDecimal("0.01"),
                Instant.parse("2026-06-27T00:00:00Z"),
                "LOCAL_IMPORT");
        CommerceAggregateRow freshRow = new CommerceAggregateRow(
                "SKU001",
                "dress",
                500L,
                new BigDecimal("0.25"),
                new BigDecimal("0.05"),
                new BigDecimal("0.03"),
                Instant.parse("2026-06-28T00:00:00Z"),
                "EXTERNAL_API:fake");
        when(mapper.aggregateBySku(anyCollection(), eq(PeriodType.DAY), any(LocalDate.class), any(LocalDate.class), anyCollection()))
                .thenReturn(List.of(staleRow))
                .thenReturn(List.of(freshRow));
        when(source.supportsLive()).thenReturn(true);
        CommerceData pulled = new CommerceData();
        pulled.setSkuId("SKU001");
        when(source.pull(any())).thenReturn(List.of(pulled));

        CommerceQueryService service = new CommerceQueryService(
                mapper,
                source,
                importService,
                new FreshnessPolicy(properties, clock),
                new BenchmarkCalculator(),
                properties,
                clock);

        CommerceQueryResult result = service.query(new CommerceQuery(
                List.of("SKU001", "SKU_FRESHLESS"),
                new TimeWindow(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-28")),
                PeriodType.DAY,
                false,
                false,
                CommerceSourceScope.ALL,
                null));

        ArgumentCaptor<PullSpec> pullSpec = ArgumentCaptor.forClass(PullSpec.class);
        verify(source).pull(pullSpec.capture());
        assertThat(pullSpec.getValue().skuIds()).containsExactlyInAnyOrder("SKU001", "SKU_FRESHLESS");
        verify(importService).importStandardized(List.of(pulled));
        assertThat(result.degraded()).isFalse();
        assertThat(result.perSku()).hasSize(1);
        assertThat(result.perSku().getFirst().aggregated().impressions()).isEqualTo(500);
        assertThat(result.perSku().getFirst().freshness().stale()).isFalse();
        assertThat(result.missingSkus()).containsExactly("SKU_FRESHLESS");
    }
}
