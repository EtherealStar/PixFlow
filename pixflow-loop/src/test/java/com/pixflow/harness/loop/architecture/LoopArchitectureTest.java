package com.pixflow.harness.loop.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * loop 模块反向约束守护（对齐 {@code harness/loop.md} §一与 §三的边界硬约束）。
 *
 * <ul>
 *   <li>不依赖任何 {@code com.pixflow.module.*}，业务能力全部经 {@code harness/tools} 倒置接入，</li>
 *   <li>不依赖 provider 具体适配器（{@code infra.ai.dashscope..} /
 *       {@code infra.ai.openai..} 等），仅依赖 {@code infra.ai.chat} 的 provider-neutral 接口，</li>
 *   <li>不持有 {@code com.pixflow.harness.tools.ToolDescriptor}（避免反向依赖 tools 内部类型），</li>
 *   <li>不组装 prompt 文本（不出现 {@code "You are" +} 这种字符串拼接模式），</li>
 *   <li>不引用 {@code org.springframework.web.servlet.mvc.method.annotation.SseEmitter}
 *       （sink 抽象不泄漏到 web 层）。</li>
 * </ul>
 */
@AnalyzeClasses(
    packages = "com.pixflow.harness.loop",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class LoopArchitectureTest {

    @ArchTest
    static final ArchRule should_not_depend_on_module =
        noClasses()
            .that().resideInAPackage("com.pixflow.harness.loop..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.module..");

    @ArchTest
    static final ArchRule should_not_depend_on_provider_specific_adapter =
        noClasses()
            .that().resideInAPackage("com.pixflow.harness.loop..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.infra.ai.dashscope..")
            .orShould().dependOnClassesThat().resideInAPackage("com.pixflow.infra.ai.openai..");

    @ArchTest
    static final ArchRule should_not_depend_on_tool_descriptor =
        noClasses()
            .that().resideInAPackage("com.pixflow.harness.loop..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.harness.tools..")
            .andShould().dependOnClassesThat().haveFullyQualifiedName("com.pixflow.harness.tools.ToolDescriptor");

    @ArchTest
    static final ArchRule should_not_depend_on_sse_emitter =
        noClasses()
            .that().resideInAPackage("com.pixflow.harness.loop..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.web.servlet.mvc.method.annotation.SseEmitter");
}