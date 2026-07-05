package com.pixflow.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class PixFlowApplicationScanBoundaryTest {
    @Test
    void applicationClassLivesInAppPackageSoDefaultScanningDoesNotReachModules() {
        assertThat(PixFlowApplication.class.getPackageName()).isEqualTo("com.pixflow.app");

        SpringBootApplication annotation = PixFlowApplication.class.getAnnotation(SpringBootApplication.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.scanBasePackages()).isEmpty();
        assertThat(annotation.scanBasePackageClasses()).isEmpty();
    }
}
