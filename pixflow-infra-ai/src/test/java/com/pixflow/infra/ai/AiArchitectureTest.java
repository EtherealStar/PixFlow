package com.pixflow.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AiArchitectureTest {
    @Test
    void aiSourceDoesNotDependOnCacheStorageVectorOrHigherLayers() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java", "com", "pixflow", "infra", "ai");
        String source = Files.walk(sourceRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .map(AiArchitectureTest::readString)
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(source)
                .doesNotContain("com.pixflow.infra.cache")
                .doesNotContain("com.pixflow.infra.storage")
                .doesNotContain("com.pixflow.infra.vector")
                .doesNotContain("com.pixflow.harness")
                .doesNotContain("com.pixflow.module")
                .doesNotContain("com.pixflow.agent");
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
