package com.pixflow.module.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MemoryReadOnlyContractTest {

    @Test
    void memoryServiceExposesOnlyPrepareContext() {
        assertThat(Arrays.stream(MemoryService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)).containsExactly("prepareContext");
    }

    @Test
    void productionMemorySourceContainsNoWriteOperations() throws Exception {
        String source = Files.walk(Path.of("src/main/java"))
                .filter(path -> path.toString().endsWith(".java"))
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .reduce("", String::concat);
        assertThat(source).doesNotContain("upsert", "deleteByFilter", "ingestAsync", "reinforce(");
    }
}
