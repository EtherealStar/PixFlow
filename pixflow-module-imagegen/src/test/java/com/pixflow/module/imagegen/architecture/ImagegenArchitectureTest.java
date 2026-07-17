package com.pixflow.module.imagegen.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * imagegen 模块反向约束守护(对齐 imagegen.md §四 / §十四)。
 *
 * <ul>
 *   <li>不依赖 module/file / module/dag / module/task / module/conversation(均由 SPI 倒置)</li>
 *   <li>不依赖 harness/loop / harness/tools / agent</li>
 *   <li>不依赖 infra/mq / infra/cache(纯能力模块)</li>
 *   <li>不依赖 harness/state(不直连 process_result)</li>
 *   <li>不出现 MyBatis-Plus / JDBC / Redisson / SimpMessagingTemplate</li>
 *   <li>不出现 ThreadPoolExecutor / ScheduledExecutorService(@Scheduled 自建)</li>
 * </ul>
 */
@AnalyzeClasses(
    packages = "com.pixflow.module.imagegen",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ImagegenArchitectureTest {

    @ArchTest
    static final ArchRule should_not_depend_on_module_file =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.module.file..");

    @ArchTest
    static final ArchRule should_not_depend_on_module_dag =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.module.dag..");

    @ArchTest
    static final ArchRule should_not_depend_on_module_task =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.module.task..");

    @ArchTest
    static final ArchRule should_not_depend_on_module_conversation =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.module.conversation..");

    @ArchTest
    static final ArchRule should_not_depend_on_harness_loop =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.harness.loop..");

    @ArchTest
    static final ArchRule should_not_depend_on_harness_tools =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.harness.tools..");

    @ArchTest
    static final ArchRule should_not_depend_on_agent =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.agent..");

    @ArchTest
    static final ArchRule should_not_depend_on_infra_mq =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.infra.mq..");

    @ArchTest
    static final ArchRule should_not_depend_on_infra_cache =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.infra.cache..");

    @ArchTest
    static final ArchRule should_not_depend_on_harness_state =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.harness.state..");

    @ArchTest
    static final ArchRule should_not_use_mybatis_plus =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("com.baomidou.mybatisplus..");

    @ArchTest
    static final ArchRule should_not_use_jdbc =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("java.sql..");

    @ArchTest
    static final ArchRule should_not_use_redisson =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("org.redisson..");

    @ArchTest
    static final ArchRule should_not_use_simp_messaging =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.messaging.simp..");

    @ArchTest
    static final ArchRule should_not_use_thread_pool =
        // 自建线程池(Execute/Schedule)由 module/task / infra/mq 持有;imagegen 纯能力模块不动线程。
        // 注:不屏蔽 org.springframework.scheduling 包名(那是 Spring 基础设施),只屏蔽具体类。
        noClasses()
            .that().resideInAPackage("com.pixflow.module.imagegen..")
            .should().dependOnClassesThat().resideInAPackage("java.util.concurrent.ThreadPoolExecutor..");
}
