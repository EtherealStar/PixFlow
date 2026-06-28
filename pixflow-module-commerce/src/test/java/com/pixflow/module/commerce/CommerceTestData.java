package com.pixflow.module.commerce;

import com.pixflow.module.commerce.query.PeriodType;
import com.pixflow.module.commerce.store.CommerceData;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class CommerceTestData {
    private CommerceTestData() {
    }

    public static CommerceData commerceData(
            String skuId,
            String category,
            long impressions,
            String ctr,
            String addCartRate,
            String purchaseRate,
            LocalDate periodStart,
            String source,
            Instant fetchedAt) {
        CommerceData data = new CommerceData();
        data.setSkuId(skuId);
        data.setCategory(category);
        data.setImpressions(impressions);
        data.setCtr(new BigDecimal(ctr));
        data.setAddCartRate(new BigDecimal(addCartRate));
        data.setPurchaseRate(new BigDecimal(purchaseRate));
        data.setPeriodType(PeriodType.DAY);
        data.setPeriodStart(periodStart);
        data.setPeriodEnd(periodStart);
        data.setSource(source);
        data.setFetchedAt(fetchedAt);
        data.setCreatedAt(fetchedAt);
        data.setUpdatedAt(fetchedAt);
        return data;
    }
}
