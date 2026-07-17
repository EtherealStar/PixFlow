package com.pixflow.infra.thirdparty;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ThirdPartyArchitectureTest {
    @Test
    void thirdPartySourceDoesNotDependOnBusinessOrRuntimeLayers() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java", "com", "pixflow", "infra", "thirdparty");
        String source = Files.walk(sourceRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .map(ThirdPartyArchitectureTest::readString)
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(source)
                .doesNotContain("com.pixflow.module")
                .doesNotContain("com.pixflow.harness")
                .doesNotContain("com.pixflow.agent")
                .doesNotContain("com.pixflow.infra.ai")
                .doesNotContain("com.pixflow.infra.storage")
                .doesNotContain("com.pixflow.infra.vector");
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
