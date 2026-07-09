package com.pixflow.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DotenvEnvironmentPostProcessorTest {

    @Test
    void candidatePathsIncludeParentWorkspaceDotenvWhenStartedFromAppModule() {
        Path workspace = Path.of("workspace").toAbsolutePath().normalize();
        Path appModule = workspace.resolve("pixflow-app");

        List<Path> candidates = DotenvEnvironmentPostProcessor.candidatePaths(appModule);

        assertThat(candidates).contains(
                appModule.resolve(".env").normalize(),
                workspace.resolve(".env").normalize());
    }
}
