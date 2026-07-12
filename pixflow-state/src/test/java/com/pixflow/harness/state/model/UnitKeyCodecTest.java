package com.pixflow.harness.state.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnitKeyCodecTest {
    @Test
    void roundTripsReservedCharactersAndKeepsTaskOutOfPersistedIdentity() {
        UnitKey source = UnitKey.group("task-1", "group|中文/1", "branch:main");

        String encoded = UnitKeyCodec.encode(source);

        assertThat(UnitKeyCodec.decode("derived-task", encoded))
                .isEqualTo(UnitKey.group("derived-task", "group|中文/1", "branch:main"));
        assertThat(encoded).doesNotContain("task-1");
        assertThat(UnitKeyCodec.sha256(source)).hasSize(64);
    }

    @Test
    void createsGenerativeIdentity() {
        UnitKey unit = UnitKey.generative("task-1", "image-9");

        assertThat(unit.kind()).isEqualTo(UnitKind.GENERATIVE);
        assertThat(UnitKeyCodec.decode("task-1", UnitKeyCodec.encode(unit))).isEqualTo(unit);
    }
}
