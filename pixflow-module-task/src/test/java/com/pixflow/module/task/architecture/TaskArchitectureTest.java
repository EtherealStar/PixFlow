package com.pixflow.module.task.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.pixflow.module.task",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class TaskArchitectureTest {
    @ArchTest
    static final ArchRule should_not_depend_on_module_file =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.task..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.module.file..");

    @ArchTest
    static final ArchRule should_not_depend_on_agent =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.task..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.agent..");

    @ArchTest
    static final ArchRule should_not_depend_on_harness_loop =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.task..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.harness.loop..");

    @ArchTest
    static final ArchRule should_not_depend_on_spring_ai =
        noClasses()
            .that().resideInAPackage("com.pixflow.module.task..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.ai..");

    @ArchTest
    static final ArchRule capability_workers_must_not_commit_or_publish =
        noClasses()
            .that().haveSimpleName("ProcessWorker")
            .or().haveSimpleName("ImageGenWorker")
            .or().haveSimpleName("FailureIsolator")
            .or().haveSimpleName("WorkUnitScheduler")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.pixflow.module.task.infra.persistence..",
                    "com.pixflow.module.task.internal.progress..",
                    "com.pixflow.module.task.api.event..");
}
