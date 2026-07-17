package com.pixflow.app.proposal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProposalOwnershipArchitectureTest {
    @Test
    void proposalToolAdaptersExistOnlyInApp() throws IOException {
        List<Path> handlers = productionSources().stream()
                .filter(path -> path.getFileName().toString().equals("SubmitImagePlanHandler.java")
                        || path.getFileName().toString().equals("SubmitImagegenPlanHandler.java"))
                .toList();

        assertFalse(handlers.isEmpty(), "proposal handlers must be registered");
        handlers.forEach(path -> assertTrue(
                normalized(path).contains("/pixflow-app/src/main/java/com/pixflow/app/proposal/"),
                normalized(path)));
    }

    @Test
    void resourceResolversRemainOwnedByFile() throws IOException {
        productionSources().stream()
                .filter(path -> path.getFileName().toString().endsWith("ReferenceResolver.java"))
                // 当前完整 Asset Reference resolver 仍是 File 计划的后续工作；先守住 owner 边界。
                .forEach(path -> assertTrue(
                        normalized(path).contains("/pixflow-module-file/src/main/java/"),
                        normalized(path)));
    }

    private static List<Path> productionSources() throws IOException {
        Path workspace = workspace();
        try (var modules = Files.list(workspace)) {
            List<Path> sourceRoots = modules
                    .filter(Files::isDirectory)
                    .map(path -> path.resolve(Path.of("src", "main", "java")))
                    .filter(Files::isDirectory)
                    .toList();
            java.util.ArrayList<Path> sources = new java.util.ArrayList<>();
            for (Path sourceRoot : sourceRoots) {
                try (var paths = Files.walk(sourceRoot)) {
                    paths.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
                }
            }
            return List.copyOf(sources);
        }
    }

    private static Path workspace() {
        Path current = Path.of("").toAbsolutePath().normalize();
        return current.getFileName().toString().equals("pixflow-app")
                ? current.getParent() : current;
    }

    private static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
