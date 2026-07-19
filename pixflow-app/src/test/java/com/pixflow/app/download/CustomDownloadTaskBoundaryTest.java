package com.pixflow.app.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CustomDownloadTaskBoundaryTest {
  @Test
  void customDownloadDependsOnlyOnTaskPublicApi() throws IOException {
    Path sourceRoot = Path.of("src", "main", "java", "com", "pixflow", "app", "download");
    try (var sources = Files.list(sourceRoot)) {
      var forbiddenImports =
          sources
              .filter(path -> path.toString().endsWith(".java"))
              .flatMap(
                  path -> {
                    try {
                      return Files.readAllLines(path).stream();
                    } catch (IOException failure) {
                      throw new IllegalStateException(failure);
                    }
                  })
              .filter(line -> line.startsWith("import com.pixflow.module.task."))
              .filter(line -> !line.startsWith("import com.pixflow.module.task.api."))
              .toList();

      assertThat(forbiddenImports).isEmpty();
    }
  }
}
