package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AttachmentTest {

    @Test
    void acceptsDurableObjectAndAttachmentReferences() {
        assertThat(new Attachment("a", "image", "object://bucket/key", Map.of()).reference())
                .isEqualTo("object://bucket/key");
        assertThat(new Attachment("b", "image", "attachment://att-1", Map.of()).reference())
                .isEqualTo("attachment://att-1");
    }

    @Test
    void rejectsNonDurableReferencesAndControlCharacters() {
        assertThatThrownBy(() -> new Attachment("a", "image", "http://x", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object://");
        assertThatThrownBy(() -> new Attachment("a", "image", "object://bucket/key\nbad", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control");
    }
}
