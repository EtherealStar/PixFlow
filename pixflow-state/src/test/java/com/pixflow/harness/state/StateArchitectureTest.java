package com.pixflow.harness.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StateArchitectureTest {

    @Test
    void stateSourceDoesNotDependOnForbiddenRuntimePackages() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java", "com", "pixflow", "harness", "state");
        String source = Files.walk(sourceRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .map(StateArchitectureTest::readString)
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(source)
                .doesNotContain("org.redisson")
                .doesNotContain("io.minio")
                .doesNotContain("com.baomidou.mybatisplus")
                .doesNotContain("java.sql")
                .doesNotContain("com.pixflow.module.task")
                .doesNotContain("com.pixflow.module.dag");
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
