package com.pixflow.module.rubrics.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RubricsPropertiesTest {
    @Test
    void rejectsEnabledBindingsWithoutPinnedIdentities() {
        RubricsProperties properties = new RubricsProperties();
        properties.getAutomation().getScheduled().setEnabled(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixed template and dataset");
    }

    @Test
    void rejectsNonPositiveRuntimeLimits() {
        RubricsProperties properties = new RubricsProperties();
        properties.setQueueCapacity(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
