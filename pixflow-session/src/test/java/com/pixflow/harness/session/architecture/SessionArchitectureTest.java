package com.pixflow.harness.session.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class SessionArchitectureTest {

    @Test
    void sessionDoesNotDependOnBannedPackages() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.pixflow");
        ArchRule rule = noClasses().that().resideInAPackage("com.pixflow.harness.session..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.pixflow.module..",
                        "com.pixflow.harness.tools..",
                        "com.pixflow.infra.cache..",
                        "com.pixflow.harness.eval..");
        rule.check(classes);

        ArchRule mapperRule = noClasses().that().resideOutsideOfPackage("com.pixflow.harness.session..")
                .should().dependOnClassesThat().haveSimpleName("MessageWriteMapper");
        mapperRule.check(classes);
    }
}
