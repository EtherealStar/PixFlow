package com.pixflow.harness.context.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessageReferenceTest {
    @Test
    void rejectsBlankAndControlCharacters() {
        assertThatThrownBy(() -> new MessageReference(" ", "path"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageReference("package:1", "path\nforged"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
