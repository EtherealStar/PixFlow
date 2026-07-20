package com.pixflow.module.rubrics.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class RubricsArchitectureTest {
    private static final String RUBRICS = "com.pixflow.module.rubrics..";

    @Test
    void rubricsDependsOnlyOnOwnerPublicSeams() {
        var classes = new ClassFileImporter().importPackages("com.pixflow.module.rubrics");

        noClasses().that().resideInAPackage(RUBRICS)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.pixflow.module.memory..",
                        "com.pixflow.module.vision..",
                        "com.pixflow.module.file..",
                        "com.pixflow.agent..",
                        "com.pixflow.harness.loop..",
                        "com.pixflow.harness.tools..",
                        "com.pixflow.harness.hooks..",
                        "com.pixflow.module.conversation.internal..",
                        "com.pixflow.module.task.internal..",
                        "com.pixflow.module.task.persistence..",
                        "com.pixflow.harness.eval.internal..",
                        "com.pixflow.harness.eval.persistence..")
                .check(classes);
    }

    @Test
    void rubricsDoesNotExposeAController() {
        var classes = new ClassFileImporter().importPackages("com.pixflow.module.rubrics");

        noClasses().that().resideInAPackage(RUBRICS)
                .should().haveSimpleNameEndingWith("Controller")
                .check(classes);
    }

    @Test
    void taskAndAiDoNotDependOnRubricsDomainTypes() {
        var classes = new ClassFileImporter().importPackages(
                "com.pixflow.module.task", "com.pixflow.infra.ai");

        noClasses().that().resideInAnyPackage(
                        "com.pixflow.module.task..", "com.pixflow.infra.ai..")
                .should().dependOnClassesThat().resideInAPackage(RUBRICS)
                .check(classes);
    }
}
