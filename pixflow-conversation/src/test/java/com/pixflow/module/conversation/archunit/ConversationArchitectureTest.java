package com.pixflow.module.conversation.archunit;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.pixflow.module.conversation")
class ConversationArchitectureTest {

    @ArchTest
    static final ArchRule conversationDoesNotUseMessageWritePath = noClasses()
            .that().resideInAPackage("..conversation..")
            .should().dependOnClassesThat().haveSimpleName("MessageWriteMapper");

    @ArchTest
    static final ArchRule conversationDoesNotAssemblePromptOrTools = noClasses()
            .that().resideInAPackage("..conversation..")
            .should().dependOnClassesThat().haveSimpleNameContaining("ToolDescriptor")
            .orShould().dependOnClassesThat().haveSimpleName("ToolRegistry");

    @ArchTest
    static final ArchRule conversationDoesNotRecordEvalTrace = noClasses()
            .that().resideInAPackage("..conversation..")
            .should().dependOnClassesThat().haveSimpleName("TraceRecorder");
}
