package com.pixflow.module.vision.config.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(
        packages = "com.pixflow.module.vision",
        importOptions = ImportOption.DoNotIncludeTests.class)
class VisionArchitectureTest {
    @ArchTest
    static final ArchRule SHOULD_NOT_DEPEND_ON_DOWNSTREAM_MODULES =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.pixflow.agent..",
                            "com.pixflow.harness.loop..",
                            "com.pixflow.module.conversation..",
                            "com.pixflow.module.dag..",
                            "com.pixflow.module.file..",
                            "com.pixflow.module.rubrics..",
                            "com.pixflow.module.task..");

    @ArchTest
    static final ArchRule MYBATIS_IS_CONFINED_TO_PERSISTENCE =
            noClasses().that().resideOutsideOfPackage("com.pixflow.module.vision.persistence..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.baomidou.mybatisplus..",
                            "org.apache.ibatis..",
                            "org.mybatis..");

    @ArchTest
    static final ArchRule SHOULD_NOT_READ_STORAGE_DIRECTLY =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .should().dependOnClassesThat().resideInAPackage("com.pixflow.infra.storage..");

    @ArchTest
    static final ArchRule SHOULD_NOT_USE_VENDOR_OR_RUNTIME_IMPLEMENTATIONS =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.redisson..",
                            "org.apache.rocketmq..",
                            "io.minio..",
                            "org.springframework.messaging.simp..");

    @Test
    void legacyVisionAndCopyEnrichmentSourcesAreAbsent() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/pixflow/module/vision");
        List<String> forbidden = List.of(
                "VisionService",
                "VisionAssessment",
                "VisionAnalysisRequest",
                "ProductCopyExtractor",
                "CopyEnrichment",
                "asset_copy");

        assertThat(sourceViolations(sourceRoot, forbidden)).isEmpty();
    }

    @Test
    void noModelOrProviderLiteralsInMainSources() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/pixflow/module/vision");
        List<String> forbidden = List.of(
                "qwen-vl",
                "gpt-4o",
                "gpt-4-vision",
                "claude-3",
                "dashscope",
                "anthropic");

        assertThat(sourceViolations(sourceRoot, forbidden)).isEmpty();
    }

    private List<String> sourceViolations(Path sourceRoot, List<String> forbidden) throws IOException {
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> violations(path, forbidden).stream())
                    .toList();
        }
    }

    private List<String> violations(Path path, List<String> forbidden) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return forbidden.stream()
                    .filter(token -> text.contains(token.toLowerCase(Locale.ROOT)))
                    .map(token -> path + " contains " + token)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
