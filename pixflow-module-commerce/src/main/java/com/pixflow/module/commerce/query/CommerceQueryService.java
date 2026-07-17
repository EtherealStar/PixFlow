package com.pixflow.module.commerce.query;

import com.pixflow.module.commerce.config.CommerceProperties;
import com.pixflow.module.commerce.importer.CommerceImportService;
import com.pixflow.module.commerce.source.CommerceDataSource;
import com.pixflow.module.commerce.source.FreshnessPolicy;
import com.pixflow.module.commerce.source.PullSpec;
import com.pixflow.module.commerce.store.CommerceAggregateRow;
import com.pixflow.module.commerce.store.CommerceBenchmarkRow;
import com.pixflow.module.commerce.store.CommerceData;
import com.pixflow.module.commerce.store.CommerceDataMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class CommerceQueryService {
    private final CommerceDataMapper mapper;

    private final CommerceDataSource externalSource;

    private final CommerceImportService importService;

    private final FreshnessPolicy freshnessPolicy;

    private final BenchmarkCalculator benchmarkCalculator;

    private final CommerceProperties properties;

    private final Clock clock;

    public CommerceQueryService(
            CommerceDataMapper mapper,
            CommerceDataSource externalSource,
            CommerceImportService importService,
            FreshnessPolicy freshnessPolicy,
            BenchmarkCalculator benchmarkCalculator,
            CommerceProperties properties,
            Clock clock) {
        this.mapper = mapper;
        this.externalSource = externalSource;
        this.importService = importService;
        this.freshnessPolicy = freshnessPolicy;
        this.benchmarkCalculator = benchmarkCalculator;
        this.properties = properties;
        this.clock = clock;
    }

    public CommerceQueryResult query(CommerceQuery rawQuery) {
        CommerceQuery query = normalize(rawQuery);
        List<String> skuIds = query.skuIds().stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        List<String> sources = sources(query.sourceScope(), query.preferredSource());
        List<CommerceAggregateRow> beforeRefresh = mapper.aggregateBySku(
                skuIds,
                query.periodType(),
                query.window().from(),
                query.window().to(),
                sources);
        boolean degraded = false;
        Set<String> staleReasons = new HashSet<>();
        if (properties.getSource().isLiveEnabled() && externalSource.supportsLive()) {
            Set<String> stale = freshnessPolicy.staleSkus(skuIds, beforeRefresh);
            if (!stale.isEmpty()) {
                try {
                    // 实时刷新只拉本次查询的过期或缺失 SKU，避免一次查询触发全量同步。
                    importService.importStandardized(externalSource.pull(new PullSpec(
                            new ArrayList<>(stale),
                            query.window(),
                            query.periodType(),
                            properties.getSource().getPlatform())));
                } catch (RuntimeException ex) {
                    degraded = true;
                    staleReasons.addAll(stale);
                }
            }
        }
        List<CommerceAggregateRow> aggregates = mapper.aggregateBySku(
                skuIds,
                query.periodType(),
                query.window().from(),
                query.window().to(),
                sources);
        Map<String, CommerceAggregateRow> bySku = aggregates.stream()
                .collect(Collectors.toMap(CommerceAggregateRow::skuId, row -> row, (a, b) -> a, LinkedHashMap::new));
        List<SkuMetrics> perSku = new ArrayList<>();
        for (String skuId : skuIds) {
            CommerceAggregateRow row = bySku.get(skuId);
            if (row == null) {
                continue;
            }
            Metrics metrics = metrics(row);
            Benchmark benchmark = null;
            if (query.withBenchmark()) {
                CommerceBenchmarkRow benchmarkRow = mapper.categoryBenchmark(
                        row.category(),
                        query.periodType(),
                        query.window().from(),
                        query.window().to(),
                        sources);
                benchmark = benchmarkCalculator.calculate(
                        metrics, benchmarkRow, properties.getQuery().getBenchmarkMinSample());
            }
            List<TrendPoint> trend = query.withTrend()
                    ? mapper.trend(skuId, query.periodType(), query.window().from(), query.window().to(), sources)
                            .stream().map(this::trend).toList()
                    : List.of();
            boolean stale = staleReasons.contains(skuId) || isStale(row.fetchedAt());
            perSku.add(new SkuMetrics(
                    skuId,
                    row.category(),
                    metrics,
                    benchmark,
                    trend,
                    new FreshnessInfo(
                            stale,
                            row.fetchedAt(),
                            row.source(),
                            stale ? "stale_or_live_refresh_failed" : null)));
        }
        List<String> missing = skuIds.stream().filter(sku -> !bySku.containsKey(sku)).toList();
        return new CommerceQueryResult(perSku, missing, degraded);
    }

    private CommerceQuery normalize(CommerceQuery query) {
        LocalDate today = LocalDate.now(clock);
        TimeWindow window = query.window() == null
                ? new TimeWindow(today.minusDays(properties.getQuery().getDefaultWindowDays()), today)
                : query.window();
        return new CommerceQuery(
                query.skuIds() == null ? List.of() : query.skuIds(),
                window,
                query.periodType() == null ? properties.getQuery().getDefaultPeriodType() : query.periodType(),
                query.withBenchmark(),
                query.withTrend(),
                query.sourceScope() == null ? properties.getQuery().getDefaultSourceScope() : query.sourceScope(),
                query.preferredSource());
    }

    private List<String> sources(CommerceSourceScope scope, String preferredSource) {
        if (scope == CommerceSourceScope.LOCAL_ONLY) {
            return List.of("LOCAL_IMPORT");
        }
        if (scope == CommerceSourceScope.EXTERNAL_ONLY) {
            return List.of("EXTERNAL_API:" + properties.getSource().getPlatform());
        }
        if ((scope == CommerceSourceScope.PREFERRED_EXTERNAL || scope == CommerceSourceScope.PREFERRED_LOCAL)
                && preferredSource != null && !preferredSource.isBlank()) {
            return List.of(preferredSource.trim());
        }
        return List.of();
    }

    private Metrics metrics(CommerceAggregateRow row) {
        return new Metrics(
                row.impressions() == null ? 0L : row.impressions(),
                row.ctr(),
                row.addCartRate(),
                row.purchaseRate());
    }

    private TrendPoint trend(CommerceData data) {
        return new TrendPoint(
                data.getPeriodStart(),
                data.getPeriodEnd(),
                new Metrics(data.getImpressions(), data.getCtr(), data.getAddCartRate(), data.getPurchaseRate()));
    }

    private boolean isStale(Instant fetchedAt) {
        if (fetchedAt == null) {
            return true;
        }
        return fetchedAt.isBefore(Instant.now(clock).minus(properties.getSource().getFreshnessTtl()));
    }
}
