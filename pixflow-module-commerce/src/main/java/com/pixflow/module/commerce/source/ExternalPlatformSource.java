package com.pixflow.module.commerce.source;

import com.pixflow.module.commerce.config.CommerceProperties;
import com.pixflow.module.commerce.store.CommerceData;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

public class ExternalPlatformSource implements CommerceDataSource {
    private final PlatformApiClient client;
    private final CommerceProperties properties;
    private final Clock clock;

    public ExternalPlatformSource(PlatformApiClient client, CommerceProperties properties, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<CommerceData> pull(PullSpec spec) {
        Callable<PlatformPullResult> call = () -> client.pull(new PlatformPullRequest(
                spec.skuIds(),
                spec.window(),
                spec.periodType(),
                spec.platform()));
        try (var executor = Executors.newSingleThreadExecutor()) {
            PlatformPullResult result = executor.submit(call).get(
                    properties.getSource().getTimeout().toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            return result.rows().stream().map(this::toData).toList();
        } catch (TimeoutException ex) {
            throw new IllegalStateException("platform pull timeout", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("platform pull failed", ex);
        }
    }

    @Override
    public boolean supportsLive() {
        return true;
    }

    private CommerceData toData(PlatformMetricRow row) {
        Instant now = Instant.now(clock);
        CommerceData data = new CommerceData();
        data.setSkuId(row.skuId());
        data.setCategory(row.category());
        data.setImpressions(row.impressions());
        data.setCtr(row.ctr());
        data.setAddCartRate(row.addCartRate());
        data.setPurchaseRate(row.purchaseRate());
        data.setPeriodType(row.periodType());
        data.setPeriodStart(row.periodStart());
        data.setPeriodEnd(row.periodEnd());
        data.setSource("EXTERNAL_API:" + properties.getSource().getPlatform());
        data.setFetchedAt(now);
        data.setCreatedAt(now);
        data.setUpdatedAt(now);
        return data;
    }
}
