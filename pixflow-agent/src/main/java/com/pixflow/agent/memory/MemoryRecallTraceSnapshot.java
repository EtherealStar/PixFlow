package com.pixflow.agent.memory;

import com.pixflow.module.memory.context.MemoryContext;
import com.pixflow.module.memory.context.MemorySection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 Memory 诊断压缩为可持久化、无正文的回合级 trace 快照。 */
public final class MemoryRecallTraceSnapshot {
    private static final int MAX_REASONS = 8;

    private static final int MAX_REASON_LENGTH = 80;

    private static final List<String> REFERENCE_DEGRADATION_STATUSES = List.of(
            "resolution_failed", "empty_package", "sku_unavailable", "truncated");

    private MemoryRecallTraceSnapshot() {
    }

    public static Map<String, Object> from(MemoryContext context) {
        if (context == null) {
            return Map.of();
        }
        Map<String, Object> trace = context.recallTrace();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("degraded", context.degraded());
        snapshot.put("token_budget", integer(trace.get("token_budget")));
        snapshot.put("used_tokens", integer(trace.get("used_tokens")));
        Map<?, ?> signals = map(trace.get("signals"));
        snapshot.put("sku_filter_count", size(signals.get("sku_ids")));
        snapshot.put("category_filter_count", size(signals.get("categories")));
        snapshot.put("degraded_reference_count", degradedReferenceCount(trace.get("reference_resolution")));
        snapshot.put("truncated_reference_count", statusCount(trace.get("reference_resolution"), "truncated"));
        snapshot.put("reference_degradation_counts", referenceDegradationCounts(trace.get("reference_resolution")));
        snapshot.put("sections", context.sections().stream().map(MemoryRecallTraceSnapshot::section).toList());
        return Map.copyOf(snapshot);
    }

    public static Map<String, Object> failed(String reason, int tokenBudget) {
        return Map.of(
                "degraded", true,
                "token_budget", Math.max(0, tokenBudget),
                "used_tokens", 0,
                "sku_filter_count", 0,
                "category_filter_count", 0,
                "degraded_reference_count", 0,
                "truncated_reference_count", 0,
                "reference_degradation_counts", Map.of(),
                "sections", List.of(),
                "degraded_reasons", List.of(sanitizeReason(reason)));
    }

    private static Map<String, Object> section(MemorySection section) {
        Map<String, Object> trace = section.trace();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", section.name());
        result.put("token_estimate", section.tokenEstimate());
        result.put("candidate_count", candidateCount(trace));
        result.put("selected_count", section.items().size());
        result.put("dependency_status", text(trace.get("dependency_status")));
        result.put("degraded_reasons", reasons(trace.get("degraded_reasons")));
        return Map.copyOf(result);
    }

    private static int candidateCount(Map<String, Object> trace) {
        int requested = integer(trace.get("requested_item_count"));
        if (requested > 0) {
            return requested;
        }
        return Math.max(integer(trace.get("fused_candidates")),
                integer(trace.get("vector_candidates")) + integer(trace.get("keyword_candidates")));
    }

    private static int degradedReferenceCount(Object value) {
        if (!(value instanceof Iterable<?> items)) {
            return 0;
        }
        int count = 0;
        for (Object item : items) {
            if (item instanceof Map<?, ?> map && !"resolved".equals(map.get("status"))) {
                count++;
            }
        }
        return count;
    }

    private static int statusCount(Object value, String status) {
        if (!(value instanceof Iterable<?> items)) {
            return 0;
        }
        int count = 0;
        for (Object item : items) {
            if (item instanceof Map<?, ?> map && status.equals(map.get("status"))) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Integer> referenceDegradationCounts(Object value) {
        if (!(value instanceof Iterable<?> items)) {
            return Map.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : REFERENCE_DEGRADATION_STATUSES) {
            int count = statusCount(items, status);
            if (count > 0) {
                counts.put(status, count);
            }
        }
        return Map.copyOf(counts);
    }

    private static List<String> reasons(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : iterable) {
            if (result.size() == MAX_REASONS) {
                break;
            }
            String reason = sanitizeReason(String.valueOf(item));
            if (!reason.isBlank()) {
                result.add(reason);
            }
        }
        return List.copyOf(result);
    }

    private static String sanitizeReason(String reason) {
        if (reason == null) {
            return "";
        }
        String normalized = reason.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.length() <= MAX_REASON_LENGTH
                ? normalized
                : normalized.substring(0, MAX_REASON_LENGTH);
    }

    private static int size(Object value) {
        return value instanceof java.util.Collection<?> collection ? collection.size() : 0;
    }

    private static int integer(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private static String text(Object value) {
        return value == null ? "" : sanitizeReason(String.valueOf(value));
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }
}
