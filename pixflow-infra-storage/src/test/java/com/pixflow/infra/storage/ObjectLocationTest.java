package com.pixflow.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ObjectLocationTest {

    @Test
    void normalizesSlashesAndLeadingSlash() {
        ObjectLocation location = ObjectLocation.of(BucketType.PACKAGES, "/42\\images//a.png");

        assertThat(location.key()).isEqualTo("42/images/a.png");
    }

    @Test
    void rejectsUnsafeKeys() {
        assertThatThrownBy(() -> ObjectLocation.of(BucketType.PACKAGES, "../a.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectLocation.of(BucketType.PACKAGES, "C:\\temp\\a.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectLocation.of(BucketType.PACKAGES, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
