package com.pixflow.infra.mq.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;

class MqPropertiesTest {

    @Test
    void defaultsToThreeBrokerRetriesAndThreeBackoffSlots() {
        MqProperties properties = new MqProperties();

        assertThat(properties.getMaxRetries()).isEqualTo(3);
        assertThat(properties.getRetryBackoff()).containsExactly(
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2));
    }

    @Test
    void springBindingAllowsExplicitRetryOverrides() {
        Map<String, Object> values = Map.of(
                "pixflow.mq.max-retries", "2",
                "pixflow.mq.retry-backoff[0]", "1s",
                "pixflow.mq.retry-backoff[1]", "4s");
        Binder binder = new Binder(ConfigurationPropertySources.from(new MapPropertySource("test", values)));

        MqProperties properties = binder.bind("pixflow.mq", Bindable.of(MqProperties.class)).get();

        assertThat(properties.getMaxRetries()).isEqualTo(2);
        assertThat(properties.getRetryBackoff()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(4));
    }
}
