package com.pixflow.common.sanitize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SanitizerTest {

    @Test
    void masksTokensAndPaths() {
        String raw = "Bearer abcdef123456 secret D:\\data\\foo.txt /var/tmp/bar.txt sk-1234567890abcdef LTAI123456";

        String sanitized = Sanitizer.sanitizeMessage(raw);

        assertThat(sanitized).doesNotContain("abcdef123456");
        assertThat(sanitized).doesNotContain("D:\\data\\foo.txt");
        assertThat(sanitized).doesNotContain("/var/tmp/bar.txt");
        assertThat(sanitized).contains("***");
        assertThat(sanitized.length()).isLessThanOrEqualTo(1000);
    }

    @Test
    void truncatesLongText() {
        String text = "a".repeat(1200);
        assertThat(Sanitizer.truncate(text, 1000)).hasSize(1000);
    }
}
