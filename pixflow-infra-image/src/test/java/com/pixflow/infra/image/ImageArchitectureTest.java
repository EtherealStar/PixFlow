package com.pixflow.infra.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ImageArchitectureTest {
    @Test
    void imageSourceDoesNotDependOnWorkflowOrStorageContexts() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java", "com", "pixflow", "infra", "image");
        String source = Files.walk(sourceRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .map(ImageArchitectureTest::readString)
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(source)
                .doesNotContain("com.pixflow.infra.storage")
                .doesNotContain("com.pixflow.module.dag")
                .doesNotContain("com.pixflow.module.task")
                .doesNotContain("com.pixflow.infra.mq");
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
