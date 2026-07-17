package com.pixflow.module.dag.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Dag 模块反向约束守护(对齐 dag.md §十三 + §一 原则 1、5):
 *
 * <ul>
 *   <li>不依赖 module/file、module/task、module/conversation</li>
 *   <li>不依赖 harness/loop、agent</li>
 *   <li>不依赖 infra/cache(中间产物引用经 state)</li>
 *   <li>只允许依赖 contracts.proposal 的 pending-plan 纯契约,不依赖 confirmation 令牌契约</li>
 *   <li>不出现线程池/MQ/Redisson/process_result 实体直连</li>
 * </ul>
 */
@AnalyzeClasses(
    packages = "com.pixflow.module.dag",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class DagArchitectureTest {

    @ArchTest
    static final ArchRule should_not_depend_on_module_file =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.dag..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.module.file..");

    @ArchTest
    static final ArchRule should_not_depend_on_module_task =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.dag..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.module.task..");

    @ArchTest
    static final ArchRule should_not_depend_on_module_conversation =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.dag..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.module.conversation..");

    @ArchTest
    static final ArchRule should_not_depend_on_infra_cache =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.dag..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.infra.cache..");

    @ArchTest
    static final ArchRule should_not_depend_on_harness_loop =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.dag..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.harness.loop..");

    @ArchTest
    static final ArchRule should_not_depend_on_agent =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.dag..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.agent..");

    @ArchTest
    static final ArchRule should_not_depend_on_message_broker =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.dag..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.pixflow.infra.mq..", "org.apache.rocketmq..", "org.springframework.amqp..");

    @ArchTest
    static final ArchRule should_not_create_worker_threads =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.dag..")
            .should().dependOnClassesThat().haveSimpleName("ExecutorService")
            .orShould().dependOnClassesThat().haveSimpleName("Executors")
            .orShould().dependOnClassesThat().haveSimpleName("ThreadPoolExecutor")
            .orShould().dependOnClassesThat().haveSimpleName("ScheduledExecutorService");

    @ArchTest
    static final ArchRule should_not_depend_on_confirmation_contracts =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.dag..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.contracts.confirmation..");
}
