package com.pixflow.infra.mq;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoLegacyMqVocabularyTest {
    private static final List<String> BANNED = List.of(
            word('R', 'a', 'b', 'b', 'i', 't', 'M', 'Q'),
            word('R', 'a', 'b', 'b', 'i', 't'),
            word('r', 'a', 'b', 'b', 'i', 't'),
            word('A', 'M', 'Q', 'P'),
            word('A', 'm', 'q', 'p'),
            word('a', 'm', 'q', 'p'),
            word('R', 'a', 'b', 'b', 'i', 't', 'T', 'e', 'm', 'p', 'l', 'a', 't', 'e'),
            word('R', 'a', 'b', 'b', 'i', 't', 'A', 'd', 'm', 'i', 'n'),
            word('R', 'a', 'b', 'b', 'i', 't', 'L', 'i', 's', 't', 'e', 'n', 'e', 'r'),
            word('s', 'p', 'r', 'i', 'n', 'g', '-', 'b', 'o', 'o', 't', '-', 's', 't', 'a', 'r', 't', 'e', 'r', '-', 'a', 'm', 'q', 'p'),
            word('M', 'e', 's', 's', 'a', 'g', 'e', 'L', 'i', 's', 't', 'e', 'n', 'e', 'r', 'C', 'o', 'n', 't', 'a', 'i', 'n', 'e', 'r'),
            word('Q', 'u', 'e', 'u', 'e', 'T', 'o', 'p', 'o', 'l', 'o', 'g', 'y'),
            word('r', 'o', 'u', 't', 'i', 'n', 'g', 'K', 'e', 'y'),
            word('r', 'o', 'u', 't', 'i', 'n', 'g', '-', 'k', 'e', 'y'),
            word('p', 'r', 'e', 'f', 'e', 't', 'c', 'h'));

    @Test
    void runtimeCodeAndConfigDoNotContainLegacyMqVocabulary() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        List<Path> scanRoots = List.of(root.resolve("pom.xml"), root.resolve("docker-compose.yml"),
                root.resolve("pixflow-infra-mq"), root.resolve("pixflow-module-file"), root.resolve("pixflow-module-vision"),
                root.resolve("pixflow-module-task"), root.resolve("pixflow-module-commerce"), root.resolve("pixflow-app/src/main/resources"));
        List<String> matches = scanRoots.stream().filter(Files::exists).flatMap(path -> walk(path).stream())
                .filter(Files::isRegularFile).filter(path -> !path.toString().contains("target"))
                .filter(path -> !path.endsWith("NoLegacyMqVocabularyTest.java"))
                .flatMap(path -> matches(path).stream()).toList();
        assertThat(matches).isEmpty();
    }

    private static String word(char... chars) {
        return new String(chars);
    }

    private static List<Path> walk(Path path) {
        try {
            if (Files.isRegularFile(path)) return List.of(path);
            try (var stream = Files.walk(path)) { return stream.toList(); }
        } catch (IOException ex) { throw new IllegalStateException(ex); }
    }

    private static List<String> matches(Path path) {
        try {
            String text = Files.readString(path);
            return BANNED.stream().filter(text::contains).map(word -> path + " contains " + word).toList();
        } catch (IOException ex) { throw new IllegalStateException(ex); }
    }
}
