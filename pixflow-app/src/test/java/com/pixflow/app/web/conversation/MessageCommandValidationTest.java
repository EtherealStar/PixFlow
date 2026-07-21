package com.pixflow.app.web.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageCommandValidationTest {
    @Test
    void acceptsReferenceOnlyMessage() {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var command = new MessageController.MessageCommand(
                    " ", List.of(new MessageController.ReferenceCommand("package:7", "summer.zip")));

            assertThat(validatorFactory.getValidator().validate(command)).isEmpty();
        }
    }

    @Test
    void rejectsMessageWithoutPromptOrReferences() {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var command = new MessageController.MessageCommand(" ", List.of());

            assertThat(validatorFactory.getValidator().validate(command))
                    .anyMatch(violation -> violation.getConstraintDescriptor()
                            .getAnnotation().annotationType()
                            .equals(jakarta.validation.constraints.AssertTrue.class));
        }
    }
}
