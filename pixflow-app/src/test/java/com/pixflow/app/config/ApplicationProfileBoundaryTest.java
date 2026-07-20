package com.pixflow.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class ApplicationProfileBoundaryTest {
    @Test
    void baseConfigurationDoesNotActivateDevelopmentProfile() throws IOException {
        var sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertThat(sources)
                .allSatisfy(source -> assertThat(source.getProperty("spring.profiles.active")).isNull());
    }
}
