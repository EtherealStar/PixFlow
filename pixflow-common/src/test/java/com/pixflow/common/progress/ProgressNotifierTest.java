package com.pixflow.common.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressNotifierTest {

    @Test
    void canBeImplementedWithoutTransportDependency() {
        List<String> channels = new ArrayList<>();
        ProgressNotifier notifier = (channel, event) -> channels.add(channel + ":" + event);

        notifier.publish("packages/42/progress", "READY");

        assertThat(channels).containsExactly("packages/42/progress:READY");
    }
}
