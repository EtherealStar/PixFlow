package com.pixflow.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ClockBeanBoundaryTest {
    private static final Pattern CLOCK_QUALIFIER = Pattern.compile("@Qualifier\\(\"[^\"]*Clock\"\\)");
    private static final Pattern NAMED_CLOCK_CONDITION =
            Pattern.compile("@ConditionalOnMissingBean\\(\\s*name\\s*=\\s*\"[^\"]*Clock\"");
    private static final Pattern CLOCK_BEAN_METHOD = Pattern.compile("public\\s+Clock\\s+\\w*Clock\\s*\\(");

    @Test
    void mainCodeDoesNotReintroduceModulePrivateClockBeans() throws IOException {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path allowedClockConfig = root.resolve(
                "pixflow-common/src/main/java/com/pixflow/common/time/TimeAutoConfiguration.java").normalize();
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("\\src\\main\\java\\"))
                    .filter(path -> !path.toAbsolutePath().normalize().equals(allowedClockConfig))
                    .forEach(path -> collectViolations(root, path, violations));
        }

        assertThat(violations)
                .as("主代码只能使用 common 的 pixflowClock，不能恢复模块私有 Clock Bean 或 Clock qualifier")
                .isEmpty();
    }

    private static void collectViolations(Path root, Path path, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (CLOCK_QUALIFIER.matcher(line).find()
                        || NAMED_CLOCK_CONDITION.matcher(line).find()
                        || CLOCK_BEAN_METHOD.matcher(line).find()) {
                    violations.add(root.relativize(path) + ":" + (index + 1) + ": " + line.trim());
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("读取源码失败: " + path, ex);
        }
    }
}
