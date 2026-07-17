package com.pixflow.module.commerce.importer;

import com.pixflow.module.commerce.query.PeriodType;
import com.pixflow.module.commerce.store.CommerceData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

public class RowValidator {
    private final Clock clock;

    public RowValidator(Clock clock) {
        this.clock = clock;
    }

    public CommerceData normalize(RawCommerceRow row, ImportOptions options) {
        if (blank(row.skuId())) {
            throw new IllegalArgumentException("sku_id must not be blank");
        }
        if (blank(row.category())) {
            throw new IllegalArgumentException("category must not be blank");
        }
        long impressions = parseLong(row.impressions(), "impressions");
        if (impressions < 0) {
            throw new IllegalArgumentException("impressions must be >= 0");
        }
        BigDecimal ctr = parseRate(row.ctr(), "ctr");
        BigDecimal addCart = parseRate(row.addCartRate(), "add_cart_rate");
        BigDecimal purchase = parseRate(row.purchaseRate(), "purchase_rate");
        PeriodType periodType = parsePeriodType(row.periodType(), options.defaultPeriodType());
        LocalDate start = parseDate(row.periodStart(), options.defaultPeriodStart(), "period_start");
        LocalDate end = parseDate(
                row.periodEnd(),
                options.defaultPeriodEnd() == null ? start : options.defaultPeriodEnd(),
                "period_end");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("period_start must be <= period_end");
        }
        Instant now = Instant.now(clock);
        CommerceData data = new CommerceData();
        data.setSkuId(row.skuId().trim());
        data.setCategory(row.category().trim());
        data.setImpressions(impressions);
        data.setCtr(ctr);
        data.setAddCartRate(addCart);
        data.setPurchaseRate(purchase);
        data.setPeriodType(periodType);
        data.setPeriodStart(start);
        data.setPeriodEnd(end);
        data.setSource(options.effectiveSource());
        data.setFetchedAt(now);
        data.setCreatedAt(now);
        data.setUpdatedAt(now);
        return data;
    }

    private static long parseLong(String raw, String field) {
        if (blank(raw)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return Long.parseLong(raw.trim());
    }

    private static BigDecimal parseRate(String raw, String field) {
        if (blank(raw)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        BigDecimal value = new BigDecimal(raw.trim().replace("%", ""));
        if (raw.trim().endsWith("%")) {
            value = value.movePointLeft(2);
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
        return value;
    }

    private static PeriodType parsePeriodType(String raw, PeriodType fallback) {
        if (blank(raw)) {
            return fallback == null ? PeriodType.DAY : fallback;
        }
        return PeriodType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    private static LocalDate parseDate(String raw, LocalDate fallback, String field) {
        if (blank(raw)) {
            if (fallback == null) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return fallback;
        }
        return LocalDate.parse(raw.trim());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
