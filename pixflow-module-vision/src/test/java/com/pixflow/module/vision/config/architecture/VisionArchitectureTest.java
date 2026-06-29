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
        importOptions = ImportOption.DoNotIncludeTests.class
)
class VisionArchitectureTest {

    @ArchTest
    static final ArchRule should_not_depend_on_harness_tools =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .should().dependOnClassesThat().resideInAPackage("com.pixflow.harness.tools..");

    @ArchTest
    static final ArchRule should_not_depend_on_harness_loop =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .should().dependOnClassesThat().resideInAPackage("com.pixflow.harness.loop..");

    @ArchTest
    static final ArchRule should_not_depend_on_agent =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .should().dependOnClassesThat().resideInAPackage("com.pixflow.agent..");

    @ArchTest
    static final ArchRule should_not_depend_on_business_downstream_modules =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.pixflow.module.dag..",
                            "com.pixflow.module.task..",
                            "com.pixflow.module.conversation..",
                            "com.pixflow.module.rubrics..",
                            "com.pixflow.module.file..");

    @ArchTest
    static final ArchRule should_not_use_mybatis_plus =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .and().resideOutsideOfPackage("com.pixflow.module.vision.enrich..")
                    .should().dependOnClassesThat().resideInAPackage("com.baomidou.mybatisplus..");

    @ArchTest
    static final ArchRule should_not_use_jdbc =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .and().resideOutsideOfPackage("com.pixflow.module.vision.enrich..")
                    .should().dependOnClassesThat().resideInAPackage("java.sql..");

    @ArchTest
    static final ArchRule should_not_use_redisson =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .should().dependOnClassesThat().resideInAPackage("org.redisson..");

    @ArchTest
    static final ArchRule should_not_use_simp_messaging =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.messaging.simp..");

    @ArchTest
    static final ArchRule should_not_use_scheduled =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.scheduling.annotation..");

    @ArchTest
    static final ArchRule should_not_use_thread_pool_executor =
            noClasses().that().resideInAPackage("com.pixflow.module.vision..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "java.util.concurrent.ThreadPoolExecutor..",
                            "java.util.concurrent.ScheduledExecutorService..",
                            "java.util.concurrent.Executors..");

    @Test
    void noModelOrProviderLiteralsInMainSources() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/pixflow/module/vision");
        List<String> forbidden = List.of(
                "qwen-vl-max",
                "qwen-vl-plus",
                "qwen-vl",
                "qwenvl",
                "gpt-4o",
                "gpt-4-vision",
                "claude-3-opus",
                "claude-3-sonnet",
                "dashscope",
                "openai",
                "anthropic");

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<String> violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> violations(path, forbidden).stream())
                    .toList();
            assertThat(violations).isEmpty();
        }
    }

    @Test
    void noSwallowedInfraAiPixFlowExceptionPattern() throws IOException {
        Path service = Path.of("src/main/java/com/pixflow/module/vision/DefaultVisionService.java");
        String text = Files.readString(service, StandardCharsets.UTF_8);

        assertThat(text).doesNotContain("catch (PixFlowException");
        assertThat(text).doesNotContain("throw new RuntimeException");
    }

    private List<String> violations(Path path, List<String> forbidden) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return forbidden.stream()
                    .filter(text::contains)
                    .map(token -> path + " contains " + token)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
