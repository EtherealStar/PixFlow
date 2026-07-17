package com.pixflow.infra.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VectorPointViewTest {
    @Test
    void recursivelyProtectsVectorAndPayload() {
        float[] vector = {1.0f, 2.0f};
        List<String> nested = new java.util.ArrayList<>(List.of("a"));
        VectorPointView point = new VectorPointView("1", vector, Map.of("nested", nested));

        vector[0] = 9.0f;
        nested.add("b");
        point.vector()[1] = 8.0f;

        assertThat(point.vector()).containsExactly(1.0f, 2.0f);
        assertThat(point.payload().get("nested")).isEqualTo(List.of("a"));
        assertThatThrownBy(() -> ((List<Object>) point.payload().get("nested")).add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesNullValuesInNestedCollections() {
        List<Object> list = new java.util.ArrayList<>();
        list.add("value");
        list.add(null);
        Object[] array = {null, Map.of("key", "value")};

        VectorPointView point = new VectorPointView("1", new float[] {1.0f}, Map.of("list", list, "array", array));

        assertThat(point.payload().get("list")).isEqualTo(list);
        assertThat(point.payload().get("array")).isEqualTo(Arrays.asList(null, Map.of("key", "value")));
    }
}
