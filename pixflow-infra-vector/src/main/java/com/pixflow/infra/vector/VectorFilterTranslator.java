package com.pixflow.infra.vector;

import io.qdrant.client.ConditionFactory;
import io.qdrant.client.grpc.Common;
import java.util.List;

final class VectorFilterTranslator {
    private VectorFilterTranslator() {
    }

    static Common.Filter translate(VectorFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        Common.Filter.Builder builder = Common.Filter.newBuilder();
        filter.must().forEach(condition -> builder.addMust(translateCondition(condition)));
        filter.should().forEach(condition -> builder.addShould(translateCondition(condition)));
        filter.mustNot().forEach(condition -> builder.addMustNot(translateCondition(condition)));
        return builder.build();
    }

    private static Common.Condition translateCondition(VectorFilter.Condition condition) {
        if (condition instanceof VectorFilter.Match match) {
            return match(match);
        }
        if (condition instanceof VectorFilter.MatchAny matchAny) {
            return matchAny(matchAny);
        }
        if (condition instanceof VectorFilter.Range range) {
            return range(range);
        }
        throw new IllegalArgumentException("Unsupported vector filter condition: " + condition.getClass().getName());
    }

    private static Common.Condition match(VectorFilter.Match match) {
        Object value = match.value();
        if (value instanceof String stringValue) {
            return ConditionFactory.matchKeyword(match.field(), stringValue);
        }
        if (value instanceof Boolean booleanValue) {
            return ConditionFactory.match(match.field(), booleanValue);
        }
        if (value instanceof Number numberValue) {
            return ConditionFactory.match(match.field(), integralLong(numberValue, "match value must be integral"));
        }
        throw new IllegalArgumentException("Unsupported match value type: " + value.getClass().getName());
    }

    private static Common.Condition matchAny(VectorFilter.MatchAny matchAny) {
        List<Object> values = matchAny.values();
        Object first = values.get(0);
        boolean allStrings = values.stream().allMatch(String.class::isInstance);
        boolean allNumbers = values.stream().allMatch(Number.class::isInstance);
        if (first instanceof String && allStrings) {
            return ConditionFactory.matchKeywords(matchAny.field(), values.stream().map(String.class::cast).toList());
        }
        if (first instanceof Number && allNumbers) {
            return ConditionFactory.matchValues(matchAny.field(), values.stream()
                    .map(Number.class::cast)
                    .map(number -> integralLong(number, "matchAny numeric values must be integral"))
                    .toList());
        }
        throw new IllegalArgumentException("matchAny values must be homogeneous strings or numbers");
    }

    private static Common.Condition range(VectorFilter.Range range) {
        Common.Range.Builder builder = Common.Range.newBuilder();
        if (range.gte() != null) {
            builder.setGte(range.gte());
        }
        if (range.lte() != null) {
            builder.setLte(range.lte());
        }
        return ConditionFactory.range(range.field(), builder.build());
    }

    private static Long integralLong(Number number, String message) {
        if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long) {
            return number.longValue();
        }
        throw new IllegalArgumentException(message + ", got: " + number.getClass().getName());
    }
}
