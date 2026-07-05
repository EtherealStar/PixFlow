package com.pixflow.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TimeAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TimeAutoConfiguration.class));

    @Test
    void createsSingleUtcPlatformClockByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context).hasBean("pixflowClock");
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        });
    }

    @Test
    void backsOffWhenApplicationProvidesClock() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-07-05T00:00:00Z"), ZoneOffset.UTC);

        contextRunner
                .withBean(Clock.class, () -> fixedClock)
                .run(context -> {
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context).doesNotHaveBean("pixflowClock");
                    assertThat(context.getBean(Clock.class)).isSameAs(fixedClock);
                });
    }
}
