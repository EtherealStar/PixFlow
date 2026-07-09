package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataValuesTest {

    @Test
    void immutableCopyDefensivelyCopiesNestedValues() {
        Map<String, Object> source = new LinkedHashMap<>();
        List<Object> nested = new ArrayList<>();
        nested.add("a");
        source.put("items", nested);

        Map<String, Object> copy = MetadataValues.immutableCopy(source);
        nested.add("b");

        assertThat((List<Object>) copy.get("items")).containsExactly("a");
        assertThatThrownBy(() -> copy.put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNonFiniteNumbersNullContainerValuesAndCycles() {
        assertThatThrownBy(() -> MetadataValues.immutableCopy(Map.of("n", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> MetadataValues.immutableCopy(Map.of("n", Float.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");

        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("x", null);
        assertThatThrownBy(() -> MetadataValues.immutableCopy(withNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);
        assertThatThrownBy(() -> MetadataValues.immutableCopy(cycle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycles");
    }
}
