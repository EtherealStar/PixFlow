package com.pixflow.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "com.pixflow", importOptions = ImportOption.DoNotIncludeTests.class)
class AppArchitectureTest {
    @ArchTest
    static final ArchRule APP_USES_ONLY_OWNER_PUBLIC_BOUNDARIES = noClasses()
            .that().resideInAPackage("com.pixflow.app..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..internal..", "..infra.persistence..", "..persistence..", "..runtime..");

    @ArchTest
    static final ArchRule MODULES_DO_NOT_DEPEND_ON_APP = noClasses()
            .that().resideOutsideOfPackage("com.pixflow.app..")
            .should().dependOnClassesThat().resideInAPackage("com.pixflow.app..");

    @ArchTest
    static final ArchRule REST_CONTROLLERS_BELONG_TO_APP = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage("com.pixflow.app.web..");

    @ArchTest
    static final ArchRule BUSINESS_MODULES_DO_NOT_OWN_WEB_TRANSPORT = noClasses()
            .that().resideInAPackage("com.pixflow.module..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..",
                    "org.springframework.messaging.simp..",
                    "org.springframework.web.socket..");
}
