package com.pixflow.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractsArchitectureTest {
    private static final Path MAIN_SOURCE = Path.of("src", "main", "java");

    @Test
    void productionSourceIsPureJdkAssetContract() throws IOException {
        List<Path> sources;
        try (var paths = Files.walk(MAIN_SOURCE)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }

        assertFalse(sources.isEmpty(), "contracts production sources must exist");
        for (Path source : sources) {
            String normalizedPath = source.toString().replace('\\', '/');
            String content = Files.readString(source);

            // contracts 只承载稳定的 Asset Reference 值语义，不得重新长出 owner 服务或流程状态。
            assertTrue(normalizedPath.contains("/com/pixflow/contracts/asset/"), normalizedPath);
            content.lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .forEach(line -> assertTrue(line.startsWith("import java."), line));
            assertFalse(content.contains("@Component"), normalizedPath);
            assertFalse(content.contains("@Configuration"), normalizedPath);
            assertFalse(content.contains("@Entity"), normalizedPath);
        }
    }
}
