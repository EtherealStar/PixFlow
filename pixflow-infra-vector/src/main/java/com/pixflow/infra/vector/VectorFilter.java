package com.pixflow.infra.vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class VectorFilter {
    private static final VectorFilter NONE = new VectorFilter(List.of(), List.of(), List.of());

    private final List<Condition> must;
    private final List<Condition> should;
    private final List<Condition> mustNot;

    private VectorFilter(List<Condition> must, List<Condition> should, List<Condition> mustNot) {
        this.must = immutableConditions(must);
        this.should = immutableConditions(should);
        this.mustNot = immutableConditions(mustNot);
    }

    public static VectorFilter none() {
        return NONE;
    }

    public static VectorFilter must(Condition... conditions) {
        return new VectorFilter(Arrays.asList(conditions), List.of(), List.of());
    }

    public static VectorFilter should(Condition... conditions) {
        return new VectorFilter(List.of(), Arrays.asList(conditions), List.of());
    }

    public static VectorFilter mustNot(Condition... conditions) {
        return new VectorFilter(List.of(), List.of(), Arrays.asList(conditions));
    }

    public static Condition match(String field, Object value) {
        return new Match(field, value);
    }

    public static Condition matchAny(String field, List<Object> values) {
        return new MatchAny(field, values);
    }

    public static Condition range(String field, Double gte, Double lte) {
        return new Range(field, gte, lte);
    }

    public VectorFilter and(VectorFilter other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        List<Condition> mergedMust = new ArrayList<>(must);
        mergedMust.addAll(other.must);
        List<Condition> mergedShould = new ArrayList<>(should);
        mergedShould.addAll(other.should);
        List<Condition> mergedMustNot = new ArrayList<>(mustNot);
        mergedMustNot.addAll(other.mustNot);
        return new VectorFilter(mergedMust, mergedShould, mergedMustNot);
    }

    public boolean isEmpty() {
        return must.isEmpty() && should.isEmpty() && mustNot.isEmpty();
    }

    public List<Condition> must() {
        return must;
    }

    public List<Condition> should() {
        return should;
    }

    public List<Condition> mustNot() {
        return mustNot;
    }

    public sealed interface Condition permits Match, MatchAny, Range {
        String field();
    }

    public record Match(String field, Object value) implements Condition {
        public Match {
            field = requireField(field);
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    public record MatchAny(String field, List<Object> values) implements Condition {
        public MatchAny {
            field = requireField(field);
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("values must not be empty");
            }
            List<Object> copy = new ArrayList<>(values.size());
            for (Object value : values) {
                copy.add(Objects.requireNonNull(value, "matchAny values must not contain null"));
            }
            values = Collections.unmodifiableList(copy);
        }
    }

    public record Range(String field, Double gte, Double lte) implements Condition {
        public Range {
            field = requireField(field);
            if (gte == null && lte == null) {
                throw new IllegalArgumentException("range must define gte or lte");
            }
            if (gte != null && !Double.isFinite(gte)) {
                throw new IllegalArgumentException("range gte must be finite");
            }
            if (lte != null && !Double.isFinite(lte)) {
                throw new IllegalArgumentException("range lte must be finite");
            }
            if (gte != null && lte != null && gte > lte) {
                throw new IllegalArgumentException("range gte must not exceed lte");
            }
        }
    }

    private static List<Condition> immutableConditions(List<Condition> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        source.forEach(condition -> Objects.requireNonNull(condition, "condition must not be null"));
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static String requireField(String field) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        return field;
    }
}
