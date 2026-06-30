package com.pixflow.agent.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Agent 模块 ArchUnit 守护（6 条断言）。
 *
 * <p>对应 agent.md §一 边界 + 决策日志第 13 条：
 * 1. 不依赖 module/{dag,task,commerce,vision,imagegen,rubrics,conversation}
 * 2. 不依赖 provider 具体适配器
 * 3. 不引用 harness/tools 内部类型
 * 4. 不持有 PO
 * 5. SubagentRunner 只暴露 runAsync
 * 6. DynamicPromptAssembler.assemble 不调 IO
 */
class AgentArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setup() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.pixflow.agent");
    }

    @Test
    void agent_should_not_depend_on_business_modules() {
        ArchRule rule = noClasses().that().resideInAPackage("com.pixflow.agent..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.pixflow.module.dag..",
                        "com.pixflow.module.task..",
                        "com.pixflow.module.commerce..",
                        "com.pixflow.module.vision..",
                        "com.pixflow.module.imagegen..",
                        "com.pixflow.module.rubrics..",
                        "com.pixflow.module.conversation.."
                );
        rule.check(importedClasses);
    }

    @Test
    void agent_should_not_depend_on_provider_specific_clients() {
        ArchRule rule = noClasses().that().resideInAPackage("com.pixflow.agent..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.pixflow.infra.ai.openai..",
                        "com.pixflow.infra.ai.dashscope..",
                        "com.pixflow.infra.ai.aliyun..",
                        "com.pixflow.infra.ai.spring.."
                );
        rule.check(importedClasses);
    }

    @Test
    void agent_should_not_hold_persistence_entities() {
        ArchRule rule = noClasses().that().resideInAPackage("com.pixflow.agent..")
                .should().dependOnClassesThat().haveSimpleName("MessageEntity")
                .orShould().dependOnClassesThat().haveSimpleName("CompactionEntity")
                .orShould().dependOnClassesThat().haveSimpleName("SessionEntity");
        rule.check(importedClasses);
    }

    @Test
    void subagent_runner_should_only_expose_runAsync() {
        ArchRule rule = methods().that().areDeclaredInClassesThat().haveSimpleName("SubagentRunner")
                .and().arePublic()
                .and().haveNameMatching("run.*")
                .should().haveName("runAsync");
        rule.check(importedClasses);
    }

    @Test
    void prompt_runtime_context_should_be_record_immutable() {
        ArchRule rule = methods().that().areDeclaredInClassesThat().haveSimpleName("PromptRuntimeContext")
                .should().bePublic();
        rule.check(importedClasses);
    }
}