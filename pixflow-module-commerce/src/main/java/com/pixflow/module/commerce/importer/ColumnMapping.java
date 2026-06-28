package com.pixflow.module.commerce.importer;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.commerce.error.CommerceErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class ColumnMapping {
    private static final Map<String, List<String>> ALIASES = Map.of(
            "skuId", List.of("sku_id", "skuid", "sku", "商品id", "商品编号", "商品sku"),
            "category", List.of("category", "类目", "分类"),
            "impressions", List.of("impressions", "exposure", "曝光量", "展现量"),
            "ctr", List.of("ctr", "click_rate", "点击率"),
            "addCartRate", List.of("add_cart_rate", "addcartrate", "加购率"),
            "purchaseRate", List.of("purchase_rate", "purchaserate", "购买率", "转化率"),
            "periodType", List.of("period_type", "periodtype", "周期类型", "统计粒度"),
            "periodStart", List.of("period_start", "periodstart", "开始日期", "统计开始"),
            "periodEnd", List.of("period_end", "periodend", "结束日期", "统计结束"));

    private final List<String> headers;
    private final String skuId;
    private final String category;
    private final String impressions;
    private final String ctr;
    private final String addCartRate;
    private final String purchaseRate;
    private final String periodType;
    private final String periodStart;
    private final String periodEnd;

    public ColumnMapping(List<String> headers, boolean strictHeader) {
        this.headers = headers == null ? List.of() : headers;
        this.skuId = resolve("skuId").orElse(null);
        this.category = resolve("category").orElse(null);
        this.impressions = resolve("impressions").orElse(null);
        this.ctr = resolve("ctr").orElse(null);
        this.addCartRate = resolve("addCartRate").orElse(null);
        this.purchaseRate = resolve("purchaseRate").orElse(null);
        this.periodType = resolve("periodType").orElse(null);
        this.periodStart = resolve("periodStart").orElse(null);
        this.periodEnd = resolve("periodEnd").orElse(null);
        if (strictHeader) {
            require("sku_id", skuId);
            require("category", category);
            require("impressions", impressions);
            require("ctr", ctr);
            require("add_cart_rate", addCartRate);
            require("purchase_rate", purchaseRate);
        }
    }

    public String skuId() {
        return skuId;
    }

    public String category() {
        return category;
    }

    public String impressions() {
        return impressions;
    }

    public String ctr() {
        return ctr;
    }

    public String addCartRate() {
        return addCartRate;
    }

    public String purchaseRate() {
        return purchaseRate;
    }

    public String periodType() {
        return periodType;
    }

    public String periodStart() {
        return periodStart;
    }

    public String periodEnd() {
        return periodEnd;
    }

    private Optional<String> resolve(String field) {
        List<String> aliases = new ArrayList<>(ALIASES.getOrDefault(field, List.of()));
        aliases.add(field);
        for (String header : headers) {
            String normalized = normalize(header);
            for (String alias : aliases) {
                if (normalized.equals(normalize(alias))) {
                    return Optional.of(header);
                }
            }
        }
        return Optional.empty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace("-", "_").replace(" ", "_").toLowerCase(Locale.ROOT);
    }

    private static void require(String displayName, String column) {
        if (column == null) {
            throw new PixFlowException(
                    CommerceErrorCode.COMMERCE_IMPORT_MISSING_COLUMN,
                    "missing required commerce column: " + displayName,
                    null,
                    Map.of("column", displayName));
        }
    }
}
